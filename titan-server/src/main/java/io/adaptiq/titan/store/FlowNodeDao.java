package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindFields;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.flow_nodes} — execution DAG nodes (stages, steps, parallel
 * branches). Composite PK: {@code (build_id, node_id)}.
 */
@RegisterFieldMapper(FlowNodeRow.class)
public interface FlowNodeDao {

  String COLS =
      "build_id, node_id, parent_ids, node_type, display_name, step_descriptor, "
          + "step_args_json, status, agent_id, agent_label, started_at, completed_at, "
          + "duration_ms, result_json, log_task_id, attempt, max_attempts, "
          + "failure_category, failure_reason, wake_at";

  /** Lookup by composite PK. */
  @SqlQuery(
      "SELECT " + COLS + " FROM titan.flow_nodes WHERE build_id = :buildId AND node_id = :nodeId")
  @NonNull
  Optional<FlowNodeRow> findByBuildAndNode(
      @Bind("buildId") long buildId, @Bind("nodeId") @NonNull String nodeId);

  /** All nodes for a build, ordered by node_id. */
  @SqlQuery("SELECT " + COLS + " FROM titan.flow_nodes WHERE build_id = :buildId ORDER BY node_id")
  @NonNull
  List<FlowNodeRow> listByBuild(@Bind("buildId") long buildId);

  /** Nodes for a build filtered by status, ordered by node_id. */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.flow_nodes WHERE build_id = :buildId "
          + "AND status = :status ORDER BY node_id")
  @NonNull
  List<FlowNodeRow> listByBuildAndStatus(
      @Bind("buildId") long buildId, @Bind("status") @NonNull String status);

  /** Count nodes that are not yet in a terminal state (still running or pending). */
  @SqlQuery(
      "SELECT COUNT(*) FROM titan.flow_nodes WHERE build_id = :buildId "
          + "AND status NOT IN ('SUCCESS','FAILED','ABORTED','SKIPPED')")
  int countNonTerminal(@Bind("buildId") long buildId);

  /**
   * Count every flow node materialised for a build, regardless of status. Zero means the build has
   * not been baked yet; non-zero means a bake already wrote the DAG — the bake-phase idempotency
   * guard (design/38 §3: a re-delivered BAKE task must not re-materialise).
   */
  @SqlQuery("SELECT COUNT(*) FROM titan.flow_nodes WHERE build_id = :buildId")
  int countByBuild(@Bind("buildId") long buildId);

  /** Nodes for a build filtered by multiple statuses. */
  default List<FlowNodeRow> listByBuildAndStatuses(long buildId, @NonNull String... statuses) {
    if (statuses.length == 0) {
      return java.util.List.of();
    }
    return listByBuildAndStatusList(buildId, java.util.Arrays.asList(statuses));
  }

  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.flow_nodes WHERE build_id = :buildId "
          + "AND status IN (<statuses>) ORDER BY node_id")
  @NonNull
  List<FlowNodeRow> listByBuildAndStatusList(
      @Bind("buildId") long buildId, @BindList("statuses") @NonNull List<String> statuses);

  /**
   * Insert a new flow node. All columns are caller-provided (composite PK, no identity).
   *
   * <p>{@code log_task_id} is wrapped in {@code CAST(... AS UUID)}: a bare bound {@code null}
   * {@link java.util.UUID} is sent as {@code character varying}, which PostgreSQL rejects for a
   * {@code uuid} column (H2 is lax). The cast is portable and a no-op for a non-null value.
   */
  @SqlUpdate(
      "INSERT INTO titan.flow_nodes (build_id, node_id, parent_ids, node_type, display_name, "
          + "step_descriptor, step_args_json, status, agent_id, agent_label, started_at, "
          + "completed_at, duration_ms, result_json, log_task_id, attempt, max_attempts, "
          + "failure_category, failure_reason) "
          + "VALUES (:buildId, :nodeId, :parentIds, :nodeType, :displayName, :stepDescriptor, "
          + ":stepArgsJson, :status, :agentId, :agentLabel, :startedAt, :completedAt, "
          + ":durationMs, :resultJson, CAST(:logTaskId AS UUID), :attempt, :maxAttempts, "
          + ":failureCategory, :failureReason)")
  void insert(@BindFields FlowNodeRow row);

  /**
   * Record the structured failure (design/45 §3/§4) on a node — its {@code failure_category} (a
   * closed-enum string) and a customer-facing {@code failure_reason} sentence. Written by the
   * orchestrator's controller-side failure paths <em>before</em> the node is compare-and-set to
   * {@code FAILED}, so the reason is in place when the build's console or the graph API next reads
   * the node. Idempotent — a re-run of the same failure path overwrites with the same values.
   */
  @SqlUpdate(
      "UPDATE titan.flow_nodes SET failure_category = :failureCategory, "
          + "failure_reason = :failureReason "
          + "WHERE build_id = :buildId AND node_id = :nodeId")
  void updateFailure(
      @Bind("buildId") long buildId,
      @Bind("nodeId") @NonNull String nodeId,
      @Bind("failureCategory") @NonNull String failureCategory,
      @Bind("failureReason") @NonNull String failureReason);

  /** Update status and timing fields for a node. */
  @SqlUpdate(
      "UPDATE titan.flow_nodes SET status = :status, "
          + "started_at = COALESCE(:startedAt, started_at), "
          + "completed_at = COALESCE(:completedAt, completed_at), "
          + "duration_ms = COALESCE(:durationMs, duration_ms), "
          + "result_json = COALESCE(:resultJson, result_json) "
          + "WHERE build_id = :buildId AND node_id = :nodeId")
  void updateStatus(
      @Bind("buildId") long buildId,
      @Bind("nodeId") @NonNull String nodeId,
      @Bind("status") @NonNull String status,
      @Bind("startedAt") @Nullable Instant startedAt,
      @Bind("completedAt") @Nullable Instant completedAt,
      @Bind("durationMs") @Nullable Long durationMs,
      @Bind("resultJson") @Nullable String resultJson);

  /**
   * Compare-and-set a node's status: move it from {@code fromStatus} to {@code toStatus} only if it
   * is still in {@code fromStatus} (design/26 Tier B — two controllers cannot both advance the same
   * node). {@code started_at} / {@code completed_at} / {@code result_json} are written only when
   * supplied (COALESCE). Returns rows affected — {@code 1} if this caller won the transition,
   * {@code 0} if the node had already moved on.
   */
  @SqlUpdate(
      "UPDATE titan.flow_nodes SET status = :toStatus, "
          + "started_at = COALESCE(:startedAt, started_at), "
          + "completed_at = COALESCE(:completedAt, completed_at), "
          + "duration_ms = COALESCE(:durationMs, duration_ms), "
          + "result_json = COALESCE(:resultJson, result_json) "
          + "WHERE build_id = :buildId AND node_id = :nodeId AND status = :fromStatus")
  int compareAndSetStatus(
      @Bind("buildId") long buildId,
      @Bind("nodeId") @NonNull String nodeId,
      @Bind("fromStatus") @NonNull String fromStatus,
      @Bind("toStatus") @NonNull String toStatus,
      @Bind("startedAt") @Nullable Instant startedAt,
      @Bind("completedAt") @Nullable Instant completedAt,
      @Bind("durationMs") @Nullable Long durationMs,
      @Bind("resultJson") @Nullable String resultJson);

  /**
   * Compare-and-set a step node's {@code attempt} for a retry re-dispatch (design/44 §4). The node
   * stays {@code QUEUED} — a retry is the same unit of work re-run — but its {@code attempt} is
   * bumped from {@code fromAttempt} to {@code fromAttempt + 1}. The WHERE clause keys on both
   * {@code status='QUEUED'} <em>and</em> {@code attempt = :fromAttempt}, so the transition is
   * idempotent: a second orchestrator pass (or a re-delivered failed task) that has already
   * advanced the attempt sees {@code 0} rows and does not double-enqueue.
   *
   * <p>Every field that records the <em>previous attempt's outcome</em> is reset, so the node
   * presents as a genuinely clean fresh attempt: {@code result_json}, {@code started_at} and {@code
   * completed_at}, and — design/45 — the structured failure {@code failure_category} / {@code
   * failure_reason}. Resetting the failure fields keeps this method's contract complete: a node
   * that fails an attempt and then succeeds on a retry must not carry a stale {@code ✗} failure
   * into its green terminal state. (The orchestrator sets the failure fields only on a terminal
   * {@code FAILED}, never before a retry — so this is belt-and-braces today, but it makes the reset
   * whole rather than three-fields-of-five.)
   *
   * @return rows affected — {@code 1} if this caller won the retry transition, else {@code 0}.
   */
  @SqlUpdate(
      "UPDATE titan.flow_nodes SET attempt = attempt + 1, "
          + "result_json = NULL, started_at = NULL, completed_at = NULL, "
          + "failure_category = NULL, failure_reason = NULL "
          + "WHERE build_id = :buildId AND node_id = :nodeId "
          + "AND status = 'QUEUED' AND attempt = :fromAttempt")
  int compareAndSetRetryAttempt(
      @Bind("buildId") long buildId,
      @Bind("nodeId") @NonNull String nodeId,
      @Bind("fromAttempt") int fromAttempt);

  /**
   * Stamp the log-stream join key on a node: the {@code task_token} (UUID) of the {@code
   * EXECUTE_COMMAND} task that is currently executing the step. The UI's per-node Logs panel reads
   * {@code flow_nodes.log_task_id} and joins {@code titan.logs.task_id} — without this column the
   * panel renders empty even for builds with persisted logs (issue #508).
   *
   * <p>Written by the orchestrator's reconciler ({@code TitanOrchestrator.reconcileFinishedSteps})
   * whenever it observes the latest {@code EXECUTE_COMMAND} for a node. Idempotent: a re-run with
   * the same token is a no-op write. A retry that dispatches a fresh task simply replaces the
   * pointer so the panel always shows the latest attempt's stream.
   *
   * <p>The bind is wrapped in {@code CAST(... AS UUID)} for the same reason as {@code insert} — a
   * bare bound UUID is otherwise sent as {@code character varying} which PostgreSQL rejects on a
   * {@code uuid} column.
   */
  @SqlUpdate(
      "UPDATE titan.flow_nodes SET log_task_id = CAST(:logTaskId AS UUID) "
          + "WHERE build_id = :buildId AND node_id = :nodeId")
  void setLogTaskId(
      @Bind("buildId") long buildId,
      @Bind("nodeId") @NonNull String nodeId,
      @Bind("logTaskId") @NonNull UUID logTaskId);

  /** Record the wake instant for a node the orchestrator has just parked as SLEEPING. */
  @SqlUpdate(
      "UPDATE titan.flow_nodes SET wake_at = :wakeAt "
          + "WHERE build_id = :buildId AND node_id = :nodeId")
  void setWakeAt(
      @Bind("buildId") long buildId,
      @Bind("nodeId") @NonNull String nodeId,
      @Bind("wakeAt") @NonNull Instant wakeAt);

  /** Delete a single node. */
  @SqlUpdate("DELETE FROM titan.flow_nodes WHERE build_id = :buildId AND node_id = :nodeId")
  void delete(@Bind("buildId") long buildId, @Bind("nodeId") @NonNull String nodeId);

  /** Delete all nodes for a build. */
  @SqlUpdate("DELETE FROM titan.flow_nodes WHERE build_id = :buildId")
  void deleteByBuild(@Bind("buildId") long buildId);
}

package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindFields;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;

/**
 * JDBI SqlObject access for {@code titan.task_queue} — the heart of the stateless execution engine.
 * Workers claim tasks via {@code SELECT ... FOR UPDATE SKIP LOCKED}.
 *
 * <p>The claim is leased, not given: {@link #claim} writes a per-claim {@code claim_token} (UUID)
 * and {@link #complete} verifies it. A zombie worker reaped to {@code QUEUED} and re-claimed by a
 * peer can no longer complete the task — its token no longer matches (doc-26 Tier A, doc-27 G3).
 */
@RegisterFieldMapper(TaskQueueRow.class)
public interface TaskQueueDao {

  String COLS =
      "id, type, queue_name, status, priority, payload_json, result_json, attempts, "
          + "max_attempts, visibility_timeout_seconds, claim_token, claimed_by, claimed_at, "
          + "available_at, build_id, node_id, task_token, created_at, completed_at, trace_parent, "
          + "cancel_requested_at";

  // ---------------------------------------------------------------------
  // Lookups
  // ---------------------------------------------------------------------

  /** Lookup by surrogate id. */
  @SqlQuery("SELECT " + COLS + " FROM titan.task_queue WHERE id = :id")
  @NonNull
  Optional<TaskQueueRow> findById(@Bind("id") long id);

  /** Lookup by idempotency token. */
  @SqlQuery("SELECT " + COLS + " FROM titan.task_queue WHERE task_token = :taskToken")
  @NonNull
  Optional<TaskQueueRow> findByTaskToken(@Bind("taskToken") @NonNull UUID taskToken);

  /** All tasks for a build, ordered chronologically. */
  @SqlQuery(
      "SELECT " + COLS + " FROM titan.task_queue WHERE build_id = :buildId ORDER BY created_at")
  @NonNull
  List<TaskQueueRow> listByBuild(@Bind("buildId") long buildId);

  /**
   * All tasks for a build, ordered chronologically, across <em>both</em> the live {@code
   * task_queue} and {@code task_archive} — the union-of-both view that the reconciler ({@link
   * io.adaptiq.titan.flow.TitanOrchestrator}) needs (issue #493). A terminal {@code
   * EXECUTE_COMMAND} task is moved into {@code task_archive} by the per-tick archive sweep, and on
   * the very next {@code ADVANCE} the reconcile loop must still see it to fold the step's {@code
   * flow_node} from {@code QUEUED} to {@code SUCCESS}/{@code FAILED}. Reading {@code task_queue}
   * alone in the reconciler loses every just-archived terminal task — the node stays {@code
   * QUEUED}, the orchestrator's "dispatch was lost" self-heal fires, and a fresh {@code
   * EXECUTE_COMMAND} is re-dispatched on every tick (infinite ORCHESTRATE loop).
   */
  @SqlQuery(
      "SELECT * FROM ("
          + "SELECT "
          + COLS
          + " FROM titan.task_queue WHERE build_id = :buildId "
          + "UNION ALL "
          + "SELECT "
          + COLS
          + " FROM titan.task_archive WHERE build_id = :buildId"
          + ") t ORDER BY created_at")
  @NonNull
  List<TaskQueueRow> listByBuildIncludingArchive(@Bind("buildId") long buildId);

  /**
   * Page of {@code QUEUED} tasks across all queues, oldest-first within priority — the queue-feed
   * view (UI Queue page, design §5.2). Only currently-claimable rows are returned (no future {@code
   * available_at}). Sort: priority DESC, then {@code created_at} ASC, which matches the worker-side
   * claim ordering in {@link #selectClaimableId}.
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.task_queue WHERE status = 'QUEUED' "
          + "AND available_at <= CURRENT_TIMESTAMP "
          + "ORDER BY priority DESC, created_at "
          + "LIMIT :limit OFFSET :offset")
  @NonNull
  List<TaskQueueRow> listQueued(@Bind("offset") int offset, @Bind("limit") int limit);

  /** Total count of currently-claimable {@code QUEUED} tasks (for the Queue page total). */
  @SqlQuery(
      "SELECT COUNT(*) FROM titan.task_queue WHERE status = 'QUEUED' "
          + "AND available_at <= CURRENT_TIMESTAMP")
  int countQueued();

  /**
   * Currently-claimable {@code QUEUED} count narrowed to a single {@code queue_name}. Powers the
   * {@code titan_queue_depth{queue=...}} Prometheus gauge (#649). Cheap — exercises the same
   * (status, queue_name) shape as the claim path's selectClaimable*.
   */
  @SqlQuery(
      "SELECT COUNT(*) FROM titan.task_queue WHERE status = 'QUEUED' "
          + "AND queue_name = :queueName "
          + "AND available_at <= CURRENT_TIMESTAMP")
  int countQueuedByQueue(@Bind("queueName") @NonNull String queueName);

  /**
   * QUEUED {@code EXECUTE_COMMAND} tasks that became claimable before {@code cutoff} — the
   * detection surface for the no-worker fail-fast sweeper (issue #1049). A task that has been
   * sitting on a queue with no live worker eventually trips this query; the controller-side sweep
   * then verifies (against {@code titan.agents}) that the queue really has no online drainer before
   * failing the task. Bounded by {@code limit} so the sweep is one round-trip even for a
   * pathological backlog. Ordered by {@code available_at} so the oldest waiter goes first — the
   * same order the worker-side claim path would consume them.
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.task_queue WHERE status = 'QUEUED' "
          + "AND type = 'EXECUTE_COMMAND' "
          + "AND available_at <= :cutoff "
          + "ORDER BY available_at LIMIT :limit")
  @NonNull
  List<TaskQueueRow> findExecuteCommandQueuedBefore(
      @Bind("cutoff") @NonNull java.sql.Timestamp cutoff, @Bind("limit") int limit);

  /**
   * Fail a still-{@code QUEUED} task — the no-worker fail-fast path (issue #1049). The normal
   * {@link #failTask} only matches {@code CLAIMED}/{@code PROCESSING} rows because completion is
   * lease-token guarded; a sweeper-failed task has no lease so we need a status-only guarded
   * variant. The {@code AND status = 'QUEUED'} clause is load-bearing: a worker that races us and
   * claims the row between detection and update is preserved unharmed (zero rows updated).
   *
   * @return {@code true} iff the row was {@code QUEUED} and is now {@code FAILED}.
   */
  default boolean failQueuedTask(long id, @NonNull String resultJson) {
    return failQueuedTaskUpdate(id, resultJson) == 1;
  }

  @SqlUpdate(
      "UPDATE titan.task_queue SET status = 'FAILED', result_json = :resultJson, "
          + "completed_at = CURRENT_TIMESTAMP "
          + "WHERE id = :id AND status = 'QUEUED'")
  int failQueuedTaskUpdate(@Bind("id") long id, @Bind("resultJson") @NonNull String resultJson);

  /**
   * Page of the most-recently-archived tasks — the "Recent activity" feed for the UI Queue page
   * when no tasks are currently in flight (issue #523). Reads {@code titan.task_archive} (the live
   * {@code task_queue} only carries claimable + in-flight rows; terminal rows are swept to the
   * archive within seconds). Ordered by {@code completed_at DESC} so the latest terminal task is
   * first. Rows with a null {@code completed_at} (defensive — should never happen for archived
   * rows) sort last via {@code NULLS LAST}.
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.task_archive "
          + "ORDER BY completed_at DESC NULLS LAST, id DESC "
          + "LIMIT :limit")
  @NonNull
  List<TaskQueueRow> listRecentArchived(@Bind("limit") int limit);

  /**
   * The most recent worker-side synthesis task for a build, if any (design/38 Stage 1b). Synthesis
   * runs on a worker as an {@code EXECUTE_COMMAND} task on the well-known {@code synthesis} queue;
   * the controller's {@code SYNTHESIZE} orchestration handler uses this to decide whether it must
   * dispatch a fresh synthesis task or is still waiting on an in-flight one. Latest-first so a
   * caller reads the current attempt's status.
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.task_queue "
          + "WHERE build_id = :buildId AND type = 'EXECUTE_COMMAND' AND queue_name = 'synthesis' "
          + "ORDER BY created_at DESC LIMIT 1")
  @NonNull
  Optional<TaskQueueRow> findLatestSynthesisTask(@Bind("buildId") long buildId);

  /**
   * Every task token for a build, in chronological order, across <em>both</em> the live {@code
   * task_queue} and {@code task_archive} — a completed build's tasks have usually been archived.
   * These tokens key the build's {@code titan.logs} chunks (used to assemble the console).
   */
  @SqlQuery(
      "SELECT task_token FROM ("
          + "SELECT task_token, created_at FROM titan.task_queue WHERE build_id = :buildId "
          + "UNION ALL "
          + "SELECT task_token, created_at FROM titan.task_archive WHERE build_id = :buildId"
          + ") t ORDER BY created_at")
  @NonNull
  List<UUID> logTokensForBuild(@Bind("buildId") long buildId);

  /**
   * The task token(s) for one DAG node of a build — across {@code task_queue} and {@code
   * task_archive}, chronological. These key the node's {@code titan.logs} chunks (the design/35
   * per-node console). Usually one token; more if the node's task was reaped + retried.
   */
  @SqlQuery(
      "SELECT task_token FROM ("
          + "SELECT task_token, created_at FROM titan.task_queue "
          + "WHERE build_id = :buildId AND node_id = :nodeId "
          + "UNION ALL "
          + "SELECT task_token, created_at FROM titan.task_archive "
          + "WHERE build_id = :buildId AND node_id = :nodeId"
          + ") t ORDER BY created_at")
  @NonNull
  List<UUID> logTokensForNode(
      @Bind("buildId") long buildId, @Bind("nodeId") @NonNull String nodeId);

  /**
   * Distinct build ids whose tasks were claimed by {@code agentId} — the builds that actually ran
   * on this worker. Spans the live {@code task_queue} and {@code task_archive} (a finished build's
   * tasks are usually archived). Most-recently-claimed build first; capped at {@code limit} rows.
   *
   * <p>{@code claimed_by} is the truth of "ran here": the orchestration task sits on the {@code
   * default} queue, but every command task is claimed by the worker that executed it. This backs
   * the per-node build history.
   */
  @SqlQuery(
      "SELECT build_id FROM ("
          + "SELECT build_id, MAX(claimed_at) AS last_claim FROM ("
          + "SELECT build_id, claimed_at FROM titan.task_queue "
          + "WHERE claimed_by = :agentId AND build_id IS NOT NULL "
          + "UNION ALL "
          + "SELECT build_id, claimed_at FROM titan.task_archive "
          + "WHERE claimed_by = :agentId AND build_id IS NOT NULL"
          + ") u GROUP BY build_id"
          + ") g ORDER BY last_claim DESC NULLS LAST LIMIT :limit")
  @NonNull
  List<Long> buildIdsClaimedByAgent(
      @Bind("agentId") @NonNull String agentId, @Bind("limit") int limit);

  // ---------------------------------------------------------------------
  // Enqueue
  // ---------------------------------------------------------------------

  /**
   * Insert a new task into the queue. The database generates {@code id}, {@code task_token}, and
   * {@code created_at}. Returns the generated id and stamps it back onto {@code row.id}.
   */
  default long insert(@NonNull TaskQueueRow row) {
    long id = insertReturningId(row);
    row.id = id;
    return id;
  }

  @SqlUpdate(
      "INSERT INTO titan.task_queue (type, queue_name, status, priority, payload_json, "
          + "attempts, max_attempts, visibility_timeout_seconds, available_at, build_id, node_id, "
          + "trace_parent) "
          + "VALUES (:type, :queueName, :status, :priority, :payloadJson, :attempts, "
          + ":maxAttempts, :visibilityTimeoutSeconds, "
          + "COALESCE(:availableAt, CURRENT_TIMESTAMP), :buildId, :nodeId, :traceParent)")
  @GetGeneratedKeys
  long insertReturningId(@BindFields TaskQueueRow row);

  /**
   * Enqueue a {@code QUEUED} task with sane defaults — the simple insert path for callers that do
   * not need to build a full {@link TaskQueueRow}. {@code available_at} defaults to now (the task
   * is immediately claimable). Returns the generated task id.
   */
  @SqlUpdate(
      "INSERT INTO titan.task_queue (type, queue_name, status, priority, payload_json, "
          + "attempts, max_attempts, visibility_timeout_seconds, available_at, build_id, node_id, "
          + "trace_parent) "
          + "VALUES (:type, :queueName, 'QUEUED', :priority, :payloadJson, 0, :maxAttempts, "
          + ":visibilityTimeoutSeconds, CURRENT_TIMESTAMP, :buildId, :nodeId, :traceParent)")
  @GetGeneratedKeys
  long enqueue(
      @Bind("type") @NonNull String type,
      @Bind("queueName") @NonNull String queueName,
      @Bind("priority") int priority,
      @Bind("payloadJson") @NonNull String payloadJson,
      @Bind("maxAttempts") int maxAttempts,
      @Bind("visibilityTimeoutSeconds") int visibilityTimeoutSeconds,
      @Bind("buildId") @Nullable Long buildId,
      @Bind("nodeId") @Nullable String nodeId,
      @Bind("traceParent") @Nullable String traceParent);

  /**
   * Back-compat overload of {@link #enqueue} that omits {@code traceParent} (binds null). Existing
   * callers that pre-date #314's trace-context propagation keep working unchanged; new callers that
   * have an active OTel span should prefer the 9-arg form so the span propagates to the worker.
   */
  default long enqueue(
      @NonNull String type,
      @NonNull String queueName,
      int priority,
      @NonNull String payloadJson,
      int maxAttempts,
      int visibilityTimeoutSeconds,
      @Nullable Long buildId,
      @Nullable String nodeId) {
    return enqueue(
        type,
        queueName,
        priority,
        payloadJson,
        maxAttempts,
        visibilityTimeoutSeconds,
        buildId,
        nodeId,
        null);
  }

  /**
   * Enqueue a {@code QUEUED} task with an explicit future {@code available_at} — the delayed /
   * scheduled delivery the durable queue already supports (design/26): the task is not claimable
   * until {@code availableAt} is in the past ({@link #selectClaimableId} gates on {@code
   * available_at <= CURRENT_TIMESTAMP}). This is the mechanism a backed-off step retry reuses
   * (design/44 §4) — a re-enqueue with {@code available_at = now + backoff(attempt)}, no
   * controller-side timer. Otherwise identical to {@link #enqueue}.
   *
   * @return the generated task id.
   */
  @SqlUpdate(
      "INSERT INTO titan.task_queue (type, queue_name, status, priority, payload_json, "
          + "attempts, max_attempts, visibility_timeout_seconds, available_at, build_id, node_id, "
          + "trace_parent) "
          + "VALUES (:type, :queueName, 'QUEUED', :priority, :payloadJson, 0, :maxAttempts, "
          + ":visibilityTimeoutSeconds, :availableAt, :buildId, :nodeId, :traceParent)")
  @GetGeneratedKeys
  long enqueueDelayed(
      @Bind("type") @NonNull String type,
      @Bind("queueName") @NonNull String queueName,
      @Bind("priority") int priority,
      @Bind("payloadJson") @NonNull String payloadJson,
      @Bind("maxAttempts") int maxAttempts,
      @Bind("visibilityTimeoutSeconds") int visibilityTimeoutSeconds,
      @Bind("availableAt") @NonNull java.time.Instant availableAt,
      @Bind("buildId") @Nullable Long buildId,
      @Bind("nodeId") @Nullable String nodeId,
      @Bind("traceParent") @Nullable String traceParent);

  /**
   * Back-compat overload of {@link #enqueueDelayed} that omits {@code traceParent} (binds null) —
   * see {@link #enqueue} for the rationale.
   */
  default long enqueueDelayed(
      @NonNull String type,
      @NonNull String queueName,
      int priority,
      @NonNull String payloadJson,
      int maxAttempts,
      int visibilityTimeoutSeconds,
      @NonNull java.time.Instant availableAt,
      @Nullable Long buildId,
      @Nullable String nodeId) {
    return enqueueDelayed(
        type,
        queueName,
        priority,
        payloadJson,
        maxAttempts,
        visibilityTimeoutSeconds,
        availableAt,
        buildId,
        nodeId,
        null);
  }

  // ---------------------------------------------------------------------
  // Claim — atomic, lease-token-bearing
  // ---------------------------------------------------------------------

  /**
   * Atomically claim the highest-priority claimable task in {@code queueName}, writing the supplied
   * {@code claimToken} as the lease. "Claimable" means {@code status='QUEUED'} and {@code
   * available_at <= now()}.
   *
   * <p>Runs select + update in one transaction. The {@code SELECT ... FOR UPDATE SKIP LOCKED} row
   * lock is held for the transaction's duration, so two concurrent workers never claim the same row
   * (no double-delivery) and never block on each other — the loser skips the locked row and takes
   * the next. {@code attempts} is incremented on every claim so the reaper can enforce {@code
   * max_attempts}.
   *
   * @return the freshly-claimed row, or empty if the queue had nothing claimable.
   */
  @Transaction
  @NonNull
  default Optional<TaskQueueRow> claim(
      @NonNull String agentId, @NonNull String queueName, @NonNull UUID claimToken) {
    Long taskId = selectClaimableId(queueName);
    if (taskId == null) {
      return Optional.empty();
    }
    markClaimed(taskId, agentId, claimToken);
    return findById(taskId);
  }

  /**
   * Controller-side claim primitive: claim the highest-priority claimable {@code ORCHESTRATE} task
   * on {@code queueName}, mirroring {@link #claimExecuteCommand} on the worker side. The {@code
   * type='ORCHESTRATE'} filter is load-bearing — controllers and workers share the {@code default}
   * queue (see design/38), so without it the controller would happily claim an {@code
   * EXECUTE_COMMAND} step task it cannot dispatch, fail it with "unknown action 'null'", and fail
   * the build (issue #486). The reciprocal filter on the worker side is in {@link
   * #selectClaimableExecuteCommandId}.
   *
   * @return the freshly-claimed row, or empty if no {@code ORCHESTRATE} task is claimable.
   */
  @Transaction
  @NonNull
  default Optional<TaskQueueRow> claimTask(@NonNull String queueName, @NonNull String claimedBy) {
    Long taskId = selectClaimableOrchestrateId(queueName);
    if (taskId == null) {
      return Optional.empty();
    }
    UUID token = UUID.randomUUID();
    markClaimed(taskId, claimedBy, token);
    return findById(taskId);
  }

  /**
   * Atomically claim the highest-priority claimable {@code EXECUTE_COMMAND} task in {@code
   * queueName}. Identical to {@link #claim} but adds a {@code type='EXECUTE_COMMAND'} filter so a
   * worker never accidentally consumes an {@code ORCHESTRATE} task that happens to share the same
   * queue name. This mirrors the {@code type='EXECUTE_COMMAND'} filter in {@code WorkerDb.claim}.
   *
   * @return the freshly-claimed row, or empty if no {@code EXECUTE_COMMAND} task is claimable.
   */
  @Transaction
  @NonNull
  default Optional<TaskQueueRow> claimExecuteCommand(
      @NonNull String agentId, @NonNull String queueName, @NonNull UUID claimToken) {
    Long taskId = selectClaimableExecuteCommandId(queueName);
    if (taskId == null) {
      return Optional.empty();
    }
    markClaimed(taskId, agentId, claimToken);
    return findById(taskId);
  }

  /**
   * The claim primitive. {@code FOR UPDATE SKIP LOCKED} makes concurrent workers skip rows already
   * locked by a peer. {@code available_at <= now()} enforces the visibility gate.
   */
  @SqlQuery(
      "SELECT id FROM titan.task_queue WHERE status = 'QUEUED' AND queue_name = :queueName "
          + "AND available_at <= CURRENT_TIMESTAMP "
          + "ORDER BY priority DESC, created_at LIMIT 1 FOR UPDATE SKIP LOCKED")
  Long selectClaimableId(@Bind("queueName") @NonNull String queueName);

  /**
   * Type-filtered variant of {@link #selectClaimableId} — selects only {@code EXECUTE_COMMAND}
   * rows. Used by {@link #claimExecuteCommand} so workers never steal {@code ORCHESTRATE} tasks.
   */
  @SqlQuery(
      "SELECT id FROM titan.task_queue WHERE status = 'QUEUED' AND queue_name = :queueName "
          + "AND type = 'EXECUTE_COMMAND' "
          + "AND available_at <= CURRENT_TIMESTAMP "
          + "ORDER BY priority DESC, created_at LIMIT 1 FOR UPDATE SKIP LOCKED")
  Long selectClaimableExecuteCommandId(@Bind("queueName") @NonNull String queueName);

  /**
   * Type-filtered variant of {@link #selectClaimableId} — selects only {@code ORCHESTRATE} rows.
   * Used by {@link #claimTask} so controllers never steal {@code EXECUTE_COMMAND} step tasks that
   * sit on the same shared {@code default} queue (issue #486).
   */
  @SqlQuery(
      "SELECT id FROM titan.task_queue WHERE status = 'QUEUED' AND queue_name = :queueName "
          + "AND type = 'ORCHESTRATE' "
          + "AND available_at <= CURRENT_TIMESTAMP "
          + "ORDER BY priority DESC, created_at LIMIT 1 FOR UPDATE SKIP LOCKED")
  Long selectClaimableOrchestrateId(@Bind("queueName") @NonNull String queueName);

  @SqlUpdate(
      "UPDATE titan.task_queue SET status = 'CLAIMED', claim_token = :claimToken, "
          + "claimed_by = :claimedBy, claimed_at = CURRENT_TIMESTAMP, attempts = attempts + 1 "
          + "WHERE id = :id")
  void markClaimed(
      @Bind("id") long id,
      @Bind("claimedBy") @NonNull String claimedBy,
      @Bind("claimToken") @NonNull UUID claimToken);

  /** Transition a claimed task to {@code PROCESSING} — the agent has started executing it. */
  @SqlUpdate(
      "UPDATE titan.task_queue SET status = 'PROCESSING' "
          + "WHERE id = :id AND claim_token = :claimToken AND status = 'CLAIMED'")
  int markProcessing(@Bind("id") long id, @Bind("claimToken") @NonNull UUID claimToken);

  // ---------------------------------------------------------------------
  // Completion — token-guarded (doc-27 G3)
  // ---------------------------------------------------------------------

  /**
   * Terminally complete a task <em>only if the supplied {@code claimToken} still matches</em> the
   * row's lease. A zombie worker whose task was reaped and re-claimed by a peer holds a stale token
   * and is rejected (0 rows). The status + result are written in a single {@code UPDATE} (no torn
   * read).
   *
   * @param status one of {@code COMPLETED}, {@code FAILED}, {@code TIMED_OUT}, {@code CANCELLED}.
   * @return rows affected — {@code 1} if the completion was accepted, {@code 0} if rejected as
   *     stale (token mismatch or task already terminal).
   */
  @SqlUpdate(
      "UPDATE titan.task_queue SET status = :status, result_json = :resultJson, "
          + "completed_at = CURRENT_TIMESTAMP "
          + "WHERE id = :id AND claim_token = :claimToken "
          + "AND status IN ('CLAIMED','PROCESSING')")
  int complete(
      @Bind("id") long id,
      @Bind("claimToken") @NonNull UUID claimToken,
      @Bind("status") @NonNull String status,
      @Bind("resultJson") @Nullable String resultJson);

  /** Mark a CLAIMED task as COMPLETED with its result. Returns true if 1 row affected. */
  default boolean completeTask(long id, @NonNull String resultJson) {
    return completeTaskUpdate(id, resultJson) == 1;
  }

  @SqlUpdate(
      "UPDATE titan.task_queue SET status = 'COMPLETED', result_json = :resultJson, "
          + "completed_at = CURRENT_TIMESTAMP "
          + "WHERE id = :id AND status IN ('CLAIMED','PROCESSING')")
  int completeTaskUpdate(@Bind("id") long id, @Bind("resultJson") @NonNull String resultJson);

  /** Mark a CLAIMED task as FAILED with its result. Returns true if 1 row affected. */
  default boolean failTask(long id, @NonNull String resultJson) {
    return failTaskUpdate(id, resultJson) == 1;
  }

  @SqlUpdate(
      "UPDATE titan.task_queue SET status = 'FAILED', result_json = :resultJson, "
          + "completed_at = CURRENT_TIMESTAMP "
          + "WHERE id = :id AND status IN ('CLAIMED','PROCESSING')")
  int failTaskUpdate(@Bind("id") long id, @Bind("resultJson") @NonNull String resultJson);

  // ---------------------------------------------------------------------
  // Cancel
  // ---------------------------------------------------------------------

  /** Cancel a QUEUED, CLAIMED or PROCESSING task. Returns true if 1 row affected. */
  default boolean cancel(long id) {
    return cancelUpdate(id) == 1;
  }

  /** Back-compat alias for {@link #cancel}. */
  default boolean cancelTask(long id) {
    return cancel(id);
  }

  @SqlUpdate(
      "UPDATE titan.task_queue SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP "
          + "WHERE id = :id AND status IN ('QUEUED','CLAIMED','PROCESSING')")
  int cancelUpdate(@Bind("id") long id);

  /**
   * Stamp {@code cancel_requested_at = now()} on every live task for {@code buildId} — the cancel
   * <em>intent</em> signal (#668), distinct from the terminal {@code status='CANCELLED'} that
   * {@link #cancel} writes. A worker mid-step polls this column on its own row from the heartbeat
   * loop, sees the stamp, and SIGTERMs/SIGKILLs the running subprocess; the worker then completes
   * the task as CANCELLED through the normal token-guarded {@link #complete} path (so the lease
   * round-trip is preserved — no controller-vs-worker race over the row).
   *
   * <p>Idempotent: only un-stamped, still-live rows are touched, so a double-cancel changes zero
   * rows on the second call. Safe to call alongside {@link #cancel} — the columns are independent.
   *
   * @return number of rows where a fresh intent was stamped (i.e. previously NULL).
   */
  @SqlUpdate(
      "UPDATE titan.task_queue SET cancel_requested_at = CURRENT_TIMESTAMP "
          + "WHERE build_id = :buildId "
          + "AND status IN ('QUEUED','CLAIMED','PROCESSING') "
          + "AND cancel_requested_at IS NULL")
  int markCancelRequested(@Bind("buildId") long buildId);

  /**
   * Read the {@code cancel_requested_at} stamp for a task, if any (#668). Used by the worker's
   * heartbeat to detect a cancel intent it must honour. Returns empty if the task does not exist,
   * never stamped, or has no row in the live queue (archived).
   */
  @SqlQuery(
      "SELECT cancel_requested_at FROM titan.task_queue "
          + "WHERE id = :taskId AND cancel_requested_at IS NOT NULL")
  @NonNull
  Optional<java.time.Instant> findCancelRequested(@Bind("taskId") long taskId);

  // ---------------------------------------------------------------------
  // Admin — drain + reorder (UI Queue page admin controls, #347)
  // ---------------------------------------------------------------------

  /**
   * Cancel every currently-{@code QUEUED} task — the admin "drain" action from the UI Queue page
   * (#347). Sets {@code status='CANCELLED'} and {@code completed_at=now()} for every row still in
   * the {@code QUEUED} state. Idempotent: a second call with an empty queue affects zero rows.
   * In-flight ({@code CLAIMED}/{@code PROCESSING}) tasks are untouched — drain is a queue-clear,
   * not a worker-kill.
   *
   * @return number of tasks moved from {@code QUEUED} to {@code CANCELLED}.
   */
  @SqlUpdate(
      "UPDATE titan.task_queue SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP "
          + "WHERE status = 'QUEUED'")
  int drainAllQueued();

  /**
   * Overwrite the {@code priority} of a single {@code QUEUED} task — building block for the admin
   * "reorder" action (#347). The worker claim order is {@code priority DESC, created_at ASC}, so
   * the caller assigns descending priorities (head-first) to enforce a new order. Only {@code
   * QUEUED} rows are touched; a task that has already been claimed cannot be reordered.
   *
   * @return rows affected — {@code 1} if updated, {@code 0} if the id is unknown or no longer
   *     {@code QUEUED}.
   */
  @SqlUpdate(
      "UPDATE titan.task_queue SET priority = :priority " + "WHERE id = :id AND status = 'QUEUED'")
  int setPriority(@Bind("id") long id, @Bind("priority") int priority);

  // ---------------------------------------------------------------------
  // Reaper — visibility-timeout recovery (doc-27 G3/G4)
  // ---------------------------------------------------------------------

  /**
   * Outcome of a {@link #reapStale} sweep.
   *
   * @param requeued number of stale tasks reset to {@code QUEUED} for retry.
   * @param failed number of stale tasks moved to {@code FAILED} because they had exhausted {@code
   *     max_attempts}.
   */
  record ReapResult(int requeued, int failed) {}

  /**
   * Reap stale tasks: {@code CLAIMED}/{@code PROCESSING} rows whose {@code claimed_at} is older
   * than {@code visibilityTimeoutSeconds}. A zombie agent's task is recovered so it can run
   * elsewhere.
   *
   * <ul>
   *   <li>If {@code attempts >= max_attempts} the task is terminally {@code FAILED} — retried
   *       enough, never claimable again.
   *   <li>Otherwise it is reset to {@code QUEUED} with {@code claim_token}, {@code claimed_by} and
   *       {@code claimed_at} cleared, and {@code available_at} bumped to now so it is immediately
   *       re-claimable. The old lease token is now dead, so a late completion from the original
   *       (zombie) claimant is rejected by {@link #complete} (doc-27 G3).
   * </ul>
   *
   * <p>The two updates run in one transaction. Returns the counts.
   */
  @Transaction
  default ReapResult reapStale(int visibilityTimeoutSeconds) {
    java.sql.Timestamp cutoff = cutoff(visibilityTimeoutSeconds);
    int failed = reapExhausted(cutoff);
    int requeued = reapRequeue(cutoff);
    return new ReapResult(requeued, failed);
  }

  /**
   * Fail stale tasks that have exhausted their retry budget.
   *
   * <p>The cutoff timestamp is computed in Java and bound as a {@link java.sql.Timestamp} rather
   * than as {@code CURRENT_TIMESTAMP - (:n * INTERVAL '1' SECOND)}: that arithmetic is valid on
   * PostgreSQL but H2 rejects it with {@code "UNKNOWN * INTERVAL SECOND"} because it cannot infer
   * the type of a bound integer multiplied by an interval. A bound timestamp is portable across
   * both dialects, which is what unblocks {@code @QuarkusTest} startup (#518). Containers in the
   * rig share the host clock, so controller-vs-DB skew is negligible against the multi-minute
   * visibility window.
   */
  @SqlUpdate(
      "UPDATE titan.task_queue SET status = 'FAILED', "
          + "result_json = '{\"error\":\"visibility timeout exceeded; retries exhausted\"}', "
          + "completed_at = CURRENT_TIMESTAMP "
          + "WHERE status IN ('CLAIMED','PROCESSING') AND attempts >= max_attempts "
          + "AND claimed_at < :cutoff")
  int reapExhausted(@Bind("cutoff") @NonNull java.sql.Timestamp cutoff);

  /** Reset stale-but-retryable tasks back to QUEUED, killing the dead lease. */
  @SqlUpdate(
      "UPDATE titan.task_queue SET status = 'QUEUED', claim_token = NULL, "
          + "claimed_by = NULL, claimed_at = NULL, available_at = CURRENT_TIMESTAMP "
          + "WHERE status IN ('CLAIMED','PROCESSING') AND attempts < max_attempts "
          + "AND claimed_at < :cutoff")
  int reapRequeue(@Bind("cutoff") @NonNull java.sql.Timestamp cutoff);

  /** Wall-clock timestamp {@code seconds} in the past — the visibility-timeout cutoff. */
  private static java.sql.Timestamp cutoff(int seconds) {
    return new java.sql.Timestamp(System.currentTimeMillis() - seconds * 1000L);
  }

  // ---------------------------------------------------------------------
  // Archival
  // ---------------------------------------------------------------------

  /**
   * Move completed/failed/cancelled tasks from {@code task_queue} to {@code task_archive}. Returns
   * the number of tasks moved. Runs in a single transaction.
   */
  @Transaction
  default int moveCompletedToArchive(int batchSize) {
    List<Long> ids = selectArchivableIds(batchSize);
    for (long id : ids) {
      archiveTask(id);
      deleteById(id);
    }
    return ids.size();
  }

  @SqlQuery(
      "SELECT id FROM titan.task_queue WHERE status IN ('COMPLETED','FAILED','CANCELLED') "
          + "AND completed_at IS NOT NULL LIMIT :batchSize")
  List<Long> selectArchivableIds(@Bind("batchSize") int batchSize);

  // NOTE: id is intentionally OMITTED from the column list — task_archive.id has its
  // own sequence default (migration V19), so the destination mints a fresh, collision-free
  // key rather than re-using task_queue.id (which collides with any pre-seeded archive
  // rows, e.g. rig/local/seed-data.sh). task_token is the durable join key shared with
  // titan.logs.task_id and BuildLogsSse, and carries a UNIQUE constraint that lets us
  // express "already archived" declaratively via a NOT EXISTS guard on the SELECT —
  // idempotent re-runs of the sweep are a no-op, not a duplicate-key crash. We use
  // NOT EXISTS (rather than PG's ON CONFLICT DO NOTHING) because H2 — used by the unit
  // test profile — rejects ON CONFLICT syntax; NOT EXISTS is ANSI-portable and yields
  // the same at-most-once semantics under our single-row, transactional sweep path.
  @SqlUpdate(
      "INSERT INTO titan.task_archive (type, queue_name, status, priority, "
          + "payload_json, result_json, attempts, max_attempts, visibility_timeout_seconds, "
          + "claim_token, claimed_by, claimed_at, available_at, build_id, node_id, task_token, "
          + "created_at, completed_at, trace_parent, cancel_requested_at) "
          + "SELECT q.type, q.queue_name, q.status, q.priority, q.payload_json, q.result_json, "
          + "q.attempts, q.max_attempts, q.visibility_timeout_seconds, q.claim_token, q.claimed_by, "
          + "q.claimed_at, q.available_at, q.build_id, q.node_id, q.task_token, q.created_at, "
          + "q.completed_at, q.trace_parent, q.cancel_requested_at "
          + "FROM titan.task_queue q "
          + "WHERE q.id = :id "
          + "AND NOT EXISTS (SELECT 1 FROM titan.task_archive a WHERE a.task_token = q.task_token)")
  void archiveTask(@Bind("id") long id);

  /** Hard delete by id. */
  @SqlUpdate("DELETE FROM titan.task_queue WHERE id = :id")
  void deleteById(@Bind("id") long id);

  /**
   * Delete archived task rows whose {@code completed_at} is older than {@code cutoff} — the daily
   * retention-horizon pruner (issue #633). {@code titan.task_archive} otherwise grows unbounded:
   * every terminal task ever processed lives there forever and the table eventually dominates the
   * DB. A scheduled caller computes {@code cutoff = now - retentionDays * 24h} in Java and binds it
   * here (Java-side cutoff for H2/PG portability — mirrors the {@link #reapStale} pattern from PR
   * #520; in-DB {@code INTERVAL} math is rejected by H2).
   *
   * @return number of archive rows deleted (0 if nothing was past the horizon).
   */
  @SqlUpdate("DELETE FROM titan.task_archive WHERE completed_at < :cutoff")
  int deleteArchiveOlderThan(@Bind("cutoff") @NonNull java.sql.Timestamp cutoff);

  /**
   * Delete every {@code titan.task_archive} row for a build — used by the per-job build-retention
   * pruner (issue #637). {@code task_archive} carries no foreign key on {@code build_id}, so the
   * {@code ON DELETE CASCADE} that cleans {@code task_queue} / {@code flow_nodes} / {@code
   * artifact} when a {@code titan.builds} row is dropped does <strong>not</strong> reach the
   * archive. The pruner calls this explicitly <em>before</em> deleting the build row; the order
   * matters only inasmuch as the build-drop cascade does not touch the archive either way.
   * Idempotent: a build with no archive rows is a no-op.
   *
   * @return number of archive rows deleted
   */
  @SqlUpdate("DELETE FROM titan.task_archive WHERE build_id = :buildId")
  int deleteArchiveByBuild(@Bind("buildId") long buildId);
}

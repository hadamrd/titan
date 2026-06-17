package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.ApprovalRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindFields;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.approvals} — the durable side of the {@code approval:}
 * parked-step gate (#715).
 *
 * <p>One row per <em>in-flight</em> approval gate: inserted PENDING by the orchestrator when it
 * parks an {@code approval:} step (mirror of the SLEEPING transition the wait steps use), flipped
 * terminal by either the REST decide endpoint (APPROVE / REJECT) or the sweep tick (TIMED_OUT). The
 * compare-and-set on PENDING is what makes a double-submit a clean 409 — no extra state.
 *
 * <p>Errors are translated to {@code TitanDataException} by the {@code TitanStores} wrapper.
 */
@RegisterFieldMapper(ApprovalRow.class)
public interface ApprovalsDao {

  String COLS =
      "id, build_id, flow_node_id, prompt, approvers_json, status, "
          + "decided_by, decided_at, expires_at, created_at";

  /** Insert a new PENDING approval row. Returns the generated id. */
  @SqlUpdate(
      "INSERT INTO titan.approvals "
          + "(build_id, flow_node_id, prompt, approvers_json, status, expires_at) "
          + "VALUES (:buildId, :flowNodeId, :prompt, :approversJson, 'PENDING', :expiresAt)")
  @GetGeneratedKeys
  long insertPending(@BindFields ApprovalRow row);

  /** Lookup by id — backs the decide endpoints. */
  @SqlQuery("SELECT " + COLS + " FROM titan.approvals WHERE id = :id")
  @NonNull
  Optional<ApprovalRow> findById(@Bind("id") long id);

  /**
   * The most-recent in-flight approval row for a (build, flow node) pair — what the orchestrator
   * looks up on every advance() to see whether a parked approval has been decided. A replay re-uses
   * the same node id so we always want the latest row, not the first.
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.approvals "
          + "WHERE build_id = :buildId AND flow_node_id = :flowNodeId "
          + "ORDER BY id DESC LIMIT 1")
  @NonNull
  Optional<ApprovalRow> findLatestForNode(
      @Bind("buildId") long buildId, @Bind("flowNodeId") @NonNull String flowNodeId);

  /**
   * List approval rows by status, newest-first. Used by {@code GET /api/v1/approvals?status=…}.
   * Status is required by the API contract; callers pass the wire string verbatim (PENDING,
   * APPROVED, REJECTED, TIMED_OUT).
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.approvals WHERE status = :status "
          + "ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
  @NonNull
  List<ApprovalRow> listByStatus(
      @Bind("status") @NonNull String status, @Bind("limit") int limit, @Bind("offset") int offset);

  /**
   * Compare-and-set the PENDING row terminal — the one path APPROVE / REJECT take. The {@code WHERE
   * status = 'PENDING'} guard is what makes a double-submit a 0-row no-op (the API turns that into
   * a 409). Sets {@code decided_by} and {@code decided_at} in the same UPDATE so the DB CHECK
   * constraint (which requires both-or-neither) is never violated mid-transition.
   */
  @SqlUpdate(
      "UPDATE titan.approvals "
          + "SET status = :toStatus, decided_by = :decidedBy, decided_at = :decidedAt "
          + "WHERE id = :id AND status = 'PENDING'")
  int decideIfPending(
      @Bind("id") long id,
      @Bind("toStatus") @NonNull String toStatus,
      @Bind("decidedBy") @NonNull String decidedBy,
      @Bind("decidedAt") @NonNull Instant decidedAt);

  /**
   * Claim every PENDING row whose {@code expires_at} has passed and flip it TIMED_OUT in one pass —
   * the sweep tick. {@code decided_by = '<timeout>'} is the synthetic decider; {@code decided_at}
   * is the wall-clock the sweep ran at. Returns the count of rows that timed out, which is what the
   * caller logs.
   *
   * <p>A PENDING row whose expiry is in the future is untouched. A row already in a terminal status
   * is untouched (the {@code WHERE status = 'PENDING'} guard).
   */
  @SqlUpdate(
      "UPDATE titan.approvals "
          + "SET status = 'TIMED_OUT', decided_by = '<timeout>', decided_at = :now "
          + "WHERE status = 'PENDING' AND expires_at <= :now")
  int sweepTimedOut(@Bind("now") @NonNull Instant now);

  /**
   * List the PENDING rows whose {@code expires_at} has passed — used by the sweep loop in
   * combination with {@link #sweepTimedOut} so the caller can enqueue an ADVANCE for each affected
   * build (the orchestrator then resumes the parked node as FAILED on the next pass).
   *
   * <p>Returns rows captured <em>before</em> the bulk UPDATE runs so callers see exactly which
   * (build, node) pairs need an ADVANCE — calling this after the UPDATE would return an empty list.
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.approvals "
          + "WHERE status = 'PENDING' AND expires_at <= :now "
          + "ORDER BY id")
  @NonNull
  List<ApprovalRow> listExpired(@Bind("now") @NonNull Instant now);

  /**
   * Delete every row of a build — companion to the build reaper. The {@code ON DELETE CASCADE} on
   * the FK already handles full-build deletion; this entry point exists for tests that want a clean
   * slate without dropping the build.
   */
  @SqlUpdate("DELETE FROM titan.approvals WHERE build_id = :buildId")
  int deleteByBuild(@Bind("buildId") long buildId);

  /** Optional filter to scope listByStatus to a single build (UI convenience). */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.approvals WHERE build_id = :buildId "
          + "ORDER BY created_at DESC, id DESC")
  @NonNull
  List<ApprovalRow> listForBuild(@Bind("buildId") long buildId);

  /**
   * Count of rows matching a status filter — used by the paginated list endpoint to populate {@code
   * total}. {@code status} is required; {@code null} is not a valid call.
   */
  @SqlQuery("SELECT COUNT(*) FROM titan.approvals WHERE status = :status")
  long countByStatus(@Bind("status") @NonNull String status);

  /**
   * Test-only escape hatch: backdate a row's {@code expires_at} so {@link #sweepTimedOut} fires in
   * a unit test without waiting wall-clock seconds. Production code never calls this — the API
   * resource has no path to it.
   */
  @SqlUpdate("UPDATE titan.approvals SET expires_at = :expiresAt WHERE id = :id")
  int testOnlySetExpiresAt(@Bind("id") long id, @Bind("expiresAt") @NonNull Instant expiresAt);

  /** Best-effort: stamp the prompt of an existing row (rare — for re-park scenarios). */
  @SqlUpdate("UPDATE titan.approvals SET prompt = :prompt WHERE id = :id")
  int updatePrompt(@Bind("id") long id, @Bind("prompt") @Nullable String prompt);
}

package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.DiscoveryEventRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindFields;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;

/**
 * JDBI SqlObject access for {@code titan.discovery_events} — the queue of observed pipeline files
 * the {@code DiscoveryWorker} drains.
 *
 * <p>Dedup is enforced by the {@code (source_id, repo, commit_sha)} unique constraint; {@link
 * #enqueue} avoids exception-driven control flow with portable {@code INSERT ... WHERE NOT EXISTS}
 * / {@code UPDATE} statements. A {@code failed} row of the same tuple is not permanently dead:
 * {@code enqueue} re-arms it to {@code pending} until {@link #MAX_ATTEMPTS} is reached, so a
 * transient failure retries while a genuinely broken pipeline eventually stops.
 *
 * <p>The {@link #claimNextPending} batch claim is token-correlated: each call mints a fresh {@code
 * claim_token}, stamps it onto the rows it flips to {@code processing}, then selects exactly the
 * rows bearing that token — so the returned set is precisely what this call claimed, never a stale
 * {@code processing} row from a crashed peer tick. {@link #reclaimStale} is the designed recovery
 * for rows stuck {@code processing} because a tick crashed mid-drain. The claim is a portable
 * {@code UPDATE ... WHERE id IN (SELECT ... LIMIT n)} — no {@code FOR UPDATE SKIP LOCKED}, so it
 * runs unchanged on H2 (MODE=PostgreSQL) and PostgreSQL.
 */
@RegisterFieldMapper(DiscoveryEventRow.class)
public interface DiscoveryEventDao {

  String COLS =
      "id, source_id, repo, branch, commit_sha, event_type, payload_json, status, "
          + "received_at, processed_at, outcome_message, attempt_count, claim_token, claimed_at";

  /**
   * How many times a {@code (source_id, repo, commit_sha)} tuple may be processed before {@link
   * #enqueue} stops re-arming it. A genuinely broken pipeline (same commit, fails every time) is
   * left {@code failed} once it reaches this cap rather than retrying forever.
   */
  int MAX_ATTEMPTS = 5;

  /**
   * Outcome of {@link #enqueue} — what the scan did with the {@code (source, repo, commit)} tuple.
   */
  enum EnqueueResult {
    /** No row existed; a fresh {@code pending} event was inserted. */
    INSERTED,
    /** A {@code failed} row under the attempt cap was re-armed to {@code pending}. */
    REARMED,
    /**
     * A row already existed in a non-re-armable state (pending/processing/done, or failed at cap).
     */
    UNCHANGED
  }

  // ──────────────────────────────────────────────
  // Queries
  // ──────────────────────────────────────────────
  @SqlQuery("SELECT " + COLS + " FROM titan.discovery_events WHERE id = :id")
  @NonNull
  Optional<DiscoveryEventRow> findById(@Bind("id") long id);

  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.discovery_events WHERE source_id = :sourceId "
          + "ORDER BY received_at")
  @NonNull
  List<DiscoveryEventRow> listBySource(@Bind("sourceId") long sourceId);

  /** Select the rows currently owned by {@code claimToken}, oldest-first. */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.discovery_events "
          + "WHERE claim_token = :claimToken ORDER BY received_at")
  @NonNull
  List<DiscoveryEventRow> selectClaimed(@Bind("claimToken") @NonNull String claimToken);

  // ──────────────────────────────────────────────
  // Enqueue — insert-or-re-arm, portable, no exception-driven control flow
  // ──────────────────────────────────────────────
  /**
   * Make the {@code (source_id, repo, commit_sha)} tuple of {@code row} a claimable {@code pending}
   * event:
   *
   * <ul>
   *   <li>no row exists → insert a fresh {@code pending} event ({@link EnqueueResult#INSERTED});
   *   <li>a {@code failed} row exists with {@code attempt_count < }{@link #MAX_ATTEMPTS} → re-arm
   *       it: {@code status='pending'}, {@code claim_token}/{@code claimed_at}/{@code
   *       outcome_message} cleared, {@code attempt_count} left as-is ({@link
   *       EnqueueResult#REARMED});
   *   <li>a {@code pending}/{@code processing}/{@code done} row, or a {@code failed} row at the
   *       attempt cap → left untouched ({@link EnqueueResult#UNCHANGED}).
   * </ul>
   *
   * <p>Both branches are portable SQL (no caught unique-constraint violation). Running the same
   * scan twice converges the row, so this is idempotent.
   *
   * @return what happened to the tuple — the worker logs it.
   */
  @Transaction
  @NonNull
  default EnqueueResult enqueue(@NonNull DiscoveryEventRow row) {
    if (insertIfAbsent(row) == 1) {
      return EnqueueResult.INSERTED;
    }
    if (rearmFailed(row) == 1) {
      return EnqueueResult.REARMED;
    }
    return EnqueueResult.UNCHANGED;
  }

  /**
   * Insert the event only if no row exists for its {@code (source_id, repo, commit_sha)} tuple.
   * Portable {@code WHERE NOT EXISTS} guard; returns {@code 1} when inserted, {@code 0} otherwise.
   */
  @SqlUpdate(
      "INSERT INTO titan.discovery_events "
          + "(source_id, repo, branch, commit_sha, event_type, payload_json, status) "
          + "SELECT :sourceId, :repo, :branch, :commitSha, :eventType, :payloadJson, :status "
          + "WHERE NOT EXISTS (SELECT 1 FROM titan.discovery_events "
          + "WHERE source_id = :sourceId AND repo = :repo "
          + "AND (commit_sha = :commitSha OR (commit_sha IS NULL AND :commitSha IS NULL)))")
  int insertIfAbsent(@BindFields DiscoveryEventRow row);

  /**
   * Re-arm an existing {@code failed} row of the tuple back to {@code pending} — but only while it
   * is under the {@link #MAX_ATTEMPTS} cap. {@code attempt_count} is preserved so the cap still
   * bites on the next failure. Returns {@code 1} when a row was re-armed, {@code 0} otherwise (no
   * row, non-{@code failed} row, or {@code failed} at the cap).
   */
  @SqlUpdate(
      "UPDATE titan.discovery_events "
          + "SET status = 'pending', claim_token = NULL, claimed_at = NULL, outcome_message = NULL "
          + "WHERE source_id = :sourceId AND repo = :repo "
          + "AND (commit_sha = :commitSha OR (commit_sha IS NULL AND :commitSha IS NULL)) "
          + "AND status = 'failed' AND attempt_count < "
          + MAX_ATTEMPTS)
  int rearmFailed(@BindFields DiscoveryEventRow row);

  // ──────────────────────────────────────────────
  // Claim — token-correlated batch claim
  // ──────────────────────────────────────────────
  /**
   * Claim up to {@code limit} oldest pending events and return <em>exactly</em> the rows this call
   * claimed. A fresh {@code claim_token} (UUID) is minted per call: the batch {@code UPDATE} stamps
   * it onto the rows it flips to {@code processing}, and the follow-up {@code SELECT} reads back
   * only the rows bearing that token. A stale {@code processing} row from a crashed or overlapping
   * peer tick carries a different token, so it can never leak into this call's result.
   *
   * <p>Composed from a portable batch {@code UPDATE} plus a {@code SELECT}, run in one transaction,
   * so it works on both H2 and PostgreSQL. Rows stuck {@code processing} because a tick crashed
   * mid-drain are recovered by {@link #reclaimStale}, not by this method.
   */
  @Transaction
  @NonNull
  default List<DiscoveryEventRow> claimNextPending(int limit) {
    String token = UUID.randomUUID().toString();
    int claimed = markPendingAsProcessing(token, limit);
    if (claimed == 0) {
      return List.of();
    }
    return selectClaimed(token);
  }

  /**
   * Flip up to {@code limit} oldest {@code pending} rows to {@code processing}, stamping {@code
   * claimToken} and {@code claimed_at} onto exactly those rows. Returns the number claimed.
   */
  @SqlUpdate(
      "UPDATE titan.discovery_events "
          + "SET status = 'processing', claim_token = :claimToken, claimed_at = CURRENT_TIMESTAMP "
          + "WHERE id IN (SELECT id FROM titan.discovery_events WHERE status = 'pending' "
          + "ORDER BY received_at LIMIT :limit)")
  int markPendingAsProcessing(
      @Bind("claimToken") @NonNull String claimToken, @Bind("limit") int limit);

  /**
   * Reclaim stale claims: rows stuck {@code processing} whose {@code claimed_at} predates {@code
   * cutoff} are reset to {@code pending} with {@code claim_token}/{@code claimed_at} cleared. This
   * is the designed recovery for an event whose owning tick crashed mid-drain — the row becomes
   * claimable again on the next scan. {@code attempt_count} is left as-is (the row never reached
   * {@code failed}, so it was not a processing <em>failure</em>). Returns the number reclaimed.
   */
  @SqlUpdate(
      "UPDATE titan.discovery_events "
          + "SET status = 'pending', claim_token = NULL, claimed_at = NULL "
          + "WHERE status = 'processing' AND claimed_at < :cutoff")
  int reclaimStale(@Bind("cutoff") @NonNull Instant cutoff);

  // ──────────────────────────────────────────────
  // Status transitions
  // ──────────────────────────────────────────────
  /**
   * Transition an event to {@code status}, recording {@code message} as its outcome.
   *
   * <ul>
   *   <li>On a transition to {@code failed}, {@code attempt_count} is incremented — the {@link
   *       #enqueue} re-arm cap counts these.
   *   <li>On any terminal transition ({@code done}/{@code failed}), {@code processed_at} is stamped
   *       and {@code claim_token}/{@code claimed_at} are cleared — the row is no longer owned, so a
   *       stale {@link #reclaimStale} sweep cannot touch it.
   * </ul>
   *
   * Portable SQL — the conditional updates use {@code CASE}, not a dialect feature.
   */
  @SqlUpdate(
      "UPDATE titan.discovery_events SET status = :status, outcome_message = :message, "
          + "attempt_count = attempt_count + CASE WHEN :status = 'failed' THEN 1 ELSE 0 END, "
          + "processed_at = CASE WHEN :status IN ('done','failed') THEN CURRENT_TIMESTAMP "
          + "ELSE processed_at END, "
          + "claim_token = CASE WHEN :status IN ('done','failed') THEN NULL ELSE claim_token END, "
          + "claimed_at = CASE WHEN :status IN ('done','failed') THEN NULL ELSE claimed_at END "
          + "WHERE id = :id")
  void updateStatus(
      @Bind("id") long id,
      @Bind("status") @NonNull String status,
      @Bind("message") @Nullable String message);
}

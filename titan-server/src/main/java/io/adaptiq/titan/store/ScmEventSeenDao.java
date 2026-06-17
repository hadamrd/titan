package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.scm_event_seen} (V36) — the dispatch-side idempotency
 * boundary shared by the webhook hot-path and the reconcile/poll replay path (issue #1118).
 *
 * <p>The unique constraint on {@code (provider, event_id)} is the single source of truth for
 * dedupe. {@link #insertClaim} is a plain INSERT (NOT {@code ON CONFLICT} — that form is
 * unsupported under H2's PG-mode subset; see {@code TaskQueueDao}/{@code StarredJobsDao}); a second
 * writer for the same {@code (provider, event_id)} therefore raises a duplicate-key exception which
 * {@link io.adaptiq.titan.scm.reconcile.JdbiEventDedupeStore#markSeen} treats as "already seen".
 * One row across both paths ⇒ exactly-once dispatch.
 */
public interface ScmEventSeenDao {

  /**
   * Insert the dispatch-claim row. Returns {@code 1} on a fresh claim. A second writer for the same
   * {@code (provider, event_id)} raises the unique-violation (surfaced as a {@link
   * io.adaptiq.titan.store.TitanDataException}) — the caller catches it and treats the event as
   * already dispatched.
   */
  @SqlUpdate(
      "INSERT INTO titan.scm_event_seen (provider, event_id, source) "
          + "VALUES (:provider, :eventId, :source)")
  int insertClaim(
      @Bind("provider") @NonNull String provider,
      @Bind("eventId") @NonNull String eventId,
      @Bind("source") @NonNull String source);

  /**
   * Delete the dispatch-claim row for {@code (provider, event_id)}. Used by {@link
   * io.adaptiq.titan.scm.reconcile.JdbiEventDedupeStore#release} to surrender a claim when the
   * fallible dispatch (clone + enqueue) FAILED, so the next scan re-emits and retries the change.
   * Returns the number of rows deleted ({@code 0} when the claim was already gone — idempotent).
   */
  @SqlUpdate("DELETE FROM titan.scm_event_seen WHERE provider = :provider AND event_id = :eventId")
  int deleteByProviderAndEventId(
      @Bind("provider") @NonNull String provider, @Bind("eventId") @NonNull String eventId);

  /** When the {@code (provider, event_id)} row was first observed, if any. */
  @SqlQuery(
      "SELECT seen_at FROM titan.scm_event_seen WHERE provider = :provider AND event_id = :eventId")
  @NonNull
  Optional<Timestamp> firstSeenAt(
      @Bind("provider") @NonNull String provider, @Bind("eventId") @NonNull String eventId);

  /** Convenience: {@link #firstSeenAt} as an {@link Instant}. */
  @NonNull
  default Optional<Instant> firstSeenAtInstant(@NonNull String provider, @NonNull String eventId) {
    return firstSeenAt(provider, eventId).map(Timestamp::toInstant);
  }
}

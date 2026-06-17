package io.adaptiq.titan.scm.reconcile;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Optional;

/**
 * Idempotency boundary for SCM event dispatch (issue #1118).
 *
 * <p>Both the webhook hot-path and the reconcile replay path MUST call {@link #markSeen} BEFORE
 * enqueueing a build. The unique constraint on {@code (provider, event_id)} in the underlying table
 * makes a second writer fail; {@link #markSeen} returns {@code false} on duplicate-key so callers
 * can skip dispatch.
 *
 * <p>Why an interface? So the {@link ReconcileScheduler} can be unit-tested with a deterministic
 * in-memory fake (Manifesto §"External I/O" — every boundary has a Fake). The real impl wraps a
 * JDBI DAO on the {@code titan.scm_event_seen} table.
 */
public interface EventDedupeStore {

  /** Source label persisted to {@code scm_event_seen.source}. */
  enum Source {
    WEBHOOK,
    RECONCILE;

    public String wire() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  /**
   * Attempt to claim dispatch ownership of {@code (provider, eventId)}. Returns {@code true} when
   * this caller wrote the row first (proceed with dispatch); {@code false} when the row already
   * exists (some other path already dispatched — skip).
   */
  boolean markSeen(@NonNull ScmProvider provider, @NonNull String eventId, @NonNull Source source);

  /**
   * Release a previously-claimed {@code (provider, eventId)} so a <em>failed</em> dispatch can be
   * retried on a later tick. The claim ({@link #markSeen}) is taken BEFORE the fallible clone +
   * enqueue; if that dispatch throws (transient: git missing, shallow-fetch rejected, node 5xx),
   * the caller must release the claim here so the next scan re-emits the change and re-dispatches
   * it.
   *
   * <p>Idempotent: releasing an unclaimed {@code (provider, eventId)} is a no-op. Releasing is
   * reserved for transient dispatch FAILURE only — a legitimate no-op (no pipeline file) must KEEP
   * the claim so it is not re-cloned every tick.
   */
  void release(@NonNull ScmProvider provider, @NonNull String eventId);

  /**
   * Look up when an event was first observed. Used by the audit row to compute {@code gapSeconds};
   * empty when the event is genuinely new.
   */
  @NonNull
  Optional<Instant> firstSeenAt(@NonNull ScmProvider provider, @NonNull String eventId);
}

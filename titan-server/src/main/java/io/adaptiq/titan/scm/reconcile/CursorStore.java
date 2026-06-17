package io.adaptiq.titan.scm.reconcile;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Optional;

/**
 * Per-(provider, repo) high-water-mark store backing {@code titan.scm_event_cursor} (issue #1118).
 *
 * <p>Interface-not-impl so {@link ReconcileScheduler} stays unit-testable. The production impl
 * delegates to a JDBI DAO; the {@code FakeCursorStore} used in tests is a plain {@code Map}.
 */
public interface CursorStore {

  /** Look up the last successfully-dispatched event id for {@code (provider, repo)}. */
  @NonNull
  Optional<String> lastEventId(@NonNull ScmProvider provider, @NonNull String repoExternalId);

  /**
   * Advance the cursor after a batch of events has dispatched. Atomic upsert: a concurrent writer
   * that committed a higher id MUST not be rolled back. Implementations enforce {@code MAX} on
   * conflict; the in-memory fake mirrors that with a guarded compareAndSet.
   */
  void advance(
      @NonNull ScmProvider provider,
      @NonNull String repoExternalId,
      @NonNull String newEventId,
      @NonNull Instant eventOccurredAt,
      @NonNull Instant reconciledAt);

  /**
   * The wall-clock instant of the last reconcile tick for {@code (provider, repo)} — used by the
   * lag gauge. {@link Optional#empty()} for a repo we have never reconciled.
   */
  @NonNull
  Optional<Instant> lastReconciledAt(@NonNull ScmProvider provider, @NonNull String repoExternalId);

  /**
   * Best-effort lookup of the timestamp on the persisted last event; used to compute {@code
   * titan_scm_reconcile_lag_seconds} when no fresh tick has happened yet.
   */
  @NonNull
  Optional<Instant> lastEventAt(@NonNull ScmProvider provider, @NonNull String repoExternalId);

  /** Read coordinates returned to the scheduler in one shot — convenience accessor. */
  @NonNull
  Cursor read(@NonNull ScmProvider provider, @NonNull String repoExternalId);

  /** Immutable tuple describing a cursor. */
  final class Cursor {
    private final @Nullable String lastEventId;
    private final @Nullable Instant lastEventAt;
    private final @Nullable Instant lastReconciledAt;

    public Cursor(
        @Nullable String lastEventId,
        @Nullable Instant lastEventAt,
        @Nullable Instant lastReconciledAt) {
      this.lastEventId = lastEventId;
      this.lastEventAt = lastEventAt;
      this.lastReconciledAt = lastReconciledAt;
    }

    @Nullable
    public String lastEventId() {
      return lastEventId;
    }

    @Nullable
    public Instant lastEventAt() {
      return lastEventAt;
    }

    @Nullable
    public Instant lastReconciledAt() {
      return lastReconciledAt;
    }
  }
}

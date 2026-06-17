package io.adaptiq.titan.scm.reconcile;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.TitanStores;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The production {@link EventDedupeStore} — a durable, DB-backed claim over {@code
 * titan.scm_event_seen} (V36). This is the single, application-scoped idempotency boundary shared
 * by every dispatch path (webhook hot-path + the Pulsar poll scanner + the reconcile loop).
 *
 * <p><strong>Why DB-backed, not in-memory.</strong> A per-tick in-memory dedupe would re-enqueue
 * the same {@code (provider, eventId)} on every scheduled pass forever. The unique constraint on
 * {@code (provider, event_id)} makes the FIRST writer win across ticks <em>and</em> across the
 * webhook hot-path, so a change builds exactly once until its tip advances (the eventId carries the
 * revision).
 *
 * <p><strong>How the claim is atomic + portable.</strong> {@link #markSeen} attempts a plain INSERT
 * (no {@code ON CONFLICT} — that form is unsupported under H2's PG-mode subset used by the unit
 * tests). A losing writer's INSERT raises a unique-violation, surfaced as a {@code
 * TitanDataException} wrapping a {@link SQLState} {@code 23505}/{@code 23001}; we detect that and
 * return {@code false} (already seen). Any other failure is re-thrown — a transient DB error must
 * NOT be misread as "already dispatched" (that would silently drop a build).
 */
public final class JdbiEventDedupeStore implements EventDedupeStore {

  /** Postgres unique-violation SQLState. */
  private static final String PG_UNIQUE_VIOLATION = "23505";

  /** H2 unique/duplicate-key SQLStates (referential + duplicate). */
  private static final String H2_DUPLICATE_KEY = "23505";

  private static final String H2_DUPLICATE_KEY_LEGACY = "23001";

  private final TitanStores stores;

  public JdbiEventDedupeStore(@NonNull TitanStores stores) {
    this.stores = Objects.requireNonNull(stores, "stores");
  }

  @Override
  public boolean markSeen(
      @NonNull ScmProvider provider, @NonNull String eventId, @NonNull Source source) {
    try {
      stores.scmEventSeen().insertClaim(provider.wire(), eventId, source.wire());
      return true;
    } catch (RuntimeException e) {
      if (isDuplicateKey(e)) {
        return false; // another path already claimed this (provider, eventId) — skip dispatch.
      }
      throw e; // a real DB error — propagate; never misread as "already dispatched".
    }
  }

  @Override
  public void release(@NonNull ScmProvider provider, @NonNull String eventId) {
    // Idempotent DELETE — re-claimable on the next tick. A 0-row delete (already gone) is fine.
    stores.scmEventSeen().deleteByProviderAndEventId(provider.wire(), eventId);
  }

  @Override
  @NonNull
  public Optional<Instant> firstSeenAt(@NonNull ScmProvider provider, @NonNull String eventId) {
    return stores.scmEventSeen().firstSeenAtInstant(provider.wire(), eventId);
  }

  /** Walk the cause chain for a {@link SQLException} carrying a unique-violation SQLState. */
  private static boolean isDuplicateKey(@NonNull Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof SQLException sql) {
        String state = sql.getSQLState();
        if (PG_UNIQUE_VIOLATION.equals(state)
            || H2_DUPLICATE_KEY.equals(state)
            || H2_DUPLICATE_KEY_LEGACY.equals(state)) {
          return true;
        }
      }
      if (c == c.getCause()) {
        break; // self-referential cause guard
      }
    }
    return false;
  }
}

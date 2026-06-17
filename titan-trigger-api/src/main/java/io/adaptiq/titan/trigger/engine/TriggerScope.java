package io.adaptiq.titan.trigger.engine;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * The engine's view of one owner's trigger state, valid only inside a {@link TriggerStore} locked
 * scope (design/50, Tier 3 SPI).
 *
 * <p>The {@code TriggerStore} opens a locked transaction, hands the engine a {@code TriggerScope}
 * bound to it, and the engine's read of {@link #lastFiredAt}, its {@link #buildInFlight} coalescing
 * check, its {@link #fire} and its {@link #recordFired} all commit together. The consumer owns the
 * lock and the transaction; the engine owns the algorithm (design/50 D4). A scope must not be
 * retained beyond the {@code inLockedScope} callback.
 */
public interface TriggerScope {

  /** When the given trigger last had a satisfied occurrence, or {@code null} if never. */
  @CheckForNull
  Instant lastFiredAt(@NonNull String triggerId);

  /**
   * Whether a build is already in flight for this owner — the coalescing check (design/50 D5). When
   * true the engine records the occurrence but does not start a duplicate build.
   */
  boolean buildInFlight();

  /** Start a build for this owner, on the locked transaction. */
  void fire();

  /** Record that the given trigger had a satisfied occurrence at {@code when}. */
  void recordFired(@NonNull String triggerId, @NonNull Instant when);

  /** Record that evaluating the given trigger failed, for diagnostics. */
  void recordError(@NonNull String triggerId, @NonNull String message);
}

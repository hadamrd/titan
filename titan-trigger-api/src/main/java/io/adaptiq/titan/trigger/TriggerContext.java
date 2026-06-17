package io.adaptiq.titan.trigger;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * The inputs a {@link Trigger} evaluates against (design/50, Tier 2; design/51).
 *
 * <p>Immutable and self-contained — a trigger's {@link Trigger#evaluate} is a pure function of its
 * own config and this context, which is what makes triggers unit-testable without a controller.
 *
 * @param now the evaluation instant — one value per firing-engine tick or event delivery.
 * @param lastFiredAt when this trigger last had a satisfied occurrence; {@code null} → never.
 * @param hashSeed a stable per-owner string seeding {@code H} cron hashing (design/50 D3).
 * @param event the event being delivered, or {@code null} on a periodic poll tick (design/51 D1).
 */
public record TriggerContext(
    @NonNull Instant now,
    @CheckForNull Instant lastFiredAt,
    @NonNull String hashSeed,
    @CheckForNull TriggerEvent event) {

  /** A poll-tick context — no event in scope. */
  public TriggerContext(
      @NonNull Instant now, @CheckForNull Instant lastFiredAt, @NonNull String hashSeed) {
    this(now, lastFiredAt, hashSeed, null);
  }
}

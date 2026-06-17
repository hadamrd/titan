package io.adaptiq.titan.ratelimit;

/**
 * Typed, immutable config for {@link TriggerRateLimiter} (closes #739).
 *
 * <p>Discriminated by field name only — no {@code Map<String,Object>} on the seam. Defaults match
 * the issue contract:
 *
 * <ul>
 *   <li>{@code burst} = 5 (initial bucket capacity, also the ceiling)
 *   <li>{@code refillPerMinute} = 1 (token replenishment rate)
 *   <li>{@code idleEvictionSeconds} = 3600 (drop buckets idle &gt; 1h; sweep is opportunistic)
 * </ul>
 *
 * <p>Surfaced via Quarkus {@code @ConfigMapping} keys:
 *
 * <ul>
 *   <li>{@code titan.trigger.rate.burst}
 *   <li>{@code titan.trigger.rate.refill-per-min}
 *   <li>{@code titan.trigger.rate.idle-eviction-seconds}
 * </ul>
 */
public record TriggerRateLimitConfig(int burst, int refillPerMinute, long idleEvictionSeconds) {

  /** Defaults from issue #739. */
  public static final TriggerRateLimitConfig DEFAULT = new TriggerRateLimitConfig(5, 1, 3600L);

  public TriggerRateLimitConfig {
    if (burst < 1) {
      throw new IllegalArgumentException("burst must be >= 1, got " + burst);
    }
    if (refillPerMinute < 1) {
      throw new IllegalArgumentException("refillPerMinute must be >= 1, got " + refillPerMinute);
    }
    if (idleEvictionSeconds < 60) {
      throw new IllegalArgumentException(
          "idleEvictionSeconds must be >= 60, got " + idleEvictionSeconds);
    }
  }
}

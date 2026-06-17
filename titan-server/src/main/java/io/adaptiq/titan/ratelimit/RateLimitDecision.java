package io.adaptiq.titan.ratelimit;

/**
 * Discriminated union — outcome of {@link TriggerRateLimiter#acquire(String, long)} (closes #739).
 *
 * <p>Two variants, exhaustive:
 *
 * <ul>
 *   <li>{@link Allowed} — caller may proceed; a token was consumed.
 *   <li>{@link Denied} — caller is throttled; carries {@code retryAfterSeconds} for the {@code
 *       Retry-After} header.
 * </ul>
 */
public sealed interface RateLimitDecision
    permits RateLimitDecision.Allowed, RateLimitDecision.Denied {

  /** Token consumed, action may proceed. */
  record Allowed() implements RateLimitDecision {
    public static final Allowed INSTANCE = new Allowed();
  }

  /**
   * Throttled. {@code retryAfterSeconds} is the integer number of seconds the caller should wait
   * before the next token will be available; always &gt;= 1.
   */
  record Denied(long retryAfterSeconds) implements RateLimitDecision {
    public Denied {
      if (retryAfterSeconds < 1) {
        retryAfterSeconds = 1;
      }
    }
  }
}

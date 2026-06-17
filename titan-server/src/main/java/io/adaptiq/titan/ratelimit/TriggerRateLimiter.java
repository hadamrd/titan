package io.adaptiq.titan.ratelimit;

import edu.umd.cs.findbugs.annotations.NonNull;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Per-(user_subject, job_id) token-bucket rate limiter for the manual trigger endpoint (closes
 * #739).
 *
 * <p>Algorithm: classic token bucket. Each {@link Key} maps to a bucket holding a fractional token
 * count (stored as {@code millitokens} to avoid double-precision drift). On {@link #acquire}:
 *
 * <ol>
 *   <li>Replenish: add {@code refillPerMinute * elapsedMillis / 60_000} tokens, capped at {@code
 *       burst}.
 *   <li>If &gt;= 1 token: subtract one, return {@link RateLimitDecision.Allowed}.
 *   <li>Else: return {@link RateLimitDecision.Denied} with the integer seconds until the next full
 *       token is available.
 * </ol>
 *
 * <p>Storage: a plain {@link ConcurrentHashMap}. Buckets idle &gt; {@code idleEvictionSeconds} are
 * dropped opportunistically on each {@code acquire} once every ~256 calls (cheap sweep — avoids a
 * background thread / scheduler dependency, keeps boot time flat).
 *
 * <p>Clock: a {@link Clock} is injected so tests can advance virtual time without sleeping. CDI
 * defaults to {@link Clock#systemUTC()} via {@link io.adaptiq.titan.boot.ClockProducer} (or a
 * raw-constructor seam for non-CDI ITs).
 *
 * <p>Constructor-injection; no field injection.
 */
@ApplicationScoped
public class TriggerRateLimiter {

  /** Composite key — user subject + job id. {@code equals/hashCode} via record canonical impl. */
  public record Key(String subject, long jobId) {
    public Key {
      Objects.requireNonNull(subject, "subject");
    }
  }

  /**
   * Mutable bucket. {@code millitokens} = current bucket level * 1000. {@code lastRefillMillis} =
   * epoch-millis of the last replenishment. {@code lastTouchMillis} drives idle eviction.
   *
   * <p>Synchronised per-instance — the {@link ConcurrentHashMap} gives us bucket-level isolation;
   * the {@code synchronized (this)} below serialises the read-modify-write within a single bucket.
   */
  private static final class Bucket {
    long millitokens;
    long lastRefillMillis;
    volatile long lastTouchMillis;

    Bucket(long millitokens, long nowMillis) {
      this.millitokens = millitokens;
      this.lastRefillMillis = nowMillis;
      this.lastTouchMillis = nowMillis;
    }
  }

  private final ConcurrentHashMap<Key, Bucket> buckets = new ConcurrentHashMap<>();
  private final AtomicLong acquireCounter = new AtomicLong();

  private final int burst;
  private final int refillPerMinute;
  private final long idleEvictionMillis;
  private final Clock clock;

  /** Primary CDI constructor — pulls config from {@code titan.trigger.rate.*}. */
  @Inject
  TriggerRateLimiter(
      @ConfigProperty(name = "titan.trigger.rate.burst", defaultValue = "5") int burst,
      @ConfigProperty(name = "titan.trigger.rate.refill-per-min", defaultValue = "1")
          int refillPerMinute,
      @ConfigProperty(name = "titan.trigger.rate.idle-eviction-seconds", defaultValue = "3600")
          long idleEvictionSeconds,
      Clock clock) {
    this(new TriggerRateLimitConfig(burst, refillPerMinute, idleEvictionSeconds), clock);
  }

  /** Explicit-config constructor — used by ITs and any non-CDI call site. */
  public TriggerRateLimiter(@NonNull TriggerRateLimitConfig config, @NonNull Clock clock) {
    this.burst = config.burst();
    this.refillPerMinute = config.refillPerMinute();
    this.idleEvictionMillis = config.idleEvictionSeconds() * 1000L;
    this.clock = clock;
  }

  @PostConstruct
  void logConfig() {
    // No-op; presence of @PostConstruct keeps the bean instantiation eager-friendly without
    // forcing eager init.
  }

  /**
   * Atomically attempt to consume one token for {@code (subject, jobId)}.
   *
   * <p>Idempotent in the sense that the decision itself never mutates external state — a {@link
   * RateLimitDecision.Denied} costs nothing; an {@link RateLimitDecision.Allowed} consumes exactly
   * one token from the per-key bucket.
   */
  @NonNull
  public RateLimitDecision acquire(@NonNull String subject, long jobId) {
    Objects.requireNonNull(subject, "subject");
    long nowMillis = clock.instant().toEpochMilli();

    // Opportunistic sweep — cheap, no background thread.
    if ((acquireCounter.incrementAndGet() & 0xff) == 0L) {
      evictIdle(nowMillis);
    }

    Key key = new Key(subject, jobId);
    Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket((long) burst * 1000L, nowMillis));

    synchronized (bucket) {
      // 1. Replenish.
      long elapsedMillis = nowMillis - bucket.lastRefillMillis;
      if (elapsedMillis > 0) {
        long addedMillitokens = (refillPerMinute * elapsedMillis) / 60L;
        if (addedMillitokens > 0) {
          bucket.millitokens =
              Math.min((long) burst * 1000L, bucket.millitokens + addedMillitokens);
          bucket.lastRefillMillis = nowMillis;
        }
      }
      bucket.lastTouchMillis = nowMillis;

      // 2. Consume or deny.
      if (bucket.millitokens >= 1000L) {
        bucket.millitokens -= 1000L;
        return RateLimitDecision.Allowed.INSTANCE;
      }

      // 3. Compute retry-after — seconds until bucket reaches 1000 millitokens.
      long deficitMillitokens = 1000L - bucket.millitokens;
      // millitokens-per-minute = refillPerMinute * 1000; convert to seconds; round up.
      double millitokensPerSecond = (refillPerMinute * 1000.0) / 60.0;
      long retryAfterSeconds = (long) Math.ceil(deficitMillitokens / millitokensPerSecond);
      if (retryAfterSeconds < 1) {
        retryAfterSeconds = 1;
      }
      return new RateLimitDecision.Denied(retryAfterSeconds);
    }
  }

  /** Drop buckets that have not been touched within the eviction window. */
  private void evictIdle(long nowMillis) {
    long threshold = nowMillis - idleEvictionMillis;
    Iterator<Map.Entry<Key, Bucket>> it = buckets.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<Key, Bucket> e = it.next();
      if (e.getValue().lastTouchMillis < threshold) {
        it.remove();
      }
    }
  }

  /** Test seam — current bucket count (live entries, before sweep). */
  public int trackedKeys() {
    return buckets.size();
  }

  /** Test seam — typed config readback. */
  public TriggerRateLimitConfig config() {
    return new TriggerRateLimitConfig(burst, refillPerMinute, idleEvictionMillis / 1000L);
  }
}

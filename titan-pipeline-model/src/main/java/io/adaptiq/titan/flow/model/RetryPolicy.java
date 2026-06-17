package io.adaptiq.titan.flow.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * The declarative per-step retry policy carried on a {@link StepModel} (design/44 §1, §2).
 *
 * <p>Titan has no blocks (design/29 §1), so retry cannot be a {@code retry(n) { … }} wrapping block
 * — it is a <em>declarative property of a step</em>, parsed off the {@code retry:} grammar scope,
 * exactly like {@code credentials:} / {@code sshAgent:}. A policy with {@link #maxAttempts} {@code
 * == 1} (the default) means no retry — exactly today's behaviour; a step with no {@code retry:} key
 * carries no {@code RetryPolicy} at all.
 *
 * <p>This is a Temporal-shaped {@code RetryPolicy} expressed declaratively (design/44 §2): bounded
 * attempts, exponential {@link Backoff} with a cap, and an optional retryable-exit-code allowlist.
 * The delay before retry <em>k</em> (1-indexed) is {@code min(initial × multiplier^(k-1), max)}.
 *
 * <p>Jackson-friendly: public no-arg constructor plus getters/setters.
 */
public class RetryPolicy {

  /** Total attempts including the first; {@code 1} = no retry (design/44 §2). */
  private int maxAttempts = 1;

  /** The exponential-backoff schedule applied between attempts. Never {@code null}. */
  @NonNull private Backoff backoff = new Backoff();

  /**
   * If non-empty, a step that exits with a code in this list is retried and any other non-zero exit
   * fails fast; if empty, any non-zero exit is retryable (design/44 §3). Default empty.
   */
  @NonNull private List<Integer> retryableExitCodes = new ArrayList<>();

  /** Default constructor for Jackson deserialization. */
  public RetryPolicy() {}

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  @NonNull
  public Backoff getBackoff() {
    return backoff;
  }

  public void setBackoff(@NonNull Backoff backoff) {
    this.backoff = backoff;
  }

  @NonNull
  public List<Integer> getRetryableExitCodes() {
    return retryableExitCodes;
  }

  public void setRetryableExitCodes(@NonNull List<Integer> retryableExitCodes) {
    this.retryableExitCodes = retryableExitCodes;
  }

  /**
   * The exponential-backoff schedule of a {@link RetryPolicy} (design/44 §2). Durations are stored
   * as a parsed millisecond {@code long} — the parser turns the {@code s}/{@code m}/{@code h}
   * suffixed YAML form into millis at parse time so the baked DAG carries no suffix strings.
   *
   * <p>Jackson-friendly: public no-arg constructor plus getters/setters.
   */
  public static class Backoff {

    /** Delay before the first retry, in milliseconds. Default {@code 10s}. */
    private long initialMillis = 10_000L;

    /** Exponential coefficient applied per attempt. Default {@code 2.0}. */
    private double multiplier = 2.0;

    /** Cap on the computed delay, in milliseconds. Default {@code 5m}. */
    private long maxMillis = 300_000L;

    /** Default constructor for Jackson deserialization. */
    public Backoff() {}

    public long getInitialMillis() {
      return initialMillis;
    }

    public void setInitialMillis(long initialMillis) {
      this.initialMillis = initialMillis;
    }

    public double getMultiplier() {
      return multiplier;
    }

    public void setMultiplier(double multiplier) {
      this.multiplier = multiplier;
    }

    public long getMaxMillis() {
      return maxMillis;
    }

    public void setMaxMillis(long maxMillis) {
      this.maxMillis = maxMillis;
    }
  }
}

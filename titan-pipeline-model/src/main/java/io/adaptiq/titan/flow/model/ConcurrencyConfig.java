package io.adaptiq.titan.flow.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Locale;
import java.util.Objects;

/**
 * Pipeline-level per-job concurrency config (issue #1101). Caps the number of <em>RUNNING</em>
 * builds of the same job and chooses what happens when a new build arrives while the cap is full.
 *
 * <p>Backwards compatible: a pipeline with no {@code concurrency:} block has no {@code
 * ConcurrencyConfig} attached and runs unlimited builds in parallel — the legacy default.
 *
 * <p>Shape (design/concurrency, GHA-shaped):
 *
 * <pre>{@code
 * concurrency:
 *   max: 2
 *   on_overflow: queue          # queue | cancel_oldest | cancel_pending
 * }</pre>
 *
 * <p>Short form: {@code concurrency: 2} expands to {@code {max: 2, on_overflow: queue}}.
 */
public final class ConcurrencyConfig {

  /** What to do when a new build arrives at the per-job concurrency ceiling. */
  public enum OnOverflow {
    /** Hold the new build (re-poll until the cap clears). The legacy "wait my turn" default. */
    QUEUE,
    /** SIGTERM the oldest still-RUNNING build of this job so the new build can proceed. */
    CANCEL_OLDEST,
    /** Cancel any other QUEUED builds of this job (keep RUNNING ones; promote the newest). */
    CANCEL_PENDING;

    /**
     * Parse a yaml-shaped string. Accepts {@code queue} / {@code cancel_oldest} / {@code
     * cancel_pending} (case-insensitive; hyphens and dashes accepted too).
     */
    @NonNull
    public static OnOverflow fromYaml(@NonNull String raw, @NonNull String where) {
      String norm = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
      return switch (norm) {
        case "queue" -> QUEUE;
        case "cancel_oldest" -> CANCEL_OLDEST;
        case "cancel_pending" -> CANCEL_PENDING;
        default ->
            throw new IllegalArgumentException(
                where
                    + ": unknown on_overflow value '"
                    + raw
                    + "' — expected one of queue / cancel_oldest / cancel_pending");
      };
    }
  }

  private final int max;

  @NonNull private final OnOverflow onOverflow;

  public ConcurrencyConfig(int max, @NonNull OnOverflow onOverflow) {
    if (max < 1) {
      throw new IllegalArgumentException("concurrency.max must be >= 1, got " + max);
    }
    this.max = max;
    this.onOverflow = Objects.requireNonNull(onOverflow, "onOverflow");
  }

  public int getMax() {
    return max;
  }

  @NonNull
  public OnOverflow getOnOverflow() {
    return onOverflow;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ConcurrencyConfig other)) return false;
    return max == other.max && onOverflow == other.onOverflow;
  }

  @Override
  public int hashCode() {
    return Objects.hash(max, onOverflow);
  }

  @Override
  public String toString() {
    return "ConcurrencyConfig{max=" + max + ", onOverflow=" + onOverflow + '}';
  }
}

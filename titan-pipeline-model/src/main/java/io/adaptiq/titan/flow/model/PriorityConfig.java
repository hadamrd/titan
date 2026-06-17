package io.adaptiq.titan.flow.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Locale;

/**
 * Per-job queue priority (issue #1100) — a discriminated-union enum that pins the runtime priority
 * value the controller writes into {@code task_queue.priority}. Higher = sooner.
 *
 * <p>The grammar is intentionally a closed enum ({@code high | normal | low}), not a free-form
 * integer: this is the principled-typed-design lesson — copy the well-known three-tier policy of
 * Kubernetes pod priority classes / GHA workflow priority discussion / Buildkite job priority
 * (which all converge on a small, named ladder), rather than re-invent a worse free-form smallint
 * surface the user must twiddle.
 *
 * <p>Mapping (locked):
 *
 * <ul>
 *   <li>{@code high} &rarr; {@code 10}
 *   <li>{@code normal} &rarr; {@code 0} (default — same as the column default)
 *   <li>{@code low} &rarr; {@code -10}
 * </ul>
 */
public enum PriorityConfig {
  HIGH(10),
  NORMAL(0),
  LOW(-10);

  private final int weight;

  PriorityConfig(int weight) {
    this.weight = weight;
  }

  /** The integer the controller writes into {@code task_queue.priority}. Higher = sooner. */
  public int weight() {
    return weight;
  }

  /**
   * Parse a yaml-shaped string. Accepts {@code high} / {@code normal} / {@code low}
   * (case-insensitive). Throws {@link IllegalArgumentException} on any other value — priority is a
   * load-bearing policy, never a silent default.
   */
  @NonNull
  public static PriorityConfig fromYaml(@NonNull String raw, @NonNull String where) {
    String norm = raw.trim().toLowerCase(Locale.ROOT);
    return switch (norm) {
      case "high" -> HIGH;
      case "normal" -> NORMAL;
      case "low" -> LOW;
      default ->
          throw new IllegalArgumentException(
              where + ": unknown priority '" + raw + "' — expected one of high / normal / low");
    };
  }
}

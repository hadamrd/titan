package io.adaptiq.titan.observability;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Metrics;
import java.time.Duration;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-step Prometheus histogram + OTel export-failure counter (issue #1081).
 *
 * <p>Exposes {@code titan_step_duration_seconds{job, step, status}} on {@code /q/metrics}.
 * Operators use it to answer the two SRE questions {@code titan_build_duration_seconds} alone
 * cannot — "which step is the bottleneck of this pipeline?" and "why did this build take 4 minutes
 * longer than the last one?" The pre-baked SLO bucket boundaries (1s, 5s, 15s, 1m, 5m, 30m) match
 * the cardinality Titan SREs already plot dashboards against and span the realistic range of CI
 * workloads from trivial lint passes through long-running deploys.
 *
 * <p>This class is deliberately small and dependency-free of CDI so the orchestrator (which is
 * instantiated by hand per build) can call it directly without an {@code Instance<TitanMetrics>}
 * lookup. Emission is non-throwing: a metrics-registry failure must never break a step transition.
 *
 * <p>Label cardinality (CONSTITUTION §6 — bounded-cardinality labels only):
 *
 * <ul>
 *   <li>{@code job} — the job's {@code full_name}; bounded by the number of jobs configured on the
 *       controller (operators are expected to keep this in the low thousands; Prometheus
 *       cardinality is their lever, not Titan's).
 *   <li>{@code step} — the step's {@code displayName}; bounded by the pipeline grammar plus
 *       user-defined stage/step names. Same operator-lever logic as {@code job}.
 *   <li>{@code status} — closed enum (see {@link #STEP_TERMINAL_STATUSES}).
 * </ul>
 *
 * <p>A null / blank label is replaced with a {@code "unknown"} sentinel rather than dropped, so the
 * histogram still observes the bucket — a missing label is itself a signal worth surfacing.
 */
public final class StepMetrics {

  private static final Logger LOGGER = Logger.getLogger(StepMetrics.class.getName());

  /** Sentinel used when the orchestrator could not resolve a job or step label. */
  static final String UNKNOWN_LABEL = "unknown";

  /** Terminal status labels we accept onto the {@code status} dimension. */
  static final Set<String> STEP_TERMINAL_STATUSES =
      Set.of("SUCCESS", "FAILED", "ABORTED", "CANCELLED", "SKIPPED");

  /**
   * Histogram bucket boundaries in seconds: 1s / 5s / 15s / 60s / 5m / 30m. Matches the acceptance
   * criteria of #1081 verbatim — do NOT add buckets here without bumping the SRE dashboard query.
   */
  static final double[] BUCKETS_SECONDS = {1.0, 5.0, 15.0, 60.0, 300.0, 1800.0};

  private StepMetrics() {}

  /**
   * Record one terminal step transition.
   *
   * @param jobName the job's {@code full_name} — may be null/blank, in which case {@code "unknown"}
   *     is substituted.
   * @param stepName the step's display name — may be null/blank, in which case {@code "unknown"} is
   *     substituted.
   * @param status the terminal status; values outside {@link #STEP_TERMINAL_STATUSES} are coerced
   *     to {@code "unknown"} rather than silently leaking unbounded label values.
   * @param duration step wall-clock duration; {@code null} or {@code <= 0} records as {@code 0.0}.
   */
  public static void recordStepDuration(
      @Nullable String jobName,
      @Nullable String stepName,
      @Nullable String status,
      @Nullable Duration duration) {
    try {
      String jobLabel = labelOrUnknown(jobName);
      String stepLabel = labelOrUnknown(stepName);
      String statusLabel =
          (status != null && STEP_TERMINAL_STATUSES.contains(status)) ? status : UNKNOWN_LABEL;
      double seconds =
          (duration == null || duration.isNegative()) ? 0.0 : durationSeconds(duration);

      histogram(jobLabel, stepLabel, statusLabel).record(seconds);
    } catch (RuntimeException e) {
      // Best-effort: never let metric emission abort the orchestrator's terminal transition.
      LOGGER.log(Level.FINE, "[titan] step-duration meter emit failed: {0}", e.getMessage());
    }
  }

  /** Increment {@code titan_otel_export_failures_total{reason}}. Bounded label, never throws. */
  public static void recordOtelExportFailure(@NonNull String reason) {
    try {
      Metrics.counter("titan.otel.export.failures.total", "reason", reason).increment();
    } catch (RuntimeException e) {
      LOGGER.log(Level.FINE, "[titan] otel-export-failure meter emit failed: {0}", e.getMessage());
    }
  }

  private static DistributionSummary histogram(String job, String step, String status) {
    return DistributionSummary.builder("titan.step.duration.seconds")
        .description("Wall-clock duration of a terminal step transition, in seconds")
        .baseUnit("seconds")
        .tag("job", job)
        .tag("step", step)
        .tag("status", status)
        .serviceLevelObjectives(BUCKETS_SECONDS)
        .register(Metrics.globalRegistry);
  }

  private static String labelOrUnknown(@Nullable String raw) {
    return (raw == null || raw.isBlank()) ? UNKNOWN_LABEL : raw;
  }

  /** Visible for tests — the conversion from {@link Duration} to seconds-as-double. */
  static double durationSeconds(@NonNull Duration d) {
    return d.toNanos() / 1_000_000_000.0;
  }
}

package io.adaptiq.titan.flow;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Immutable per-build context handed to {@link NotificationDispatcher} at terminal-write time so
 * the dispatched message can carry the full SRE-actionable payload (#1102 — Slack on build-fail,
 * with deep-link).
 *
 * <p>The orchestrator's {@code BuildCloser} populates this once per build close — the dispatcher
 * does NOT re-query the DB while iterating the hook list, so a build with 20 hooks still costs one
 * DAO round-trip for the previous-build lookup.
 *
 * <p>Recovery semantics (#1102): a build is a "recovery" when its terminal {@link #result} is
 * {@code SUCCESS} and the previous finished build of the same job was {@code FAILED}. The flag is
 * computed by the caller and cached on this record — every hook in the loop sees the same answer.
 *
 * @param buildId the closing build's id.
 * @param result terminal status — {@code SUCCESS} or {@code FAILED}.
 * @param previousResult the previous finished build's terminal status for the same job, or {@code
 *     null} if this is the first finished build for the job.
 * @param recovery {@code true} iff {@code result == SUCCESS && previousResult == FAILED}. Cached
 *     here so every hook sees a consistent answer.
 * @param firstFailureAfterPasses {@code true} iff {@code result == FAILED && previousResult ==
 *     SUCCESS} — the "first failure after N passes" formatter case (#1102 test matrix).
 * @param jobName the job's display name / full name, used in the message header. May be {@code
 *     null} for degenerate builds with no resolvable job.
 * @param durationMs build duration in milliseconds (queued→finished or started→finished). {@code
 *     null} if {@code started_at} was never set (degenerate path) — the formatter renders {@code
 *     "n/a"} in that case.
 * @param failedStageName the display name of the first/only failed stage, or {@code null} on a
 *     {@code SUCCESS} (and {@code null}-tolerated on a {@code FAILED} when the failure was
 *     pre-stage — e.g. a BAKE failure).
 * @param deepLink fully-qualified URL to the build-detail page (e.g. {@code
 *     https://titan.example.com/builds/4711}); {@code null} when the deployment has not configured
 *     {@code titan.public-url}.
 */
public record NotificationContext(
    long buildId,
    @NonNull String result,
    @Nullable String previousResult,
    boolean recovery,
    boolean firstFailureAfterPasses,
    @Nullable String jobName,
    @Nullable Long durationMs,
    @Nullable String failedStageName,
    @Nullable String deepLink) {

  /** Convenience constructor that derives {@link #recovery} + {@link #firstFailureAfterPasses}. */
  public static NotificationContext of(
      long buildId,
      @NonNull String result,
      @Nullable String previousResult,
      @Nullable String jobName,
      @Nullable Long durationMs,
      @Nullable String failedStageName,
      @Nullable String deepLink) {
    boolean recovery = "SUCCESS".equals(result) && "FAILED".equals(previousResult);
    boolean firstFailure = "FAILED".equals(result) && "SUCCESS".equals(previousResult);
    return new NotificationContext(
        buildId,
        result,
        previousResult,
        recovery,
        firstFailure,
        jobName,
        durationMs,
        failedStageName,
        deepLink);
  }

  /** Compact "duration: 1m 2s" style rendering — {@code "n/a"} when unknown. */
  @NonNull
  public String renderDuration() {
    if (durationMs == null || durationMs <= 0L) {
      return "n/a";
    }
    long total = durationMs;
    long h = total / 3_600_000L;
    long m = (total % 3_600_000L) / 60_000L;
    long s = (total % 60_000L) / 1_000L;
    if (h > 0) {
      return h + "h " + m + "m " + s + "s";
    }
    if (m > 0) {
      return m + "m " + s + "s";
    }
    return s + "s";
  }
}

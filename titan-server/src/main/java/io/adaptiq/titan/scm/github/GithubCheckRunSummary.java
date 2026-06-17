package io.adaptiq.titan.scm.github;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.JobRow;
import java.time.Duration;
import java.util.List;

/**
 * Pure functions that build the {@code output.title} / {@code output.summary} / {@code output.text}
 * fields of a GitHub Check-Run PATCH body (#965). Kept distinct from {@link GithubCheckRunReporter}
 * so the reporter stays focused on HTTP orchestration and the formatting stays trivially
 * unit-testable.
 *
 * <h2>Truncation contract</h2>
 *
 * GitHub caps {@code output.text} at 64 KiB. We treat 60 KB (60_000 bytes) as the soft cap on the
 * <em>summary</em> string (the caller decides where to put it). Anything over → truncated with a
 * suffix that points the operator at {@code details_url} for the full thing. The cap is on
 * <em>characters</em>, not bytes — close enough for ASCII payloads and conservatively under the
 * UTF-8 budget for anything else.
 */
final class GithubCheckRunSummary {

  /**
   * Soft cap on summary length. GitHub's hard cap is 64 KiB on {@code output.text}; we use 60_000
   * chars on the {@code summary} string to leave headroom for the truncation suffix and any UTF-8
   * multi-byte runs.
   */
  static final int MAX_SUMMARY_CHARS = 60_000;

  private GithubCheckRunSummary() {}

  /**
   * Build the {@code output.title} field — short, scannable on the PR-checks list.
   *
   * <p>Format: {@code "<pipelineName> · <outcome>"} where outcome is the human verb derived from
   * the terminal status. {@code pipelineName} falls back to the job's full-name then to {@code
   * "Titan"} so the title is never blank.
   */
  @NonNull
  static String title(@NonNull String pipelineName, @NonNull String terminalStatus) {
    String outcome =
        switch (terminalStatus) {
          case "SUCCESS" -> "succeeded";
          case "FAILED" -> "failed";
          case "ABORTED" -> "aborted";
          case "CANCELLED" -> "cancelled";
          case "UNSTABLE" -> "unstable";
          default -> terminalStatus.toLowerCase(java.util.Locale.ROOT);
        };
    return pipelineName + " · " + outcome;
  }

  /**
   * Build the {@code output.summary} field — Markdown-friendly multi-line block: pipeline name,
   * duration, stage count, list of FAILED stage names. Truncated with a link to the rig build URL
   * when it would exceed {@link #MAX_SUMMARY_CHARS}.
   */
  @NonNull
  static String summary(
      @NonNull String pipelineName,
      @Nullable Long durationMs,
      int stageCount,
      @NonNull List<String> failedStageNames,
      @NonNull String detailsUrl) {
    StringBuilder b = new StringBuilder(512);
    b.append("**Pipeline:** ").append(pipelineName).append("\n");
    b.append("**Duration:** ").append(formatDuration(durationMs)).append("\n");
    b.append("**Stages:** ").append(stageCount).append("\n");
    if (!failedStageNames.isEmpty()) {
      b.append("\n**Failed stages:**\n");
      for (String name : failedStageNames) {
        b.append("- ").append(name).append("\n");
      }
    }
    return truncate(b.toString(), detailsUrl);
  }

  /**
   * Truncate a body to {@link #MAX_SUMMARY_CHARS} characters. When the body is over budget, the
   * suffix {@code "\n\n…see <detailsUrl> for full output"} replaces the trailing characters so the
   * combined string fits exactly under the cap. The suffix is unicode-fixed so the operator can
   * tell the truncation apart from an organic close.
   */
  @NonNull
  static String truncate(@NonNull String body, @NonNull String detailsUrl) {
    if (body.length() <= MAX_SUMMARY_CHARS) {
      return body;
    }
    String suffix = "\n\n…see " + detailsUrl + " for full output";
    int keep = Math.max(0, MAX_SUMMARY_CHARS - suffix.length());
    return body.substring(0, keep) + suffix;
  }

  /**
   * Human-readable duration (e.g. {@code "1m 23s"}). {@code "n/a"} when {@code durationMs} null.
   */
  @NonNull
  static String formatDuration(@Nullable Long durationMs) {
    if (durationMs == null || durationMs <= 0) {
      return "n/a";
    }
    Duration d = Duration.ofMillis(durationMs);
    long h = d.toHours();
    long m = d.toMinutesPart();
    long s = d.toSecondsPart();
    if (h > 0) {
      return h + "h " + m + "m " + s + "s";
    }
    if (m > 0) {
      return m + "m " + s + "s";
    }
    return s + "s";
  }

  /** Names of stage / step nodes that ended {@code FAILED}, in node-id order. */
  @NonNull
  static java.util.List<String> failedStageNames(@NonNull java.util.List<FlowNodeRow> nodes) {
    java.util.List<String> out = new java.util.ArrayList<>();
    for (FlowNodeRow n : nodes) {
      if (!"FAILED".equals(n.status)) {
        continue;
      }
      // Prefer the human display name; fall back to nodeId for steps without one.
      String name = n.displayName != null && !n.displayName.isBlank() ? n.displayName : n.nodeId;
      out.add(name);
    }
    return out;
  }

  /**
   * The pipeline name to surface on the check-run. Order: explicit build display-name set by {@code
   * setBuildName:} (#762), then the job's {@code displayName}, then the job's {@code fullName},
   * then a constant fallback. Never returns blank.
   */
  @NonNull
  static String pipelineName(@Nullable BuildRow build, @Nullable JobRow job) {
    if (build != null && build.displayName != null && !build.displayName.isBlank()) {
      return build.displayName;
    }
    if (job != null && job.displayName != null && !job.displayName.isBlank()) {
      return job.displayName;
    }
    if (job != null && job.fullName != null && !job.fullName.isBlank()) {
      return job.fullName;
    }
    return "Titan";
  }
}

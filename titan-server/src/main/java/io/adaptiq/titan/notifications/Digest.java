package io.adaptiq.titan.notifications;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The structured summary of a 24-hour build window — what the digest builder produces and what the
 * HTML renderer (and golden-snapshot test) consume.
 *
 * <p>Keeping this as a record makes the test matrix easy: build a {@link Digest}, render it,
 * compare to the golden file. The HTML template ({@code digest.html.ftl}) is a pure function of
 * this record.
 *
 * <p>{@code failingJobs} are ordered by failure count DESC, then job full-name ASC. {@code
 * longestBuilds} are the top 5 builds in the window by {@code durationMs} DESC.
 */
public record Digest(
    @NonNull Instant windowStart,
    @NonNull Instant windowEnd,
    int totalBuilds,
    int totalFailures,
    @NonNull List<FailingJob> failingJobs,
    @NonNull List<BuildEntry> longestBuilds) {

  public Digest {
    failingJobs = List.copyOf(failingJobs);
    longestBuilds = List.copyOf(longestBuilds);
  }

  /** True when there is nothing actionable to email about (caller short-circuits the send). */
  public boolean isEmpty() {
    return totalFailures == 0;
  }

  /** One job with at least one failed build in the window. */
  public record FailingJob(
      long jobId,
      @NonNull String jobName,
      int failureCount,
      int totalCount,
      @NonNull Instant lastFailureAt) {}

  /** A single build referenced from {@code longestBuilds}. */
  public record BuildEntry(
      long buildId,
      long jobId,
      @NonNull String jobName,
      int buildNumber,
      @NonNull String status,
      @NonNull Duration duration,
      @NonNull Instant finishedAt) {}
}

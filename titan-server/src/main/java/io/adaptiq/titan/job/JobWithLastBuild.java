package io.adaptiq.titan.job;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Domain pair returned by {@link JobService#listAllWithLastBuild()} (issue #529): a {@link Job}
 * alongside a compact summary of its most-recent build, or {@code null} when the job has never run.
 *
 * <p>Lives in the domain layer so the HTTP DTO mapping (in {@code JobsApi}) stays a pure
 * translation step — never reaches into storage rows.
 */
public record JobWithLastBuild(@NonNull Job job, @Nullable LastBuildSummary lastBuild) {

  /**
   * Strict subset of the per-build domain record — only the fields the {@code /jobs} fleet-health
   * pill needs. {@code finishedAt} and {@code durationMs} are nullable for non-terminal builds.
   */
  public record LastBuildSummary(
      long id,
      int buildNumber,
      @NonNull String status,
      @Nullable Long durationMs,
      @Nullable Instant finishedAt) {}
}

package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Flat row carrying a {@link JobRow}'s columns alongside a left-joined snapshot of the job's
 * most-recent build (issue #529). All {@code lastBuild*} fields are {@code null} when no build
 * exists for the job.
 *
 * <p>Kept distinct from {@link JobRow} so {@code titan.jobs} mappings stay independent of the
 * fleet-health join; callers that don't care about last-build state still hit the plain {@link
 * JobRow} mapper.
 */
public class JobWithLastBuildRow {
  // ── Job columns ─────────────────────────────────────────────────────────────
  public long id;
  public String fullName;

  @Nullable public String displayName;

  @Nullable public String folderPath;

  public String pipelineScript;
  public String configJson;

  @Nullable public String createdBy;

  public Instant createdAt;
  public Instant updatedAt;
  public boolean enabled;

  // ── Last-build columns (nullable — no build yet) ────────────────────────────
  @Nullable public Long lastBuildId;

  @Nullable public Integer lastBuildNumber;

  @Nullable public String lastBuildStatus;

  @Nullable public Long lastBuildDurationMs;

  @Nullable public Instant lastBuildFinishedAt;
}

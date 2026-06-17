package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/** Mutable POJO mapping to a row in {@code rf_jobs}. */
public class JobRow {
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

  /**
   * GitHub installation id this job is derived from — populated when the job was auto-created from
   * a discovered {@code .titan/pipelines/*.yml} file (design 66). NULL for jobs created by the
   * legacy manual onboarding path.
   */
  @Nullable public Long githubInstallationId;

  /** GitHub repo id this job is derived from — see {@link #githubInstallationId}. */
  @Nullable public Long githubRepoId;
}

package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.github_pipelines_discovered} — one row per {@code
 * .yml} file the {@link io.adaptiq.titan.scm.github.GithubRepoScanner} found under {@code
 * .titan/pipelines/} in a given repo (#833, design/63).
 *
 * <p><strong>Two terminal shapes</strong> (discriminated by {@link #parseError}):
 *
 * <ul>
 *   <li>{@code parseError == null} → the file parsed cleanly; {@link #parsedMetadata} carries a
 *       JSON projection of the {@link io.adaptiq.titan.flow.model.PipelineModel} (name, stages,
 *       triggers, parameters) — the UI's "discovered pipelines" view consumes this without re-
 *       parsing.
 *   <li>{@code parseError != null} → the file failed to parse; the human-readable error string is
 *       persisted so the UI can surface "this pipeline file has a problem" without crashing the
 *       whole scan. {@link #parsedMetadata} is {@code null} in this case.
 * </ul>
 */
public class GithubPipelineDiscoveredRow {
  public long id;
  public long repoId;

  /**
   * Branch this row was parsed against. Introduced in V31 (#887, design/65 follow-up) — GitHub
   * Actions evaluates each push against its branch's tree-of-commit, so we key rows per-(repo,
   * branch, filename). The default-branch row remains the canonical one for UI display; per-branch
   * rows feed per-event build dispatch invisibly. Existing rows backfilled from
   * github_repositories.default_branch (or 'main' as a fallback).
   */
  public String branch;

  public String filename;
  public String contentSha;
  @Nullable public String parsedMetadata;
  @Nullable public String parseError;
  public Instant lastScannedAt;
  public Instant createdAt;
}

package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.GithubPipelineDiscoveredRow;
import java.util.List;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.github_pipelines_discovered} (#833, design/63).
 *
 * <p>Per-(repo, branch, filename) since V31 (#887, design/65 follow-up). The scanner replaces all
 * rows for a {@code (repo_id, branch)} pair on every per-branch scan (delete-then-insert) — files
 * removed from that branch must lose their row, not retain a stale entry — while leaving the rows
 * for other branches untouched.
 *
 * <p><strong>UI invariant:</strong> the UI ({@code GET /api/v1/github-app/installations}) only
 * surfaces default-branch rows; {@link #listByRepo(long)} keeps its original semantics (every row
 * for the repo) so we can preserve back-compat for callers that don't yet know about branches, but
 * the {@code GithubAppApi} enrichment filters down to the default branch.
 */
@RegisterFieldMapper(GithubPipelineDiscoveredRow.class)
public interface GithubPipelineDiscoveredDao {

  String COLS =
      "id, repo_id, branch, filename, content_sha, parsed_metadata, parse_error, "
          + "last_scanned_at, created_at";

  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.github_pipelines_discovered WHERE repo_id = :repoId "
          + "ORDER BY branch, filename")
  @NonNull
  List<GithubPipelineDiscoveredRow> listByRepo(@Bind("repoId") long repoId);

  /**
   * Rows for one (repo, branch) pair — used by the scanner's per-branch delete-then-insert flush
   * and by callers that want to render exactly one branch's parses (e.g. the default-branch view
   * the UI shows).
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.github_pipelines_discovered "
          + "WHERE repo_id = :repoId AND branch = :branch "
          + "ORDER BY filename")
  @NonNull
  List<GithubPipelineDiscoveredRow> listByRepoAndBranch(
      @Bind("repoId") long repoId, @Bind("branch") @NonNull String branch);

  /**
   * Delete all rows for one (repo, branch). Per-branch scans use this so they don't trample rows
   * parsed against other branches of the same repo.
   */
  @SqlUpdate(
      "DELETE FROM titan.github_pipelines_discovered "
          + "WHERE repo_id = :repoId AND branch = :branch")
  int deleteByRepoAndBranch(@Bind("repoId") long repoId, @Bind("branch") @NonNull String branch);

  /** Drop every row for the repo, across all branches — used by repo-deletion cleanup paths. */
  @SqlUpdate("DELETE FROM titan.github_pipelines_discovered WHERE repo_id = :repoId")
  int deleteByRepo(@Bind("repoId") long repoId);

  @SqlUpdate(
      "INSERT INTO titan.github_pipelines_discovered "
          + "(repo_id, branch, filename, content_sha, parsed_metadata, parse_error) "
          + "VALUES (:repoId, :branch, :filename, :contentSha, :parsedMetadata, :parseError)")
  int insert(
      @Bind("repoId") long repoId,
      @Bind("branch") @NonNull String branch,
      @Bind("filename") @NonNull String filename,
      @Bind("contentSha") @NonNull String contentSha,
      @Bind("parsedMetadata") @Nullable String parsedMetadata,
      @Bind("parseError") @Nullable String parseError);
}

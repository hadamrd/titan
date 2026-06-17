package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.GithubRepositoryRow;
import java.util.List;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.github_repositories} (#832, design/63).
 *
 * <p>The {@code is_private} column is aliased to {@code isPrivate} via {@code SELECT ... AS}; the
 * row POJO uses {@code isPrivate} (because {@code private} is a Java reserved word).
 */
@RegisterFieldMapper(GithubRepositoryRow.class)
public interface GithubRepositoryDao {

  String COLS =
      "id, install_id, repo_id, owner, name, default_branch, "
          + "is_private AS is_private, last_scanned_at, created_at";

  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.github_repositories WHERE install_id = :installId "
          + "ORDER BY owner, name")
  @NonNull
  List<GithubRepositoryRow> listByInstall(@Bind("installId") long installId);

  /** Idempotent upsert by {@code repo_id} — implemented as DELETE + INSERT for portability. */
  @SqlUpdate("DELETE FROM titan.github_repositories WHERE repo_id = :repoId")
  int deleteByRepoId(@Bind("repoId") long repoId);

  @SqlUpdate(
      "INSERT INTO titan.github_repositories "
          + "(install_id, repo_id, owner, name, default_branch, is_private) "
          + "VALUES (:installId, :repoId, :owner, :name, :defaultBranch, :isPrivate)")
  int insert(
      @Bind("installId") long installId,
      @Bind("repoId") long repoId,
      @Bind("owner") @NonNull String owner,
      @Bind("name") @NonNull String name,
      @Bind("defaultBranch") @Nullable String defaultBranch,
      @Bind("isPrivate") boolean isPrivate);

  @SqlUpdate("DELETE FROM titan.github_repositories WHERE install_id = :installId")
  int deleteByInstall(@Bind("installId") long installId);

  /**
   * Stamp {@code last_scanned_at = CURRENT_TIMESTAMP} on a repo row — called by the scanner at the
   * END of every scan attempt, including the 404 / "no .titan/pipelines dir" path, so the freshness
   * signal in the UI does not get stuck on repos that legitimately have no pipelines.
   */
  @SqlUpdate(
      "UPDATE titan.github_repositories SET last_scanned_at = CURRENT_TIMESTAMP "
          + "WHERE repo_id = :repoId")
  int markScanned(@Bind("repoId") long repoId);
}

package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.GithubInstallationRow;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/** JDBI SqlObject access for {@code titan.github_installations} (#832, design/63). */
@RegisterFieldMapper(GithubInstallationRow.class)
public interface GithubInstallationDao {

  String COLS =
      "id, install_id, account_login, account_type, target_type, "
          + "suspended_at, created_at, updated_at";

  @SqlQuery("SELECT " + COLS + " FROM titan.github_installations ORDER BY account_login")
  @NonNull
  List<GithubInstallationRow> listAll();

  @SqlQuery("SELECT " + COLS + " FROM titan.github_installations WHERE install_id = :installId")
  @NonNull
  Optional<GithubInstallationRow> findByInstallId(@Bind("installId") long installId);

  /**
   * Insert a fresh installation row. Callers should call {@link #findByInstallId} first; the unique
   * constraint on {@code install_id} will surface as a runtime exception otherwise.
   */
  @SqlUpdate(
      "INSERT INTO titan.github_installations "
          + "(install_id, account_login, account_type, target_type, suspended_at) "
          + "VALUES (:installId, :accountLogin, :accountType, :targetType, :suspendedAt)")
  @GetGeneratedKeys
  long insert(
      @Bind("installId") long installId,
      @Bind("accountLogin") @NonNull String accountLogin,
      @Bind("accountType") @NonNull String accountType,
      @Bind("targetType") @NonNull String targetType,
      @Bind("suspendedAt") java.time.Instant suspendedAt);

  /**
   * Idempotent update of mutable fields — called on every {@code installation} webhook redelivery.
   */
  @SqlUpdate(
      "UPDATE titan.github_installations SET "
          + " account_login = :accountLogin, account_type = :accountType, "
          + " target_type = :targetType, suspended_at = :suspendedAt, "
          + " updated_at = CURRENT_TIMESTAMP "
          + "WHERE install_id = :installId")
  int update(
      @Bind("installId") long installId,
      @Bind("accountLogin") @NonNull String accountLogin,
      @Bind("accountType") @NonNull String accountType,
      @Bind("targetType") @NonNull String targetType,
      @Bind("suspendedAt") java.time.Instant suspendedAt);

  @SqlUpdate("DELETE FROM titan.github_installations WHERE install_id = :installId")
  int deleteByInstallId(@Bind("installId") long installId);

  /**
   * Stamp {@code suspended_at = CURRENT_TIMESTAMP} on an installation — called by the scanner when
   * the GitHub API returns 401 for the installation token (App was uninstalled or its access was
   * revoked). The row stays so the operator can see "this install was suspended at T"; it is
   * cleared on the next successful sync.
   */
  @SqlUpdate(
      "UPDATE titan.github_installations SET "
          + "suspended_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP "
          + "WHERE install_id = :installId")
  int markSuspended(@Bind("installId") long installId);
}

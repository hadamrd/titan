package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.GithubAppRow;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.BindFields;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.github_app} — the single registered GitHub App row.
 *
 * <p>Single-row invariant: {@code id = 1} primary key + CHECK constraint. The {@link #upsert}
 * statement uses the H2/PG-portable {@code MERGE} dialect via two-step "delete-if-mismatch + insert
 * + try-update" — encoded in the service layer instead, since portable MERGE syntax differs.
 *
 * <p>Sealed bytes are read for the in-process JWT signer; they are NEVER exposed via API DTOs.
 */
@RegisterFieldMapper(GithubAppRow.class)
public interface GithubAppDao {

  String COLS =
      "id, app_id, name, slug, html_url, "
          + "pem_sealed_value, pem_wrapped_dek, pem_kek_version, "
          + "webhook_secret_sealed_value, webhook_secret_wrapped_dek, webhook_secret_kek_version, "
          + "created_at, updated_at";

  @SqlQuery("SELECT " + COLS + " FROM titan.github_app WHERE id = 1")
  @NonNull
  Optional<GithubAppRow> findSingleton();

  /**
   * Insert the single row (id = 1). Throws if a row already exists — call {@link #update} on
   * subsequent manifest callbacks.
   */
  @SqlUpdate(
      "INSERT INTO titan.github_app "
          + "(id, app_id, name, slug, html_url, "
          + " pem_sealed_value, pem_wrapped_dek, pem_kek_version, "
          + " webhook_secret_sealed_value, webhook_secret_wrapped_dek, webhook_secret_kek_version) "
          + "VALUES (1, :appId, :name, :slug, :htmlUrl, "
          + " :pemSealedValue, :pemWrappedDek, :pemKekVersion, "
          + " :webhookSecretSealedValue, :webhookSecretWrappedDek, :webhookSecretKekVersion)")
  int insert(@BindFields GithubAppRow row);

  /** Replace the single row in place — used when the manifest-callback is replayed (#832 row 7). */
  @SqlUpdate(
      "UPDATE titan.github_app SET "
          + " app_id = :appId, name = :name, slug = :slug, html_url = :htmlUrl, "
          + " pem_sealed_value = :pemSealedValue, pem_wrapped_dek = :pemWrappedDek, "
          + " pem_kek_version = :pemKekVersion, "
          + " webhook_secret_sealed_value = :webhookSecretSealedValue, "
          + " webhook_secret_wrapped_dek = :webhookSecretWrappedDek, "
          + " webhook_secret_kek_version = :webhookSecretKekVersion, "
          + " updated_at = CURRENT_TIMESTAMP "
          + "WHERE id = 1")
  int update(@BindFields GithubAppRow row);

  @SqlUpdate("DELETE FROM titan.github_app WHERE id = 1")
  int deleteSingleton();

  /** Convenience: do we have a registered App? */
  @SqlQuery("SELECT COUNT(*) FROM titan.github_app WHERE id = 1")
  int count();

  /** Look up app_id directly — used by the JWT signer hot path so it doesn't load the whole row. */
  @SqlQuery("SELECT app_id FROM titan.github_app WHERE id = 1")
  @NonNull
  Optional<Long> findAppId();
}

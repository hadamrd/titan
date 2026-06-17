package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.CredentialRow;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindFields;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.credentials} — Titan's encrypted secrets store (closes
 * #274). Mirrors the {@link JobDao} pattern.
 *
 * <p>The persistent column for the user-facing addressing key is named {@code cred_key} (not {@code
 * key} — {@code key} is a reserved word in several SQL dialects). The DAO aliases it to {@code key}
 * on read so the {@link CredentialRow#key} field maps automatically via JDBI's field mapper, and
 * binds the parameter via {@code :credKey} on writes.
 */
@RegisterFieldMapper(CredentialRow.class)
public interface CredentialDao {

  String COLS =
      "id, kind, scope, cred_key, sealed_value, aad, created_at, updated_at, "
          + "wrapped_dek, kek_version, dek_version";

  // ──────────────────────────────────────────────
  // Single-row queries
  // ──────────────────────────────────────────────
  @SqlQuery("SELECT " + COLS + " FROM titan.credentials WHERE id = :id")
  @NonNull
  Optional<CredentialRow> findById(@Bind("id") long id);

  @SqlQuery(
      "SELECT " + COLS + " FROM titan.credentials WHERE scope = :scope AND cred_key = :credKey")
  @NonNull
  Optional<CredentialRow> findByScopeAndKey(
      @Bind("scope") @NonNull String scope, @Bind("credKey") @NonNull String key);

  // ──────────────────────────────────────────────
  // List queries
  // ──────────────────────────────────────────────
  @SqlQuery("SELECT " + COLS + " FROM titan.credentials ORDER BY scope, cred_key")
  @NonNull
  List<CredentialRow> listAll();

  @SqlQuery("SELECT " + COLS + " FROM titan.credentials WHERE scope = :scope ORDER BY cred_key")
  @NonNull
  List<CredentialRow> listByScope(@Bind("scope") @NonNull String scope);

  // ──────────────────────────────────────────────
  // Mutations
  // ──────────────────────────────────────────────
  /**
   * Insert a new credential. Returns the generated id. {@code created_at}/{@code updated_at} fall
   * to their column defaults ({@code CURRENT_TIMESTAMP}). {@link CredentialRow#key} maps to the
   * {@code cred_key} column via the named parameter {@code :credKey}.
   */
  @SqlUpdate(
      "INSERT INTO titan.credentials "
          + "(kind, scope, cred_key, sealed_value, aad, wrapped_dek, kek_version, dek_version) "
          + "VALUES (:kind, :scope, :credKey, :sealedValue, :aad, :wrappedDek, :kekVersion, "
          + ":dekVersion)")
  @GetGeneratedKeys
  long insert(@BindFields CredentialRow row);

  /**
   * Update the sealed value, aad, and wrapped DEK for an existing row. Bumps {@code updated_at}.
   */
  @SqlUpdate(
      "UPDATE titan.credentials SET kind = :kind, sealed_value = :sealedValue, aad = :aad, "
          + "wrapped_dek = :wrappedDek, kek_version = :kekVersion, dek_version = :dekVersion, "
          + "updated_at = CURRENT_TIMESTAMP WHERE id = :id")
  void update(@BindFields CredentialRow row);

  /** Re-wrap only — leaves sealed_value untouched. Used by KEK rotation. */
  @SqlUpdate(
      "UPDATE titan.credentials SET wrapped_dek = :wrappedDek, kek_version = :kekVersion, "
          + "updated_at = CURRENT_TIMESTAMP WHERE id = :id")
  void updateWrappedDek(
      @Bind("id") long id,
      @Bind("wrappedDek") @NonNull String wrappedDek,
      @Bind("kekVersion") int kekVersion);

  @SqlUpdate("DELETE FROM titan.credentials WHERE id = :id")
  void delete(@Bind("id") long id);
}

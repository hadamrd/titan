package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.PersonalAccessTokenRow;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindFields;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.personal_access_tokens} — per-user API tokens (closes
 * #434). Mirrors the {@link CredentialDao} pattern.
 *
 * <p>Scope is enforced at the DAO boundary: every query that returns user-visible data takes a
 * {@code userSubject} parameter and filters on it. The {@link PersonalAccessTokenApi} layer pulls
 * the subject from {@code SecurityIdentity} — even ADMIN cannot list tokens that are not theirs.
 */
@RegisterFieldMapper(PersonalAccessTokenRow.class)
public interface PersonalAccessTokenDao {

  String COLS =
      "id, user_subject, name, token_hash, prefix, created_at, last_used_at, revoked_at,"
          + " scopes_json, job_pattern";

  // ── Single-row queries ────────────────────────────────────────────────────

  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.personal_access_tokens "
          + "WHERE id = :id AND user_subject = :userSubject")
  @NonNull
  Optional<PersonalAccessTokenRow> findByIdForUser(
      @Bind("id") long id, @Bind("userSubject") @NonNull String userSubject);

  // ── List queries ──────────────────────────────────────────────────────────

  /**
   * List every token (active and revoked) for the given user, newest first. The token_hash column
   * is selected because it maps to the row POJO, but callers (the API layer) MUST project it out of
   * the response DTO.
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.personal_access_tokens "
          + "WHERE user_subject = :userSubject ORDER BY created_at DESC")
  @NonNull
  List<PersonalAccessTokenRow> listByUser(@Bind("userSubject") @NonNull String userSubject);

  // ── Mutations ─────────────────────────────────────────────────────────────

  /**
   * Insert a new token row. Returns the generated id. {@code created_at} falls to its column
   * default ({@code CURRENT_TIMESTAMP}); {@code last_used_at} and {@code revoked_at} default to
   * NULL.
   */
  @SqlUpdate(
      "INSERT INTO titan.personal_access_tokens "
          + "(user_subject, name, token_hash, prefix, scopes_json, job_pattern) "
          + "VALUES (:userSubject, :name, :tokenHash, :prefix, :scopesJson, :jobPattern)")
  @GetGeneratedKeys
  long insert(@BindFields PersonalAccessTokenRow row);

  /**
   * Revoke a token (soft delete) — sets {@code revoked_at = CURRENT_TIMESTAMP} only when the row is
   * owned by {@code userSubject} AND is not already revoked. Returns the number of rows updated (0
   * = not found / not yours / already revoked, 1 = revoked).
   */
  @SqlUpdate(
      "UPDATE titan.personal_access_tokens SET revoked_at = CURRENT_TIMESTAMP "
          + "WHERE id = :id AND user_subject = :userSubject AND revoked_at IS NULL")
  int revoke(@Bind("id") long id, @Bind("userSubject") @NonNull String userSubject);

  // ── Bearer-auth lookups (closes #477) ─────────────────────────────────────

  /**
   * Find every non-revoked token whose stored {@code prefix} matches {@code prefix}. The bearer-
   * auth path uses this to narrow candidates before doing a per-row BCrypt {@code checkpw} compare.
   *
   * <p>Returned in id order so the auth mechanism's iteration is deterministic across replicas.
   * Usually returns 0 or 1 rows — {@code prefix} carries 4 base32 chars after the {@code
   * "titanpat_"} marker, giving a 1-in-1024 collision rate at the prefix index.
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.personal_access_tokens "
          + "WHERE prefix = :prefix AND revoked_at IS NULL "
          + "ORDER BY id")
  @NonNull
  List<PersonalAccessTokenRow> findActiveByPrefix(@Bind("prefix") @NonNull String prefix);

  /**
   * Stamp {@code last_used_at = CURRENT_TIMESTAMP} on a token. Idempotent / write-amplified —
   * called once per successful PAT-authenticated request. Returns rows-updated (1 on success, 0 if
   * the row was revoked between lookup and stamp).
   */
  @SqlUpdate(
      "UPDATE titan.personal_access_tokens SET last_used_at = CURRENT_TIMESTAMP "
          + "WHERE id = :id AND revoked_at IS NULL")
  int touchLastUsed(@Bind("id") long id);
}

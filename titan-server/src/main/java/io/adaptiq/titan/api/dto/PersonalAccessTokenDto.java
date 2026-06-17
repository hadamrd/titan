package io.adaptiq.titan.api.dto;

import io.adaptiq.titan.auth.PatScopes;
import io.adaptiq.titan.store.rows.PersonalAccessTokenRow;
import java.time.Instant;
import java.util.List;

/**
 * API response DTO for a personal access token — metadata only (closes #434).
 *
 * <p><strong>Never exposes the plaintext token or the BCrypt hash.</strong> The wire format is a
 * strict subset of {@link PersonalAccessTokenRow}: id, name, prefix, timestamps, revoked flag,
 * scopes. The plaintext is shown to the user exactly once, in the {@link
 * PersonalAccessTokenCreatedDto} response of {@code POST /api/v1/me/tokens}, and is never returned
 * again.
 *
 * <p>{@code scopes} (closes #500): when the row was created with a scope restriction this is the
 * list of role strings (e.g. {@code ["READ_JOB","TRIGGER_BUILD"]}). {@code null} = legacy token
 * that inherits the creator's full role set.
 *
 * <p>{@code jobPattern} (closes #1082): the per-token job-name glob, or {@code null} for tokens
 * with no path restriction. The UI renders this as a separate badge in the tokens table so an
 * operator can tell at a glance which jobs a given token can reach.
 *
 * <p>NOTE: no {@code @JsonInclude(NON_NULL)} — nulls MUST serialise as JSON null so the SPA
 * receives explicit {@code null} (not missing keys / undefined). Without this, an active token with
 * no revokedAt rendered as "Revoked" because {@code undefined !== null} in TypeScript.
 */
public record PersonalAccessTokenDto(
    long id,
    String name,
    String prefix,
    Instant createdAt,
    Instant lastUsedAt,
    Instant revokedAt,
    List<String> scopes,
    String jobPattern) {

  /** Map from a DAO row — drops {@code tokenHash} and {@code userSubject} on purpose. */
  public static PersonalAccessTokenDto from(PersonalAccessTokenRow row) {
    List<String> scopes = PatScopes.parseOrNull(row.scopesJson);
    return new PersonalAccessTokenDto(
        row.id,
        row.name,
        row.prefix,
        row.createdAt,
        row.lastUsedAt,
        row.revokedAt,
        scopes,
        row.jobPattern);
  }
}

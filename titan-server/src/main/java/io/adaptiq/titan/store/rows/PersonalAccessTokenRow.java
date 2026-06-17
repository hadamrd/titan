package io.adaptiq.titan.store.rows;

import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.personal_access_tokens} — per-user API tokens for
 * headless / CLI / CI use (closes #434).
 *
 * <p><strong>Security invariants:</strong>
 *
 * <ul>
 *   <li>{@code tokenHash} stores a BCrypt digest. The plaintext token is shown to the user exactly
 *       once at generation time (the POST response) and is never persisted.
 *   <li>{@code prefix} is the first ~8 characters of the public token (e.g. {@code
 *       "titanpat_a3f4"}) — safe to render in list views.
 *   <li>{@code revokedAt} is a soft-delete tombstone. Verification MUST treat any non-null value as
 *       failed authentication.
 * </ul>
 */
public class PersonalAccessTokenRow {
  public long id;
  public String userSubject;
  public String name;
  public String tokenHash;
  public String prefix;
  public Instant createdAt;
  public Instant lastUsedAt;
  public Instant revokedAt;

  /**
   * Per-PAT scope restriction (closes #500). {@code null} = legacy behaviour (inherit the creator's
   * full role set). Non-null = a JSON array string (e.g. {@code ["READ_JOB","TRIGGER_BUILD"]})
   * whose entries narrow the resolved {@code SecurityIdentity}'s roles to the intersection of
   * (creator roles) ∩ (scopes).
   *
   * <p>JSON parsing happens at the API/auth layer — the row mapper stores the raw VARCHAR.
   */
  public String scopesJson;

  /**
   * Per-PAT job-name glob restriction (closes #1082). {@code null} = no path restriction (token
   * operates across every job inside its role scope, same as #500 day-1 behaviour). Non-null = a
   * glob string (e.g. {@code "acme/web-*"} or {@code "org/my-app/**"}) that the request's resolved
   * job {@code full_name} must match, or the request is denied with 403 and a {@code
   * PAT_SCOPE_DENIED} audit row.
   *
   * <p>The glob lives in app code ({@code io.adaptiq.titan.auth.PatJobPattern}) so the SQL layer
   * stays portable across H2 and PostgreSQL — no LIKE / regex pushdown.
   */
  public String jobPattern;
}

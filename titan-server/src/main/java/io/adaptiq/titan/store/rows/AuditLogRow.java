package io.adaptiq.titan.store.rows;

import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.audit_log} — audit trail for high-risk actions
 * (closes #478).
 *
 * <p>Public fields per the project convention (JDBI {@code @RegisterFieldMapper}). The actor is the
 * OIDC {@code preferred_username} (preferred) or {@code sub} (fallback); the API layer pulls it
 * from {@code SecurityIdentity} — never a body-supplied identity.
 *
 * <p><strong>Security invariant:</strong> {@code detailsJson} MUST NOT carry plaintext secrets.
 * Token-create / credential-create paths record the row id only (e.g. {@code {"tokenId": 7}}).
 */
public class AuditLogRow {
  public long id;
  public Instant occurredAt;
  public String actor;
  public String action;
  public String targetType;
  public String targetId;
  public String detailsJson;
}

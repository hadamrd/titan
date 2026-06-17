package io.adaptiq.titan.store.rows;

import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.audit_retention_policy} — per-event-kind audit-log
 * retention horizon (closes #1104).
 *
 * <p>Public fields per the project convention (JDBI {@code @RegisterFieldMapper}).
 *
 * <ul>
 *   <li>{@code kind} — the audit action code (1:1 with {@link io.adaptiq.titan.audit.AuditAction})
 *       OR the reserved sentinel {@code "*"} carrying the server-wide default.
 *   <li>{@code maxAgeDays} — retention horizon in days; always {@code > 0} (DB CHECK + API guard).
 *   <li>{@code updatedAt} — last time this policy row was created or overridden.
 * </ul>
 */
public class AuditRetentionPolicyRow {
  public String kind;
  public int maxAgeDays;
  public Instant updatedAt;
}

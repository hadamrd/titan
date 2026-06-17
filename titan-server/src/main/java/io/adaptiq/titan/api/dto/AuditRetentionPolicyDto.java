package io.adaptiq.titan.api.dto;

import io.adaptiq.titan.store.rows.AuditRetentionPolicyRow;
import java.time.Instant;

/**
 * Wire format for one audit-log retention policy (closes #1104). Backs {@code GET/PUT
 * /api/v1/admin/audit/policy}.
 *
 * @param kind the audit action code, or {@code "*"} for the server-wide default
 * @param maxAgeDays retention horizon in days (always {@code >= 1})
 * @param updatedAt when this policy was last created / overridden (null for the in-code fallback)
 */
public record AuditRetentionPolicyDto(String kind, int maxAgeDays, Instant updatedAt) {

  /** Map a persisted row to its wire DTO. */
  public static AuditRetentionPolicyDto from(AuditRetentionPolicyRow row) {
    return new AuditRetentionPolicyDto(row.kind, row.maxAgeDays, row.updatedAt);
  }
}

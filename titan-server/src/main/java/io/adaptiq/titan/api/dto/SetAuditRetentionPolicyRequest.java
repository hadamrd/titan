package io.adaptiq.titan.api.dto;

/**
 * Wire format for {@code PUT /api/v1/admin/audit/policy} (closes #1104) — set or override one
 * kind's retention horizon.
 *
 * @param kind the audit action code (validated against {@code AuditAction}) or {@code "*"} for the
 *     server-wide default
 * @param maxAgeDays retention horizon in days; MUST be {@code >= 1} — a 0-day policy is rejected
 *     with HTTP 400 (it would purge the kind's entire history)
 */
public record SetAuditRetentionPolicyRequest(String kind, Integer maxAgeDays) {}

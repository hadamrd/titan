package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.adaptiq.titan.auth.RbacDecision;
import io.adaptiq.titan.store.rows.RbacAuditRow;
import java.time.Instant;

/**
 * Wire shape for a single typed RBAC audit row from {@code titan.rbac_audit} (closes #1167).
 *
 * <p>This is the operator-facing projection of {@link RbacAuditRow}. Unlike the generic {@link
 * AuditEventDto} (whose RBAC verdict is buried inside an opaque {@code detailsJson} blob), the
 * {@code verdict} here is a <b>first-class, closed-union</b> field: the {@link RbacDecision} enum.
 * Jackson serialises it as {@code "ALLOW"} / {@code "DENY"}. Per the manifesto's
 * no-stringly-typed-cross-module-discriminators rule, adding a new verdict server-side forces a
 * compile break (the enum is the single source of truth) rather than silently rendering as a
 * generic string on the UI.
 *
 * <p>{@code actor} (the {@code user_id} column) and {@code effectiveRole} are nullable — an
 * anonymous deny has no resolved principal and the rare "could not resolve role" case leaves {@code
 * effectiveRole} null. The UI must render both without crashing (tested in {@code
 * rbac-audit.test.tsx}).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RbacAuditEventDto(
    long id,
    Instant occurredAt,
    String actor,
    String endpoint,
    String scopeKind,
    String scopeId,
    String requiredRole,
    String effectiveRole,
    RbacDecision verdict) {

  /**
   * Project a persisted row onto the wire DTO. {@code decision} is materialised back into the
   * {@link RbacDecision} enum at this boundary — the DB CHECK constraint guarantees it is one of
   * {@code ALLOW}/{@code DENY}, so {@link RbacDecision#valueOf} never throws on a well-formed row.
   */
  public static RbacAuditEventDto from(RbacAuditRow row) {
    return new RbacAuditEventDto(
        row.id,
        row.occurredAt,
        row.userId,
        row.endpoint,
        row.scopeKind,
        row.scopeId,
        row.requiredRole,
        row.effectiveRole,
        RbacDecision.valueOf(row.decision));
  }
}

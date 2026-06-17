package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.AuditRetentionPolicyDto;
import io.adaptiq.titan.api.dto.SetAuditRetentionPolicyRequest;
import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.audit.AuditRetentionPolicy;
import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.store.TitanStores;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Jakarta REST resource: {@code /api/v1/admin/audit/policy} — operator override surface for the
 * audit-log retention policy applied by the nightly {@link io.adaptiq.titan.audit.RetentionJob}
 * (closes #1104).
 *
 * <ul>
 *   <li>{@code GET /api/v1/admin/audit/policy} — list every configured policy row (the {@code "*"}
 *       default plus any per-kind overrides), newest-updated semantics aside; the operator sees
 *       exactly what is persisted.
 *   <li>{@code PUT /api/v1/admin/audit/policy} — set or override one kind's {@code max_age_days}.
 *       {@code kind} must be a recognised {@link AuditAction} or the {@code "*"} default sentinel;
 *       {@code maxAgeDays} must be {@code >= 1}. A 0-day (or negative) policy is rejected with HTTP
 *       400 — the operator-misconfig guard: it would purge the kind's entire history on the next
 *       sweep.
 * </ul>
 *
 * <p><strong>RBAC.</strong> {@link Roles#ADMIN} only — retention is destructive config; {@code
 * READ_AUDIT} grants view of the trail, never the power to shorten how long it is kept. The check
 * is at the controller boundary (manifesto §security) via {@link RolesAllowed}, before any store
 * call.
 */
@Path("/api/v1/admin/audit/policy")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class AuditPolicyHandler {

  private static final Logger LOG = Logger.getLogger(AuditPolicyHandler.class.getName());

  /** Sane upper bound so an operator cannot fat-finger a horizon that overflows the cutoff math. */
  private static final int MAX_AGE_DAYS_CAP = 36_500; // ~100 years

  private final TitanStores stores;

  AuditPolicyHandler(TitanStores stores) {
    this.stores = stores;
  }

  @GET
  @RolesAllowed(Roles.ADMIN)
  public List<AuditRetentionPolicyDto> list() {
    return stores.auditRetentionPolicy().findAll().stream()
        .map(AuditRetentionPolicyDto::from)
        .toList();
  }

  @PUT
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed(Roles.ADMIN)
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeId = "global")
  public AuditRetentionPolicyDto set(SetAuditRetentionPolicyRequest req) {
    if (req == null) {
      throw new ApiBadRequestException("request body is required");
    }
    String kind = validateKind(req.kind());
    int maxAgeDays = validateMaxAgeDays(req.maxAgeDays());

    stores.auditRetentionPolicy().upsert(kind, maxAgeDays);
    LOG.log(
        java.util.logging.Level.INFO,
        "[titan-audit-retention] policy override: kind={0} max_age_days={1}",
        new Object[] {kind, maxAgeDays});

    return stores
        .auditRetentionPolicy()
        .findByKind(kind)
        .map(AuditRetentionPolicyDto::from)
        .orElseGet(() -> new AuditRetentionPolicyDto(kind, maxAgeDays, null));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /**
   * Validate the policy kind: either the {@code "*"} default sentinel or a recognised {@link
   * AuditAction}. An unknown kind is a 400 (not a silent no-op) so a typo cannot create a dead
   * policy row the job will never apply.
   */
  private static String validateKind(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ApiBadRequestException("field 'kind' is required");
    }
    String k = raw.trim();
    if (AuditRetentionPolicy.DEFAULT_KEY.equals(k)) {
      return k;
    }
    String upper = k.toUpperCase(Locale.ROOT);
    try {
      AuditAction.valueOf(upper);
    } catch (IllegalArgumentException e) {
      throw new ApiBadRequestException(
          "field 'kind' is not a recognised audit action (or the '*' default): " + raw);
    }
    return upper;
  }

  /** Validate the horizon: present, {@code >= 1} (the 0-day guard), and under the sanity cap. */
  private static int validateMaxAgeDays(Integer raw) {
    if (raw == null) {
      throw new ApiBadRequestException("field 'maxAgeDays' is required");
    }
    int days = raw;
    if (days < 1) {
      throw new ApiBadRequestException(
          "field 'maxAgeDays' must be >= 1 (a 0-day policy would purge the kind's entire history);"
              + " got "
              + days);
    }
    if (days > MAX_AGE_DAYS_CAP) {
      throw new ApiBadRequestException(
          "field 'maxAgeDays' exceeds the cap " + MAX_AGE_DAYS_CAP + "; got " + days);
    }
    return days;
  }
}

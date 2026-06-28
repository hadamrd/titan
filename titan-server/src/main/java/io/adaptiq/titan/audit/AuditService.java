package io.adaptiq.titan.audit;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.observability.TitanMetrics;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.AuditLogRow;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Best-effort emitter for {@code titan.audit_log} rows (closes #478).
 *
 * <p>Two surfaces:
 *
 * <ul>
 *   <li>{@link #record(AuditAction, AuditTargetType, String, String)} — picks the actor from the
 *       request-scoped {@link SecurityIdentity}. Used by the REST resources.
 *   <li>{@link #recordAs(String, AuditAction, AuditTargetType, String, String)} — explicit actor.
 *       Used by callers that do not have a security identity in scope (background tasks; today the
 *       trigger engine does not call audit, but the signature is here for symmetry).
 * </ul>
 *
 * <p><strong>Best-effort:</strong> the INSERT is wrapped in a try-catch so an audit failure cannot
 * fail the main action. Failures are logged at {@code WARNING}; the request still returns 2xx.
 *
 * <p><strong>Security invariant:</strong> {@code detailsJson} MUST NOT carry plaintext secrets. The
 * PAT_CREATE path records the row id only — never the token. Callers are responsible for this; see
 * {@link io.adaptiq.titan.api.PersonalAccessTokenApi#create} for the canonical pattern.
 */
@ApplicationScoped
public class AuditService {

  private static final Logger LOGGER = Logger.getLogger(AuditService.class.getName());

  private final TitanStores stores;
  private final SecurityIdentity identity;
  // Injected as Instance<> so unit tests / non-CDI callers without a MeterRegistry on the
  // classpath still resolve the bean. Metrics are best-effort (#649).
  private final Instance<TitanMetrics> metrics;

  @Inject
  public AuditService(
      TitanStores stores, SecurityIdentity identity, Instance<TitanMetrics> metrics) {
    this.stores = stores;
    this.identity = identity;
    this.metrics = metrics;
  }

  /**
   * Record an audit event under the current OIDC identity. Returns silently on any failure — the
   * caller's main action must not be blocked by an audit write.
   */
  public void record(
      @NonNull AuditAction action,
      @NonNull AuditTargetType targetType,
      @Nullable String targetId,
      @Nullable String detailsJson) {
    recordAs(resolveActor(), action, targetType, targetId, detailsJson);
  }

  /** Explicit-actor variant — for callers without a {@link SecurityIdentity} in scope. */
  public void recordAs(
      @NonNull String actor,
      @NonNull AuditAction action,
      @NonNull AuditTargetType targetType,
      @Nullable String targetId,
      @Nullable String detailsJson) {
    try {
      AuditLogRow row = new AuditLogRow();
      row.actor = actor;
      row.action = action.name();
      row.targetType = targetType.name();
      row.targetId = targetId;
      row.detailsJson = detailsJson;
      stores.auditLog().insert(row);
      // Increment titan_audit_events_total{action=...} — best-effort, never fails the request.
      try {
        if (metrics != null && !metrics.isUnsatisfied()) {
          metrics.get().recordAuditEvent(action.name());
        }
      } catch (RuntimeException me) {
        LOGGER.log(Level.FINE, "[titan] audit meter emit failed: {0}", me.getMessage());
      }
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] audit emit failed (action={0}, target={1}/{2}): {3}",
          new Object[] {action, targetType, targetId, e.getMessage()});
    }
  }

  /**
   * Resolve the OIDC principal name for the audit actor column. Prefers {@code preferred_username}
   * (human-friendly), falls back to {@code sub}, then to the JAX-RS principal name. Returns {@code
   * "anonymous"} only when no identity is present at all.
   *
   * <p>Mirrors {@code JobsApi.principalName()} — kept here so the audit emit point is
   * self-contained.
   */
  private String resolveActor() {
    if (identity == null || identity.isAnonymous()) {
      return "anonymous";
    }
    Object preferred = identity.getAttribute("preferred_username");
    if (preferred instanceof String s && !s.isBlank()) {
      return s;
    }
    Object sub = identity.getAttribute("sub");
    if (sub instanceof String s && !s.isBlank()) {
      return s;
    }
    String name = identity.getPrincipal() != null ? identity.getPrincipal().getName() : null;
    return (name != null && !name.isBlank()) ? name : "anonymous";
  }
}

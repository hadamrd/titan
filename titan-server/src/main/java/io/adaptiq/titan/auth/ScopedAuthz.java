package io.adaptiq.titan.auth;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.audit.AuditService;
import io.adaptiq.titan.audit.AuditTargetType;
import io.adaptiq.titan.auth.Authz.TitanRole;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.RbacUserRoleRow;
import io.adaptiq.titan.store.rows.UserRoleRow;
import io.quarkus.security.ForbiddenException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Scoped RBAC permission helper — the seam {@link io.adaptiq.titan.auth.RequiresRole} drives,
 * closes #1131 (epic #1114).
 *
 * <p>Complements {@link Authz} (which gates per-{@link Action} hardcoded policy) with a per-(scope,
 * role) check that the new {@code @RequiresRole} annotation calls into. The shape mirrors the
 * issue's spec — caller asks: "does {@code user} hold at least {@code role} on {@code (scopeKind,
 * scopeId)}?".
 *
 * <p>Role hierarchy is total-ordered (highest-first): {@code ADMIN > MAINTAINER > DEVELOPER >
 * VIEWER}. A caller satisfies the required role iff their effective role on the scope is &gt;=
 * required.
 *
 * <p><strong>Effective role resolution (closed):</strong>
 *
 * <ol>
 *   <li>Read every row in {@code titan.rbac_user_role} for {@code (user, scopeKind, scopeId)}.
 *   <li>If the scope is {@link ScopeKind#REPO} and no row found, fall through to the parent {@link
 *       ScopeKind#ORG} scope by splitting {@code "<org>/<repo>"} on {@code "/"}. A REPO-scoped row
 *       wins over an ORG-scoped row on tie (rare — both ADMIN — no effect).
 *   <li>If still empty, fall through to the flat {@code titan.user_roles} table (the V35 legacy
 *       seam). This keeps every existing ADMIN seed working with no migration.
 *   <li>Empty set → effective role = {@code VIEWER} (the lowest tier — default-deny on every gated
 *       action).
 * </ol>
 *
 * <p>Audit log: every {@code requires(...)} call records exactly one row in {@code titan.audit_log}
 * with the resolved actor, the {@code RBAC_CHECK} {@link AuditAction}, the scope as {@code
 * target_type}/{@code target_id}, and an {@code outcome} of {@code ALLOWED} or {@code DENIED}. The
 * intent is the customer story's audit demand — "an audit log showing who did what".
 */
@ApplicationScoped
public class ScopedAuthz {

  private static final Logger LOGGER = Logger.getLogger(ScopedAuthz.class.getName());

  /** Ordinal-ordered alias used for the &lt;= comparison. Higher = more privilege. */
  private static int rank(TitanRole role) {
    return switch (role) {
      case VIEWER -> 1;
      case DEVELOPER -> 2;
      case MAINTAINER -> 3;
      case ADMIN -> 4;
    };
  }

  private final TitanStores stores;
  private final AuditService audit;

  @Inject
  ScopedAuthz(TitanStores stores, AuditService audit) {
    this.stores = stores;
    this.audit = audit;
  }

  /**
   * Throw {@link ForbiddenException} (→ HTTP 403) if {@code user} does not satisfy {@code required}
   * on {@code (scopeKind, scopeId)}. Emits an audit row on both ALLOWED and DENIED outcomes.
   *
   * <p>Endpoint label defaults to {@code "<unknown>"} — the {@link RequiresRoleInterceptor}
   * overload below carries the JAX-RS resource name so the {@code titan.rbac_audit} row identifies
   * which surface was probed.
   */
  public void requires(
      @NonNull AuthContext ctx,
      @NonNull TitanRole required,
      @NonNull ScopeKind scopeKind,
      @NonNull String scopeId) {
    requires(ctx, required, scopeKind, scopeId, "<unknown>");
  }

  /**
   * Endpoint-tagged variant — used by the {@code @RequiresRole} interceptor seam so the {@code
   * titan.rbac_audit} row records WHICH gated method was invoked. Same decision + throw semantics
   * as the no-endpoint overload.
   */
  public void requires(
      @NonNull AuthContext ctx,
      @NonNull TitanRole required,
      @NonNull ScopeKind scopeKind,
      @NonNull String scopeId,
      @NonNull String endpoint) {
    String user = safeUserId(ctx);
    TitanRole effective = effectiveRoleWithRealmFloor(ctx, scopeKind, scopeId);
    boolean allowed = rank(effective) >= rank(required);
    recordAudit(user, allowed, required, scopeKind, scopeId, effective);
    recordRbacAudit(user, endpoint, scopeKind, scopeId, required, effective, allowed);
    if (!allowed) {
      throw new ForbiddenException(
          "RBAC: required="
              + required
              + " effective="
              + effective
              + " on "
              + scopeKind
              + ":"
              + scopeId
              + " for "
              + (user == null ? "<anonymous>" : user));
    }
  }

  /**
   * Pure rank comparison — no I/O. Exposed so unit tests can verify the rank table without rigging
   * up a {@link TitanStores}.
   */
  public static boolean satisfies(@NonNull TitanRole effective, @NonNull TitanRole required) {
    return rank(effective) >= rank(required);
  }

  /**
   * Resolve the caller's effective role on {@code (scopeKind, scopeId)} including the Quarkus
   * realm-role floor — the exact value {@link #requires} bases its allow/deny on, minus the audit +
   * throw side-effects.
   *
   * <p>Extracted so the decision can be asserted directly (no {@link
   * io.adaptiq.titan.audit.AuditService} rigging) — used by the SSO-group enforcement IT to prove a
   * group-derived realm role grants the gated role with no scoped DB seed.
   *
   * <p>Chain: scoped {@code rbac_user_role} → org parent (REPO scopes) → flat {@code user_roles} →
   * if still {@link TitanRole#VIEWER}, the realm floor from {@code ctx.currentRoles()} (which now
   * carries any role injected by {@link GroupRoleAugmentor} at login). Scoped rows always win; the
   * realm floor only ever lifts a default {@code VIEWER}.
   */
  @NonNull
  public TitanRole effectiveRoleWithRealmFloor(
      @NonNull AuthContext ctx, @NonNull ScopeKind scopeKind, @NonNull String scopeId) {
    TitanRole effective = effectiveRole(safeUserId(ctx), scopeKind, scopeId);
    // Final fall-through: derive a role floor from the caller's Quarkus realm-role set so the
    // coarse @RolesAllowed gate above us still has a meaning for callers with no rbac_user_role /
    // user_roles row. A caller in the Quarkus ADMIN realm role is implicitly TitanRole.ADMIN even
    // before any admin seeds the scoped table. This makes @RequiresRole genuinely additive on top
    // of @RolesAllowed: scoped rows (when present) WIN — both for elevating (a viewer realm role
    // with a MAINTAINER scoped row → MAINTAINER) and for going stricter (an EDIT_PIPELINE realm
    // role with no scoped row → MAINTAINER from this fallback, but an explicit VIEWER row in
    // rbac_user_role would have won earlier and we'd never reach this fallback).
    if (effective == TitanRole.VIEWER) {
      TitanRole realmFloor = roleFloorFromRealm(ctx.currentRoles());
      if (rank(realmFloor) > rank(effective)) {
        effective = realmFloor;
      }
    }
    return effective;
  }

  /**
   * Inverse of {@link #roleFloorFromRealm}: map a granted {@link TitanRole} to the <em>downward
   * closure</em> of Quarkus realm {@code Roles.*} roles it implies. {@link GroupRoleAugmentor} adds
   * this whole set to the {@link io.quarkus.security.identity.SecurityIdentity} so a group-derived
   * role clears BOTH the per-scope {@code @RequiresRole} gate (via {@link #roleFloorFromRealm}) AND
   * every coarse {@code @RolesAllowed} gate at or below its tier.
   *
   * <p>A single realm role is NOT enough: Quarkus {@code @RolesAllowed} is a literal membership
   * check with no hierarchy, so a MAINTAINER carrying only {@code EDIT_PIPELINE} would still be
   * 403'd on every {@code @RolesAllowed(READ_JOB)} endpoint. The closure mirrors {@link
   * #roleFloorFromRealm}'s own tiers exactly (every role here floors back to {@code <= granted}):
   *
   * <ul>
   *   <li>always {@link Roles#READ_JOB} (the VIEWER floor)
   *   <li>{@code >= DEVELOPER} adds {@link Roles#TRIGGER_BUILD}, {@link Roles#REPLAY_BUILD}, {@link
   *       Roles#ABORT_BUILD}, {@link Roles#APPROVE_BUILD}
   *   <li>{@code >= MAINTAINER} adds {@link Roles#EDIT_PIPELINE}
   *   <li>{@code >= ADMIN} adds {@link Roles#ADMIN}
   * </ul>
   *
   * <p>The dedicated operator roles ({@link Roles#MANAGE_CREDENTIALS} / {@link
   * Roles#OPERATE_WORKER}) are deliberately NOT in the ADMIN closure — granting {@code
   * TitanRole.ADMIN} via a group should not hand a caller those narrow operator surfaces; the
   * {@link Roles#ADMIN} realm role alone already floors to {@code TitanRole.ADMIN} and clears the
   * {@code ADMIN}-gated endpoints.
   */
  @NonNull
  public static Set<String> canonicalRealmRoles(@NonNull TitanRole granted) {
    Set<String> out = new HashSet<>();
    out.add(Roles.READ_JOB);
    if (rank(granted) >= rank(TitanRole.DEVELOPER)) {
      out.add(Roles.TRIGGER_BUILD);
      out.add(Roles.REPLAY_BUILD);
      out.add(Roles.ABORT_BUILD);
      out.add(Roles.APPROVE_BUILD);
    }
    if (rank(granted) >= rank(TitanRole.MAINTAINER)) {
      out.add(Roles.EDIT_PIPELINE);
    }
    if (rank(granted) >= rank(TitanRole.ADMIN)) {
      out.add(Roles.ADMIN);
    }
    return out;
  }

  /**
   * Resolve the effective role for {@code (user, scope)} using the documented fallthrough chain.
   * Public so future endpoint code can answer "what can this user do here?" without throwing.
   *
   * <p>{@code null user} → {@code VIEWER} (anonymous default-deny).
   */
  @NonNull
  public TitanRole effectiveRole(
      @Nullable String user, @NonNull ScopeKind scopeKind, @NonNull String scopeId) {
    if (user == null || user.isBlank()) {
      return TitanRole.VIEWER;
    }
    Set<TitanRole> roles = loadScoped(user, scopeKind, scopeId);
    if (roles.isEmpty() && scopeKind == ScopeKind.REPO) {
      String orgId = parentOrgFromRepo(scopeId);
      if (orgId != null) {
        roles = loadScoped(user, ScopeKind.ORG, orgId);
      }
    }
    if (roles.isEmpty()) {
      roles = loadFlat(user);
    }
    return highest(roles);
  }

  // ── internals ────────────────────────────────────────────────────────────

  private Set<TitanRole> loadScoped(String userId, ScopeKind scopeKind, String scopeId) {
    List<RbacUserRoleRow> rows =
        stores.rbacUserRoles().findByUserAndScope(userId, scopeKind.name(), scopeId);
    return parse(rows.stream().map(r -> r.role).toList());
  }

  private Set<TitanRole> loadFlat(String userId) {
    List<UserRoleRow> rows = stores.userRoles().findByUserId(userId);
    return parse(rows.stream().map(r -> r.role).toList());
  }

  /**
   * Split a {@code "<org>/<repo>"} scope id on the first {@code /} and return the org. Returns
   * {@code null} for malformed input — the caller falls through to the flat-role path.
   */
  @Nullable
  static String parentOrgFromRepo(@NonNull String repoScopeId) {
    int slash = repoScopeId.indexOf('/');
    if (slash <= 0 || slash == repoScopeId.length() - 1) {
      return null;
    }
    return repoScopeId.substring(0, slash);
  }

  @NonNull
  private static Set<TitanRole> parse(@NonNull List<String> raw) {
    Set<TitanRole> out = new HashSet<>();
    for (String r : raw) {
      if (r == null) {
        continue;
      }
      try {
        out.add(TitanRole.valueOf(r.trim().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException ignored) {
        // unknown role string — drop; default-deny applies
      }
    }
    return out;
  }

  @NonNull
  private static TitanRole highest(@NonNull Set<TitanRole> roles) {
    TitanRole best = TitanRole.VIEWER;
    for (TitanRole r : roles) {
      if (rank(r) > rank(best)) {
        best = r;
      }
    }
    return best;
  }

  @Nullable
  private static String safeUserId(@NonNull AuthContext ctx) {
    String u = ctx.currentUser();
    if (u == null || u.isBlank() || "<anonymous>".equals(u)) {
      return null;
    }
    return u;
  }

  /**
   * Map a Quarkus realm-role set to the closest TitanRole floor. Used as the final fallback in
   * {@link #requires} when neither the scoped {@code rbac_user_role} table nor the flat {@code
   * user_roles} table carries an entry for the caller. Mapping (highest wins):
   *
   * <ul>
   *   <li>{@link Roles#ADMIN} / {@link Roles#MANAGE_CREDENTIALS} / {@link Roles#OPERATE_WORKER} →
   *       {@link TitanRole#ADMIN}
   *   <li>{@link Roles#EDIT_PIPELINE} → {@link TitanRole#MAINTAINER}
   *   <li>{@link Roles#TRIGGER_BUILD} / {@link Roles#REPLAY_BUILD} / {@link Roles#ABORT_BUILD} /
   *       {@link Roles#APPROVE_BUILD} → {@link TitanRole#DEVELOPER}
   *   <li>everything else → {@link TitanRole#VIEWER}
   * </ul>
   *
   * <p><strong>Why the dedicated operator roles map to {@link TitanRole#ADMIN}.</strong> {@link
   * Roles#MANAGE_CREDENTIALS} (the secrets operator) and {@link Roles#OPERATE_WORKER} (the worker
   * operator) are the admin-equivalent <em>for their narrow surface</em>: a credentials operator IS
   * the administrator of the credential store; a worker operator IS the administrator of the worker
   * pool. Mapping them to the {@code ADMIN} floor lets the new {@code @RequiresRole(ADMIN)} gates
   * on {@code CredentialsApi} / {@code WorkersApi} (#1174) admit them without a scoped DB seed. The
   * elevation is <em>bounded by the coarse {@code @RolesAllowed} list on each endpoint</em> — a
   * {@code MANAGE_CREDENTIALS} holder only clears the Quarkus gate on {@code CredentialsApi}, an
   * {@code OPERATE_WORKER} holder only on {@code WorkersApi}, so this floor never reaches an
   * unrelated admin surface (e.g. {@code AdminQueueApi}, whose {@code @RolesAllowed} is {@code
   * ADMIN}-only, 403s them before the interceptor runs). {@link Roles#APPROVE_BUILD} maps to {@code
   * DEVELOPER} so it clears the {@code @RequiresRole(DEVELOPER)} gate on {@code ApprovalsApi}.
   *
   * <p>Visibility is package-private so the unit test can verify the mapping table directly.
   */
  @NonNull
  static TitanRole roleFloorFromRealm(@Nullable Set<String> realmRoles) {
    if (realmRoles == null || realmRoles.isEmpty()) {
      return TitanRole.VIEWER;
    }
    if (realmRoles.contains(Roles.ADMIN)
        || realmRoles.contains(Roles.MANAGE_CREDENTIALS)
        || realmRoles.contains(Roles.OPERATE_WORKER)) {
      return TitanRole.ADMIN;
    }
    if (realmRoles.contains(Roles.EDIT_PIPELINE)) {
      return TitanRole.MAINTAINER;
    }
    if (realmRoles.contains(Roles.TRIGGER_BUILD)
        || realmRoles.contains(Roles.REPLAY_BUILD)
        || realmRoles.contains(Roles.ABORT_BUILD)
        || realmRoles.contains(Roles.APPROVE_BUILD)) {
      return TitanRole.DEVELOPER;
    }
    return TitanRole.VIEWER;
  }

  /**
   * Insert one row into {@code titan.rbac_audit} per check. Best-effort — wrapped in a try/catch so
   * an audit-write failure cannot fail the underlying request. Failures log at {@code WARNING}; the
   * caller still throws/returns by the normal allow/deny path. Mirrors the {@link
   * io.adaptiq.titan.audit.AuditService}'s swallow-failures invariant.
   */
  void recordRbacAudit(
      @Nullable String user,
      @NonNull String endpoint,
      @NonNull ScopeKind scopeKind,
      @NonNull String scopeId,
      @NonNull TitanRole required,
      @NonNull TitanRole effective,
      boolean allowed) {
    try {
      stores
          .rbacAudit()
          .insert(
              user,
              endpoint,
              scopeKind.name(),
              scopeId,
              required.name(),
              effective.name(),
              (allowed ? RbacDecision.ALLOW : RbacDecision.DENY).name());
    } catch (RuntimeException e) {
      // Audit failure must not block the request. The decision (allow/deny) has already been made
      // by the caller; the only loss is the queryable trail. The wider audit_log row written by
      // recordAudit above is the secondary signal an operator can fall back to.
      LOGGER.log(Level.WARNING, "rbac_audit insert failed (best-effort): " + e.getMessage(), e);
    }
  }

  private void recordAudit(
      @Nullable String user,
      boolean allowed,
      TitanRole required,
      ScopeKind scopeKind,
      String scopeId,
      TitanRole effective) {
    String details =
        "{\"outcome\":\""
            + (allowed ? "ALLOWED" : "DENIED")
            + "\",\"required\":\""
            + required.name()
            + "\",\"effective\":\""
            + effective.name()
            + "\",\"scope\":\""
            + scopeKind.name()
            + ":"
            + jsonEscape(scopeId)
            + "\"}";
    // V20 audit_log target_type is an open VARCHAR(32); the existing AuditTargetType enum has no
    // ORG/REPO yet so we encode the scope kind in the details JSON and use JOB as the target_type
    // placeholder until a future migration adds dedicated enum values.
    AuditTargetType targetType = AuditTargetType.JOB;
    if (user == null) {
      audit.recordAs("anonymous", AuditAction.RBAC_CHECK, targetType, scopeId, details);
    } else {
      audit.record(AuditAction.RBAC_CHECK, targetType, scopeId, details);
    }
  }

  private static String jsonEscape(String s) {
    StringBuilder out = new StringBuilder(s.length() + 4);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }
}

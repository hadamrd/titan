package io.adaptiq.titan.auth;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.audit.AuditService;
import io.adaptiq.titan.audit.AuditTargetType;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.UserRoleRow;
import io.quarkus.security.ForbiddenException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * RBAC permission helper — the v1 enforceable RBAC seam (closes #1121).
 *
 * <p>Usage from a JAX-RS resource:
 *
 * <pre>{@code
 * @POST @Path("...") public Response replay(...) {
 *   authz.requires(auth, Action.BUILD_RERUN, new Resource.JobResource(build.jobId()));
 *   // ... side effects ...
 * }
 * }</pre>
 *
 * <p>{@code requires(...)} reads the caller's flat roles from {@code titan.user_roles}, applies the
 * hardcoded action→roles policy table (see {@link #isAllowed}), emits an audit row on BOTH allow
 * and deny, and throws {@link ForbiddenException} on deny (mapped to HTTP 403 by the existing
 * {@link io.adaptiq.titan.api.exception.ForbiddenExceptionMapper}).
 *
 * <p><strong>Default-deny.</strong> A caller with no row in {@code titan.user_roles} resolves to an
 * empty role set — denied for every privileged action. The check is intentionally NOT "if no rows,
 * allow as superuser" — we'd rather break a brand-new deploy until someone seeds the first ADMIN
 * row than ship the dev-mode foot-gun.
 *
 * <p><strong>Composition with Quarkus realm roles.</strong> The Quarkus security pipeline runs
 * first ({@code @RolesAllowed} on the resource method), gating coarse classes of access. {@code
 * Authz.requires(...)} runs after, gating per-resource decisions inside the handler. Both must pass
 * — the order is by design (cheap-coarse first, DB-backed-fine second).
 *
 * <p><strong>Why a separate role enum.</strong> {@link Roles} carries the legacy Keycloak realm
 * roles (READ_JOB, TRIGGER_BUILD, EDIT_PIPELINE, …) that feed {@code @RolesAllowed}. {@link
 * TitanRole} carries the v1 flat-RBAC roles (ADMIN, MAINTAINER, DEVELOPER, VIEWER) #1114 will lift
 * into groups + scoped folders.
 */
@ApplicationScoped
public class Authz {

  /** v1 role set. Closed enum so policy-table key lookups are compile-checked. */
  public enum TitanRole {
    ADMIN,
    MAINTAINER,
    DEVELOPER,
    VIEWER
  }

  private final TitanStores stores;
  private final AuditService audit;

  @Inject
  Authz(TitanStores stores, AuditService audit) {
    this.stores = stores;
    this.audit = audit;
  }

  /**
   * Throw {@link ForbiddenException} (→ HTTP 403) if {@code ctx} is not authorized to perform
   * {@code action} on {@code resource}. Returns void on allow. Emits an audit row in both cases.
   */
  public void requires(
      @NonNull AuthContext ctx, @NonNull Action action, @NonNull Resource resource) {
    requires(ctx, action, resource, null);
  }

  /** Variant that accepts a remoteIp for the audit row. */
  public void requires(
      @NonNull AuthContext ctx,
      @NonNull Action action,
      @NonNull Resource resource,
      String remoteIp) {
    String user = safeUserId(ctx);
    Set<TitanRole> userRoles = loadRoles(user);
    boolean allowed = isAllowed(action, userRoles);
    AuditAction auditAction = auditActionFor(action);
    AuditTargetType targetType = AuditTargetType.JOB; // v1: every Resource is JobResource
    String targetId = targetIdOf(resource);
    String details = renderDetails(allowed, resource, remoteIp);
    if (user == null) {
      audit.recordAs("anonymous", auditAction, targetType, targetId, details);
    } else {
      audit.record(auditAction, targetType, targetId, details);
    }
    if (!allowed) {
      throw new ForbiddenException(
          "RBAC: "
              + action.name()
              + " on "
              + resource.auditTag()
              + " denied for "
              + (user == null ? "<anonymous>" : user));
    }
  }

  /**
   * Pure policy decision — no I/O. The hardcoded action→roles table for v1. Both guarded actions
   * require {@link TitanRole#ADMIN} or {@link TitanRole#MAINTAINER}; {@code DEVELOPER} and {@code
   * VIEWER} are denied. {@code switch} is exhaustive over {@link Action} — a new action without a
   * row here fails the compile, not silently allows.
   */
  public static boolean isAllowed(@NonNull Action action, @NonNull Set<TitanRole> roles) {
    return switch (action) {
      case BUILD_RERUN, PIPELINE_EDIT ->
          roles.contains(TitanRole.ADMIN) || roles.contains(TitanRole.MAINTAINER);
    };
  }

  /** Map an {@link Action} to its paired {@link AuditAction}. Total over the enum. */
  static AuditAction auditActionFor(@NonNull Action action) {
    return switch (action) {
      case BUILD_RERUN -> AuditAction.BUILD_RERUN;
      case PIPELINE_EDIT -> AuditAction.PIPELINE_EDIT;
    };
  }

  /**
   * Parse a list of role-name strings (as carried in {@code titan.user_roles.role}) into typed
   * {@link TitanRole}s. Unknown / null / blank values are silently dropped — a stale string left
   * over from a future migration won't crash the request but also won't grant access.
   */
  public static Set<TitanRole> parseRoles(@NonNull List<String> raw) {
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

  // ── internals ────────────────────────────────────────────────────────────

  private Set<TitanRole> loadRoles(String userId) {
    if (userId == null) {
      return Set.of();
    }
    List<UserRoleRow> rows = stores.userRoles().findByUserId(userId);
    if (rows.isEmpty()) {
      return Set.of();
    }
    Set<TitanRole> roles = new HashSet<>();
    for (UserRoleRow r : rows) {
      if (r.role == null) {
        continue;
      }
      try {
        roles.add(TitanRole.valueOf(r.role.trim().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException ignored) {
        // unknown role string — drop
      }
    }
    return roles;
  }

  private static String safeUserId(AuthContext ctx) {
    String u = ctx.currentUser();
    if (u == null || u.isBlank() || "<anonymous>".equals(u)) {
      return null;
    }
    return u;
  }

  private static String targetIdOf(Resource resource) {
    if (resource instanceof Resource.JobResource j) {
      return Long.toString(j.jobId());
    }
    return resource.auditTag();
  }

  private static String renderDetails(boolean allowed, Resource resource, String remoteIp) {
    StringBuilder sb = new StringBuilder(96);
    sb.append("{\"outcome\":\"").append(allowed ? "ALLOWED" : "DENIED").append("\"");
    sb.append(",\"resource\":\"").append(jsonEscape(resource.auditTag())).append("\"");
    if (remoteIp != null && !remoteIp.isBlank()) {
      sb.append(",\"remoteIp\":\"").append(jsonEscape(remoteIp)).append("\"");
    }
    sb.append("}");
    return sb.toString();
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

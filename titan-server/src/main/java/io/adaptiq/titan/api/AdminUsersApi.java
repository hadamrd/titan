package io.adaptiq.titan.api;

import io.adaptiq.titan.admin.KeycloakAdminClient;
import io.adaptiq.titan.api.dto.RoleAssignmentDto;
import io.adaptiq.titan.api.dto.RoleAssignmentRequest;
import io.adaptiq.titan.api.dto.UserDto;
import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.audit.AuditService;
import io.adaptiq.titan.audit.AuditTargetType;
import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.store.TitanStores;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Jakarta REST resource: {@code /api/v1/admin/users} — admin-only realm-user listing (backend half
 * of #603) plus scoped Titan role-assignment writes (epic #1114 item 6, closes #1236).
 *
 * <ul>
 *   <li>{@code GET /api/v1/admin/users} — list Keycloak realm users, each enriched with its scoped
 *       Titan role assignments ({@link UserDto#titanRoles()}) read from {@code
 *       titan.rbac_user_role}.
 *   <li>{@code PUT /api/v1/admin/users/{userId}/roles} — grant {@code {role, scopeKind, scopeId}}
 *       to a user, upserting into {@code titan.rbac_user_role}; returns the user's resulting role
 *       set. Unknown role/scopeKind → 400 problem+json.
 *   <li>{@code DELETE /api/v1/admin/users/{userId}/roles/{scopeKind}/{scopeId}} — revoke every role
 *       the user holds on that scope. Idempotent: an absent assignment → 404 (never 500); a present
 *       one → 204.
 * </ul>
 *
 * <p><strong>RBAC.</strong> The read {@code GET} is gated by the coarse {@link Roles#ADMIN} Quarkus
 * role. The two mutating verbs are gated by {@code @RequiresRole(ADMIN, ORG:global)} as their
 * <em>sole</em> gate — deliberately WITHOUT the coarse {@code @RolesAllowed(ADMIN)}. That coarse
 * gate 403s a non-admin in the Quarkus security layer <em>before</em> the {@code @RequiresRole}
 * interceptor runs, so the DENY {@code rbac_audit} row AC5 demands would never be written (no
 * caller can both clear {@code @RolesAllowed(ADMIN)} and fail {@code @RequiresRole(ADMIN)} — a
 * realm ADMIN resolves to {@code TitanRole.ADMIN} on every scope). Routing the denial through
 * {@link io.adaptiq.titan.auth.ScopedAuthz} instead lets a non-admin's 403 carry the DENY audit
 * row, and the {@code @RequiresRole} gate alone still satisfies the #1174 {@code
 * RbacEndpointCoverageTest} deny-by-default contract. The first {@code ADMIN} on a fresh deploy is
 * still seeded out-of-band (env/seed-script) — this surface assumes one ADMIN exists, then lets
 * them grant others without DB access.
 *
 * <p>Every successful grant/revoke writes one {@code ROLE_GRANT}/{@code ROLE_REVOKE} audit row
 * carrying actor + target user + scope + before/after role set (mirrors {@code SsoMappingApi}'s
 * before/after pattern). Validation 4xx paths write no audit row — an audit of every rejected
 * request is noise, not signal.
 */
@Path("/api/v1/admin/users")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class AdminUsersApi {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 200;

  /**
   * Scope the admin write surface gates on. Role administration is a global-admin operation, so —
   * like {@code AdminQueueApi} — the {@code @RequiresRole} floor keys the org scope to {@code
   * "global"} rather than a per-row scope. (The scope a role is GRANTED on is the request body's
   * {@code scopeId}; this constant is the scope the CALLER must be ADMIN on to perform the grant.)
   */
  private static final String ADMIN_SCOPE = "global";

  private final KeycloakAdminClient admin;
  private final TitanStores stores;
  private final AuditService audit;

  AdminUsersApi(KeycloakAdminClient admin, TitanStores stores, AuditService audit) {
    this.admin = admin;
    this.stores = stores;
    this.audit = audit;
  }

  // ── GET ──────────────────────────────────────────────────────────────────────

  @GET
  @RolesAllowed({Roles.ADMIN})
  public List<UserDto> list(
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("limit") @DefaultValue("50") int limit) {
    if (offset < 0) {
      throw new ApiBadRequestException("query param 'offset' must be >= 0");
    }
    if (limit <= 0) {
      limit = DEFAULT_LIMIT;
    }
    if (limit > MAX_LIMIT) {
      limit = MAX_LIMIT;
    }
    List<UserDto> page = admin.listUsers(offset, limit);
    // Enrich the whole page with ONE batched SELECT, then group by user in memory — listing N users
    // must not issue N findByUser() round trips (the N+1 the admin list path would otherwise have).
    List<String> usernames = page.stream().map(UserDto::username).toList();
    Map<String, List<RoleAssignmentDto>> byUser =
        stores.rbacUserRoles().findByUsers(usernames).stream()
            .collect(
                Collectors.groupingBy(
                    r -> r.userId,
                    Collectors.mapping(
                        r -> new RoleAssignmentDto(r.role, r.scopeKind, r.scopeId),
                        Collectors.toList())));
    return page.stream().map(u -> withTitanRoles(u, byUser)).toList();
  }

  // ── PUT {userId}/roles ─────────────────────────────────────────────────────────

  @PUT
  @Path("/{userId}/roles")
  @Consumes(MediaType.APPLICATION_JSON)
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeId = ADMIN_SCOPE)
  public List<RoleAssignmentDto> grantRole(
      @PathParam("userId") String userId, RoleAssignmentRequest req) {
    String user = requireUserId(userId);
    if (req == null) {
      throw new ApiBadRequestException("request body is required");
    }
    Authz.TitanRole role = parseRole(req.role());
    ScopeKind scopeKind = parseScopeKind(req.scopeKind());
    String scopeId = requireScopeId(req.scopeId());

    List<String> before = rolesOnScope(user, scopeKind, scopeId);
    stores.rbacUserRoles().grant(user, scopeKind.name(), scopeId, role.name());
    List<String> after = rolesOnScope(user, scopeKind, scopeId);

    audit.record(
        AuditAction.ROLE_GRANT,
        AuditTargetType.USER,
        user,
        "{\"scope\":\""
            + scopeKind.name()
            + ":"
            + JobsApi.jsonEscape(scopeId)
            + "\",\"role\":\""
            + role.name()
            + "\",\"before\":"
            + jsonStringArray(before)
            + ",\"after\":"
            + jsonStringArray(after)
            + "}");

    return assignments(user);
  }

  // ── DELETE {userId}/roles/{scopeKind}/{scopeId} ────────────────────────────────

  @DELETE
  @Path("/{userId}/roles/{scopeKind}/{scopeId}")
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeId = ADMIN_SCOPE)
  public Response revokeRole(
      @PathParam("userId") String userId,
      @PathParam("scopeKind") String scopeKindStr,
      @PathParam("scopeId") String scopeId) {
    String user = requireUserId(userId);
    ScopeKind scopeKind = parseScopeKind(scopeKindStr);
    String scope = requireScopeId(scopeId);

    List<String> before = rolesOnScope(user, scopeKind, scope);
    int deleted = stores.rbacUserRoles().revokeScope(user, scopeKind.name(), scope);
    if (deleted == 0) {
      // Idempotent: nothing to revoke. 404 (never 500) — the caller may safely repeat.
      throw new ApiNotFoundException(
          "no role assignment for user '" + user + "' on " + scopeKind.name() + ":" + scope);
    }

    audit.record(
        AuditAction.ROLE_REVOKE,
        AuditTargetType.USER,
        user,
        "{\"scope\":\""
            + scopeKind.name()
            + ":"
            + JobsApi.jsonEscape(scope)
            + "\",\"before\":"
            + jsonStringArray(before)
            + ",\"after\":[]}");

    return Response.noContent().build();
  }

  // ── helpers ────────────────────────────────────────────────────────────────────

  /**
   * Enrich an IdP-projected {@link UserDto} with its scoped Titan-role assignments, drawn from the
   * page's pre-fetched (batched) assignment map rather than a per-user query.
   */
  private UserDto withTitanRoles(UserDto u, Map<String, List<RoleAssignmentDto>> byUser) {
    return new UserDto(
        u.username(),
        u.email(),
        u.displayName(),
        u.realmRoles(),
        byUser.getOrDefault(u.username(), List.of()));
  }

  /** Every scoped assignment a user holds, as response DTOs. */
  private List<RoleAssignmentDto> assignments(String userId) {
    return stores.rbacUserRoles().findByUser(userId).stream()
        .map(r -> new RoleAssignmentDto(r.role, r.scopeKind, r.scopeId))
        .toList();
  }

  /** The user's role names on exactly one (scopeKind, scopeId), for before/after audit capture. */
  private List<String> rolesOnScope(String userId, ScopeKind scopeKind, String scopeId) {
    return stores.rbacUserRoles().findByUserAndScope(userId, scopeKind.name(), scopeId).stream()
        .map(r -> r.role)
        .toList();
  }

  private static String requireUserId(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ApiBadRequestException("path param 'userId' must not be blank");
    }
    return raw;
  }

  private static String requireScopeId(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ApiBadRequestException("'scopeId' must not be blank");
    }
    return raw;
  }

  private static Authz.TitanRole parseRole(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ApiBadRequestException("'role' is required");
    }
    try {
      return Authz.TitanRole.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ApiBadRequestException(
          "unknown role '" + raw + "' — expected one of ADMIN, MAINTAINER, DEVELOPER, VIEWER");
    }
  }

  private static ScopeKind parseScopeKind(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ApiBadRequestException("'scopeKind' is required");
    }
    try {
      return ScopeKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ApiBadRequestException(
          "unknown scopeKind '" + raw + "' — expected one of ORG, REPO");
    }
  }

  /** Render a list of (already enum-validated) role names as a JSON string array. */
  private static String jsonStringArray(List<String> values) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('"').append(JobsApi.jsonEscape(values.get(i))).append('"');
    }
    return sb.append(']').toString();
  }
}

package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.CreateGroupRoleMappingRequest;
import io.adaptiq.titan.api.dto.GroupRoleMappingDto;
import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.audit.AuditService;
import io.adaptiq.titan.audit.AuditTargetType;
import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.GroupMappingValidator;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.store.GroupRoleMappingDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.GroupRoleMappingRow;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;

/**
 * Jakarta REST resource: {@code /api/v1/orgs/{orgId}/sso/mappings} — per-org SSO group → Titan role
 * mapping CRUD (closes #1136).
 *
 * <ul>
 *   <li>{@code GET /api/v1/orgs/{orgId}/sso/mappings} — list every mapping for an org.
 *   <li>{@code POST /api/v1/orgs/{orgId}/sso/mappings} — create a mapping. 400 on bad {@code
 *       groupPath} / {@code role}; 409 on duplicate {@code (orgId, groupPath)}.
 *   <li>{@code PUT /api/v1/orgs/{orgId}/sso/mappings/{id}} — change the role on an existing row.
 *   <li>{@code DELETE /api/v1/orgs/{orgId}/sso/mappings/{id}} — remove the mapping.
 * </ul>
 *
 * <p><strong>Security model.</strong> Every endpoint is gated to {@link Roles#ADMIN}. Org-scoped
 * admin (admin-on-org-X but not on org-Y) is future work — this PR uses the existing realm-level
 * {@code ADMIN} role because the per-org admin role machinery lands on top of #1131. Until then the
 * audit row carries the {@code orgId} so a future review can reconstruct which org was touched.
 *
 * <p>Every successful mutation writes one {@code SSO_MAPPING_*} audit row with the before/after
 * role on UPDATE and the full {@code (orgId, groupPath, role)} on CREATE / DELETE. Failure paths
 * (validation 400, not-found 404, conflict 409) do not write audit rows — manifesto §"errors": an
 * audit log of every 4xx is noise, not signal.
 */
@Path("/api/v1/orgs/{orgId}/sso/mappings")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class SsoMappingApi {

  private final TitanStores stores;
  private final AuditService audit;

  SsoMappingApi(TitanStores stores, AuditService audit) {
    this.stores = stores;
    this.audit = audit;
  }

  // ── GET ────────────────────────────────────────────────────────────────────

  @GET
  @RolesAllowed(Roles.ADMIN)
  public List<GroupRoleMappingDto> list(@PathParam("orgId") String orgId) {
    String validOrg = validateOrgId(orgId);
    return stores.groupRoleMapping().listByOrg(validOrg).stream()
        .map(GroupRoleMappingDto::from)
        .toList();
  }

  // ── POST ───────────────────────────────────────────────────────────────────

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed(Roles.ADMIN)
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeIdParam = "orgId")
  public Response create(@PathParam("orgId") String orgId, CreateGroupRoleMappingRequest req) {
    if (req == null) {
      throw new ApiBadRequestException("request body is required");
    }
    String validOrg = validateOrgId(orgId);
    String groupPath = validateGroupPath(req.groupPath());
    String role = validateRole(req.role());

    GroupRoleMappingDao dao = stores.groupRoleMapping();
    if (dao.findByOrgAndGroup(validOrg, groupPath).isPresent()) {
      // 409 — duplicate (orgId, group_path).
      throw new ApiBadRequestException(
          "mapping for group_path '" + groupPath + "' already exists on org " + validOrg);
    }
    long id;
    try {
      id = dao.insert(validOrg, groupPath, role);
    } catch (RuntimeException e) {
      if (isUniqueViolation(e)) {
        throw new ApiBadRequestException(
            "mapping for group_path '" + groupPath + "' already exists on org " + validOrg);
      }
      throw e;
    }

    audit.record(
        AuditAction.SSO_MAPPING_CREATE,
        AuditTargetType.SSO_MAPPING,
        Long.toString(id),
        "{\"orgId\":\""
            + JobsApi.jsonEscape(validOrg)
            + "\",\"groupPath\":\""
            + JobsApi.jsonEscape(groupPath)
            + "\",\"role\":\""
            + JobsApi.jsonEscape(role)
            + "\"}");

    Optional<GroupRoleMappingRow> persisted = dao.findById(id);
    GroupRoleMappingDto dto =
        persisted
            .map(GroupRoleMappingDto::from)
            .orElseGet(() -> new GroupRoleMappingDto(id, validOrg, groupPath, role, null, null));
    return Response.status(201).entity(dto).build();
  }

  // ── PUT ────────────────────────────────────────────────────────────────────

  @PUT
  @Path("/{id}")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed(Roles.ADMIN)
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeIdParam = "orgId")
  public GroupRoleMappingDto update(
      @PathParam("orgId") String orgId,
      @PathParam("id") String idStr,
      CreateGroupRoleMappingRequest req) {
    if (req == null) {
      throw new ApiBadRequestException("request body is required");
    }
    String validOrg = validateOrgId(orgId);
    long id = JobsApi.parseLong(idStr, "id");
    String newRole = validateRole(req.role());

    GroupRoleMappingDao dao = stores.groupRoleMapping();
    GroupRoleMappingRow existing =
        dao.findById(id)
            .orElseThrow(() -> new ApiNotFoundException("mapping " + id + " not found"));
    if (!validOrg.equals(existing.orgId)) {
      // Caller is asking about org X but row belongs to org Y — present as 404 so a probe
      // cannot enumerate rows by id across orgs.
      throw new ApiNotFoundException("mapping " + id + " not found");
    }
    String beforeRole = existing.role;
    if (!beforeRole.equals(newRole)) {
      int updated = dao.updateRole(id, newRole);
      if (updated == 0) {
        // Concurrent delete — treat as 404 for the caller.
        throw new ApiNotFoundException("mapping " + id + " not found");
      }
      audit.record(
          AuditAction.SSO_MAPPING_UPDATE,
          AuditTargetType.SSO_MAPPING,
          Long.toString(id),
          "{\"orgId\":\""
              + JobsApi.jsonEscape(validOrg)
              + "\",\"groupPath\":\""
              + JobsApi.jsonEscape(existing.groupPath)
              + "\",\"before\":\""
              + JobsApi.jsonEscape(beforeRole)
              + "\",\"after\":\""
              + JobsApi.jsonEscape(newRole)
              + "\"}");
    }
    return dao.findById(id)
        .map(GroupRoleMappingDto::from)
        .orElseThrow(() -> new ApiNotFoundException("mapping " + id + " not found"));
  }

  // ── DELETE ─────────────────────────────────────────────────────────────────

  @DELETE
  @Path("/{id}")
  @RolesAllowed(Roles.ADMIN)
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeIdParam = "orgId")
  public Response delete(@PathParam("orgId") String orgId, @PathParam("id") String idStr) {
    String validOrg = validateOrgId(orgId);
    long id = JobsApi.parseLong(idStr, "id");
    GroupRoleMappingDao dao = stores.groupRoleMapping();
    GroupRoleMappingRow existing =
        dao.findById(id)
            .orElseThrow(() -> new ApiNotFoundException("mapping " + id + " not found"));
    if (!validOrg.equals(existing.orgId)) {
      throw new ApiNotFoundException("mapping " + id + " not found");
    }
    int deleted = dao.delete(id);
    if (deleted == 0) {
      throw new ApiNotFoundException("mapping " + id + " not found");
    }
    audit.record(
        AuditAction.SSO_MAPPING_DELETE,
        AuditTargetType.SSO_MAPPING,
        Long.toString(id),
        "{\"orgId\":\""
            + JobsApi.jsonEscape(validOrg)
            + "\",\"groupPath\":\""
            + JobsApi.jsonEscape(existing.groupPath)
            + "\",\"role\":\""
            + JobsApi.jsonEscape(existing.role)
            + "\"}");
    return Response.noContent().build();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static String validateOrgId(String raw) {
    try {
      return GroupMappingValidator.validateOrgId(raw);
    } catch (GroupMappingValidator.InvalidGroupMappingException e) {
      throw new ApiBadRequestException(e.getMessage());
    }
  }

  private static String validateGroupPath(String raw) {
    try {
      return GroupMappingValidator.validateGroupPath(raw);
    } catch (GroupMappingValidator.InvalidGroupMappingException e) {
      throw new ApiBadRequestException(e.getMessage());
    }
  }

  private static String validateRole(String raw) {
    try {
      return GroupMappingValidator.validateRole(raw);
    } catch (GroupMappingValidator.InvalidGroupMappingException e) {
      throw new ApiBadRequestException(e.getMessage());
    }
  }

  private static boolean isUniqueViolation(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      String msg = c.getMessage();
      if (msg != null
          && (msg.contains("uq_group_role_mapping_org_group")
              || msg.contains("Unique index")
              || msg.contains("duplicate key"))) {
        return true;
      }
    }
    return false;
  }
}

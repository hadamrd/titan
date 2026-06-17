package io.adaptiq.titan.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.api.dto.GateDto;
import io.adaptiq.titan.auth.AuthContext;
import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.build.BuildService;
import io.adaptiq.titan.flow.GateService;
import io.adaptiq.titan.flow.GateService.Decision;
import io.adaptiq.titan.flow.GateService.GateOutcome;
import io.adaptiq.titan.flow.TitanFlowExecution;
import io.adaptiq.titan.flow.model.GateModel;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Jakarta REST resource: gate inspection + decision endpoints (closes #295 backend).
 *
 * <ul>
 *   <li>{@code GET /api/v1/builds/{buildId}/gates} — list pending gates (status=RUNNING)
 *   <li>{@code POST /api/v1/builds/{buildId}/gates/{nodeId}/approve} — record an approval
 *   <li>{@code POST /api/v1/builds/{buildId}/gates/{nodeId}/reject} — record a rejection
 * </ul>
 *
 * <p>The audit JSON the engine writes onto the gate node ({@code decidedBy}, {@code decidedAt})
 * stays internal — this resource only returns the {@link GateDto} shape. Reject reasons supplied in
 * the request body are validated for length but not yet plumbed into {@code GateService.decide} —
 * the engine's audit JSON evolves separately.
 */
@Path("/api/v1/builds/{buildId}/gates")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class GatesApi {

  private static final int MAX_REASON_LEN = 500;

  private final TitanStores stores;
  private final BuildService builds;
  private final AuthContext authContext;

  GatesApi(TitanStores stores, BuildService builds, AuthContext authContext) {
    this.stores = stores;
    this.builds = builds;
    this.authContext = authContext;
  }

  // ── GET /api/v1/builds/{buildId}/gates ────────────────────────────────────

  @GET
  @RolesAllowed({Roles.READ_JOB, Roles.TRIGGER_BUILD, Roles.ADMIN})
  public List<GateDto> listPending(@PathParam("buildId") String buildIdStr) {
    long buildId = JobsApi.parseLong(buildIdStr, "buildId");
    requireBuildExists(buildId);

    PipelineModel model;
    try {
      model = new TitanFlowExecution(stores, buildId).loadModel();
    } catch (IllegalStateException notBaked) {
      // Build exists but has no synthesized pipeline yet — no gates to list.
      return List.of();
    }

    List<GateDto> out = new ArrayList<>();
    for (GateModel gate : model.getGates()) {
      FlowNodeRow row = stores.flowNodes().findByBuildAndNode(buildId, gate.getId()).orElse(null);
      if (row == null || !"RUNNING".equals(row.status)) {
        continue;
      }
      out.add(
          new GateDto(
              gate.getId(),
              gate.getName(),
              List.copyOf(gate.getApprovers()),
              row.status,
              row.startedAt));
    }
    return out;
  }

  // ── POST /api/v1/builds/{buildId}/gates/{nodeId}/approve ──────────────────

  @POST
  @Path("/{nodeId}/approve")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.TRIGGER_BUILD, Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.DEVELOPER, kind = ScopeKind.ORG, scopeId = "global")
  public Response approve(
      @PathParam("buildId") String buildIdStr,
      @PathParam("nodeId") String nodeId,
      @Nullable DecisionBody body) {
    return decide(buildIdStr, nodeId, body, Decision.APPROVED);
  }

  // ── POST /api/v1/builds/{buildId}/gates/{nodeId}/reject ───────────────────

  @POST
  @Path("/{nodeId}/reject")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.TRIGGER_BUILD, Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.DEVELOPER, kind = ScopeKind.ORG, scopeId = "global")
  public Response reject(
      @PathParam("buildId") String buildIdStr,
      @PathParam("nodeId") String nodeId,
      @Nullable DecisionBody body) {
    return decide(buildIdStr, nodeId, body, Decision.REJECTED);
  }

  // ── internals ─────────────────────────────────────────────────────────────

  private Response decide(
      String buildIdStr, String nodeId, @Nullable DecisionBody body, Decision decision) {
    long buildId = JobsApi.parseLong(buildIdStr, "buildId");
    validateReason(body);
    requireBuildExists(buildId);

    String actor = authContext.currentUser();
    Set<String> identities = new HashSet<>(authContext.currentRoles());
    identities.add(actor);

    GateOutcome outcome;
    try {
      outcome = GateService.decide(stores, buildId, nodeId, actor, identities, decision);
    } catch (IllegalArgumentException missing) {
      throw new ApiNotFoundException(missing.getMessage());
    } catch (SecurityException forbidden) {
      throw new WebApplicationException(forbidden.getMessage(), Response.Status.FORBIDDEN);
    }

    DecisionResponse payload =
        new DecisionResponse(outcome.applied(), outcome.status(), outcome.message());
    int status = outcome.applied() ? 200 : 409;
    return Response.status(status).entity(payload).build();
  }

  private void requireBuildExists(long buildId) {
    builds
        .findById(buildId)
        .orElseThrow(() -> new ApiNotFoundException("build " + buildId + " not found"));
  }

  private static void validateReason(@Nullable DecisionBody body) {
    if (body == null || body.reason() == null) {
      return;
    }
    if (body.reason().length() > MAX_REASON_LEN) {
      throw new ApiBadRequestException(
          "field 'reason' must be at most " + MAX_REASON_LEN + " characters");
    }
  }

  /** Request body for approve / reject. {@code reason} is optional. */
  @JsonInclude(Include.NON_NULL)
  public record DecisionBody(@Nullable String reason) {}

  /** Response body for approve / reject. */
  public record DecisionResponse(boolean applied, String status, String message) {}
}

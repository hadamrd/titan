package io.adaptiq.titan.api;

import io.adaptiq.titan.auth.AuthContext;
import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.build.BuildService;
import io.adaptiq.titan.build.RetryStageOutcome;
import io.adaptiq.titan.build.RetryStagePreconditionException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Jakarta REST resource: {@code POST /api/v1/builds/{buildId}/stages/{stageId}/retry} — retry a
 * single failed stage in place (#744 backend half). Unlike {@link BuildReplayApi} which forks a
 * fresh build, this resets the named stage (+ DAG descendants) inside the same build row and
 * re-enqueues {@code ORCHESTRATE/ADVANCE} so the orchestrator re-dispatches the work.
 *
 * <p>Wire contract:
 *
 * <pre>
 *   request  : empty body
 *   response : 200 { "type":"applied", "buildId":&lt;long&gt;, "stageId":"&lt;str&gt;",
 *              "resetNodeIds":[...], "taskId":&lt;long&gt; }
 *   errors   : 404 — build does not exist
 *              404 — stage node does not exist for that build
 *              409 — stage is not in FAILED state
 *              403 — caller lacks {@link Roles#REPLAY_BUILD} / {@link Roles#ADMIN}
 * </pre>
 *
 * <p>The actor on the audit row is the OIDC subject from {@link AuthContext}, never a body-supplied
 * identity. Idempotent: a double-call moves the stage out of {@code FAILED} on the first call, so
 * the second call sees the new status and returns 409.
 */
@Path("/api/v1/builds")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class StageRetryApi {

  private final BuildService builds;
  private final AuthContext authContext;

  StageRetryApi(BuildService builds, AuthContext authContext) {
    this.builds = builds;
    this.authContext = authContext;
  }

  @POST
  @Path("{buildId}/stages/{stageId}/retry")
  @RolesAllowed({Roles.REPLAY_BUILD, Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.DEVELOPER, kind = ScopeKind.ORG, scopeId = "global")
  public Response retryStage(
      @PathParam("buildId") String buildIdStr, @PathParam("stageId") String stageId) {
    long buildId = JobsApi.parseLong(buildIdStr, "buildId");
    if (stageId == null || stageId.isBlank()) {
      throw new ApiBadRequestException("path parameter 'stageId' must be non-blank");
    }
    try {
      RetryStageOutcome outcome = builds.retryStage(buildId, stageId, authContext.currentUser());
      return Response.status(200).entity(outcome).build();
    } catch (RetryStagePreconditionException e) {
      return Response.status(409)
          .entity(new ErrorBody("conflict", e.getMessage()))
          .type(MediaType.APPLICATION_JSON_TYPE)
          .build();
    }
  }

  /** Minimal error body shape for the 409 path. */
  record ErrorBody(String error, String message) {}
}

package io.adaptiq.titan.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.api.dto.BuildDto;
import io.adaptiq.titan.auth.Action;
import io.adaptiq.titan.auth.AuthContext;
import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.Resource;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.build.Build;
import io.adaptiq.titan.build.BuildService;
import io.adaptiq.titan.build.ReplayOptions;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/**
 * Jakarta REST resource: {@code POST /api/v1/builds/{buildId}/replay} — replay-from-node (issue
 * #307, v3 design moment #5.4). Creates a fresh build of the parent's job, baked from the parent's
 * already-synthesised pipeline model, that re-runs from {@code nodeId} onward.
 *
 * <p><strong>Wire contract.</strong>
 *
 * <pre>
 *   request  : { "nodeId": "<node-id>", "withChanges": { "params": { k: v, ... } }? }
 *   response : 201 { "newBuildId": &lt;long&gt;, "queuedPosition": &lt;int|null&gt; }
 *   errors   : 404 — parent build does not exist
 *              400 — nodeId missing in body
 *              400 — nodeId not in parent's pipeline model
 *              400 — nodeId is not terminal in the parent's DAG
 *              403 — caller lacks {@link Roles#TRIGGER_BUILD} / {@link Roles#ADMIN}
 * </pre>
 *
 * <p>Validation is centralised in {@link BuildService#replay} — this resource is a thin
 * wire-translation layer.
 */
@Path("/api/v1/builds")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class BuildReplayApi {

  private final BuildService builds;
  private final Authz authz;
  private final AuthContext auth;

  BuildReplayApi(BuildService builds, Authz authz, AuthContext auth) {
    this.builds = builds;
    this.authz = authz;
    this.auth = auth;
  }

  /**
   * Resolve the owning job of {@code parentBuildId} and run the {@link Action#BUILD_RERUN} RBAC
   * check against it (closes #1121). Returns the resolved job id for downstream use.
   *
   * <p>Since #126 the check resolves roles through the canonical {@code rbac_user_role} chain
   * (scoped grant → legacy {@code user_roles} → realm floor) — a MAINTAINER+ grant made via {@code
   * AdminUsersApi} or an ADMIN realm role on the JWT satisfies it; nothing needs a pg-direct seed.
   *
   * <p><strong>Order:</strong> 404 BEFORE 403. If the build doesn't exist we throw {@link
   * ApiNotFoundException} first — the alternative (403-on-missing) would let an unprivileged caller
   * probe build-id existence by reading the error code. Documented in the test matrix on #1121.
   */
  private long checkRerunAuthority(long parentBuildId) {
    Build parent =
        builds
            .findById(parentBuildId)
            .orElseThrow(() -> new ApiNotFoundException("build " + parentBuildId + " not found"));
    authz.requires(auth, Action.BUILD_RERUN, new Resource.JobResource(parent.jobId()));
    return parent.jobId();
  }

  // ── POST /api/v1/builds/{buildId}/replay ──────────────────────────────────

  @POST
  @Path("{buildId}/replay")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.REPLAY_BUILD, Roles.TRIGGER_BUILD, Roles.ADMIN})
  // #1131 — `POST /api/v1/jobs/{id}/rerun` (per issue body) maps to this replay endpoint in the
  // codebase. DEVELOPER+ at ORG scope; the existing per-job Authz.requires(BUILD_RERUN) call below
  // remains as the fine-grained gate. ORG scope with literal "global" because the scope id we'd
  // want (the parent job) requires a DB lookup not available before the filter fires.
  @RequiresRole(role = Authz.TitanRole.DEVELOPER, kind = ScopeKind.ORG, scopeId = "global")
  public Response replay(@PathParam("buildId") String buildIdStr, @Nullable ReplayRequest req) {
    long parentBuildId = JobsApi.parseLong(buildIdStr, "buildId");
    // RBAC: 404 (build missing) → 403 (caller not MAINTAINER+) → 400 (bad body). Closes #1121.
    checkRerunAuthority(parentBuildId);
    if (req == null || req.nodeId() == null || req.nodeId().isBlank()) {
      throw new ApiBadRequestException("replay: request body must include a non-blank nodeId");
    }

    Map<String, String> paramOverrides =
        req.withChanges() != null ? req.withChanges().params() : null;
    Build replay = builds.replay(parentBuildId, req.nodeId(), new ReplayOptions(paramOverrides));

    // `queuedPosition` is reserved on the wire (the engine has no global queue depth today —
    // a node-label queue's depth is meaningful only against its agent pool). The field is
    // present so the UI's pagination/toast wiring can render it without a contract bump
    // when the engine gains it; @JsonInclude(NON_NULL) keeps it off the wire for now.
    return Response.status(201).entity(new ReplayResponse(replay.id(), null)).build();
  }

  // ── POST /api/v1/builds/{buildId}/replay-from-failed ─────────────────────
  //
  // Issue #664. Convenience shortcut on top of /replay: locate the first stage that ended FAILED
  // on the parent (in declared YAML order) and replay from it. The existing /replay endpoint is
  // intentionally NOT extended with a "fromStage" parameter — keeping these endpoints distinct on
  // the wire is the discriminated-union typing the engine team prefers (no string-sniffing on the
  // body to decide which mode the caller meant).
  //
  // Returns the new build's full DTO (vs /replay's compact { newBuildId, queuedPosition }) — the
  // UI navigates straight to /builds/{id} on success and the extra context (buildNumber, status,
  // jobId) is cheap to include in the 201 body.

  @POST
  @Path("{buildId}/replay-from-failed")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.REPLAY_BUILD, Roles.TRIGGER_BUILD, Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.DEVELOPER, kind = ScopeKind.ORG, scopeId = "global")
  public Response replayFromFailed(
      @PathParam("buildId") String buildIdStr, @Nullable ReplayFromFailedRequest req) {
    long parentBuildId = JobsApi.parseLong(buildIdStr, "buildId");
    // RBAC: 404 (build missing) → 403 (caller not MAINTAINER+). Closes #1121.
    checkRerunAuthority(parentBuildId);

    Map<String, String> paramOverrides =
        req != null && req.withChanges() != null ? req.withChanges().params() : null;
    Build replay = builds.replayFromFirstFailed(parentBuildId, new ReplayOptions(paramOverrides));

    return Response.status(201).entity(BuildDto.from(replay)).build();
  }

  // ── DTOs ──────────────────────────────────────────────────────────────────

  /**
   * Request body for {@code POST /api/v1/builds/{buildId}/replay}.
   *
   * @param nodeId the flow-node id in the parent's pipeline model to replay from
   * @param withChanges optional parameter-overrides; if absent, the parent's parameters are used
   *     verbatim
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ReplayRequest(@Nullable String nodeId, @Nullable ReplayChanges withChanges) {}

  /** {@code withChanges.params} — a flat string→string map merged on top of the parent's params. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ReplayChanges(@Nullable Map<String, String> params) {}

  /** {@code 201 Created} body. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ReplayResponse(long newBuildId, @Nullable Integer queuedPosition) {}

  /**
   * Request body for {@code POST /api/v1/builds/{buildId}/replay-from-failed}. The body is optional
   * — callers that don't need parameter overrides may send an empty body or none at all.
   *
   * @param withChanges optional parameter-overrides; if absent, the parent's parameters are used
   *     verbatim
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ReplayFromFailedRequest(@Nullable ReplayChanges withChanges) {}
}

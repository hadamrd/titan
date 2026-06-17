package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.BuildDto;
import io.adaptiq.titan.api.dto.FlowNodeDto;
import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.audit.AuditService;
import io.adaptiq.titan.audit.AuditTargetType;
import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.build.Build;
import io.adaptiq.titan.build.BuildService;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.flow.BuildAbortService;
import io.adaptiq.titan.store.TitanStores;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Jakarta REST resource: build detail endpoints (not scoped to a job).
 *
 * <ul>
 *   <li>{@code GET /api/v1/builds/{buildId}} — single build
 *   <li>{@code GET /api/v1/builds/{buildId}/nodes} — flow nodes (DAG order)
 *   <li>{@code POST /api/v1/builds/{buildId}/cancel} — abort a build (202)
 * </ul>
 *
 * <p>Split from the former {@code BuildsApi} (which had {@code @Path("/api/v1")}) to give each
 * resource a flat, unambiguous root path — Quarkus RESTEasy Reactive requires non-overlapping root
 * paths for reliable route resolution.
 *
 * <p>Constructor-injection only — {@link TitanStores} wired by Quarkus ARC via {@link
 * io.adaptiq.titan.boot.StoresProducer}. No {@code @Inject} field injection.
 */
@Path("/api/v1/builds")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class BuildDetailApi {

  /**
   * Build statuses for which a cancel is a 409 — see {@link
   * io.adaptiq.titan.flow.BuildAbortService}. Kept in lockstep with that service's {@code
   * BUILD_TERMINAL} set; an upstream change should also update this constant. Both sets
   * intentionally enumerate the same string literals rather than sharing a reference so the API
   * layer's contract is visible at the endpoint.
   */
  static final Set<String> BUILD_TERMINAL = Set.of("SUCCESS", "FAILED", "ABORTED", "UNSTABLE");

  private static final String PROBLEM_CONTENT_TYPE = "application/problem+json";

  private final TitanStores stores;
  private final BuildService builds;
  private final AuditService audit;
  private final Event<BuildStateChangedEvent> stateChangedEvent;

  BuildDetailApi(
      TitanStores stores,
      BuildService builds,
      AuditService audit,
      Event<BuildStateChangedEvent> stateChangedEvent) {
    this.stores = stores;
    this.builds = builds;
    this.audit = audit;
    this.stateChangedEvent = stateChangedEvent;
  }

  // ── GET /api/v1/builds/{buildId} ─────────────────────────────────────────────

  @GET
  @Path("{buildId}")
  @RolesAllowed({Roles.READ_JOB, Roles.ADMIN})
  public Response getBuild(
      @PathParam("buildId") String buildIdStr, @Context HttpHeaders requestHeaders) {
    long buildId = JobsApi.parseLong(buildIdStr, "buildId");
    Build build =
        builds
            .findById(buildId)
            .orElseThrow(() -> new ApiNotFoundException("build " + buildId + " not found"));
    // Issue #1099 — UI polls this endpoint while a build streams; emit a weak ETag so identical
    // payloads return 304 + empty body instead of re-serializing the full DTO.
    //
    // Issue #892 — for GitHub-App-triggered builds, resolve the Job's GitHub linkage so the DTO
    // carries provenance (org/repo + commit deep link) for the build-detail badge. The lookup is
    // gated on the trigger type so manual / scheduled / generic-webhook builds pay no extra query
    // and serialize byte-identically to pre-#892. A missing linkage (orphaned build) yields a plain
    // DTO — never a 500.
    BuildDto dto =
        BuildDto.isGithubAppTrigger(build.triggerType())
            ? BuildDto.from(build, stores.jobs().findGithubLinkage(build.jobId()).orElse(null))
            : BuildDto.from(build);
    return Etag.respond(requestHeaders, dto);
  }

  // ── GET /api/v1/builds/{buildId}/nodes ───────────────────────────────────────

  @GET
  @Path("{buildId}/nodes")
  @RolesAllowed({Roles.READ_JOB, Roles.ADMIN})
  public List<FlowNodeDto> listNodes(@PathParam("buildId") String buildIdStr) {
    long buildId = JobsApi.parseLong(buildIdStr, "buildId");
    requireBuildExists(buildId);
    return stores.flowNodes().listByBuild(buildId).stream().map(FlowNodeDto::from).toList();
  }

  // ── POST /api/v1/builds/{buildId}/cancel ─────────────────────────────────────

  @POST
  @Path("{buildId}/cancel")
  @RolesAllowed({Roles.ABORT_BUILD, Roles.TRIGGER_BUILD, Roles.ADMIN})
  // #1131 — `POST /api/v1/builds/{id}/cancel`. DEVELOPER+ for all callers in this slice (issue
  // body's spec). TODO #1114 — ownership-based check ("developer can cancel own builds but not
  // others") wants a follow-up ticket that resolves build → triggering user; current behaviour
  // gates everyone at DEVELOPER+. ORG scope with literal "global" — the scope a future
  // build→repo lookup would resolve to is the repo, not derivable from buildId at filter time.
  @RequiresRole(role = Authz.TitanRole.DEVELOPER, kind = ScopeKind.ORG, scopeId = "global")
  public Response cancelBuild(@PathParam("buildId") String buildIdStr) {
    long buildId = JobsApi.parseLong(buildIdStr, "buildId");
    Build build =
        builds
            .findById(buildId)
            .orElseThrow(() -> new ApiNotFoundException("build " + buildId + " not found"));

    // #962 — cancel on a build that is already terminal is not "success" and was not silent: it
    // returned 202 + {"aborted":false}, which a status-only client (curl -f, fetch().ok) reads
    // as success. Promote that case to a 409 + RFC 7807 problem+json so the contract is honest.
    // RUNNING/QUEUED still flow through BuildAbortService.abort below and yield 202.
    if (BUILD_TERMINAL.contains(build.status())) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("type", "about:blank");
      body.put("title", "Conflict");
      body.put("status", 409);
      body.put("detail", "Build is already terminal: " + build.status());
      body.put("build_id", buildId);
      body.put("current_status", build.status());
      return Response.status(409).type(PROBLEM_CONTENT_TYPE).entity(body).build();
    }

    // Pass the CDI event channel through so SCM status reporters (#1080) see the cancellation.
    BuildAbortService.AbortOutcome outcome =
        BuildAbortService.abort(stores, buildId, "api", stateChangedEvent);
    audit.record(
        AuditAction.BUILD_ABORT,
        AuditTargetType.BUILD,
        Long.toString(buildId),
        "{\"aborted\":" + outcome.aborted() + "}");
    return Response.status(202).entity(outcome).build();
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private void requireBuildExists(long buildId) {
    builds
        .findById(buildId)
        .orElseThrow(() -> new ApiNotFoundException("build " + buildId + " not found"));
  }
}

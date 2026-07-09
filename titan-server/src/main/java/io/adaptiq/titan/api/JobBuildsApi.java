package io.adaptiq.titan.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.api.dto.BuildDto;
import io.adaptiq.titan.api.dto.BuildsPage;
import io.adaptiq.titan.api.dto.TriggerBuildRequest;
import io.adaptiq.titan.api.dto.TriggerBuildResponse;
import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.audit.AuditService;
import io.adaptiq.titan.audit.AuditTargetType;
import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.build.Build;
import io.adaptiq.titan.build.BuildEnqueuer;
import io.adaptiq.titan.build.BuildService;
import io.adaptiq.titan.flow.model.ParameterModel;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.parser.PipelineParseException;
import io.adaptiq.titan.flow.parser.TitanYamlParser;
import io.adaptiq.titan.job.Job;
import io.adaptiq.titan.job.JobService;
import io.adaptiq.titan.ratelimit.RateLimitDecision;
import io.adaptiq.titan.ratelimit.TriggerRateLimiter;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Jakarta REST resource: build endpoints scoped to a job.
 *
 * <ul>
 *   <li>{@code GET /api/v1/jobs/{jobId}/builds} — paginated build list
 *   <li>{@code POST /api/v1/jobs/{jobId}/builds} — trigger a build (201)
 * </ul>
 *
 * <p>Split from the former {@code BuildsApi} (which had {@code @Path("/api/v1")}) to give each
 * resource a flat, unambiguous root path — Quarkus RESTEasy Reactive requires non-overlapping root
 * paths for reliable route resolution.
 *
 * <p>Constructor-injection only — {@link TitanStores} wired by Quarkus ARC via {@link
 * io.adaptiq.titan.boot.StoresProducer}. No {@code @Inject} field injection.
 */
@Path("/api/v1/jobs/{jobId}/builds")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class JobBuildsApi {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final TitanStores stores;
  private final BuildService builds;
  private final JobService jobsService;
  private final AuditService audit;
  private final TriggerRateLimiter rateLimiter;
  private final SecurityIdentity identity;

  JobBuildsApi(
      TitanStores stores,
      BuildService builds,
      JobService jobsService,
      AuditService audit,
      TriggerRateLimiter rateLimiter,
      SecurityIdentity identity) {
    this.stores = stores;
    this.builds = builds;
    this.jobsService = jobsService;
    this.audit = audit;
    this.rateLimiter = rateLimiter;
    this.identity = identity;
  }

  // ── GET /api/v1/jobs/{jobId}/builds ─────────────────────────────────────────

  @GET
  @RolesAllowed({Roles.READ_JOB, Roles.ADMIN})
  public BuildsPage listBuilds(
      @PathParam("jobId") String jobIdStr,
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("limit") @DefaultValue("50") int limit) {
    long jobId = JobsApi.parseLong(jobIdStr, "jobId");
    requireJobExists(jobId);

    int cappedLimit = Math.min(Math.max(limit, 0), 200);
    int safeOffset = Math.max(offset, 0);

    List<Build> all = builds.listByJobId(jobId);
    int total = all.size();
    List<BuildDto> page =
        all.stream().skip(safeOffset).limit(cappedLimit).map(BuildDto::from).toList();

    return new BuildsPage(page, total, safeOffset, cappedLimit);
  }

  // ── POST /api/v1/jobs/{jobId}/builds ────────────────────────────────────────

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.TRIGGER_BUILD, Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.DEVELOPER, kind = ScopeKind.REPO, scopeIdParam = "jobId")
  public Response triggerBuild(@PathParam("jobId") String jobIdStr, TriggerBuildRequest req) {
    long jobId = JobsApi.parseLong(jobIdStr, "jobId");
    requireJobExists(jobId);

    // Per-(user, job) token-bucket rate limit (closes #739). Key = OIDC subject from the
    // SecurityIdentity — NEVER from req.triggeredBy() (which is caller-controlled).
    String subject = rateLimitSubject();
    RateLimitDecision decision = rateLimiter.acquire(subject, jobId);
    if (decision instanceof RateLimitDecision.Denied denied) {
      audit.record(
          AuditAction.TRIGGER_RATE_LIMITED,
          AuditTargetType.JOB,
          Long.toString(jobId),
          "{\"retryAfterSeconds\":" + denied.retryAfterSeconds() + "}");
      return Response.status(429)
          .header("Retry-After", Long.toString(denied.retryAfterSeconds()))
          .entity(
              new io.adaptiq.titan.api.dto.ProblemJson(
                  "https://titan.adaptiq.io/problems/trigger-rate-limited",
                  "Too Many Requests",
                  429,
                  "manual trigger rate limit exceeded for job "
                      + jobId
                      + "; retry in "
                      + denied.retryAfterSeconds()
                      + "s",
                  "/api/v1/jobs/" + jobId + "/builds"))
          .type("application/problem+json")
          .build();
    }

    // #774 — typed parameters win over the raw parametersJson when both are present.
    // Validates against the job's declared parameters: name must exist + type must match.
    // Throws ApiBadRequestException → 400 problem+json.
    final String effectiveParametersJson = resolveParametersJson(jobId, req);

    TriggerBuildResponse resp =
        stores.withTransaction(
            conn -> {
              int buildNumber = stores.builds().nextBuildNumber(conn, jobId);

              BuildRow build = new BuildRow();
              build.jobId = jobId;
              build.buildNumber = buildNumber;
              build.status = "QUEUED";
              build.parametersJson = effectiveParametersJson;
              build.triggeredBy =
                  req != null && req.triggeredBy() != null ? req.triggeredBy() : "api";
              build.triggerType = "manual";
              build.queuedAt = Instant.now();

              long buildId = stores.builds().insert(conn, build);

              // The shared entry-task contract (SYNTHESIZE action, priority, lease) lives in
              // BuildEnqueuer — see its javadoc for the design/38 §3 rationale (closes #486,
              // #106).
              BuildEnqueuer.enqueueSynthesizeEntryTask(conn, buildId);

              return new TriggerBuildResponse(buildId, buildNumber, "QUEUED");
            });

    audit.record(
        AuditAction.BUILD_TRIGGER,
        AuditTargetType.BUILD,
        Long.toString(resp.buildId()),
        "{\"jobId\":" + jobId + ",\"buildNumber\":" + resp.buildNumber() + "}");
    return Response.status(201).entity(resp).build();
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  /**
   * Resolve the rate-limit bucket key from the authenticated identity. Prefers OIDC {@code sub}
   * (stable across username changes), falls back to {@code preferred_username}, then to the
   * principal name. Returns {@code "anonymous"} only when no identity is present (unit tests
   * without {@code @TestSecurity}).
   *
   * <p>NEVER read from the request body — {@code triggeredBy} is a free-form audit hint, not an
   * auth claim.
   */
  private String rateLimitSubject() {
    if (identity == null || identity.isAnonymous()) {
      return "anonymous";
    }
    Object sub = identity.getAttribute("sub");
    if (sub instanceof String s && !s.isBlank()) {
      return s;
    }
    Object preferred = identity.getAttribute("preferred_username");
    if (preferred instanceof String s && !s.isBlank()) {
      return s;
    }
    String name = identity.getPrincipal() != null ? identity.getPrincipal().getName() : null;
    return (name != null && !name.isBlank()) ? name : "anonymous";
  }

  private void requireJobExists(long jobId) {
    stores
        .jobs()
        .findById(jobId)
        .orElseThrow(() -> new ApiNotFoundException("job " + jobId + " not found"));
  }

  /**
   * Resolve the parameters JSON the BuildRow will carry, given an incoming trigger request.
   *
   * <ul>
   *   <li>{@code req == null} or both fields absent → {@code null} (defaults applied at bake).
   *   <li>{@code req.parameters()} present → validate keys against declared parameter names, coerce
   *       types per {@link ParameterModel#getType()}, serialize to JSON.
   *   <li>{@code req.parameters()} absent + {@code req.parametersJson()} present → returned
   *       verbatim (legacy callers — bake validates).
   * </ul>
   *
   * <p>Validation surfaces as {@link ApiBadRequestException} → HTTP 400 problem+json. We catch the
   * type errors at the HTTP boundary so the user sees the error immediately instead of waiting for
   * the bake to fail with the same message (#774 adversarial case).
   */
  private String resolveParametersJson(long jobId, TriggerBuildRequest req) {
    if (req == null) {
      return null;
    }
    Map<String, Object> typed = req.parameters();
    if (typed == null || typed.isEmpty()) {
      return req.parametersJson();
    }

    // Load the declared parameter set from the job's pipeline YAML so we can validate.
    Job job =
        jobsService
            .findById(jobId)
            .orElseThrow(() -> new ApiNotFoundException("job " + jobId + " not found"));
    String yaml = job.pipelineScript();
    Map<String, ParameterModel> declared = Map.of();
    if (yaml != null && !yaml.isBlank()) {
      try {
        PipelineModel model = TitanYamlParser.parseAndValidate(yaml);
        declared =
            model.getParameters().stream()
                .collect(java.util.stream.Collectors.toMap(ParameterModel::getName, p -> p));
      } catch (PipelineParseException e) {
        throw new ApiBadRequestException(
            "pipeline YAML does not parse — cannot validate parameters: " + e.getMessage());
      }
    }

    for (Map.Entry<String, Object> e : typed.entrySet()) {
      String name = e.getKey();
      Object value = e.getValue();
      ParameterModel decl = declared.get(name);
      if (decl == null) {
        throw new ApiBadRequestException(
            "unknown parameter '" + name + "' — not declared in pipeline");
      }
      validateValue(decl, value);
    }

    try {
      return JSON.writeValueAsString(typed);
    } catch (JsonProcessingException ex) {
      throw new ApiBadRequestException(
          "parameters cannot be serialized to JSON: " + ex.getMessage());
    }
  }

  /**
   * Type-check one parameter value against its declaration. Mirrors the coercion rules the bake's
   * {@link io.adaptiq.titan.flow.ParameterResolver} applies — duplicated at the HTTP boundary so a
   * bad value yields 400 at trigger time instead of QUEUED-then-FAILED at bake time.
   *
   * <p>Issue #61 (spec 27) — this boundary check MUST accept everything the bake accepts, or the
   * two validators drift and a value the bake would happily coerce (the params modal and the #778
   * trigger contract both submit every value as a string — {@code VERBOSE: "true"}) is rejected
   * with a spurious 400. The rules, verbatim from {@code ParameterResolver.coerce}:
   *
   * <ul>
   *   <li>{@code boolean} — {@link Boolean}, or a string whose trimmed lowercase form is exactly
   *       {@code "true"}/{@code "false"}.
   *   <li>{@code number} — {@link Number}, or a string that parses as a double.
   *   <li>{@code choice} — any scalar; its {@code String.valueOf} form must be a declared choice.
   *   <li>{@code string} — any scalar ({@code String.valueOf} coercion never fails at bake).
   *       Structured values (objects / arrays) are still rejected: the bake would stringify them
   *       into JSON-ish noise, which is never what the caller meant.
   * </ul>
   */
  private static void validateValue(ParameterModel decl, Object value) {
    if (value == null) {
      if (decl.isRequired()) {
        throw new ApiBadRequestException("parameter '" + decl.getName() + "' is required");
      }
      return;
    }
    if (value instanceof Map || value instanceof List) {
      throw new ApiBadRequestException(
          "parameter '"
              + decl.getName()
              + "' expects a scalar "
              + decl.getType()
              + " value, got a structured "
              + value.getClass().getSimpleName());
    }
    switch (decl.getType()) {
      case "boolean" -> {
        if (value instanceof Boolean) {
          return;
        }
        String b = String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
        if (!b.equals("true") && !b.equals("false")) {
          throw new ApiBadRequestException(
              "parameter '"
                  + decl.getName()
                  + "' expects boolean (true/false), got "
                  + value.getClass().getSimpleName()
                  + " '"
                  + value
                  + "'");
        }
      }
      case "number" -> {
        if (value instanceof Number) {
          return;
        }
        try {
          Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
          throw new ApiBadRequestException(
              "parameter '"
                  + decl.getName()
                  + "' expects number, got "
                  + value.getClass().getSimpleName()
                  + " '"
                  + value
                  + "'");
        }
      }
      case "choice" -> {
        String s = String.valueOf(value);
        if (!decl.getChoices().contains(s)) {
          throw new ApiBadRequestException(
              "parameter '"
                  + decl.getName()
                  + "' value '"
                  + s
                  + "' is not one of "
                  + decl.getChoices());
        }
      }
      case "string" -> {
        // Any scalar coerces via String.valueOf at bake — nothing to reject here.
      }
      default -> {
        // Unknown declared type — be permissive; the bake will surface the issue.
      }
    }
  }
}

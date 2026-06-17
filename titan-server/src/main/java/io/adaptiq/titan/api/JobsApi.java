package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.CreateJobRequest;
import io.adaptiq.titan.api.dto.JobDto;
import io.adaptiq.titan.api.dto.JobsPage;
import io.adaptiq.titan.api.dto.LastBuildSummaryDto;
import io.adaptiq.titan.api.dto.ProblemJson;
import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.audit.AuditService;
import io.adaptiq.titan.audit.AuditTargetType;
import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.build.BuildService;
import io.adaptiq.titan.flow.parser.PipelineParseException;
import io.adaptiq.titan.flow.parser.TitanYamlParser;
import io.adaptiq.titan.job.Job;
import io.adaptiq.titan.job.JobService;
import io.adaptiq.titan.job.JobUpdate;
import io.adaptiq.titan.job.JobWithLastBuild;
import io.adaptiq.titan.job.NewJobRequest;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * Jakarta REST resource: {@code /api/v1/jobs}.
 *
 * <ul>
 *   <li>{@code GET /api/v1/jobs} — paginated job list
 *   <li>{@code GET /api/v1/jobs/{jobId}} — single job or 404
 * </ul>
 *
 * <p>Constructor-injection only — {@link JobService} is wired by Quarkus ARC. The HTTP layer talks
 * to the domain service; storage details (JDBI, {@code titan.jobs}) are entirely behind the service
 * boundary.
 */
@Path("/api/v1/jobs")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RolesAllowed({Roles.READ_JOB, Roles.ADMIN})
public class JobsApi {

  private final JobService jobs;
  private final BuildService builds;
  private final SecurityIdentity identity;
  private final AuditService audit;

  JobsApi(JobService jobs, BuildService builds, SecurityIdentity identity, AuditService audit) {
    this.jobs = jobs;
    this.builds = builds;
    this.identity = identity;
    this.audit = audit;
  }

  // ── GET /api/v1/jobs ───────────────────────────────────────────────────────

  /** Hard cap on {@code search} length — guards against pathological inputs (mirrors BuildsApi). */
  private static final int MAX_SEARCH_LEN = 200;

  @GET
  public JobsPage listJobs(
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("limit") @DefaultValue("50") int limit,
      @QueryParam("search") String search,
      @QueryParam("after") String afterCursor) {
    int cappedLimit = Math.min(Math.max(limit, 0), 200);
    int safeOffset = Math.max(offset, 0);

    // #693 — optional ?search= filter (Cmd+K palette server-side search). Blank/null is a no-op;
    // existing callers see the same result set as before. Substring match (case-insensitive)
    // against full_name OR display_name; parameterised bind, no string concat.
    String normalisedSearch = nullIfBlank(search);
    if (normalisedSearch != null && normalisedSearch.length() > MAX_SEARCH_LEN) {
      throw new ApiBadRequestException(
          "search length " + normalisedSearch.length() + " exceeds cap " + MAX_SEARCH_LEN);
    }

    // #1098 — cursor decode at the HTTP boundary. Malformed cursor → 400 (Pagination.decode).
    io.adaptiq.titan.api.Pagination.Cursor cursor =
        io.adaptiq.titan.api.Pagination.decode(afterCursor);

    // #529 — fetch jobs alongside a snapshot of each job's most-recent build so the /jobs page
    // can render a per-row status pill at a glance. Jobs that have never run carry a null
    // lastBuild summary; the UI renders that as a muted "never run" pill (not a status colour).
    List<JobWithLastBuild> all = jobs.listAllWithLastBuild(normalisedSearch);
    int total = all.size();

    if (cursor != null) {
      // Cursor mode: sort newest-first by createdAt (tie-broken by id) and drop everything
      // not strictly older than the cursor row. In-memory because the underlying service loads
      // the full list anyway; a DAO-level cursor would only matter at >>10k jobs scale.
      final io.adaptiq.titan.api.Pagination.Cursor c = cursor;
      // +1 probe (see BuildsApi): take limit+1 so a full final page (exact multiple) does not
      // emit a dangling cursor that pages a client into an empty result.
      List<JobWithLastBuild> probed =
          all.stream()
              .sorted(JOBS_NEWEST_FIRST)
              .filter(
                  j ->
                      j.job().createdAt().isBefore(c.ts())
                          || (j.job().createdAt().equals(c.ts()) && j.job().id() < c.id()))
              .limit(cappedLimit + 1L)
              .toList();
      boolean hasMore = probed.size() > cappedLimit;
      List<JobWithLastBuild> windowed = hasMore ? probed.subList(0, cappedLimit) : probed;
      String next = hasMore ? jobsNextCursor(windowed) : null;
      return new JobsPage(
          windowed.stream().map(JobsApi::toDto).toList(), total, safeOffset, cappedLimit, next);
    }

    // Legacy first-page path — keep alphabetical (full_name) ordering existing callers rely
    // on for offset pagination. The `next` cursor is still emitted against the last visible
    // job's createdAt so a caller can switch to cursor paging mid-stream without losing place.
    // +1 probe so `next` is null on the genuine last page (no dangling empty page).
    List<JobWithLastBuild> probed = all.stream().skip(safeOffset).limit(cappedLimit + 1L).toList();
    boolean hasMore = probed.size() > cappedLimit;
    List<JobWithLastBuild> windowed = hasMore ? probed.subList(0, cappedLimit) : probed;
    String next = hasMore ? jobsNextCursor(windowed) : null;
    return new JobsPage(
        windowed.stream().map(JobsApi::toDto).toList(), total, safeOffset, cappedLimit, next);
  }

  /**
   * Newest-first by {@code createdAt}, tie-broken by id DESC. This matches the cursor sort used for
   * {@code ?after=} so a paged stream stays self-consistent.
   */
  private static final java.util.Comparator<JobWithLastBuild> JOBS_NEWEST_FIRST =
      java.util.Comparator.comparing((JobWithLastBuild j) -> j.job().createdAt())
          .reversed()
          .thenComparing((JobWithLastBuild j) -> j.job().id(), java.util.Comparator.reverseOrder());

  /** Compute the next-page cursor for jobs from the trimmed page — see {@code BuildsApi}. */
  @edu.umd.cs.findbugs.annotations.Nullable
  private static String jobsNextCursor(List<JobWithLastBuild> rows) {
    if (rows.isEmpty()) {
      return null;
    }
    JobWithLastBuild last = rows.get(rows.size() - 1);
    if (last.job().createdAt() == null) {
      return null;
    }
    return io.adaptiq.titan.api.Pagination.encode(
        new io.adaptiq.titan.api.Pagination.Cursor(last.job().createdAt(), last.job().id()));
  }

  /**
   * Legacy 3-arg overload — kept so existing tests compile unchanged. Equivalent to passing {@code
   * afterCursor = null}.
   */
  public JobsPage listJobs(int offset, int limit, String search) {
    return listJobs(offset, limit, search, null);
  }

  /** Trim and treat blank/null as absent — mirrors {@code BuildsApi#nullIfBlank}. */
  private static String nullIfBlank(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }

  /**
   * Map a {@link JobWithLastBuild} domain pair to the wire {@link JobDto} — including the nullable
   * {@code lastBuild} summary (issue #529).
   */
  private static JobDto toDto(JobWithLastBuild pair) {
    JobWithLastBuild.LastBuildSummary lb = pair.lastBuild();
    LastBuildSummaryDto lbDto =
        lb == null
            ? null
            : new LastBuildSummaryDto(
                lb.id(), lb.buildNumber(), lb.status(), lb.durationMs(), lb.finishedAt());
    return JobDto.from(pair.job(), lbDto);
  }

  // ── POST /api/v1/jobs ──────────────────────────────────────────────────────

  /**
   * Create a new job (closes #507).
   *
   * <p>The incoming pipeline YAML is round-tripped through {@link
   * TitanYamlParser#parseAndValidate(String)} — exactly the same code path the bake step uses — so
   * the server never persists a malformed pipeline. A {@link PipelineParseException} surfaces as
   * HTTP 400 problem+json with the parser's located message.
   *
   * <p>Idempotency: a duplicate {@code fullName} surfaces as HTTP 409 problem+json so seed scripts
   * and re-runs can skip the row instead of treating the failure as fatal. The {@code created_by}
   * column is populated from the authenticated principal (OIDC {@code preferred_username} / {@code
   * sub}) — never trusting a body field.
   *
   * <p><strong>Deprecated for GitHub-discovered pipelines (design 66):</strong> jobs derived from
   * {@code .titan/pipelines/*.yml} are now auto-created by {@link
   * io.adaptiq.titan.scm.github.GithubRepoScanner}; the UI's old "Enable" button is a no-op (the
   * row is already there). This endpoint stays for non-GitHub flows (seed scripts, direct API
   * callers) and remains the path forward for any future "pipeline created without an SCM source"
   * variant. A subsequent UI PR removes the Enable button.
   */
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.EDIT_PIPELINE, Roles.ADMIN})
  // #1131 — repo-create (maps to job-create in this codebase: jobs ARE the repo+pipeline pair). The
  // issue body's `POST /api/v1/repos` doesn't exist as a distinct route; this is the closest seam.
  // Org scope with literal "global" — the ScopedAuthz fall-through chain consults rbac_user_role
  // (ORG, global) → user_roles flat table, so existing ADMIN seeds keep working unchanged.
  @RequiresRole(role = Authz.TitanRole.MAINTAINER, kind = ScopeKind.ORG, scopeId = "global")
  @Deprecated
  public Response createJob(CreateJobRequest req) {
    if (req == null) {
      throw new ApiBadRequestException("request body is required");
    }
    if (req.fullName() == null || req.fullName().isBlank()) {
      throw new ApiBadRequestException("field 'fullName' is required");
    }
    if (req.pipelineScript() == null) {
      throw new ApiBadRequestException("field 'pipelineScript' is required");
    }
    try {
      TitanYamlParser.parseAndValidate(req.pipelineScript());
    } catch (PipelineParseException e) {
      throw new ApiBadRequestException(e.getMessage());
    }

    String createdBy = principalName();
    NewJobRequest create =
        new NewJobRequest(
            req.fullName().trim(),
            req.displayName(),
            req.folderPath(),
            req.pipelineScript(),
            req.configJson() != null ? req.configJson() : "{}",
            createdBy,
            req.enabled() == null ? true : req.enabled());

    Job created;
    try {
      created = jobs.create(create);
    } catch (RuntimeException e) {
      if (isUniqueViolation(e)) {
        // 409 — caller (notably seed-data.sh) treats this as idempotent skip.
        ProblemJson body =
            new ProblemJson(
                "about:blank",
                "Conflict",
                409,
                "job with fullName '" + req.fullName() + "' already exists",
                null);
        return Response.status(409).type("application/problem+json").entity(body).build();
      }
      throw e;
    }
    audit.record(
        AuditAction.JOB_CREATE,
        AuditTargetType.JOB,
        Long.toString(created.id()),
        "{\"fullName\":\"" + jsonEscape(created.fullName()) + "\"}");
    return Response.status(201).entity(JobDto.from(created)).build();
  }

  // ── GET /api/v1/jobs/{jobId} ───────────────────────────────────────────────

  @GET
  @Path("/{jobId}")
  public Response getJob(@PathParam("jobId") String jobIdStr, @Context HttpHeaders requestHeaders) {
    long jobId = parseLong(jobIdStr, "jobId");
    Job job =
        jobs.findById(jobId)
            .orElseThrow(() -> new ApiNotFoundException("job " + jobId + " not found"));
    // Issue #1099 — job-detail view polls this endpoint; emit a weak ETag so identical responses
    // collapse to 304 + empty body.
    return Etag.respond(requestHeaders, JobDto.from(job));
  }

  // ── PATCH /api/v1/jobs/{jobId} ─────────────────────────────────────────────

  /**
   * Partial update of a job — currently the {@code pipelineScript} only (ticket #439).
   *
   * <p>The incoming YAML is round-tripped through {@link TitanYamlParser#parseAndValidate(String)}
   * so the engine never stores a malformed pipeline. A {@link PipelineParseException} surfaces as
   * HTTP 400 with the parser's located message — the UI shows this inline next to the offending
   * trigger row.
   *
   * <p>The request body is intentionally a single-field record so future fields (e.g. {@code
   * enabled}) can be added back-compat without breaking older clients.
   */
  @PATCH
  @Path("/{jobId}")
  @Consumes(MediaType.APPLICATION_JSON)
  // The coarse @RolesAllowed gate only filters realm roles before the scoped @RequiresRole gate
  // below; it must admit every realm role the scoped gate then decides on (incl. READ_JOB) so the
  // genuine DENY is made — and audited — by ONE gate (#1221). READ_JOB resolves to a VIEWER floor
  // and is denied by @RequiresRole(MAINTAINER); EDIT_PIPELINE/ADMIN resolve to MAINTAINER+/ALLOW.
  @RolesAllowed({Roles.READ_JOB, Roles.EDIT_PIPELINE, Roles.ADMIN})
  // #1221 — single RBAC gate, honouring the realm-floor the rest of the codebase uses. ORG scope
  // with literal "global" (mirrors createJob): structurally resolvable by ScopedAuthz, unlike a
  // REPO scope bound to the numeric job id (parentOrgFromRepo("42") is null → un-resolvable). The
  // redundant in-body authz.requires(PIPELINE_EDIT) was deleted — it read only the flat user_roles
  // table with no realm-floor, 403'ing realm-role callers before validation/persist ran.
  @RequiresRole(role = Authz.TitanRole.MAINTAINER, kind = ScopeKind.ORG, scopeId = "global")
  public JobDto patchJob(@PathParam("jobId") String jobIdStr, JobPatchRequest patch) {
    long jobId = parseLong(jobIdStr, "jobId");
    Job existing =
        jobs.findById(jobId)
            .orElseThrow(() -> new ApiNotFoundException("job " + jobId + " not found"));
    if (patch == null || patch.pipelineScript() == null) {
      throw new ApiBadRequestException("pipelineScript is required");
    }
    String newScript = patch.pipelineScript();
    // Validate by running it through the parser — same code path the bake step uses.
    try {
      TitanYamlParser.parseAndValidate(newScript);
    } catch (PipelineParseException e) {
      throw new ApiBadRequestException(e.getMessage());
    }
    JobUpdate update =
        new JobUpdate(
            existing.displayName(),
            existing.folderPath(),
            newScript,
            existing.configJson(),
            existing.enabled());
    Job updated = jobs.update(jobId, update);
    audit.record(
        AuditAction.JOB_UPDATE,
        AuditTargetType.JOB,
        Long.toString(jobId),
        "{\"fullName\":\""
            + jsonEscape(updated.fullName())
            + "\",\"oldScriptLen\":"
            + (existing.pipelineScript() == null ? 0 : existing.pipelineScript().length())
            + ",\"newScriptLen\":"
            + newScript.length()
            + "}");
    return JobDto.from(updated);
  }

  // ── DELETE /api/v1/jobs/{jobId} ────────────────────────────────────────────

  /**
   * Hard-delete a job and all its dependent rows (closes #964).
   *
   * <p>Returns:
   *
   * <ul>
   *   <li><b>204 No Content</b> on success — the row, every build, every flow_node, every
   *       task_archive entry, every job_trigger, the discovery_state row, and the user_starred_jobs
   *       row are gone. All FK constraints declare {@code ON DELETE CASCADE} (see V1, V3, V6, V12,
   *       V13, V19, V20, V24, V26 migrations); the cascade happens atomically inside Postgres.
   *   <li><b>404 Not Found</b> if no job exists for {@code jobId}. Idempotent: a second DELETE on
   *       the same id returns 404, never 500.
   *   <li><b>403 Forbidden</b> if the caller is not ADMIN. Job deletion is destructive — the {@code
   *       EDIT_PIPELINE} role can author pipelines but cannot wipe historical builds.
   *   <li><b>409 Conflict</b> if the job has a build that is still {@code QUEUED} or {@code
   *       RUNNING}. The caller must cancel the build first — refusing here is the safer default
   *       (matches the spec's "reject" choice in #964) so a delete never silently wipes a live
   *       workload mid-run.
   * </ul>
   *
   * <p>Emits a {@code JOB_DELETE} audit row carrying the {@code fullName} of the deleted job — the
   * row itself is gone immediately after, so the audit trail is the only post-hoc reference.
   */
  @DELETE
  @Path("/{jobId}")
  @RolesAllowed({Roles.ADMIN})
  // #1131 — `DELETE /api/v1/repos/{id}` maps to DELETE /jobs/{id} here. Admin-only, REPO scope,
  // path-param "jobId". The @RolesAllowed coarse gate (Quarkus realm role) runs first; the
  // ScopedAuthz check runs second per the documented Priority + 10 ordering.
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.REPO, scopeIdParam = "jobId")
  public Response deleteJob(@PathParam("jobId") String jobIdStr) {
    long jobId = parseLong(jobIdStr, "jobId");
    Job existing =
        jobs.findById(jobId)
            .orElseThrow(() -> new ApiNotFoundException("job " + jobId + " not found"));
    int active = builds.countActiveByJobId(jobId);
    if (active > 0) {
      ProblemJson body =
          new ProblemJson(
              "about:blank",
              "Conflict",
              409,
              "cannot delete job "
                  + jobId
                  + ": "
                  + active
                  + " build(s) still QUEUED or RUNNING — cancel first",
              null);
      return Response.status(409).type("application/problem+json").entity(body).build();
    }
    jobs.delete(jobId);
    audit.record(
        AuditAction.JOB_DELETE,
        AuditTargetType.JOB,
        Long.toString(jobId),
        "{\"fullName\":\"" + jsonEscape(existing.fullName()) + "\"}");
    return Response.noContent().build();
  }

  /**
   * Minimal JSON string escape for audit details JSON. The details payload is hand-assembled (the
   * fields are short and well-known) — Jackson would be overkill here. Handles the four characters
   * that would break a string literal: backslash, double-quote, newline, carriage return.
   */
  static String jsonEscape(String s) {
    if (s == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '"' -> out.append("\\\"");
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

  /** PATCH body — single mutable field for now; record makes future expansion explicit. */
  public record JobPatchRequest(String pipelineScript) {}

  // ── helpers ───────────────────────────────────────────────────────────────

  /**
   * Parse a path segment as a {@code long}; throws {@link ApiBadRequestException} on failure so the
   * global {@link io.adaptiq.titan.api.exception.BadRequestExceptionMapper} produces HTTP 400.
   */
  static long parseLong(String raw, String paramName) {
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException e) {
      throw new ApiBadRequestException("path parameter '" + paramName + "' must be a number");
    }
  }

  /**
   * Resolve the OIDC principal name for the {@code created_by} audit column. Prefers {@code
   * preferred_username} (human-friendly), falls back to {@code sub}, then to the JAX-RS principal
   * name. Returns {@code "anonymous"} only when no identity is present at all — every authenticated
   * call (REST in prod, {@code @TestSecurity} in unit tests) populates at least one of these.
   */
  private String principalName() {
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

  /**
   * Detect a Postgres / H2 unique-constraint violation by walking the cause chain — JDBI wraps the
   * driver's SQLException so the exception class itself is not load-bearing. Mirrors the pattern in
   * {@link PersonalAccessTokenApi#isUniqueViolation}.
   */
  static boolean isUniqueViolation(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      String msg = c.getMessage();
      if (msg != null
          && (msg.contains("jobs_full_name")
              || msg.contains("full_name")
              || msg.contains("Unique index")
              || msg.contains("duplicate key"))) {
        return true;
      }
    }
    return false;
  }
}

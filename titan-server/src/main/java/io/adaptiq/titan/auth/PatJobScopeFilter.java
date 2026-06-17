package io.adaptiq.titan.auth;

import io.adaptiq.titan.api.dto.ProblemJson;
import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.audit.AuditService;
import io.adaptiq.titan.audit.AuditTargetType;
import io.adaptiq.titan.build.BuildService;
import io.adaptiq.titan.job.Job;
import io.adaptiq.titan.job.JobService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Post-matching JAX-RS filter that enforces the per-PAT {@code job_pattern} restriction (closes
 * #1082).
 *
 * <h2>When this runs</h2>
 *
 * Quarkus pipeline order: <strong>authentication → authorization ({@code @RolesAllowed}) → THIS
 * filter → resource method</strong>. We run at {@link Priorities#AUTHORIZATION} {@code + 1} so:
 *
 * <ul>
 *   <li>Anonymous / wrong-role requests are already 401 / 403'd by the standard
 *       {@code @RolesAllowed} machinery before we look at them.
 *   <li>An OIDC-authenticated request (no {@code titan.pat.jobPattern} attribute) sails through
 *       this filter with zero overhead — a single attribute lookup, no DB hit.
 *   <li>A PAT-authenticated request DOES carry the attribute (when the token was minted with a
 *       pattern); we resolve {@code jobId} / {@code buildId} from the matched path parameters and
 *       check the resulting {@code job.full_name} against the glob.
 * </ul>
 *
 * <h2>What it protects</h2>
 *
 * Every JAX-RS resource that takes a {@code @PathParam("jobId")} or {@code @PathParam("buildId")} —
 * i.e. every per-job and per-build endpoint in {@link io.adaptiq.titan.api}. We deliberately do NOT
 * try to introspect query strings or request bodies: a pattern-restricted PAT that calls a "list
 * everything" endpoint will still get the unscoped result set, but it cannot mutate or trigger
 * anything outside its pattern. This matches GitHub PAT semantics (a repo-scoped token still sees
 * the public list APIs).
 *
 * <h2>URL-encoding safety</h2>
 *
 * JAX-RS path-parameter binding decodes the path-parameter value before we read it from {@link
 * ContainerRequestContext#getUriInfo()}{@code .getPathParameters()}. A path like {@code
 * /api/v1/jobs/acme%2Fweb/builds} therefore surfaces as the parsed {@code long} id (or fails the
 * route-matcher entirely, returning 404), NOT as a sneaky string that bypasses our pattern. We
 * additionally use {@link PatJobPattern#matches(String, String)} which fails closed on any
 * candidate containing a literal {@code %} — defence-in-depth in case a future controller accepts a
 * string job-name in the path.
 *
 * <h2>Deny path</h2>
 *
 * On mismatch the filter:
 *
 * <ol>
 *   <li>Emits a {@link AuditAction#PAT_SCOPE_DENIED} row (best-effort — never blocks the response).
 *   <li>Aborts the request with a 403 {@link ProblemJson} carrying a stable {@code type} URI so CLI
 *       tooling can branch on it.
 * </ol>
 *
 * The deny path NEVER leaks the bearer or the hash. The audit row references the PAT id and the
 * resolved job's full name only.
 */
@Provider
@Priority(Priorities.AUTHORIZATION + 1)
public class PatJobScopeFilter implements ContainerRequestFilter {

  private static final Logger LOG = Logger.getLogger(PatJobScopeFilter.class);

  /** Stable problem-type URI for the structured 403. */
  static final String PROBLEM_TYPE = "https://titan.adaptiq.io/problems/pat-scope-denied";

  private final SecurityIdentity identity;
  private final JobService jobs;
  private final BuildService builds;
  private final AuditService audit;

  @Inject
  PatJobScopeFilter(
      SecurityIdentity identity, JobService jobs, BuildService builds, AuditService audit) {
    this.identity = identity;
    this.jobs = jobs;
    this.builds = builds;
    this.audit = audit;
  }

  @Override
  public void filter(ContainerRequestContext ctx) {
    // Fast path: not a PAT-with-pattern request. OIDC sessions, legacy PATs, and scope-only PATs
    // all skip with a single attribute lookup. No DB hit.
    if (identity == null || identity.isAnonymous()) {
      return;
    }
    Object patternAttr = identity.getAttribute(PatAuthenticationMechanism.PAT_JOB_PATTERN_ATTR);
    if (!(patternAttr instanceof String pattern) || pattern.isBlank()) {
      return;
    }
    Object patIdAttr = identity.getAttribute("titan.pat.id");

    MultivaluedMap<String, String> params = ctx.getUriInfo().getPathParameters();
    // Try jobId first (cheaper — one DAO hit), then buildId (two hits: build → job).
    Long jobId = parseLong(params.getFirst("jobId"));
    Long buildId = parseLong(params.getFirst("buildId"));
    if (jobId == null && buildId == null) {
      // The matched route isn't job-scoped (e.g. GET /api/v1/me, /api/v1/info). No path-restriction
      // can apply because there's nothing to compare against — let it through.
      return;
    }

    Optional<Job> resolved = Optional.empty();
    try {
      if (jobId != null) {
        resolved = jobs.findById(jobId);
      } else {
        resolved = builds.findById(buildId).flatMap(b -> jobs.findById(b.jobId()));
      }
    } catch (RuntimeException e) {
      // A DAO outage during the scope-check must not silently bypass the restriction. Fail closed.
      LOG.warnf(e, "pat-scope: job/build lookup failed (jobId=%s, buildId=%s)", jobId, buildId);
      deny(ctx, pattern, jobId, buildId, null, patIdAttr);
      return;
    }
    if (resolved.isEmpty()) {
      // No such job (or no such build). The resource method will return 404 — let it. We can't
      // audit a scope-deny for a non-existent target, and the user gets a true error code.
      return;
    }
    String fullName = resolved.get().fullName();
    if (PatJobPattern.matches(pattern, fullName)) {
      return;
    }
    deny(ctx, pattern, resolved.get().id(), buildId, fullName, patIdAttr);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static Long parseLong(String s) {
    if (s == null || s.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      // Non-numeric path param → not our concern; the route either matched something else (e.g.
      // a String @PathParam) or the resource method will 400 on the parse itself. We do not
      // attempt to enforce the pattern on string-shaped ids; that surface is empty today.
      return null;
    }
  }

  private void deny(
      ContainerRequestContext ctx,
      String pattern,
      Long jobId,
      Long buildId,
      String jobFullName,
      Object patIdAttr) {
    String path = ctx.getUriInfo().getPath();
    String method = ctx.getMethod();

    StringBuilder details = new StringBuilder(128);
    details.append("{\"pattern\":\"").append(jsonEscape(pattern)).append('"');
    if (jobId != null) {
      details.append(",\"jobId\":").append(jobId);
    }
    if (buildId != null) {
      details.append(",\"buildId\":").append(buildId);
    }
    if (jobFullName != null) {
      details.append(",\"jobFullName\":\"").append(jsonEscape(jobFullName)).append('"');
    }
    details.append(",\"path\":\"").append(jsonEscape(path)).append('"');
    details.append(",\"method\":\"").append(jsonEscape(method)).append('"');
    details.append('}');

    String patId = patIdAttr == null ? null : patIdAttr.toString();
    try {
      audit.record(AuditAction.PAT_SCOPE_DENIED, AuditTargetType.PAT, patId, details.toString());
    } catch (RuntimeException e) {
      // AuditService swallows its own failures; this catch is for paranoia. Never block deny on
      // audit emission.
      LOG.warnf(e, "pat-scope: audit emission failed (patId=%s)", patId);
    }

    String detail =
        jobFullName == null
            ? "this personal access token is scoped to a job pattern that does not match the"
                + " requested resource"
            : "this personal access token is scoped to '"
                + pattern
                + "' and cannot operate on job '"
                + jobFullName
                + "'";
    ProblemJson body = new ProblemJson(PROBLEM_TYPE, "Forbidden", 403, detail, "/" + path);
    ctx.abortWith(
        Response.status(403)
            .type(MediaType.valueOf("application/problem+json"))
            .entity(body)
            .build());
  }

  /** Escape a string for embedding inside a JSON string literal. Keep in sync with JobsApi. */
  private static String jsonEscape(String s) {
    if (s == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
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

  // Visible for test — unused stub keeper to silence "unused" check on @Inject ctor.
  @SuppressWarnings("unused")
  List<String> internalKeepalive() {
    return List.of();
  }
}

package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.JobDto;
import io.adaptiq.titan.api.dto.ProblemJson;
import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.store.StarredJobsDao;
import io.adaptiq.titan.store.TitanStores;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * Jakarta REST resource: {@code /api/v1/me/starred-jobs} — per-user pinned jobs (closes #703).
 *
 * <ul>
 *   <li>{@code GET /api/v1/me/starred-jobs} → {@code List<JobDto>}, newest-pin first; empty array
 *       when the user has no stars (never {@code null}).
 *   <li>{@code PUT /api/v1/me/starred-jobs/{jobId}} → 204 on success, 409 if the user is already at
 *       the {@link #MAX_STARS_PER_USER}-star cap, 404 if {@code jobId} is not a known job. Calling
 *       PUT on an already-starred job is a no-op 204 (idempotent — caller need not check first).
 *   <li>{@code DELETE /api/v1/me/starred-jobs/{jobId}} → 204; deleting a non-starred row is also
 *       204 (idempotent — caller need not check first).
 * </ul>
 *
 * <p><strong>Security model.</strong> Same shape as {@link PersonalAccessTokenApi}:
 * {@code @RolesAllowed("**")} accepts any authenticated user, and the OIDC subject claim (never a
 * request body field) keys the per-user scope. ADMIN cannot see another user's stars — there is no
 * admin-override path. Unauthenticated clients hit 401 at the OIDC filter before reaching this
 * resource.
 *
 * <p><strong>10-star cap.</strong> Enforced at the API layer rather than the DB (a CHECK constraint
 * would couple the cap to schema versioning and a trigger-counted INSERT would lose the ability to
 * return a clean 409 with the current count in the problem body). The cap is a soft UX guard —
 * power users can always unstar before re-starring; doubling the cap later is a one-line change
 * here.
 *
 * <p><strong>Migrating from localStorage.</strong> v0 of #636 stored favorites in localStorage
 * under {@code titan.ui.favoriteJobs}. The UI now reads/writes through these endpoints.
 * localStorage data is intentionally not migrated server-side — favorites were per-tab anyway, and
 * a forced one-way migration would surprise users on shared machines.
 */
@Path("/api/v1/me/starred-jobs")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class StarredJobsApi {

  /**
   * Hard cap on how many jobs a single user may pin. 10 is the GitHub-stars-rail / Argo / Buildkite
   * default — small enough to fit in a sidebar without scroll, big enough to cover a realistic
   * SRE's daily rotation. Doubling later is a one-line change.
   */
  public static final int MAX_STARS_PER_USER = 10;

  private final TitanStores stores;
  private final SecurityIdentity identity;

  StarredJobsApi(TitanStores stores, SecurityIdentity identity) {
    this.stores = stores;
    this.identity = identity;
  }

  // ── GET /api/v1/me/starred-jobs ────────────────────────────────────────────

  @GET
  @RolesAllowed({Roles.READ_JOB, Roles.TRIGGER_BUILD, Roles.ADMIN})
  public List<JobDto> list() {
    String subject = requireSubject();
    return stores.starredJobs().listForUser(subject).stream().map(JobDto::from).toList();
  }

  // ── PUT /api/v1/me/starred-jobs/{jobId} ────────────────────────────────────

  @PUT
  @Path("/{jobId}")
  @RolesAllowed({Roles.READ_JOB, Roles.TRIGGER_BUILD, Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.VIEWER, kind = ScopeKind.ORG, scopeId = "global")
  public Response star(@PathParam("jobId") String jobIdStr) {
    long jobId = JobsApi.parseLong(jobIdStr, "jobId");
    String subject = requireSubject();
    StarredJobsDao dao = stores.starredJobs();

    // PUT-idempotent: a re-star is a no-op even when the user is at the cap. Check existence
    // FIRST so a user at 10/10 stars who re-stars one of their own pinned jobs gets 204, not 409.
    if (dao.existsForUser(subject, jobId) > 0) {
      return Response.noContent().build();
    }

    int currentCount = dao.countForUser(subject);
    if (currentCount >= MAX_STARS_PER_USER) {
      ProblemJson body =
          new ProblemJson(
              "about:blank",
              "Conflict",
              409,
              "starred-jobs cap reached: user already has "
                  + currentCount
                  + " stars (max "
                  + MAX_STARS_PER_USER
                  + "); unstar one before pinning a new job",
              null);
      return Response.status(409).type("application/problem+json").entity(body).build();
    }

    // Confirm the job exists. The FK on user_starred_jobs.job_id would reject a non-existent id
    // with a 500-ish FK violation; we'd rather return a clean 404.
    if (stores.jobs().findById(jobId).isEmpty()) {
      throw new ApiNotFoundException("job " + jobId + " not found");
    }

    try {
      dao.insert(subject, jobId);
    } catch (RuntimeException e) {
      // Race: another tab inserted the same (subject, jobId) between our existence check and the
      // insert. PK collision → no-op success, mirroring the idempotent contract.
      if (isUniqueViolation(e)) {
        return Response.noContent().build();
      }
      throw e;
    }
    return Response.noContent().build();
  }

  // ── DELETE /api/v1/me/starred-jobs/{jobId} ─────────────────────────────────

  @DELETE
  @Path("/{jobId}")
  @RolesAllowed({Roles.READ_JOB, Roles.TRIGGER_BUILD, Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.VIEWER, kind = ScopeKind.ORG, scopeId = "global")
  public Response unstar(@PathParam("jobId") String jobIdStr) {
    long jobId = JobsApi.parseLong(jobIdStr, "jobId");
    String subject = requireSubject();
    // Return 204 whether or not a row was deleted — DELETE is idempotent and the caller cannot
    // distinguish "already unstarred" from "successfully unstarred" anyway.
    stores.starredJobs().delete(subject, jobId);
    return Response.noContent().build();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /**
   * Pull the OIDC subject from {@code SecurityIdentity}. Mirrors {@link
   * PersonalAccessTokenApi#requireSubject()} — falls back to the principal name so
   * {@code @TestSecurity} unit tests work without a populated {@code sub} attribute.
   */
  private String requireSubject() {
    if (identity.isAnonymous()) {
      throw new ApiBadRequestException("authentication required");
    }
    Object sub = identity.getAttribute("sub");
    if (sub instanceof String s && !s.isBlank()) {
      return s;
    }
    String name = identity.getPrincipal().getName();
    if (name == null || name.isBlank()) {
      throw new ApiBadRequestException("authentication required");
    }
    return name;
  }

  /** Detect a PG/H2 PK unique-violation; mirrors {@link JobsApi#isUniqueViolation}. */
  private static boolean isUniqueViolation(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      String msg = c.getMessage();
      if (msg != null
          && (msg.contains("user_starred_jobs_pk")
              || msg.contains("Unique index")
              || msg.contains("duplicate key"))) {
        return true;
      }
    }
    return false;
  }
}

package io.adaptiq.titan.api;

import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.discovery.DiscoveryService;
import io.adaptiq.titan.job.JobNotFoundException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Jakarta REST resource: {@code POST /api/v1/jobs/{jobId}/discover} — one-shot SCM poll for a
 * single job. Mirrors what the {@code @Scheduled} {@link
 * io.adaptiq.titan.discovery.DiscoveryScheduler} does for the whole fleet on its cadence; lets an
 * operator force a poll without waiting (closes #275).
 *
 * <p>Constructor injection only. Restricted to {@link Roles#ADMIN}.
 */
@Path("/api/v1/jobs/{jobId}/discover")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class JobDiscoveryApi {

  private final DiscoveryService discovery;

  JobDiscoveryApi(DiscoveryService discovery) {
    this.discovery = discovery;
  }

  @POST
  @RolesAllowed(Roles.ADMIN)
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.REPO, scopeIdParam = "jobId")
  public Response discover(@PathParam("jobId") String jobIdStr) {
    long jobId = JobsApi.parseLong(jobIdStr, "jobId");

    Optional<Long> triggered;
    try {
      triggered = discovery.pollOne(jobId);
    } catch (JobNotFoundException e) {
      throw new ApiNotFoundException("job " + jobId + " not found");
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("jobId", jobId);
    body.put("triggered", triggered.isPresent());
    triggered.ifPresent(id -> body.put("buildId", id));
    return Response.accepted().entity(body).build();
  }
}

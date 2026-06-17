package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.PipelineParameterDto;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.parser.PipelineParseException;
import io.adaptiq.titan.flow.parser.TitanYamlParser;
import io.adaptiq.titan.job.Job;
import io.adaptiq.titan.job.JobService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Jakarta REST resource: {@code GET /api/v1/jobs/{jobId}/parameters} — return the list of declared
 * pipeline parameters for the given job (closes #774).
 *
 * <p>Carved out of {@link JobsApi} so the heavy {@link io.adaptiq.titan.api.dto.JobDto} response
 * isn't bloated by an extra list the bulk-listing callers don't need. The UI trigger-with-params
 * modal calls this lazily on Run-pipeline click.
 *
 * <p>The response shape is the typed {@link PipelineParameterDto} (discriminated by {@code type}) —
 * not a free-form map. A pipeline with no declared parameters returns {@code []} (HTTP 200), the UI
 * uses an empty list as the "no modal, fire directly" signal.
 *
 * <p>A pipeline whose YAML fails to parse returns {@code []} as well (logged at FINE) — the trigger
 * button is best-effort: rather than block on a parser regression in an unrelated stage we fall
 * through to the no-modal path; the bake step will surface the parse error to the build log.
 */
@Path("/api/v1/jobs/{jobId}/parameters")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RolesAllowed({Roles.READ_JOB, Roles.ADMIN})
public class JobParametersApi {

  private static final Logger LOGGER = Logger.getLogger(JobParametersApi.class.getName());

  private final JobService jobs;

  JobParametersApi(JobService jobs) {
    this.jobs = jobs;
  }

  @GET
  public List<PipelineParameterDto> listParameters(@PathParam("jobId") String jobIdStr) {
    long jobId = JobsApi.parseLong(jobIdStr, "jobId");
    Job job =
        jobs.findById(jobId)
            .orElseThrow(() -> new ApiNotFoundException("job " + jobId + " not found"));

    String yaml = job.pipelineScript();
    if (yaml == null || yaml.isBlank()) {
      return List.of();
    }
    try {
      PipelineModel model = TitanYamlParser.parseAndValidate(yaml);
      return model.getParameters().stream().map(PipelineParameterDto::from).toList();
    } catch (PipelineParseException e) {
      LOGGER.log(
          Level.FINE,
          "[titan-api] job " + jobId + ": parameters parse failed — returning empty list",
          e);
      return List.of();
    }
  }
}

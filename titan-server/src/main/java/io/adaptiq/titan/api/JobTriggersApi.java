package io.adaptiq.titan.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.api.dto.JobTriggerDto;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.job.Job;
import io.adaptiq.titan.job.JobService;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobTriggerRow;
import io.adaptiq.titan.trigger.CronTrigger;
import io.adaptiq.titan.trigger.GithubTrigger;
import io.adaptiq.titan.trigger.Trigger;
import io.adaptiq.titan.trigger.TriggerCodec;
import io.adaptiq.titan.trigger.cron.CronSchedule;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Jakarta REST resource: {@code GET /api/v1/jobs/{id}/triggers} — closes #725.
 *
 * <p>Joins the parsed trigger definitions from {@code titan.jobs.config_json} (the source of truth
 * for "what is configured?") with the runtime state in {@code titan.job_triggers} (the source of
 * truth for "when did it last fire / fail?"). The UI's {@code CronTriggersPanel} previously had to
 * parse triggers out of {@code pipelineScript} client-side and had no access to runtime state at
 * all — this endpoint folds both into one typed response.
 *
 * <p>Response shape is a discriminated-union list: every entry carries a closed {@code type}
 * discriminator ({@code cron} / {@code github} / {@code manual}). The server filters out any
 * forward-compat trigger types the wire enum does not yet know about — the server is the source of
 * truth for the union, the UI never has to sniff strings (feedback_principled_typed_design).
 *
 * <p>For cron triggers the {@code nextFireAt} field carries the next occurrence in UTC computed by
 * the vendored cron grammar ({@link CronSchedule#nextRun()}). Github / manual triggers leave it
 * absent — they fire on external events, not on a wall clock.
 */
@Path("/api/v1/jobs/{jobId}/triggers")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RolesAllowed({Roles.READ_JOB, Roles.ADMIN})
public class JobTriggersApi {

  private static final Logger LOGGER = Logger.getLogger(JobTriggersApi.class.getName());

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final JobService jobs;
  private final TitanStores stores;

  JobTriggersApi(JobService jobs, TitanStores stores) {
    this.jobs = jobs;
    this.stores = stores;
  }

  @GET
  @NonNull
  public List<JobTriggerDto> listTriggers(@PathParam("jobId") String jobIdStr) {
    long jobId = JobsApi.parseLong(jobIdStr, "jobId");
    Job job =
        jobs.findById(jobId)
            .orElseThrow(() -> new ApiNotFoundException("job " + jobId + " not found"));

    List<Trigger> triggers = parseTriggers(job);
    List<JobTriggerDto> out = new ArrayList<>(triggers.size());
    for (Trigger t : triggers) {
      JobTriggerDto dto = toDto(job, t);
      if (dto != null) {
        out.add(dto);
      }
    }
    return out;
  }

  /**
   * Project a single {@link Trigger} onto its wire DTO, joining {@code titan.job_triggers} runtime
   * state. Returns {@code null} for a trigger whose type is not in the closed wire enum — a
   * forward-compat read of a trigger written by a newer build is dropped rather than leaked as an
   * opaque shape (the engine still polls it, the wire never sees it).
   */
  private JobTriggerDto toDto(@NonNull Job job, @NonNull Trigger t) {
    JobTriggerDto.Type type = mapType(t.getType());
    if (type == null) {
      LOGGER.log(
          Level.FINE,
          "[titan] trigger type {0} not in wire enum — filtered from response",
          t.getType());
      return null;
    }
    Optional<JobTriggerRow> stateRow = stores.jobTriggers().find(job.id(), t.getId());
    Instant lastFiredAt = stateRow.map(r -> r.lastFiredAt).orElse(null);
    String lastError = stateRow.map(r -> r.lastError).orElse(null);

    String expression = null;
    Instant nextFireAt = null;
    if (t instanceof CronTrigger cron) {
      expression = cron.getSpec();
      nextFireAt = computeNextFireAt(cron, job);
    }

    return new JobTriggerDto(
        t.getId(), type.name(), expression, lastFiredAt, lastError, nextFireAt);
  }

  /**
   * Compute the next cron occurrence — null on any error, including an invalid spec. The list
   * endpoint is read-only; a broken spec should not 500 the whole response. {@link
   * CronSchedule#nextRun()} returns {@link Optional#empty()} when no occurrence fits in the horizon
   * (e.g. {@code 0 0 30 2 *}).
   */
  private Instant computeNextFireAt(@NonNull CronTrigger cron, @NonNull Job job) {
    String spec = cron.getSpec();
    if (spec == null || spec.isBlank()) {
      return null;
    }
    try {
      return CronSchedule.of(spec, job.fullName()).nextRun().orElse(null);
    } catch (IllegalArgumentException e) {
      LOGGER.log(
          Level.FINE,
          "[titan] invalid cron spec on job {0} trigger {1}: {2}",
          new Object[] {job.fullName(), cron.getId(), e.getMessage()});
      return null;
    }
  }

  /** Map an engine-side discriminator to the closed wire enum; null if not surfaceable. */
  private static JobTriggerDto.Type mapType(@NonNull String engineType) {
    return switch (engineType) {
      case CronTrigger.TYPE -> JobTriggerDto.Type.cron;
      case GithubTrigger.TYPE -> JobTriggerDto.Type.github;
      default -> null;
    };
  }

  /**
   * Parse the {@code triggers} JSON array out of {@code job.config_json}. Mirrors {@code
   * DbTriggerSubsystem#parseTriggers} — kept inline rather than extracted because the subsystem is
   * a CDI bean with engine wiring; the API just needs a pure parse for the read path.
   */
  @NonNull
  private static List<Trigger> parseTriggers(@NonNull Job job) {
    String configJson = job.configJson();
    if (configJson == null || configJson.isBlank()) {
      return List.of();
    }
    try {
      JsonNode root = MAPPER.readTree(configJson);
      JsonNode triggers = root.path("triggers");
      return TriggerCodec.read(triggers);
    } catch (IOException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] could not parse config_json triggers for job " + job.fullName(),
          e);
      return List.of();
    }
  }
}

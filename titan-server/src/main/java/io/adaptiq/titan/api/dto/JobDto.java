package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.job.Job;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.trigger.GithubTrigger;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * API response DTO for a single job. Never exposes {@link JobRow} or the domain {@link Job} record
 * directly — keeps the wire format decoupled from both the storage POJO and the domain type.
 *
 * <p>{@code lastBuild} (issue #529) is a nullable summary of this job's most-recent build, used by
 * the {@code /jobs} fleet-health list. {@code null} on a job that has never run; populated with a
 * strict subset of {@link BuildDto} fields when at least one build row exists.
 *
 * <p>{@code repoUrl} (issue #606) is the upstream SCM URL — non-null only when the job's {@code
 * config_json} carries both a {@code scm.url} block AND at least one {@link GithubTrigger}. Legacy
 * jobs without a github trigger serialize {@code repoUrl} as absent (per {@link
 * JsonInclude.Include#NON_NULL}). Unblocks the frontend "Open repo" action (#605).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobDto(
    long id,
    String fullName,
    String displayName,
    String folderPath,
    String pipelineScript,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt,
    LastBuildSummaryDto lastBuild,
    String repoUrl) {

  private static final Logger LOGGER = Logger.getLogger(JobDto.class.getName());
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Map from a storage row. {@code displayName} falls back to {@code fullName} when null. */
  public static JobDto from(JobRow row) {
    return new JobDto(
        row.id,
        row.fullName,
        row.displayName != null ? row.displayName : row.fullName,
        row.folderPath,
        row.pipelineScript != null ? row.pipelineScript : "",
        row.enabled,
        row.createdAt,
        row.updatedAt,
        null,
        extractRepoUrl(row.configJson, row.fullName));
  }

  /** Map from a domain record. {@code displayName} falls back to {@code fullName} when null. */
  public static JobDto from(Job job) {
    return new JobDto(
        job.id(),
        job.fullName(),
        job.displayName() != null ? job.displayName() : job.fullName(),
        job.folderPath(),
        job.pipelineScript() != null ? job.pipelineScript() : "",
        job.enabled(),
        job.createdAt(),
        job.updatedAt(),
        null,
        extractRepoUrl(job.configJson(), job.fullName()));
  }

  /**
   * Map from a domain record + last-build summary (issue #529). Pass {@code null} for {@code
   * lastBuild} when the job has never run.
   */
  public static JobDto from(Job job, LastBuildSummaryDto lastBuild) {
    return new JobDto(
        job.id(),
        job.fullName(),
        job.displayName() != null ? job.displayName() : job.fullName(),
        job.folderPath(),
        job.pipelineScript() != null ? job.pipelineScript() : "",
        job.enabled(),
        job.createdAt(),
        job.updatedAt(),
        lastBuild,
        extractRepoUrl(job.configJson(), job.fullName()));
  }

  /**
   * Extract the upstream SCM URL when both (a) a {@link GithubTrigger} is present in {@code
   * triggers[]} AND (b) an {@code scm.url} block exists. Returns {@code null} otherwise — legacy
   * jobs, cron-only jobs, or jobs missing an scm block all yield null. Pure; swallows parse errors
   * at FINE so a malformed config never breaks list responses.
   *
   * <p>Wire shape consumed (mirrors what {@code DiscoveryServiceImpl.scmConfig} writes and what
   * {@code TriggerCodec} round-trips):
   *
   * <pre>{@code
   * {
   *   "scm":      { "url": "https://github.com/acme/billing", "branch": "main" },
   *   "triggers": [ { "type": "github", ... } ]
   * }
   * }</pre>
   */
  static String extractRepoUrl(String configJson, String jobFullName) {
    if (configJson == null || configJson.isBlank()) {
      return null;
    }
    try {
      JsonNode root = MAPPER.readTree(configJson);
      if (!hasGithubTrigger(root.path("triggers"))) {
        return null;
      }
      JsonNode scm = root.path("scm");
      if (!scm.isObject()) {
        return null;
      }
      String url = scm.path("url").asText("");
      if (url.isBlank()) {
        return null;
      }
      return url.trim();
    } catch (Exception e) {
      LOGGER.log(
          Level.FINE, "[titan-api] job " + jobFullName + ": configJson repoUrl parse failed", e);
      return null;
    }
  }

  /**
   * True when {@code triggers} is an array containing at least one entry whose {@code type} equals
   * {@link GithubTrigger#TYPE} ({@code "github"}). Uses the typed discriminator constant — no
   * string literals leak past the trigger module.
   */
  private static boolean hasGithubTrigger(JsonNode triggersArray) {
    if (triggersArray == null || !triggersArray.isArray()) {
      return false;
    }
    for (JsonNode t : triggersArray) {
      if (t != null && GithubTrigger.TYPE.equals(t.path("type").asText(""))) {
        return true;
      }
    }
    return false;
  }
}

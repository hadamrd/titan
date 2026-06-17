package io.adaptiq.titan.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.discovery.DiscoverySource.DiscoveredFile;
import io.adaptiq.titan.flow.parser.TitanYamlParser;
import io.adaptiq.titan.job.Job;
import io.adaptiq.titan.job.JobService;
import io.adaptiq.titan.job.JobUpdate;
import io.adaptiq.titan.job.NewJobRequest;
import io.adaptiq.titan.store.rows.DiscoverySourceRow;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Projects a discovered {@code titan-pipeline.yml} into a {@link Job} — the controller-side sink
 * the {@code DiscoveryWorker} hands every claimed discovery event to.
 *
 * <p>A safe job name is derived by slugifying the <em>full</em> repo identifier (so {@code
 * acme/billing} and {@code globex/billing} never collide); the job is created if absent, updated if
 * it is already discovery-owned. A newly-created job is stamped discovery-owned immediately — even
 * before the YAML is parsed — so a later failure leaves a recoverable job, not a stranded one. A
 * pre-existing job is touched only when its {@code discovery} block exists and its {@code repo}
 * matches: a missing block means hand-created, a mismatching {@code repo} means a slug collision —
 * either way the provenance guard skips it.
 *
 * <p>The pipeline YAML is parsed and validated by {@link TitanYamlParser#parseAndValidate} (a
 * {@code PipelineParseException} propagates so the worker marks the event {@code failed}).
 *
 * <p>Maps a discovered job to the {@link Job} domain record and persists it via the {@link
 * JobService} CDI bean.
 */
@ApplicationScoped
public class DiscoverySink {

  private static final Logger LOGGER = Logger.getLogger(DiscoverySink.class.getName());
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final JobService jobs;

  public DiscoverySink(JobService jobs) {
    this.jobs = jobs;
  }

  /**
   * Parse, validate and project {@code yamlBytes} into a {@link Job}.
   *
   * @throws io.adaptiq.titan.flow.parser.PipelineParseException if the YAML is invalid.
   * @throws IOException if the job cannot be created or saved.
   */
  public void apply(
      @NonNull byte[] yamlBytes, @NonNull DiscoveredFile file, @NonNull DiscoverySourceRow source)
      throws IOException {
    String yaml = new String(yamlBytes, StandardCharsets.UTF_8);

    String repo = file.repo != null ? file.repo : source.name;
    String name = jobName(repo);
    if (name.isEmpty()) {
      throw new IOException("Could not derive a safe job name from repo: " + repo);
    }

    // Find-or-create. A newly-created job is unconditionally discovery-owned.
    Job job = jobs.findByFullName(name).orElse(null);
    boolean created = false;
    if (job == null) {
      // H2: stamp the discovery block IMMEDIATELY so a later failure (parse, save) leaves a
      // recoverable discovery-owned job — never a stranded block-less job the guard skips.
      String stampedConfig = writeDiscoveryBlock("{}", source.name, repo, file);
      NewJobRequest req =
          new NewJobRequest(
              name, /* displayName */
              null, /* folderPath */
              null,
              "",
              stampedConfig,
              /* createdBy */ "discovery", /* enabled */
              true);
      job = jobs.create(req);
      created = true;
      LOGGER.log(Level.INFO, "[titan] discovery created job: {0}", name);
    } else if (!matchesDiscoveryProvenance(job, repo)) {
      // H1: a pre-existing job either was hand-created (no discovery block) or belongs to a
      // different repo whose slug collides with this one — never clobber it.
      LOGGER.log(
          Level.WARNING,
          "[titan] discovered repo {0} collides with existing discovery job {1} for a different repo"
              + " — skipping",
          new Object[] {repo, name});
      return;
    }

    // Parse + DAG-validate. A PipelineParseException propagates → event marked failed. The job
    // already exists and is discovery-owned, so the re-armed event retries cleanly next poll.
    TitanYamlParser.parseAndValidate(yaml);

    // One coherent config_json write: re-stamp the discovery block.
    String newConfig;
    try {
      newConfig = writeDiscoveryBlock(job.configJson(), source.name, repo, file);
    } catch (UncheckedIOException e) {
      throw new IOException(
          "Failed to update config_json for discovered job " + name, e.getCause());
    }

    JobUpdate update =
        new JobUpdate(job.displayName(), job.folderPath(), yaml, newConfig, job.enabled());
    jobs.update(job.id(), update);
    LOGGER.log(
        Level.INFO,
        created
            ? "[titan] discovery applied pipeline to new job: {0}"
            : "[titan] discovery applied pipeline to job: {0}",
        name);
  }

  /**
   * Stamp the {@code discovery} block into the supplied {@code configJson}. Pure — returns the new
   * JSON string; the caller persists.
   */
  @NonNull
  private static String writeDiscoveryBlock(
      @NonNull String configJson,
      @NonNull String sourceName,
      @NonNull String repo,
      @NonNull DiscoveredFile file) {
    try {
      JsonNode parsed =
          MAPPER.readTree(configJson == null || configJson.isBlank() ? "{}" : configJson);
      ObjectNode root = parsed instanceof ObjectNode obj ? obj : MAPPER.createObjectNode();

      ObjectNode discovery = MAPPER.createObjectNode();
      discovery.put("sourceName", sourceName);
      discovery.put("repo", repo);
      discovery.put("path", file.path);
      if (file.commitSha != null) {
        discovery.put("commitSha", file.commitSha);
      }
      discovery.put("discoveredAt", Instant.now().toString());
      root.set("discovery", discovery);

      return MAPPER.writeValueAsString(root);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to update config_json", e);
    }
  }

  /**
   * Provenance check for a <em>pre-existing</em> job: true only when the job carries a {@code
   * discovery} block whose {@code repo} matches {@code repo}. A missing block means the job was
   * hand-created; a mismatching {@code repo} means a slug collision with a different repo. Either
   * way the job must not be touched.
   */
  private static boolean matchesDiscoveryProvenance(@NonNull Job job, @NonNull String repo) {
    try {
      String cj = job.configJson();
      JsonNode root = MAPPER.readTree(cj == null || cj.isBlank() ? "{}" : cj);
      JsonNode discovery = root.get("discovery");
      if (discovery == null || !discovery.isObject()) {
        return false;
      }
      return repo.equals(discovery.path("repo").asText(null));
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Derive a safe job name by slugifying the <em>full</em> repo identifier — lowercase, runs of
   * non-alphanumerics collapse to a single {@code -}, leading/trailing {@code -} trimmed. The whole
   * identifier is slugified (not just the last segment) so {@code acme/billing} and {@code
   * globex/billing} produce distinct names: {@code acme-billing} and {@code globex-billing}. {@code
   * hadamrd/titan-e2e-fixture} → {@code hadamrd-titan-e2e-fixture}; a bare local-directory name
   * like {@code billing} → {@code billing}.
   */
  @NonNull
  static String jobName(@NonNull String repo) {
    return repo.toLowerCase(java.util.Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("^-+", "")
        .replaceAll("-+$", "");
  }
}

package io.adaptiq.titan.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.build.BuildEnqueuer;
import io.adaptiq.titan.job.JobNotFoundException;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.DiscoveryStateRow;
import io.adaptiq.titan.store.rows.JobRow;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Default {@link DiscoveryService}: walks enabled jobs, resolves remote HEAD SHA via {@link
 * GitHeadResolver}, and triggers a build when the SHA has changed since the last poll.
 *
 * <p>Build-trigger insertion mirrors {@code JobBuildsApi#triggerBuild}: a {@code QUEUED} build plus
 * an {@code ORCHESTRATE/BAKE} task in one transaction, stamped {@code triggerType="discovery"} for
 * audit.
 *
 * <p>Constructor injection only — Quarkus ARC wires {@link TitanStores} (via {@code
 * StoresProducer}) and {@link GitHeadResolver} ({@link ProcessGitHeadResolver}).
 */
@ApplicationScoped
public class DiscoveryServiceImpl implements DiscoveryService {

  private static final Logger LOG = Logger.getLogger(DiscoveryServiceImpl.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final TitanStores stores;
  private final GitHeadResolver gitHead;

  public DiscoveryServiceImpl(TitanStores stores, GitHeadResolver gitHead) {
    this.stores = stores;
    this.gitHead = gitHead;
  }

  @Override
  public int pollAll() {
    int triggered = 0;
    for (JobRow job : stores.jobs().listEnabled()) {
      try {
        if (pollJob(job).isPresent()) {
          triggered++;
        }
      } catch (RuntimeException e) {
        // Per-job isolation: one failure must not break the loop.
        LOG.warnf(e, "[titan-discovery] poll failed for job id=%d (%s)", job.id, job.fullName);
      }
    }
    return triggered;
  }

  @Override
  @NonNull
  public Optional<Long> pollOne(long jobId) {
    JobRow job = stores.jobs().findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
    return pollJob(job);
  }

  @Override
  @NonNull
  public Optional<Instant> lastPollOf(long jobId) {
    return stores.discoveryState().findByJobId(jobId).map(r -> r.lastPolledAt);
  }

  // ── internals ─────────────────────────────────────────────────────────────

  @NonNull
  private Optional<Long> pollJob(@NonNull JobRow job) {
    Optional<ScmConfig> scmOpt = scmConfig(job);
    if (scmOpt.isEmpty()) {
      // Job has no scm block — nothing to poll. Not an error.
      return Optional.empty();
    }
    ScmConfig scm = scmOpt.get();

    String observed;
    try {
      observed = gitHead.resolve(scm.url, scm.branch);
    } catch (GitHeadResolver.GitHeadException e) {
      LOG.warnf(
          "[titan-discovery] git head resolve failed for job %d (%s@%s): %s",
          job.id, scm.url, scm.branch, e.getMessage());
      stores.discoveryState().recordFailed(job.id, e.getMessage());
      return Optional.empty();
    }

    Optional<DiscoveryStateRow> existing = stores.discoveryState().findByJobId(job.id);
    String lastSeen = existing.map(r -> r.lastSeenSha).orElse(null);

    if (observed.equals(lastSeen)) {
      // No-op: stamp the poll so lastPolledAt reflects the live check, but no trigger.
      stores.discoveryState().recordOk(job.id, observed);
      return Optional.empty();
    }

    long buildId =
        BuildEnqueuer.enqueue(
            stores, job.id, "discovery", "discovery", "{\"commitSha\":\"" + observed + "\"}", null);
    stores.discoveryState().recordOk(job.id, observed);
    LOG.infof(
        "[titan-discovery] triggered build %d for job %d (%s) — sha %s → %s",
        buildId, job.id, job.fullName, abbrev(lastSeen), abbrev(observed));
    return Optional.of(buildId);
  }

  /**
   * Extract an {@code scm} block from the job's {@code config_json}. Shape:
   *
   * <pre>{@code
   * { "scm": { "url": "https://github.com/acme/billing", "branch": "main" } }
   * }</pre>
   *
   * Empty when no scm block exists or its {@code url} is blank.
   */
  @NonNull
  static Optional<ScmConfig> scmConfig(@NonNull JobRow job) {
    String cj = job.configJson;
    if (cj == null || cj.isBlank()) {
      return Optional.empty();
    }
    try {
      JsonNode root = MAPPER.readTree(cj);
      JsonNode scm = root.path("scm");
      if (scm.isMissingNode() || !scm.isObject()) {
        return Optional.empty();
      }
      String url = scm.path("url").asText("");
      if (url.isBlank()) {
        return Optional.empty();
      }
      String branch = scm.path("branch").asText("");
      if (branch.isBlank()) {
        branch = "main";
      }
      return Optional.of(new ScmConfig(url.trim(), branch.trim()));
    } catch (Exception e) {
      LOG.debugf(e, "[titan-discovery] malformed config_json on job %d", job.id);
      return Optional.empty();
    }
  }

  @NonNull
  private static String abbrev(@Nullable String sha) {
    if (sha == null) {
      return "<none>";
    }
    return sha.length() <= 8 ? sha : sha.substring(0, 8);
  }

  /** Resolved SCM config for one job — {@code (url, branch)}. */
  record ScmConfig(@NonNull String url, @NonNull String branch) {}
}

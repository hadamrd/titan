package io.adaptiq.titan.scm.pulsar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.scm.pulsar.PulsarClient.CheckConclusion;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Observes {@link BuildStateChangedEvent} and reports the Titan build verdict back to the Pulsar
 * node as the {@code build} CI check on the open change (issue #1282, convergence merge-gate
 * payoff). Mirrors {@link io.adaptiq.titan.scm.github.GithubStatusReporter} / {@link
 * io.adaptiq.titan.scm.github.GithubCheckRunReporter} — a CDI observer that filters to its own
 * provenance and posts a single check per transition through the shared {@link PulsarClient} (no
 * second HTTP transport).
 *
 * <h2>Why this matters</h2>
 *
 * Pulsar's {@code sample}-style repos require a green {@code build} check ({@code
 * required_checks:["build"]}) before a change can merge. Nothing produced it before — the policy
 * had to be cleared by hand. This reporter publishes it, so the merge gate works properly: a
 * SUCCEEDED build flips the change's decision {@code refused_incomplete}→{@code allowed}; a FAILED
 * build keeps it refused.
 *
 * <h2>Filter</h2>
 *
 * Posts only when {@code event.triggerType()} starts with {@code "pulsar"} (the value {@code
 * PulsarWebhookApi.enqueueBuild} writes) AND the {@code triggerMetaJson} carries a {@code changeId}
 * AND the job resolves to a {@code fullName} (the Pulsar repo). Any missing piece → silent skip.
 * Manual / cron / GitHub builds never reach the Pulsar ledger.
 *
 * <h2>Conclusion mapping</h2>
 *
 * <ul>
 *   <li>{@code QUEUED} / {@code RUNNING} → {@link CheckConclusion#PENDING}
 *   <li>{@code SUCCESS} → {@link CheckConclusion#SUCCESS}
 *   <li>{@code FAILED} / {@code ABORTED} / {@code CANCELLED} → {@link CheckConclusion#FAILURE}
 *   <li>{@code UNSTABLE} → {@link CheckConclusion#UNSTABLE} (wired as {@code failure} — the Pulsar
 *       CI event accepts only {@code pending|success|failure}, and an unstable build is not a clean
 *       green, so the gate stays refused)
 * </ul>
 *
 * <h2>Lifecycle phase (issue #5 — GitHub-checks parity)</h2>
 *
 * Both {@code QUEUED} and {@code RUNNING} map to {@code conclusion:"pending"}, so before #5 they
 * produced byte-identical events and a reviewer could not tell enqueued-but-not-started from
 * actually-running. GitHub distinguishes {@code queued} from {@code in_progress} under one PENDING
 * state; this reporter restores parity by also emitting a {@code phase} field:
 *
 * <ul>
 *   <li>{@code QUEUED} → {@link PulsarClient.Phase#QUEUED} ({@code "queued"})
 *   <li>{@code RUNNING} → {@link PulsarClient.Phase#IN_PROGRESS} ({@code "in_progress"}) — fired at
 *       the real worker-pickup transition, not at enqueue
 *   <li>terminal verdicts (SUCCESS/FAILURE/UNSTABLE) → NO phase (a finished build has no in-flight
 *       phase)
 * </ul>
 *
 * The {@code phase} field is orthogonal to {@code conclusion}: both phases stay {@code pending}, so
 * neither clears the merge gate — only the terminal {@code success} event does. A {@code
 * QUEUED → RUNNING → SUCCESS} build therefore posts three ordered, distinct lifecycle events, only
 * the last clearing the gate.
 *
 * <h2>Error handling</h2>
 *
 * A check post MUST NOT fail the build (the status write has already committed). Every {@link
 * PulsarApiException} is caught at the observer boundary. A transient failure (5xx or a transport
 * error, {@code status == -1}) is retried exactly once; a permanent 4xx is logged and skipped. A
 * failed post is never mistaken for a cleared gate — the verdict simply isn't published.
 *
 * <h2>Feature flag</h2>
 *
 * Toggle via {@code titan.pulsar.checks.enabled} (default {@code true}). When {@code false}, the
 * observer short-circuits.
 */
@ApplicationScoped
public class PulsarCheckReporter {

  private static final Logger LOGGER = Logger.getLogger(PulsarCheckReporter.class.getName());

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Trigger-type value written by {@code PulsarWebhookApi.enqueueBuild}. */
  static final String PULSAR_TRIGGER_PREFIX = "pulsar";

  /** The check name Pulsar's {@code required_checks} contract expects — MUST be {@code build}. */
  static final String CHECK_NAME = "build";

  private final TitanStores stores;
  private final PulsarClient client;
  private final String publicBaseUrl;
  private final boolean enabled;

  /** Production constructor — builds a {@link PulsarClient} for the configured node. */
  @Inject
  public PulsarCheckReporter(
      TitanStores stores,
      @ConfigProperty(
              name = "pulsar.node-base-url",
              defaultValue = PulsarClientFactory.DEFAULT_ENDPOINT)
          String nodeBaseUrl,
      @ConfigProperty(name = "titan.public-url", defaultValue = "http://localhost:8080")
          String publicBaseUrl,
      @ConfigProperty(name = "titan.pulsar.checks.enabled", defaultValue = "true")
          boolean enabled) {
    this(stores, new PulsarClient(nodeBaseUrl), publicBaseUrl, enabled);
  }

  /** Test-only constructor — explicit {@link PulsarClient} (point it at a fake node), no config. */
  PulsarCheckReporter(
      @NonNull TitanStores stores,
      @NonNull PulsarClient client,
      @NonNull String publicBaseUrl,
      boolean enabled) {
    this.stores = stores;
    this.client = client;
    this.publicBaseUrl = trimTrailingSlash(publicBaseUrl);
    this.enabled = enabled;
  }

  // ── observer ───────────────────────────────────────────────────────────────

  public void onBuildStateChanged(@Observes @NonNull BuildStateChangedEvent event) {
    if (!enabled) {
      return;
    }
    try {
      report(event);
    } catch (RuntimeException unexpected) {
      LOGGER.log(
          Level.WARNING,
          "[pulsar-check] unexpected error reporting build {0} state {1}: {2}",
          new Object[] {event.buildId(), event.newStatus(), unexpected.getMessage()});
    }
  }

  /** Visible for direct invocation from tests. */
  void report(@NonNull BuildStateChangedEvent event) {
    String trigger = event.triggerType();
    if (trigger == null || !trigger.startsWith(PULSAR_TRIGGER_PREFIX)) {
      return;
    }
    Optional<CheckConclusion> conclusion = mapConclusion(event.newStatus());
    if (conclusion.isEmpty()) {
      return; // intermediate state we don't report on (e.g. SLEEPING)
    }
    @Nullable String changeId = extractChangeId(event.triggerMetaJson());
    if (changeId == null || changeId.isEmpty()) {
      LOGGER.log(
          Level.FINE,
          "[pulsar-check] skipping build {0}: no changeId in trigger meta",
          event.buildId());
      return;
    }
    Optional<JobRow> jobOpt = stores.jobs().findById(event.jobId());
    if (jobOpt.isEmpty() || jobOpt.get().fullName == null || jobOpt.get().fullName.isBlank()) {
      LOGGER.log(
          Level.FINE,
          "[pulsar-check] skipping build {0}: job {1} has no resolvable repo",
          new Object[] {event.buildId(), event.jobId()});
      return;
    }
    String repo = jobOpt.get().fullName;
    String detailsUrl = publicBaseUrl + "/builds/" + event.buildId();

    postWithRetry(
        repo, changeId, conclusion.get(), mapPhase(event.newStatus()).orElse(null), detailsUrl,
        event.buildId());
  }

  // ── http ──────────────────────────────────────────────────────────────────

  private void postWithRetry(
      @NonNull String repo,
      @NonNull String changeId,
      @NonNull CheckConclusion conclusion,
      @Nullable PulsarClient.Phase phase,
      @NonNull String detailsUrl,
      long buildId) {
    try {
      client.postCheck(repo, changeId, CHECK_NAME, conclusion, phase, detailsUrl);
      return;
    } catch (PulsarApiException e) {
      if (!isRetryable(e.status())) {
        LOGGER.log(
            Level.WARNING,
            "[pulsar-check] build {0} change {1}: non-retryable failure (HTTP {2}): {3} — skipping",
            new Object[] {buildId, changeId, e.status(), e.getMessage()});
        return;
      }
      LOGGER.log(
          Level.FINE,
          "[pulsar-check] build {0} change {1}: transient failure (HTTP {2}) — retrying once",
          new Object[] {buildId, changeId, e.status()});
    }

    // Retry path (transient failure only) — a second failure is logged + skipped, never a crash and
    // never a silent success: the verdict is simply not published this time.
    try {
      client.postCheck(repo, changeId, CHECK_NAME, conclusion, phase, detailsUrl);
    } catch (PulsarApiException retry) {
      LOGGER.log(
          Level.WARNING,
          "[pulsar-check] build {0} change {1}: retry still failed (HTTP {2}): {3} — skipping",
          new Object[] {buildId, changeId, retry.status(), retry.getMessage()});
    }
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /** A 5xx or a transport-level error ({@code status == -1}) is worth one retry; a 4xx is not. */
  static boolean isRetryable(int status) {
    return status == -1 || status >= 500;
  }

  @NonNull
  static Optional<CheckConclusion> mapConclusion(@NonNull String status) {
    return switch (status) {
      case "QUEUED", "RUNNING" -> Optional.of(CheckConclusion.PENDING);
      case "SUCCESS" -> Optional.of(CheckConclusion.SUCCESS);
      case "FAILED", "ABORTED", "CANCELLED" -> Optional.of(CheckConclusion.FAILURE);
      case "UNSTABLE" -> Optional.of(CheckConclusion.UNSTABLE);
      default -> Optional.empty();
    };
  }

  /**
   * The in-flight lifecycle marker for a non-terminal build state, or empty for terminal verdicts
   * (issue #5). {@code QUEUED → queued}, {@code RUNNING → in_progress} (the real start transition,
   * mirroring GitHub {@code queued → in_progress}); SUCCESS/FAILED/etc. carry no phase. Both phases
   * keep {@code conclusion:"pending"} so neither clears the gate — the distinction is purely the
   * reviewer-visible signal that a worker has picked the build up.
   */
  @NonNull
  static Optional<PulsarClient.Phase> mapPhase(@NonNull String status) {
    return switch (status) {
      case "QUEUED" -> Optional.of(PulsarClient.Phase.QUEUED);
      case "RUNNING" -> Optional.of(PulsarClient.Phase.IN_PROGRESS);
      default -> Optional.empty();
    };
  }

  @Nullable
  static String extractChangeId(@Nullable String triggerMetaJson) {
    if (triggerMetaJson == null || triggerMetaJson.isBlank()) {
      return null;
    }
    try {
      JsonNode node = MAPPER.readTree(triggerMetaJson);
      String changeId = node.path("changeId").asText("");
      return changeId.isEmpty() ? null : changeId;
    } catch (IOException e) {
      return null;
    }
  }

  @NonNull
  private static String trimTrailingSlash(@NonNull String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}

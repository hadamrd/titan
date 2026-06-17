package io.adaptiq.titan.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.JobGithubLinkRow;
import io.adaptiq.titan.store.rows.JobRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHCheckRun;
import org.kohsuke.github.GHCheckRun.Conclusion;
import org.kohsuke.github.GHCheckRun.Status;
import org.kohsuke.github.GHCheckRunBuilder;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.HttpException;

/**
 * Observes {@link BuildStateChangedEvent} and posts richer GitHub Check-Runs in addition to the
 * commit-status badges written by {@link GithubStatusReporter} (closes #965).
 *
 * <h2>Why check-runs as well as statuses</h2>
 *
 * Commit-status is the legacy mechanism: a single state + a target_url. Check-runs are the modern
 * counterpart — they carry a title, a Markdown summary, and a {@code text} field that the PR-checks
 * UI renders inline. Both can co-exist and GitHub renders them in different lanes of the PR.
 *
 * <h2>Lifecycle</h2>
 *
 * <ul>
 *   <li>Build first transitions to {@code QUEUED} or {@code RUNNING} and the {@code builds} row has
 *       no {@code external_check_run_id} yet → POST {@code /repos/.../check-runs} with status
 *       {@code in_progress}. Persist the returned numeric id onto the build row.
 *   <li>Build transitions to a terminal status ({@code SUCCESS}/{@code FAILED}/{@code ABORTED}/
 *       {@code UNSTABLE}/{@code CANCELLED}) → PATCH {@code /repos/.../check-runs/{id}} with the
 *       mapped {@code conclusion} and a Markdown summary. No-op when the build never got a
 *       check-run id (App was not the trigger, or the POST failed).
 * </ul>
 *
 * <h2>Conclusion mapping</h2>
 *
 * <ul>
 *   <li>{@code SUCCESS} → {@link Conclusion#SUCCESS}
 *   <li>{@code FAILED} → {@link Conclusion#FAILURE}
 *   <li>{@code ABORTED} / {@code CANCELLED} → {@link Conclusion#CANCELLED}
 *   <li>{@code UNSTABLE} → {@link Conclusion#NEUTRAL}
 * </ul>
 *
 * <h2>Error handling</h2>
 *
 * Same contract as {@link GithubStatusReporter}: every {@link IOException} / {@link HttpException}
 * is caught at the observer boundary, logged at {@code WARNING}, and swallowed. The build's status
 * write has already committed before this observer runs; a failed Check-Run posting is cosmetic.
 *
 * <h2>Feature flag</h2>
 *
 * Toggle via {@code titan.github.check-runs.enabled} (default {@code true}). When {@code false},
 * the observer short-circuits and the column is never populated.
 */
@ApplicationScoped
public class GithubCheckRunReporter {

  private static final Logger LOGGER = Logger.getLogger(GithubCheckRunReporter.class.getName());

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Trigger-type prefix written by {@code GithubAppWebhookApi.enqueueBuild}. */
  static final String GITHUB_APP_TRIGGER_PREFIX = "github-app";

  private final TitanStores stores;
  private final GithubAppService appService;
  private final GithubClientFactory clientFactory;
  private final String publicBaseUrl;
  private final boolean enabled;

  @Inject
  public GithubCheckRunReporter(
      TitanStores stores,
      GithubAppService appService,
      GithubClientFactory clientFactory,
      @ConfigProperty(name = "titan.public-url", defaultValue = "http://localhost:8080")
          String publicBaseUrl,
      @ConfigProperty(name = "titan.github.check-runs.enabled", defaultValue = "true")
          boolean enabled) {
    this.stores = stores;
    this.appService = appService;
    this.clientFactory = clientFactory;
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
          "[github-checkrun] unexpected error reporting build {0} state {1}: {2}",
          new Object[] {event.buildId(), event.newStatus(), unexpected.getMessage()});
    }
  }

  /** Visible for direct invocation from tests. */
  void report(@NonNull BuildStateChangedEvent event) {
    String trigger = event.triggerType();
    if (trigger == null || !trigger.startsWith(GITHUB_APP_TRIGGER_PREFIX)) {
      return;
    }
    @Nullable String sha = extractCommitSha(event.triggerMetaJson());
    if (sha == null || sha.isEmpty()) {
      return;
    }
    Optional<JobGithubLinkRow> linkOpt = stores.jobs().findGithubLinkage(event.jobId());
    if (linkOpt.isEmpty()) {
      return;
    }
    JobGithubLinkRow link = linkOpt.get();

    if (isOpening(event.newStatus())) {
      handleStart(event, link, sha);
    } else if (isTerminal(event.newStatus())) {
      handleFinish(event, link);
    }
    // Intermediate states (e.g. SLEEPING) are no-ops — the check-run sits in_progress.
  }

  // ── start ────────────────────────────────────────────────────────────────

  private void handleStart(
      @NonNull BuildStateChangedEvent event, @NonNull JobGithubLinkRow link, @NonNull String sha) {
    // Idempotency: if the column is already set we have already posted (e.g. a QUEUED→RUNNING
    // transition after we already POSTed on QUEUED).
    Optional<BuildRow> buildOpt = stores.builds().findById(event.buildId());
    if (buildOpt.isEmpty() || buildOpt.get().externalCheckRunId != null) {
      return;
    }
    BuildRow build = buildOpt.get();
    JobRow job = stores.jobs().findById(event.jobId()).orElse(null);
    String pipelineName = GithubCheckRunSummary.pipelineName(build, job);
    String detailsUrl = publicBaseUrl + "/builds/" + event.buildId();

    try {
      Long checkRunId = createCheckRun(link, sha, pipelineName, detailsUrl);
      if (checkRunId != null) {
        stores.builds().setExternalCheckRunId(event.buildId(), checkRunId);
      }
    } catch (HttpException e) {
      LOGGER.log(
          Level.WARNING,
          "[github-checkrun] build {0} sha {1}: GitHub returned HTTP {2} on create: {3} — skipping",
          new Object[] {event.buildId(), sha, e.getResponseCode(), e.getMessage()});
    } catch (IOException e) {
      LOGGER.log(
          Level.WARNING,
          "[github-checkrun] build {0} sha {1}: I/O error creating check-run: {2} — skipping",
          new Object[] {event.buildId(), sha, e.getMessage()});
    } catch (GithubApiException e) {
      LOGGER.log(
          Level.WARNING,
          "[github-checkrun] build {0} sha {1}: install-token mint failed (HTTP {2}): {3}",
          new Object[] {event.buildId(), sha, e.status(), e.getMessage()});
    }
  }

  @Nullable
  private Long createCheckRun(
      @NonNull JobGithubLinkRow link,
      @NonNull String sha,
      @NonNull String pipelineName,
      @NonNull String detailsUrl)
      throws IOException {
    String token = appService.getInstallationToken(link.installId);
    GitHub client = clientFactory.asInstallation(token);
    GHRepository repo = client.getRepository(link.owner + "/" + link.name);
    GHCheckRunBuilder builder =
        repo.createCheckRun("Titan / " + pipelineName, sha)
            .withStatus(Status.IN_PROGRESS)
            .withStartedAt(new java.util.Date())
            .withDetailsURL(detailsUrl);
    GHCheckRun created = builder.create();
    long id = created.getId();
    return id > 0 ? id : null;
  }

  // ── finish ───────────────────────────────────────────────────────────────

  private void handleFinish(@NonNull BuildStateChangedEvent event, @NonNull JobGithubLinkRow link) {
    Optional<BuildRow> buildOpt = stores.builds().findById(event.buildId());
    if (buildOpt.isEmpty()) {
      return;
    }
    BuildRow build = buildOpt.get();
    Long checkRunId = build.externalCheckRunId;
    if (checkRunId == null) {
      // No check-run was opened for this build (App was not the trigger, or POST failed). No-op.
      return;
    }
    Conclusion conclusion = mapConclusion(event.newStatus());
    if (conclusion == null) {
      return;
    }
    JobRow job = stores.jobs().findById(event.jobId()).orElse(null);
    String pipelineName = GithubCheckRunSummary.pipelineName(build, job);
    String detailsUrl = publicBaseUrl + "/builds/" + event.buildId();

    List<FlowNodeRow> nodes;
    try {
      nodes = stores.flowNodes().listByBuild(event.buildId());
    } catch (RuntimeException dbFail) {
      // Don't let a DAO failure cascade into the GitHub call — build the patch with an empty list.
      LOGGER.log(
          Level.FINE,
          "[github-checkrun] build {0}: flowNodes lookup failed: {1}",
          new Object[] {event.buildId(), dbFail.getMessage()});
      nodes = List.of();
    }
    int stageCount = nodes.size();
    List<String> failed = GithubCheckRunSummary.failedStageNames(nodes);

    String title = GithubCheckRunSummary.title(pipelineName, event.newStatus());
    String summary =
        GithubCheckRunSummary.summary(
            pipelineName, build.durationMs, stageCount, failed, detailsUrl);

    try {
      updateCheckRun(link, checkRunId, conclusion, title, summary, detailsUrl);
    } catch (HttpException e) {
      LOGGER.log(
          Level.WARNING,
          "[github-checkrun] build {0} check-run {1}: GitHub returned HTTP {2} on update: {3}",
          new Object[] {event.buildId(), checkRunId, e.getResponseCode(), e.getMessage()});
    } catch (IOException e) {
      LOGGER.log(
          Level.WARNING,
          "[github-checkrun] build {0} check-run {1}: I/O error updating: {2}",
          new Object[] {event.buildId(), checkRunId, e.getMessage()});
    } catch (GithubApiException e) {
      LOGGER.log(
          Level.WARNING,
          "[github-checkrun] build {0} check-run {1}: install-token mint failed (HTTP {2}): {3}",
          new Object[] {event.buildId(), checkRunId, e.status(), e.getMessage()});
    }
  }

  private void updateCheckRun(
      @NonNull JobGithubLinkRow link,
      long checkRunId,
      @NonNull Conclusion conclusion,
      @NonNull String title,
      @NonNull String summary,
      @NonNull String detailsUrl)
      throws IOException {
    String token = appService.getInstallationToken(link.installId);
    GitHub client = clientFactory.asInstallation(token);
    GHRepository repo = client.getRepository(link.owner + "/" + link.name);
    repo.updateCheckRun(checkRunId)
        .withStatus(Status.COMPLETED)
        .withConclusion(conclusion)
        .withCompletedAt(new java.util.Date())
        .withDetailsURL(detailsUrl)
        .add(new GHCheckRunBuilder.Output(title, summary))
        .create();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  static boolean isOpening(@NonNull String status) {
    return "QUEUED".equals(status) || "RUNNING".equals(status);
  }

  static boolean isTerminal(@NonNull String status) {
    return switch (status) {
      case "SUCCESS", "FAILED", "ABORTED", "CANCELLED", "UNSTABLE" -> true;
      default -> false;
    };
  }

  @Nullable
  static Conclusion mapConclusion(@NonNull String status) {
    return switch (status) {
      case "SUCCESS" -> Conclusion.SUCCESS;
      case "FAILED" -> Conclusion.FAILURE;
      case "ABORTED", "CANCELLED" -> Conclusion.CANCELLED;
      case "UNSTABLE" -> Conclusion.NEUTRAL;
      default -> null;
    };
  }

  @Nullable
  static String extractCommitSha(@Nullable String triggerMetaJson) {
    if (triggerMetaJson == null || triggerMetaJson.isBlank()) {
      return null;
    }
    try {
      JsonNode node = MAPPER.readTree(triggerMetaJson);
      String sha = node.path("commitSha").asText("");
      return sha.isEmpty() ? null : sha;
    } catch (IOException e) {
      return null;
    }
  }

  @NonNull
  private static String trimTrailingSlash(@NonNull String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}

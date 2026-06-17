package io.adaptiq.titan.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobGithubLinkRow;
import io.adaptiq.titan.trigger.StatusReporter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.HttpException;

/**
 * Observes {@link BuildStateChangedEvent} and posts commit status back to GitHub for builds that
 * originated from a GitHub App push / pull_request webhook (closes #835, child D of EPIC #831).
 *
 * <h2>Filter</h2>
 *
 * Posts only when {@code event.triggerType()} starts with {@code "github-app"} (the webhook stores
 * discriminated strings like {@code "github-app:push"} or {@code "github-app:pull_request:opened"})
 * AND the job has a resolvable {@code (installId, repoId, owner, name)} linkage AND the {@code
 * triggerMetaJson} carries a {@code commitSha}. Any missing piece → silent skip (no log spam, no
 * exception). Manual / cron / dogfood / replay builds never reach the GitHub status API.
 *
 * <h2>State mapping</h2>
 *
 * <ul>
 *   <li>{@code RUNNING} → {@link GHCommitState#PENDING} with description {@code "Running"}.
 *   <li>{@code QUEUED} → {@link GHCommitState#PENDING} with description {@code "Queued"} (covered
 *       so a long-queued build still shows up as in-flight on the PR rather than 404-stale).
 *   <li>{@code SUCCESS} → {@link GHCommitState#SUCCESS}.
 *   <li>{@code FAILED} / {@code ABORTED} / {@code CANCELLED} / {@code UNSTABLE} → {@link
 *       GHCommitState#FAILURE} (GitHub does not surface {@code ERROR} differently in PR badges, so
 *       we collapse to {@code failure} — same semantic as CircleCI / BuildKite).
 * </ul>
 *
 * <h2>Error handling — CRITICAL CONTRACT</h2>
 *
 * A GitHub status post MUST NOT fail the build. Every {@link IOException} / {@link HttpException} /
 * runtime exception is caught at the observer boundary, logged at {@code WARNING}, and swallowed.
 * The build's status transition has already been written to the DB before this observer runs;
 * losing the cosmetic GitHub badge is strictly better than rolling back a real build write.
 *
 * <h2>401 retry</h2>
 *
 * Installation tokens expire after 60 min. {@link GithubAppService} caches them with a 50-min
 * refresh threshold, but a long-running build CAN cross the boundary. On a {@code 401} we
 * invalidate the cached token, mint a fresh one, and retry exactly once. A second 401 → log + skip
 * (the install was probably suspended / revoked).
 *
 * <h2>Constitution §6</h2>
 *
 * No plaintext-secret caching. All round-trips go through the {@code org.kohsuke:github-api} client
 * built by {@link GithubClientFactory}.
 */
@ApplicationScoped
public class GithubStatusReporter implements StatusReporter {

  /** {@inheritDoc} */
  @Override
  @NonNull
  public String triggerTypePrefix() {
    return GITHUB_APP_TRIGGER_PREFIX;
  }

  /**
   * SPI bridge — adapts the trigger-api {@link BuildStatusEvent} projection to the existing
   * CDI-coupled report path. The CDI {@link #onBuildStateChanged} observer is the production entry
   * point; this method exists so the reporter satisfies the {@link StatusReporter} contract and can
   * be invoked uniformly by any future cross-SCM dispatcher (issue #1080).
   */
  @Override
  public void report(@NonNull BuildStatusEvent event) {
    report(
        new BuildStateChangedEvent(
            event.buildId(),
            event.newStatus(),
            event.triggerType(),
            event.triggerMetaJson(),
            event.jobId(),
            event.buildNumber()));
  }

  private static final Logger LOGGER = Logger.getLogger(GithubStatusReporter.class.getName());

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** {@code context} field GitHub displays in the PR-checks UI. */
  static final String CONTEXT = "ci/titan";

  /** The trigger-type value emitted by {@code GithubAppWebhookApi.enqueueBuild}. */
  static final String GITHUB_APP_TRIGGER_PREFIX = "github-app";

  private final TitanStores stores;
  private final GithubAppService appService;
  private final GithubClientFactory clientFactory;
  private final String publicBaseUrl;

  @Inject
  public GithubStatusReporter(
      TitanStores stores,
      GithubAppService appService,
      GithubClientFactory clientFactory,
      @ConfigProperty(name = "titan.public-url", defaultValue = "http://localhost:8080")
          String publicBaseUrl) {
    this.stores = stores;
    this.appService = appService;
    this.clientFactory = clientFactory;
    this.publicBaseUrl = trimTrailingSlash(publicBaseUrl);
  }

  // ── observer ───────────────────────────────────────────────────────────────

  /**
   * CDI observer. Synchronous on purpose — the {@code BuildServiceImpl} dispatcher already wraps
   * this call in a best-effort try/catch, so a slow GitHub call would only block one DB
   * transaction's completion (the status update). If status latency becomes an issue we'd flip to
   * {@code @ObservesAsync} — for V1 the cap of one HTTP round-trip per transition is acceptable.
   */
  public void onBuildStateChanged(@Observes @NonNull BuildStateChangedEvent event) {
    try {
      report(event);
    } catch (RuntimeException unexpected) {
      // Defence-in-depth — anything that escaped the inner catch.
      LOGGER.log(
          Level.WARNING,
          "[github-status] unexpected error reporting build {0} state {1}: {2}",
          new Object[] {event.buildId(), event.newStatus(), unexpected.getMessage()});
    }
  }

  /** Visible for direct invocation from tests. */
  void report(@NonNull BuildStateChangedEvent event) {
    String trigger = event.triggerType();
    if (trigger == null || !trigger.startsWith(GITHUB_APP_TRIGGER_PREFIX)) {
      // Webhook stores discriminated trigger strings: "github-app:push",
      // "github-app:pull_request:opened", etc. — match the prefix, not the
      // bare token. (Live-rig verification on 2026-05-25 caught the original
      // equality check rejecting every real event.)
      return;
    }
    Optional<GHCommitState> mapped = mapStatus(event.newStatus());
    if (mapped.isEmpty()) {
      return; // intermediate state we don't report on (e.g. PENDING/SLEEPING)
    }

    @Nullable String sha = extractCommitSha(event.triggerMetaJson());
    if (sha == null || sha.isEmpty()) {
      LOGGER.log(
          Level.FINE,
          "[github-status] skipping build {0}: no commitSha in trigger meta",
          event.buildId());
      return;
    }

    Optional<JobGithubLinkRow> linkOpt = stores.jobs().findGithubLinkage(event.jobId());
    if (linkOpt.isEmpty()) {
      LOGGER.log(
          Level.FINE,
          "[github-status] skipping build {0}: job {1} has no GitHub-App linkage",
          new Object[] {event.buildId(), event.jobId()});
      return;
    }
    JobGithubLinkRow link = linkOpt.get();

    GHCommitState state = mapped.get();
    // For a red check, name the stage that broke so the PR-checks tab is
    // actionable without clicking through to the build-detail UI (#1240).
    String failedStage =
        state == GHCommitState.FAILURE ? resolveFailedStage(event.buildId()) : null;
    String description = describe(state, event.newStatus(), failedStage);
    String targetUrl = publicBaseUrl + "/builds/" + event.buildId();

    postStatusWithRetry(link, sha, state, targetUrl, description, event.buildId());
  }

  // ── http ──────────────────────────────────────────────────────────────────

  private void postStatusWithRetry(
      @NonNull JobGithubLinkRow link,
      @NonNull String sha,
      @NonNull GHCommitState state,
      @NonNull String targetUrl,
      @NonNull String description,
      long buildId) {
    try {
      doPost(link, sha, state, targetUrl, description);
      return;
    } catch (HttpException e) {
      if (e.getResponseCode() != 401) {
        LOGGER.log(
            Level.WARNING,
            "[github-status] build {0} sha {1}: GitHub returned HTTP {2}: {3} — skipping",
            new Object[] {buildId, sha, e.getResponseCode(), e.getMessage()});
        return;
      }
      // 401: token likely expired mid-build. Force a fresh mint and try once more.
      LOGGER.log(
          Level.FINE,
          "[github-status] build {0}: 401 from GitHub — invalidating install token and retrying",
          buildId);
      appService.invalidateInstallationToken(link.installId);
    } catch (IOException e) {
      LOGGER.log(
          Level.WARNING,
          "[github-status] build {0} sha {1}: I/O error posting status: {2} — skipping",
          new Object[] {buildId, sha, e.getMessage()});
      return;
    } catch (GithubApiException e) {
      LOGGER.log(
          Level.WARNING,
          "[github-status] build {0} sha {1}: install-token mint failed (HTTP {2}): {3} — skipping",
          new Object[] {buildId, sha, e.status(), e.getMessage()});
      return;
    }

    // Retry path (post-401 only).
    try {
      doPost(link, sha, state, targetUrl, description);
    } catch (IOException retry) {
      LOGGER.log(
          Level.WARNING,
          "[github-status] build {0} sha {1}: retry after 401 still failed: {2} — skipping",
          new Object[] {buildId, sha, retry.getMessage()});
    } catch (GithubApiException retry) {
      LOGGER.log(
          Level.WARNING,
          "[github-status] build {0} sha {1}: install-token mint failed on retry (HTTP {2}): {3}"
              + " — skipping",
          new Object[] {buildId, sha, retry.status(), retry.getMessage()});
    }
  }

  private void doPost(
      @NonNull JobGithubLinkRow link,
      @NonNull String sha,
      @NonNull GHCommitState state,
      @NonNull String targetUrl,
      @NonNull String description)
      throws IOException {
    String token = appService.getInstallationToken(link.installId);
    GitHub client = clientFactory.asInstallation(token);
    GHRepository repo = client.getRepository(link.owner + "/" + link.name);
    repo.createCommitStatus(sha, state, targetUrl, description, CONTEXT);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  @NonNull
  static Optional<GHCommitState> mapStatus(@NonNull String status) {
    return switch (status) {
      case "QUEUED", "RUNNING" -> Optional.of(GHCommitState.PENDING);
      case "SUCCESS" -> Optional.of(GHCommitState.SUCCESS);
      case "FAILED", "ABORTED", "CANCELLED", "UNSTABLE" -> Optional.of(GHCommitState.FAILURE);
      default -> Optional.empty();
    };
  }

  @NonNull
  static String describe(
      @NonNull GHCommitState state, @NonNull String status, @Nullable String failedStage) {
    return switch (state) {
      case PENDING -> "RUNNING".equals(status) ? "Running" : "Queued";
      case SUCCESS -> "Build succeeded";
      case FAILURE -> {
        String base =
            switch (status) {
              case "ABORTED" -> "Build aborted";
              case "CANCELLED" -> "Build cancelled";
              case "UNSTABLE" -> "Build unstable";
              default -> "Build failed";
            };
        // Name the broken stage when we resolved one — "Build failed: test".
        // GitHub truncates status descriptions to ~140 chars; stage display
        // names are short, so no explicit clamp is needed for V1.
        yield (failedStage == null || failedStage.isBlank()) ? base : base + ": " + failedStage;
      }
      case ERROR -> "Build errored";
    };
  }

  /**
   * Resolve the display name of the first {@code FAILED} flow node of a build, so a red commit
   * status can name the stage that broke. Delegates to the shared {@link
   * GithubCheckRunSummary#failedStageNames} resolver so the failed-stage semantics stay identical
   * to the check-run reporter on the same build, and takes the first entry ({@code null} if none).
   * Best-effort: any DB error returns {@code null} and the description degrades to the generic
   * "Build failed" — naming the stage is a nicety, never a reason to drop the badge.
   */
  @Nullable
  private String resolveFailedStage(long buildId) {
    try {
      List<String> failed =
          GithubCheckRunSummary.failedStageNames(stores.flowNodes().listByBuild(buildId));
      return failed.isEmpty() ? null : failed.get(0);
    } catch (RuntimeException dbFail) {
      LOGGER.log(
          Level.FINE,
          "[github-status] build {0}: failed-stage lookup failed: {1}",
          new Object[] {buildId, dbFail.getMessage()});
      return null;
    }
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

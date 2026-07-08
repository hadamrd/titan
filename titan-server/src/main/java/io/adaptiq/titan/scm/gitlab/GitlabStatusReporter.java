package io.adaptiq.titan.scm.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.trigger.StatusReporter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Observes {@link BuildStateChangedEvent} and POSTs a commit status back to GitLab for builds that
 * originated from a GitLab webhook trigger (issue #1080).
 *
 * <h2>Filter</h2>
 *
 * Posts only when {@code event.triggerType()} equals {@code "gitlab"} (the value {@code
 * GitlabWebhookApi.enqueueBuild} stamps onto every webhook-triggered build) AND the trigger
 * metadata JSON carries {@code commitSha}, {@code projectId}, and {@code gitlabCredentialsId}. Any
 * missing piece → silent skip (no log spam, no exception). Manual / cron / dogfood / replay builds
 * never reach the GitLab status API.
 *
 * <h2>State mapping</h2>
 *
 * <ul>
 *   <li>{@code QUEUED} → {@code running} (GitLab's {@code pending} means "queued before any
 *       pipeline started" — using it for our QUEUED would lose the in-flight signal once the build
 *       moves to RUNNING. Mapping both to {@code running} matches what GitHub's PR-checks UI does
 *       semantically and what GitLab CI itself does for its own queued jobs).
 *   <li>{@code RUNNING} → {@code running}.
 *   <li>{@code SUCCESS} → {@code success}.
 *   <li>{@code FAILED} / {@code UNSTABLE} → {@code failed}.
 *   <li>{@code ABORTED} / {@code CANCELLED} → {@code canceled} (GitLab spells it American-style
 *       with one l — see <a
 *       href="https://docs.gitlab.com/ee/api/commits.html#post-the-build-status-to-a-commit">API
 *       reference</a>).
 * </ul>
 *
 * <h2>Error handling — CRITICAL CONTRACT</h2>
 *
 * A GitLab status post MUST NOT fail the build. Every {@link IOException} / {@link
 * InterruptedException} / non-2xx response is caught at the observer boundary, logged at {@code
 * WARNING}, and swallowed. The build's status transition has already been written to the DB before
 * this observer runs.
 *
 * <h2>Security</h2>
 *
 * The {@code PRIVATE-TOKEN} value resolved from {@link CredentialsService} NEVER appears in log
 * output, not even on a {@code 401}. The reporter logs only HTTP status + response body length —
 * never the token, never the response body itself (which on a 401 echoes the {@code PRIVATE-TOKEN}
 * header value in some Heroku-fronted setups).
 *
 * <h2>Credentials scope — v1 reuses webhook secret</h2>
 *
 * v1 resolves the post-back token from the SAME {@code "gitlab-webhook"} scope the inbound
 * verification uses, keyed by the trigger's {@code credentialsId}. On the rig users typically point
 * the trigger at a single credential entry that holds both the shared webhook secret AND a personal
 * access token (the GitLab UI even encourages this — a single "CI integration token"). A future
 * ticket will split into a dedicated {@code "gitlab-api"} scope; until then the reporter and
 * verifier share one entry.
 *
 * <h2>Constitution §6</h2>
 *
 * No plaintext-secret caching. Tokens are fetched fresh from {@link CredentialsService} per call —
 * never cached on this class.
 */
@ApplicationScoped
public class GitlabStatusReporter implements StatusReporter {

  private static final Logger LOGGER = Logger.getLogger(GitlabStatusReporter.class.getName());

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Trigger-type value emitted by {@code GitlabWebhookApi.enqueueBuild}. */
  static final String GITLAB_TRIGGER_PREFIX = "gitlab";

  /** {@code context} parameter GitLab displays in its commit-statuses UI. */
  static final String CONTEXT = "ci/titan";

  /**
   * Credentials scope the v1 reporter reads from. Documented in the class Javadoc above — same
   * scope as {@code GitlabWebhookApi}, will split in a future ticket.
   */
  static final String CREDENTIALS_SCOPE = "gitlab-webhook";

  private final CredentialsService credentials;
  private final String gitlabBaseUrl;
  private final String publicBaseUrl;
  private final HttpClient http;

  @Inject
  public GitlabStatusReporter(
      CredentialsService credentials,
      @ConfigProperty(name = "gitlab.base-url", defaultValue = "https://gitlab.com")
          String gitlabBaseUrl,
      @ConfigProperty(name = "titan.public-url", defaultValue = "http://localhost:8080")
          String publicBaseUrl) {
    this(
        credentials,
        gitlabBaseUrl,
        publicBaseUrl,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
  }

  /** Visible-for-testing constructor accepting an explicit {@link HttpClient}. */
  GitlabStatusReporter(
      @NonNull CredentialsService credentials,
      @NonNull String gitlabBaseUrl,
      @NonNull String publicBaseUrl,
      @NonNull HttpClient http) {
    this.credentials = credentials;
    this.gitlabBaseUrl = trimTrailingSlash(gitlabBaseUrl);
    this.publicBaseUrl = trimTrailingSlash(publicBaseUrl);
    this.http = http;
  }

  // ── SPI ────────────────────────────────────────────────────────────────────

  @Override
  @NonNull
  public String triggerTypePrefix() {
    return GITLAB_TRIGGER_PREFIX;
  }

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

  // ── observer ───────────────────────────────────────────────────────────────

  /**
   * CDI observer. Synchronous on purpose — {@code BuildServiceImpl} already wraps observer dispatch
   * in best-effort try/catch (see the comment on {@code BuildServiceImpl.update}); a slow GitLab
   * call only blocks the status-update transaction's completion, not the build's progress.
   */
  public void onBuildStateChanged(@Observes @NonNull BuildStateChangedEvent event) {
    try {
      report(event);
    } catch (RuntimeException unexpected) {
      // Defence-in-depth — anything that escaped the inner catch.
      LOGGER.log(
          Level.WARNING,
          "[gitlab-status] unexpected error reporting build {0} state {1}: {2}",
          new Object[] {event.buildId(), event.newStatus(), unexpected.getMessage()});
    }
  }

  /** Visible for direct invocation from tests. */
  void report(@NonNull BuildStateChangedEvent event) {
    String trigger = event.triggerType();
    if (trigger == null || !trigger.startsWith(GITLAB_TRIGGER_PREFIX)) {
      return;
    }
    Optional<String> mapped = mapStatus(event.newStatus());
    if (mapped.isEmpty()) {
      return; // intermediate state we don't report on
    }

    @Nullable Meta meta = extractMeta(event.triggerMetaJson());
    if (meta == null) {
      LOGGER.log(
          Level.FINE,
          "[gitlab-status] skipping build {0}: trigger meta missing required fields"
              + " (commitSha / projectId / gitlabCredentialsId)",
          event.buildId());
      return;
    }

    Optional<String> tokenOpt = credentials.resolvePlaintext(CREDENTIALS_SCOPE, meta.credentialsId);
    if (tokenOpt.isEmpty() || tokenOpt.get().isEmpty()) {
      LOGGER.log(
          Level.WARNING,
          "[gitlab-status] build {0}: credentialsId ''{1}'' did not resolve — skipping",
          new Object[] {event.buildId(), meta.credentialsId});
      return;
    }

    String state = mapped.get();
    String description = describe(state, event.newStatus());
    String targetUrl = publicBaseUrl + "/builds/" + event.buildId();
    post(meta, state, description, targetUrl, tokenOpt.get(), event.buildId());
  }

  // ── http ──────────────────────────────────────────────────────────────────

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void post(
      @NonNull Meta meta,
      @NonNull String state,
      @NonNull String description,
      @NonNull String targetUrl,
      @NonNull String token,
      long buildId) {
    URI uri =
        URI.create(
            gitlabBaseUrl
                + "/api/v4/projects/"
                + meta.projectId
                + "/statuses/"
                + urlPath(meta.commitSha)
                + "?state="
                + urlQ(state)
                + "&target_url="
                + urlQ(targetUrl)
                + "&description="
                + urlQ(description)
                + "&context="
                + urlQ(CONTEXT));

    HttpRequest req =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("PRIVATE-TOKEN", token)
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

    try {
      HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
      int sc = resp.statusCode();
      if (sc >= 200 && sc < 300) {
        return;
      }
      // SECURITY: log code + body length only. The token MUST NEVER appear; the response body
      // is also withheld because GitLab error responses occasionally echo header values.
      LOGGER.log(
          Level.WARNING,
          "[gitlab-status] build {0} sha {1} project {2}: GitLab returned HTTP {3}"
              + " (body {4} bytes) — skipping",
          new Object[] {buildId, meta.commitSha, meta.projectId, sc, resp.body().length});
    } catch (IOException e) {
      LOGGER.log(
          Level.WARNING,
          "[gitlab-status] build {0} sha {1}: I/O error posting status: {2} — skipping",
          new Object[] {buildId, meta.commitSha, e.getMessage()});
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.log(
          Level.WARNING,
          "[gitlab-status] build {0} sha {1}: interrupted posting status — skipping",
          new Object[] {buildId, meta.commitSha});
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  @NonNull
  static Optional<String> mapStatus(@NonNull String status) {
    return switch (status) {
      case "QUEUED", "RUNNING" -> Optional.of("running");
      case "SUCCESS" -> Optional.of("success");
      case "FAILED", "UNSTABLE" -> Optional.of("failed");
      case "ABORTED", "CANCELLED" -> Optional.of("canceled");
      default -> Optional.empty();
    };
  }

  @NonNull
  static String describe(@NonNull String state, @NonNull String status) {
    return switch (state) {
      case "running" -> "RUNNING".equals(status) ? "Running" : "Queued";
      case "success" -> "Build succeeded";
      case "failed" -> "UNSTABLE".equals(status) ? "Build unstable" : "Build failed";
      case "canceled" -> "CANCELLED".equals(status) ? "Build cancelled" : "Build aborted";
      default -> status;
    };
  }

  /**
   * Strict extractor — all three of {@code commitSha}, {@code projectId}, {@code
   * gitlabCredentialsId} MUST be present. Returns {@code null} when any is missing; the reporter
   * silently skips.
   */
  @Nullable
  static Meta extractMeta(@Nullable String triggerMetaJson) {
    if (triggerMetaJson == null || triggerMetaJson.isBlank()) {
      return null;
    }
    try {
      JsonNode node = MAPPER.readTree(triggerMetaJson);
      String sha = node.path("commitSha").asText("");
      long projectId = node.path("projectId").asLong(0L);
      String credId = node.path("gitlabCredentialsId").asText("");
      if (sha.isEmpty() || projectId <= 0L || credId.isEmpty()) {
        return null;
      }
      return new Meta(sha, projectId, credId);
    } catch (IOException e) {
      return null;
    }
  }

  /** URL-encode a path segment. */
  @NonNull
  private static String urlPath(@NonNull String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  /** URL-encode a query parameter value. */
  @NonNull
  private static String urlQ(@NonNull String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  @NonNull
  private static String trimTrailingSlash(@NonNull String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  /** Internal — the three scalars the reporter needs from {@code triggerMetaJson}. */
  static final class Meta {
    final String commitSha;
    final long projectId;
    final String credentialsId;

    Meta(@NonNull String commitSha, long projectId, @NonNull String credentialsId) {
      this.commitSha = commitSha;
      this.projectId = projectId;
      this.credentialsId = credentialsId;
    }
  }
}

package io.adaptiq.titan.scm.bitbucket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Observes {@link BuildStateChangedEvent} and POSTs a Bitbucket Cloud commit-build-status for
 * builds that originated from a Bitbucket webhook trigger (issue #1080).
 *
 * <h2>Dormant in v1 — no inbound trigger yet</h2>
 *
 * Titan does NOT yet expose an inbound Bitbucket webhook resource (closest analogues are {@code
 * GithubAppWebhookApi} and {@code GitlabWebhookApi}). Until that lands, no build will ever carry
 * {@code triggerType == "bitbucket"} and this reporter is a quiet no-op. It ships now so the SCM
 * status-reporting fan-out shape (closes #1080) is complete and the Bitbucket inbound webhook
 * ticket has nothing left to do but stamp the right discriminator.
 *
 * <h2>Filter</h2>
 *
 * Posts only when {@code event.triggerType()} starts with {@code "bitbucket"} AND the trigger
 * metadata JSON carries {@code commitSha}, {@code workspace}, {@code repoSlug}, {@code
 * bitbucketCredentialsId}. Any missing piece → silent skip.
 *
 * <h2>State mapping (Bitbucket Cloud build statuses)</h2>
 *
 * <ul>
 *   <li>{@code QUEUED} / {@code RUNNING} → {@code INPROGRESS}.
 *   <li>{@code SUCCESS} → {@code SUCCESSFUL}.
 *   <li>{@code FAILED} / {@code UNSTABLE} / {@code CANCELLED} / {@code ABORTED} → {@code FAILED}.
 *       (Bitbucket Cloud has no "cancelled" build state — collapsing to FAILED matches what
 *       CircleCI / BuildKite do.)
 * </ul>
 *
 * <h2>Error / security contract</h2>
 *
 * Same as the GitLab/GitHub reporters: best-effort, never throws, tokens NEVER appear in logs.
 *
 * <h2>Credentials</h2>
 *
 * Resolved via {@link CredentialsService#resolvePlaintext(String, String) resolvePlaintext} under
 * scope {@code "bitbucket-webhook"} using the {@code bitbucketCredentialsId} key from the trigger
 * meta. The plaintext is expected to be an app password / repository access token — the reporter
 * sends it as {@code Authorization: Bearer <token>}.
 */
@ApplicationScoped
public class BitbucketStatusReporter implements StatusReporter {

  private static final Logger LOGGER = Logger.getLogger(BitbucketStatusReporter.class.getName());

  private static final ObjectMapper MAPPER = new ObjectMapper();

  static final String BITBUCKET_TRIGGER_PREFIX = "bitbucket";

  static final String KEY = "ci/titan";

  static final String NAME = "Titan";

  /** Credentials scope. Symmetric with {@code gitlab-webhook}. */
  static final String CREDENTIALS_SCOPE = "bitbucket-webhook";

  private final CredentialsService credentials;
  private final String bitbucketBaseUrl;
  private final String publicBaseUrl;
  private final HttpClient http;

  @Inject
  public BitbucketStatusReporter(
      CredentialsService credentials,
      @ConfigProperty(name = "bitbucket.base-url", defaultValue = "https://api.bitbucket.org")
          String bitbucketBaseUrl,
      @ConfigProperty(name = "titan.public-url", defaultValue = "http://localhost:8080")
          String publicBaseUrl) {
    this(
        credentials,
        bitbucketBaseUrl,
        publicBaseUrl,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
  }

  /** Visible-for-testing constructor accepting an explicit {@link HttpClient}. */
  BitbucketStatusReporter(
      @NonNull CredentialsService credentials,
      @NonNull String bitbucketBaseUrl,
      @NonNull String publicBaseUrl,
      @NonNull HttpClient http) {
    this.credentials = credentials;
    this.bitbucketBaseUrl = trimTrailingSlash(bitbucketBaseUrl);
    this.publicBaseUrl = trimTrailingSlash(publicBaseUrl);
    this.http = http;
  }

  // ── SPI ───────────────────────────────────────────────────────────────────

  @Override
  @NonNull
  public String triggerTypePrefix() {
    return BITBUCKET_TRIGGER_PREFIX;
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

  // ── observer ──────────────────────────────────────────────────────────────

  public void onBuildStateChanged(@Observes @NonNull BuildStateChangedEvent event) {
    try {
      report(event);
    } catch (RuntimeException unexpected) {
      LOGGER.log(
          Level.WARNING,
          "[bitbucket-status] unexpected error reporting build {0} state {1}: {2}",
          new Object[] {event.buildId(), event.newStatus(), unexpected.getMessage()});
    }
  }

  /** Visible for direct invocation from tests. */
  void report(@NonNull BuildStateChangedEvent event) {
    String trigger = event.triggerType();
    if (trigger == null || !trigger.startsWith(BITBUCKET_TRIGGER_PREFIX)) {
      return;
    }
    Optional<String> mapped = mapStatus(event.newStatus());
    if (mapped.isEmpty()) {
      return;
    }

    @Nullable Meta meta = extractMeta(event.triggerMetaJson());
    if (meta == null) {
      LOGGER.log(
          Level.FINE,
          "[bitbucket-status] skipping build {0}: trigger meta missing required fields"
              + " (commitSha / workspace / repoSlug / bitbucketCredentialsId)",
          event.buildId());
      return;
    }

    Optional<String> tokenOpt = credentials.resolvePlaintext(CREDENTIALS_SCOPE, meta.credentialsId);
    if (tokenOpt.isEmpty() || tokenOpt.get().isEmpty()) {
      LOGGER.log(
          Level.WARNING,
          "[bitbucket-status] build {0}: credentialsId ''{1}'' did not resolve — skipping",
          new Object[] {event.buildId(), meta.credentialsId});
      return;
    }

    String state = mapped.get();
    String description = describe(state, event.newStatus());
    String targetUrl = publicBaseUrl + "/builds/" + event.buildId();
    String body = renderBody(state, targetUrl, description);
    post(meta, body, tokenOpt.get(), event.buildId());
  }

  // ── http ─────────────────────────────────────────────────────────────────

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void post(@NonNull Meta meta, @NonNull String body, @NonNull String token, long buildId) {
    URI uri =
        URI.create(
            bitbucketBaseUrl
                + "/2.0/repositories/"
                + meta.workspace
                + "/"
                + meta.repoSlug
                + "/commit/"
                + meta.commitSha
                + "/statuses/build");

    HttpRequest req =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

    try {
      HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
      int sc = resp.statusCode();
      if (sc >= 200 && sc < 300) {
        return;
      }
      LOGGER.log(
          Level.WARNING,
          "[bitbucket-status] build {0} sha {1} {2}/{3}: Bitbucket returned HTTP {4}"
              + " (body {5} bytes) — skipping",
          new Object[] {
            buildId, meta.commitSha, meta.workspace, meta.repoSlug, sc, resp.body().length
          });
    } catch (IOException e) {
      LOGGER.log(
          Level.WARNING,
          "[bitbucket-status] build {0} sha {1}: I/O error posting status: {2} — skipping",
          new Object[] {buildId, meta.commitSha, e.getMessage()});
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.log(
          Level.WARNING,
          "[bitbucket-status] build {0} sha {1}: interrupted posting status — skipping",
          new Object[] {buildId, meta.commitSha});
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  @NonNull
  static Optional<String> mapStatus(@NonNull String status) {
    return switch (status) {
      case "QUEUED", "RUNNING" -> Optional.of("INPROGRESS");
      case "SUCCESS" -> Optional.of("SUCCESSFUL");
      case "FAILED", "UNSTABLE", "CANCELLED", "ABORTED" -> Optional.of("FAILED");
      default -> Optional.empty();
    };
  }

  @NonNull
  static String describe(@NonNull String state, @NonNull String status) {
    return switch (state) {
      case "INPROGRESS" -> "RUNNING".equals(status) ? "Running" : "Queued";
      case "SUCCESSFUL" -> "Build succeeded";
      case "FAILED" ->
          switch (status) {
            case "UNSTABLE" -> "Build unstable";
            case "CANCELLED" -> "Build cancelled";
            case "ABORTED" -> "Build aborted";
            default -> "Build failed";
          };
      default -> status;
    };
  }

  /**
   * Render the Bitbucket commit-status JSON body. Public for parity with the fixture-equality test
   * under {@code src/test/resources/scm/bitbucket-status-payload.json}.
   */
  @NonNull
  static String renderBody(
      @NonNull String state, @NonNull String url, @NonNull String description) {
    ObjectNode obj = MAPPER.createObjectNode();
    obj.put("state", state);
    obj.put("key", KEY);
    obj.put("name", NAME);
    obj.put("url", url);
    obj.put("description", description);
    try {
      return MAPPER.writeValueAsString(obj);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // Impossible — every field is a literal scalar — but the checked exception forces a path.
      throw new IllegalStateException("bitbucket status body serialisation failed", e);
    }
  }

  @Nullable
  static Meta extractMeta(@Nullable String triggerMetaJson) {
    if (triggerMetaJson == null || triggerMetaJson.isBlank()) {
      return null;
    }
    try {
      JsonNode node = MAPPER.readTree(triggerMetaJson);
      String sha = node.path("commitSha").asText("");
      String ws = node.path("workspace").asText("");
      String repo = node.path("repoSlug").asText("");
      String credId = node.path("bitbucketCredentialsId").asText("");
      if (sha.isEmpty() || ws.isEmpty() || repo.isEmpty() || credId.isEmpty()) {
        return null;
      }
      return new Meta(sha, ws, repo, credId);
    } catch (IOException e) {
      return null;
    }
  }

  @NonNull
  private static String trimTrailingSlash(@NonNull String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  /** Internal — the four scalars the reporter needs from {@code triggerMetaJson}. */
  static final class Meta {
    final String commitSha;
    final String workspace;
    final String repoSlug;
    final String credentialsId;

    Meta(
        @NonNull String commitSha,
        @NonNull String workspace,
        @NonNull String repoSlug,
        @NonNull String credentialsId) {
      this.commitSha = commitSha;
      this.workspace = workspace;
      this.repoSlug = repoSlug;
      this.credentialsId = credentialsId;
    }
  }
}

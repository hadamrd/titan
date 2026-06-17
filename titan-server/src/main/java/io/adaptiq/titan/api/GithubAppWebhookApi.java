package io.adaptiq.titan.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.build.BuildEnqueuer;
import io.adaptiq.titan.scm.github.GithubAppService;
import io.adaptiq.titan.scm.github.GithubAppWebhookSecretComparator;
import io.adaptiq.titan.scm.github.GithubRepoScanner;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.GithubInstallationRow;
import io.adaptiq.titan.store.rows.JobRow;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Jakarta REST resource: {@code POST /api/v1/github-app/events} — the GitHub App's single webhook
 * sink (child C of epic #831, design/63).
 *
 * <p><strong>Why a separate endpoint from {@code /api/v1/triggers/github}.</strong> The per-trigger
 * receiver ({@link io.adaptiq.titan.api.triggers.GithubWebhookApi}) authenticates with a
 * per-trigger secret bound to the job's {@code config_json}. The App flow has exactly one global
 * webhook secret stored in the {@code titan.github_app} singleton row, and one App identity per
 * tenant — so the dispatch model is "match jobs by ({@code github_installation_id}, {@code
 * github_repo_id})" rather than "scan all jobs for github triggers". The two flows coexist and the
 * UI can choose which to surface per-job.
 *
 * <p><strong>Auth:</strong> {@code @PermitAll} — GitHub does not speak OIDC. Signature verification
 * is the entire auth check; constant-time HMAC compare via {@link
 * GithubAppWebhookSecretComparator#constantTimeEquals}.
 *
 * <p><strong>Idempotency:</strong> GitHub re-delivers on any non-2xx. The {@code X-GitHub-Delivery}
 * header carries a unique UUID per delivery; we keep an in-memory LRU of recent delivery ids and
 * fast-skip duplicates seen within {@value #DELIVERY_TTL_MIN} minutes. The LRU is bounded at
 * {@value #DELIVERY_LRU_CAP} entries.
 *
 * <p><strong>Constitution §6 — plaintext secret handling:</strong> the webhook secret is unsealed
 * per-request (no cache), used immediately for HMAC compare, and dropped out of scope. It is never
 * logged. {@link GithubAppService#unsealWebhookSecret} returns a fresh String each call.
 */
@Path("/api/v1/github-app/events")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@PermitAll
public class GithubAppWebhookApi {

  private static final Logger LOGGER = Logger.getLogger(GithubAppWebhookApi.class.getName());

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Max delivery-ids retained in the in-memory replay-suppression LRU. */
  static final int DELIVERY_LRU_CAP = 1024;

  /** TTL after which a delivery id can replay (e.g. GitHub re-delivers later than 10 min). */
  static final Duration DELIVERY_TTL_MIN = Duration.ofMinutes(10);

  private final TitanStores stores;
  private final Supplier<Optional<String>> webhookSecretSource;
  private final WebhookTriggerMatcher triggerMatcher;

  /**
   * Event-driven discovery hook (issue #886 / design 65). Invoked on push to re-parse the affected
   * repo's {@code .titan/pipelines/} synchronously instead of relying on the scheduled crawler.
   * Nullable for unit-test constructors that don't exercise the discovery path.
   */
  @Nullable private final GithubRepoScanner repoScanner;

  /** Bounded insertion-ordered map → simple LRU for delivery-id replay suppression. */
  private final Map<String, Instant> deliveryLru =
      Collections.synchronizedMap(
          new java.util.LinkedHashMap<>(DELIVERY_LRU_CAP * 4 / 3, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
              return size() > DELIVERY_LRU_CAP;
            }
          });

  /** Production constructor — pulls the webhook secret from {@link GithubAppService}. */
  @Inject
  public GithubAppWebhookApi(
      TitanStores stores, GithubAppService service, GithubRepoScanner repoScanner) {
    this(
        stores,
        () -> {
          try {
            return Optional.of(service.unsealWebhookSecret());
          } catch (IllegalStateException notRegistered) {
            return Optional.empty();
          }
        },
        repoScanner);
  }

  /** Test-only constructor — accepts an explicit secret source, no scanner wired. */
  GithubAppWebhookApi(
      @NonNull TitanStores stores, @NonNull Supplier<Optional<String>> webhookSecretSource) {
    this(stores, webhookSecretSource, null);
  }

  /** Test-only constructor — accepts an explicit secret source and a scanner stub. */
  GithubAppWebhookApi(
      @NonNull TitanStores stores,
      @NonNull Supplier<Optional<String>> webhookSecretSource,
      @Nullable GithubRepoScanner repoScanner) {
    this.stores = stores;
    this.webhookSecretSource = webhookSecretSource;
    this.repoScanner = repoScanner;
    this.triggerMatcher = new WebhookTriggerMatcher(stores);
  }

  // ── POST /api/v1/github-app/events ─────────────────────────────────────────

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response receive(@jakarta.ws.rs.core.Context HttpHeaders headers, byte[] body) {
    String signatureHeader = firstHeader(headers, "X-Hub-Signature-256");
    String eventType = firstHeader(headers, "X-GitHub-Event");
    String deliveryId = firstHeader(headers, "X-GitHub-Delivery");
    byte[] rawBody = body == null ? new byte[0] : body;

    if (signatureHeader == null || signatureHeader.isBlank()) {
      return problem(401, "webhook-signature-invalid", "X-Hub-Signature-256 header missing");
    }

    // Step 1: verify signature BEFORE parsing — never let an unauthenticated body reach Jackson.
    Optional<String> secret = webhookSecretSource.get();
    if (secret.isEmpty() || secret.get().isEmpty()) {
      // No App registered yet — refuse rather than 500. The admin must register first.
      return problem(
          401,
          "github-app-not-registered",
          "no GitHub App registered for this tenant; complete the manifest callback first");
    }
    if (!verifyHmac(secret.get(), rawBody, signatureHeader)) {
      return problem(401, "webhook-signature-invalid", "X-Hub-Signature-256 did not match");
    }

    // Step 2: idempotency check (after signature so an attacker can't poison the LRU).
    if (deliveryId != null && !deliveryId.isBlank() && isDuplicateDelivery(deliveryId)) {
      LOGGER.log(
          Level.FINE, "[github-app] duplicate delivery {0} — skipping", new Object[] {deliveryId});
      return Response.noContent().build();
    }

    // Step 3: parse + dispatch by event type.
    if (eventType == null || eventType.isBlank()) {
      return Response.noContent().build();
    }
    JsonNode payload;
    try {
      payload = MAPPER.readTree(rawBody);
    } catch (IOException e) {
      LOGGER.log(Level.FINE, "[github-app] body is not JSON", e);
      return problem(400, "webhook-malformed", "request body is not valid JSON");
    }

    return switch (eventType.toLowerCase(Locale.ROOT)) {
      case "push" -> handlePush(payload);
      case "pull_request" -> handlePullRequest(payload);
      case "repository_dispatch" -> handleRepositoryDispatch(payload);
      case "installation" -> handleInstallation(payload);
      case "installation_repositories" -> handleInstallationRepositories(payload);
      case "ping" -> Response.ok(Map.of("pong", true)).build();
      default -> Response.noContent().build();
    };
  }

  // ── push ───────────────────────────────────────────────────────────────────

  @NonNull
  private Response handlePush(@NonNull JsonNode payload) {
    long installId = payload.path("installation").path("id").asLong(0);
    long repoId = payload.path("repository").path("id").asLong(0);
    String ref = payload.path("ref").asText("");
    String branch = refToBranch(ref);

    if (installId == 0L) {
      return problem(404, "github-installation-unknown", "payload missing installation.id");
    }

    // Install lookup. Unknown install → 404 fast-fail, no DB writes anywhere else.
    Optional<GithubInstallationRow> install =
        stores.githubInstallations().findByInstallId(installId);
    if (install.isEmpty()) {
      return problem(
          404, "github-installation-unknown", "installation " + installId + " is not registered");
    }
    if (install.get().suspendedAt != null) {
      return problem(
          410,
          "github-installation-suspended",
          "installation " + installId + " is suspended; resume on GitHub to receive events");
    }

    if (branch == null || branch.isEmpty() || repoId == 0L) {
      // Authenticated but no actionable target (e.g. tag push). Acknowledge, do nothing.
      return Response.noContent().build();
    }

    // Event-driven discovery (issue #886 / design 65): re-parse this repo's .titan/pipelines/
    // synchronously so build dispatch sees the freshest YAML rather than the scheduler's cached
    // copy. Branch-aware since #887 — we parse against the push branch's HEAD tree (GitHub
    // Actions semantics), not the default branch. A scanner failure MUST NOT regress build
    // dispatch — log + continue.
    reparseRepoOnPush(installId, repoId, branch);

    List<JobRow> matches = stores.jobs().findByGithubRepo(installId, repoId);
    if (matches.isEmpty()) {
      return Response.noContent().build();
    }

    String triggerMetaJson = buildTriggerMetaJson(payload, branch);
    // Webhook payload params (#938). For push events GitHub doesn't carry inputs, so this map is
    // empty; the matcher's supplied-set then collapses to the legacy Set.of() behaviour. We route
    // through the shared helper so the call shape is uniform across handlers.
    Map<String, String> supplied = WebhookPayloadParams.extract(payload, "push");
    int enqueued = 0;
    int skipped = 0;
    for (JobRow job : matches) {
      if (!triggerMatcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, branch)) {
        skipped++;
        LOGGER.log(
            Level.FINE,
            "[github-app] push to {0}: skipping job {1} — no matching trigger",
            new Object[] {branch, job.fullName});
        continue;
      }
      List<String> missing =
          triggerMatcher.unsatisfiedRequiredParams(job, branch, supplied.keySet());
      if (!missing.isEmpty()) {
        skipped++;
        LOGGER.log(
            Level.INFO,
            "pipeline_skipped reason=missing_required_params pipeline={0} missing={1} trigger={2}",
            new Object[] {job.fullName, missing, "push"});
        continue;
      }
      enqueueBuild(
          job.id,
          "github-app:push",
          triggerMetaJson,
          WebhookPayloadParams.toParametersJson(supplied));
      enqueued++;
    }
    return Response.status(202)
        .entity(
            Map.of(
                "accepted",
                true,
                "event",
                "push",
                "branch",
                branch,
                "enqueued",
                enqueued,
                "skipped",
                skipped))
        .build();
  }

  /**
   * Event-driven re-parse for issue #886. Best-effort: any failure (scanner null in tests, GitHub
   * API hiccup, repo not in registry) is logged and swallowed so the build-dispatch path proceeds
   * with the previous discovery row. A 401 inside the scanner has already suspended the install
   * before the exception reached us; subsequent webhook deliveries for the same install will
   * short-circuit at the suspended-check.
   */
  private void reparseRepoOnPush(long installId, long repoId, @NonNull String branch) {
    if (repoScanner == null) {
      return;
    }
    try {
      repoScanner.scanSingleRepo(installId, repoId, branch);
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[github-app] push-driven re-parse failed for install {0} repo {1} branch {2}: {3} —"
              + " continuing with build dispatch using prior discovery rows",
          new Object[] {installId, repoId, branch, e.getMessage()});
    }
  }

  // ── pull_request ───────────────────────────────────────────────────────────

  @NonNull
  private Response handlePullRequest(@NonNull JsonNode payload) {
    String action = payload.path("action").asText("");
    if (!("opened".equals(action) || "synchronize".equals(action) || "reopened".equals(action))) {
      return Response.noContent().build();
    }

    long installId = payload.path("installation").path("id").asLong(0);
    long repoId = payload.path("repository").path("id").asLong(0);
    if (installId == 0L) {
      return problem(404, "github-installation-unknown", "payload missing installation.id");
    }

    Optional<GithubInstallationRow> install =
        stores.githubInstallations().findByInstallId(installId);
    if (install.isEmpty()) {
      return problem(
          404, "github-installation-unknown", "installation " + installId + " is not registered");
    }
    if (install.get().suspendedAt != null) {
      return problem(
          410, "github-installation-suspended", "installation " + installId + " is suspended");
    }

    if (repoId == 0L) {
      return Response.noContent().build();
    }

    // For PR events the "branch" is the head ref of the PR.
    String branch = payload.path("pull_request").path("head").path("ref").asText("");
    List<JobRow> matches = stores.jobs().findByGithubRepo(installId, repoId);
    if (matches.isEmpty()) {
      return Response.noContent().build();
    }

    String triggerMetaJson = buildPullRequestMetaJson(payload, branch);
    // pull_request payloads don't carry inputs — helper returns empty. Routed through it anyway
    // so all three webhook call sites share one extraction shape (#938).
    Map<String, String> supplied = WebhookPayloadParams.extract(payload, "pull_request");
    int enqueued = 0;
    int skipped = 0;
    for (JobRow job : matches) {
      if (!triggerMatcher.shouldDispatch(
          job, WebhookTriggerMatcher.EventType.PULL_REQUEST, branch)) {
        skipped++;
        LOGGER.log(
            Level.FINE,
            "[github-app] pull_request {0}: skipping job {1} — no matching trigger",
            new Object[] {action, job.fullName});
        continue;
      }
      List<String> missing =
          triggerMatcher.unsatisfiedRequiredParams(job, branch, supplied.keySet());
      if (!missing.isEmpty()) {
        skipped++;
        LOGGER.log(
            Level.INFO,
            "pipeline_skipped reason=missing_required_params pipeline={0} missing={1} trigger={2}",
            new Object[] {job.fullName, missing, "pull_request"});
        continue;
      }
      enqueueBuild(
          job.id,
          "github-app:pull_request:" + action,
          triggerMetaJson,
          WebhookPayloadParams.toParametersJson(supplied));
      enqueued++;
    }
    return Response.status(202)
        .entity(
            Map.of(
                "accepted",
                true,
                "event",
                "pull_request",
                "action",
                action,
                "enqueued",
                enqueued,
                "skipped",
                skipped))
        .build();
  }

  // ── repository_dispatch (issue #938) ───────────────────────────────────────

  /**
   * Handle a {@code repository_dispatch} delivery — GitHub's third-party-driven build trigger. The
   * caller's {@code POST /repos/{owner}/{repo}/dispatches} carries an opaque {@code client_payload}
   * JSON object; each scalar key/value becomes a supplied build parameter (issue #938, mirrors
   * GitHub Actions semantics).
   *
   * <p>Unlike push / pull_request, this event has no {@code ref} and is not filtered by the
   * pipeline's {@code triggers:} block — it is an explicit "build me now" signal from outside
   * GitHub. Every enabled job in the linked repo is considered; the required-no-default param skip
   * still applies, using {@code client_payload}'s keys as the supplied set.
   */
  @NonNull
  private Response handleRepositoryDispatch(@NonNull JsonNode payload) {
    long installId = payload.path("installation").path("id").asLong(0);
    long repoId = payload.path("repository").path("id").asLong(0);
    if (installId == 0L) {
      return problem(404, "github-installation-unknown", "payload missing installation.id");
    }

    Optional<GithubInstallationRow> install =
        stores.githubInstallations().findByInstallId(installId);
    if (install.isEmpty()) {
      return problem(
          404, "github-installation-unknown", "installation " + installId + " is not registered");
    }
    if (install.get().suspendedAt != null) {
      return problem(
          410, "github-installation-suspended", "installation " + installId + " is suspended");
    }
    if (repoId == 0L) {
      return Response.noContent().build();
    }

    String action = payload.path("action").asText("");
    Map<String, String> supplied = WebhookPayloadParams.extract(payload, "repository_dispatch");
    String parametersJson = WebhookPayloadParams.toParametersJson(supplied);

    List<JobRow> matches = stores.jobs().findByGithubRepo(installId, repoId);
    if (matches.isEmpty()) {
      return Response.noContent().build();
    }

    // No branch context on repository_dispatch — use the repo default for the discovered-row
    // lookup the matcher needs. Empty string is fine: findDiscoveredRow falls back to any row.
    String branch = "";
    int enqueued = 0;
    int skipped = 0;
    for (JobRow job : matches) {
      List<String> missing =
          triggerMatcher.unsatisfiedRequiredParams(job, branch, supplied.keySet());
      if (!missing.isEmpty()) {
        skipped++;
        LOGGER.log(
            Level.INFO,
            "pipeline_skipped reason=missing_required_params pipeline={0} missing={1} trigger={2}",
            new Object[] {job.fullName, missing, "repository_dispatch"});
        continue;
      }
      enqueueBuild(
          job.id,
          "github-app:repository_dispatch" + (action.isEmpty() ? "" : ":" + action),
          null,
          parametersJson);
      enqueued++;
    }
    return Response.status(202)
        .entity(
            Map.of(
                "accepted",
                true,
                "event",
                "repository_dispatch",
                "action",
                action,
                "enqueued",
                enqueued,
                "skipped",
                skipped))
        .build();
  }

  // ── installation lifecycle ─────────────────────────────────────────────────

  @NonNull
  private Response handleInstallation(@NonNull JsonNode payload) {
    String action = payload.path("action").asText("");
    long installId = payload.path("installation").path("id").asLong(0);
    if (installId == 0L) {
      return Response.noContent().build();
    }
    switch (action) {
      case "created" -> {
        // Fire-and-forget upsert from the payload. A full sync via the GithubAppService is the
        // admin's call from the UI; here we just record the existence.
        String login = payload.path("installation").path("account").path("login").asText("");
        String accountType = payload.path("installation").path("account").path("type").asText("");
        String targetType = payload.path("installation").path("target_type").asText("");
        if (!login.isEmpty()) {
          if (stores.githubInstallations().findByInstallId(installId).isPresent()) {
            stores.githubInstallations().update(installId, login, accountType, targetType, null);
          } else {
            stores.githubInstallations().insert(installId, login, accountType, targetType, null);
          }
        }
      }
      case "deleted" -> stores.githubInstallations().deleteByInstallId(installId);
      case "suspend" -> markSuspended(installId, Instant.now());
      case "unsuspend" -> markSuspended(installId, null);
      default -> {
        /* unknown lifecycle action — ignore */
      }
    }
    return Response.status(202).entity(Map.of("accepted", true, "event", "installation")).build();
  }

  private void markSuspended(long installId, @Nullable Instant suspendedAt) {
    Optional<GithubInstallationRow> existing =
        stores.githubInstallations().findByInstallId(installId);
    if (existing.isEmpty()) {
      return;
    }
    GithubInstallationRow row = existing.get();
    stores
        .githubInstallations()
        .update(installId, row.accountLogin, row.accountType, row.targetType, suspendedAt);
  }

  // ── installation_repositories ──────────────────────────────────────────────

  @NonNull
  private Response handleInstallationRepositories(@NonNull JsonNode payload) {
    String action = payload.path("action").asText("");
    if (!("added".equals(action) || "removed".equals(action))) {
      return Response.noContent().build();
    }
    long installId = payload.path("installation").path("id").asLong(0);
    if (installId == 0L) {
      return Response.noContent().build();
    }
    // TODO(#833): wire to GithubRepoScanner.scanInstallation(installId) when child B lands.
    // Until then, mutate the repositories table directly from the payload so the UI's repo list
    // doesn't go stale between webhook deliveries.
    if ("removed".equals(action)) {
      JsonNode removed = payload.path("repositories_removed");
      if (removed.isArray()) {
        for (JsonNode r : removed) {
          long repoId = r.path("id").asLong(0);
          if (repoId != 0L) {
            stores.githubRepositories().deleteByRepoId(repoId);
          }
        }
      }
    } else {
      JsonNode added = payload.path("repositories_added");
      if (added.isArray()) {
        for (JsonNode r : added) {
          long repoId = r.path("id").asLong(0);
          if (repoId == 0L) {
            continue;
          }
          String fullName = r.path("full_name").asText("");
          int slash = fullName.indexOf('/');
          if (slash <= 0) {
            continue;
          }
          String owner = fullName.substring(0, slash);
          String name = fullName.substring(slash + 1);
          boolean isPrivate = r.path("private").asBoolean(false);
          // Idempotent — repo_id is UNIQUE, so we delete-then-insert.
          stores.githubRepositories().deleteByRepoId(repoId);
          stores.githubRepositories().insert(installId, repoId, owner, name, null, isPrivate);
        }
      }
    }
    return Response.status(202)
        .entity(Map.of("accepted", true, "event", "installation_repositories", "action", action))
        .build();
  }

  // ── HMAC ───────────────────────────────────────────────────────────────────

  /**
   * Verify a {@code sha256=<hex>} header against the App's webhook secret using a constant-time
   * compare ({@link GithubAppWebhookSecretComparator#constantTimeEquals}).
   */
  static boolean verifyHmac(
      @NonNull String secret, @NonNull byte[] body, @NonNull String signatureHeader) {
    String prefix = "sha256=";
    if (!signatureHeader.startsWith(prefix)) {
      return false;
    }
    String hex = signatureHeader.substring(prefix.length());
    if (hex.isEmpty() || (hex.length() & 1) != 0) {
      return false;
    }
    String expectedHex;
    try {
      expectedHex = HexFormat.of().formatHex(hmacSha256(secret, body));
    } catch (RuntimeException e) {
      return false;
    }
    return GithubAppWebhookSecretComparator.constantTimeEquals(
        expectedHex, hex.toLowerCase(Locale.ROOT));
  }

  @NonNull
  private static byte[] hmacSha256(@NonNull String secret, @NonNull byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(body);
    } catch (Exception e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  // ── idempotency LRU ────────────────────────────────────────────────────────

  /**
   * Returns {@code true} if {@code deliveryId} was seen within {@link #DELIVERY_TTL_MIN}. Touches
   * the LRU (record/refresh) as a side effect.
   */
  boolean isDuplicateDelivery(@NonNull String deliveryId) {
    Instant now = Instant.now();
    synchronized (deliveryLru) {
      Instant prev = deliveryLru.get(deliveryId);
      if (prev != null && Duration.between(prev, now).compareTo(DELIVERY_TTL_MIN) <= 0) {
        return true;
      }
      deliveryLru.put(deliveryId, now);
      return false;
    }
  }

  // ── enqueue (mirrors GithubWebhookApi.enqueueBuild — same SYNTHESIZE path #821) ───────────

  private long enqueueBuild(
      long jobId,
      @NonNull String triggeredBy,
      @Nullable String metaJson,
      @Nullable String parametersJson) {
    return BuildEnqueuer.enqueue(
        stores, jobId, triggeredBy, "github-app", metaJson, parametersJson);
  }

  // ── trigger meta ────────────────────────────────────────────────────────────

  @Nullable
  static String buildTriggerMetaJson(@NonNull JsonNode payload, @NonNull String branch) {
    String sha = nullIfBlank(payload.path("head_commit").path("id").asText(""));
    if (sha == null) {
      sha = nullIfBlank(payload.path("after").asText(""));
    }
    String actor = payload.path("pusher").path("name").asText("");
    if (actor.isEmpty()) {
      actor = payload.path("sender").path("login").asText("");
    }
    return buildMeta(branch, sha, actor.isEmpty() ? null : actor, null);
  }

  /**
   * Build the trigger-meta JSON for a {@code pull_request} delivery. Includes {@code prNumber} when
   * the payload carries one — picked up by {@link
   * io.adaptiq.titan.scm.github.GithubPrCommentReporter} (closes #966) to post a single, sticky
   * PR-comment on build completion. A null/0 PR number means we still emit the meta without the
   * field (push-style flow), and the comment-reporter no-ops.
   */
  @Nullable
  static String buildPullRequestMetaJson(@NonNull JsonNode payload, @NonNull String branch) {
    String sha = nullIfBlank(payload.path("pull_request").path("head").path("sha").asText(""));
    String actor = payload.path("sender").path("login").asText("");
    int prNumber = payload.path("pull_request").path("number").asInt(0);
    if (prNumber <= 0) {
      // Some PR-shaped deliveries carry the number at the top-level rather than under pull_request
      // (rare, but matches GitHub's docs for closed-edge events). Fall back so we never lose it.
      prNumber = payload.path("number").asInt(0);
    }
    return buildMeta(branch, sha, actor.isEmpty() ? null : actor, prNumber > 0 ? prNumber : null);
  }

  @Nullable
  private static String buildMeta(
      @NonNull String branch,
      @Nullable String sha,
      @Nullable String actor,
      @Nullable Integer prNumber) {
    if (branch.isEmpty() && sha == null && actor == null && prNumber == null) {
      return null;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    if (!branch.isEmpty()) {
      out.put("branch", branch);
    }
    if (sha != null) {
      out.put("commitSha", sha);
    }
    if (actor != null) {
      out.put("actor", actor);
    }
    if (prNumber != null) {
      out.put("prNumber", prNumber);
    }
    try {
      return MAPPER.writeValueAsString(out);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return null;
    }
  }

  /**
   * Store the FULL SHA in the build's trigger meta. GitHub's {@code POST /repos/.../statuses/{sha}}
   * rejects abbreviated SHAs ({@code "Sha must be a valid hex object ID"}) — the previous {@code
   * shortSha} truncation broke {@code GithubStatusReporter} on the live rig. UI components that
   * want a short SHA derive it from the full one at render time.
   */
  @Nullable
  private static String nullIfBlank(@Nullable String s) {
    return (s == null || s.isEmpty()) ? null : s;
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  @Nullable
  private static String refToBranch(@Nullable String ref) {
    if (ref == null) {
      return null;
    }
    String prefix = "refs/heads/";
    if (!ref.startsWith(prefix)) {
      return null;
    }
    return ref.substring(prefix.length());
  }

  @Nullable
  private static String firstHeader(@Nullable HttpHeaders headers, @NonNull String name) {
    if (headers == null) {
      return null;
    }
    String v = headers.getHeaderString(name);
    if (v != null) {
      return v;
    }
    return headers.getHeaderString(name.toLowerCase(Locale.ROOT));
  }

  @NonNull
  private static Response problem(int status, @NonNull String slug, @NonNull String detail) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "https://titan.adaptiq.io/problems/" + slug);
    body.put("title", slug);
    body.put("status", status);
    body.put("detail", detail);
    return Response.status(status).type("application/problem+json").entity(body).build();
  }
}

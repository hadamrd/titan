package io.adaptiq.titan.scm.bitbucket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.credentials.CredentialsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Observes terminal {@link BuildStateChangedEvent}s for Bitbucket-Cloud-triggered builds and posts
 * (or edits-in-place) a single sticky PR summary comment with the build verdict + run URL (issue
 * #1117). Mirrors {@code GithubPrCommentReporter}.
 *
 * <h2>Sticky comment — edit, never append</h2>
 *
 * The body starts with a hidden marker {@code <!-- titan:pr-summary:<jobId> -->}. The {@code jobId}
 * is stable across reruns of the same pipeline on the same PR, so on a subsequent run we find the
 * existing comment carrying that marker and PUT it in place rather than POSTing a new one. Result:
 * one Titan comment per PR that edits across pushes, not N stale comments.
 *
 * <h2>Error contract</h2>
 *
 * Best-effort, identical to the status reporter: every failure is logged and swallowed; a cosmetic
 * comment MUST NEVER fail a build. 401 → clean credential warning (no token in logs). 404 (PR
 * closed between trigger and post) → warn + continue.
 */
@ApplicationScoped
public class BitbucketPrCommentReporter {

  private static final Logger LOGGER = Logger.getLogger(BitbucketPrCommentReporter.class.getName());

  /** Credentials scope — symmetric with the status reporter. */
  static final String CREDENTIALS_SCOPE = "bitbucket-webhook";

  static final String BITBUCKET_TRIGGER_PREFIX = "bitbucket";

  /**
   * Upper bound on comment-list pages we walk when hunting the sticky marker. At {@code
   * pagelen=100} this covers 5,000 comments — far past any realistic PR — while guaranteeing the
   * pagination loop can never run unbounded on a malformed/cyclic {@code next} cursor.
   */
  static final int MAX_COMMENT_PAGES = 50;

  /** Terminal statuses that warrant a PR-comment update. */
  private static final Set<String> TERMINAL_STATUSES =
      Set.of("SUCCESS", "FAILED", "ABORTED", "CANCELLED", "UNSTABLE", "SKIPPED");

  private final CredentialsService credentials;
  private final BitbucketClientFactory clientFactory;
  private final String publicBaseUrl;
  private final boolean featureEnabled;

  @Inject
  public BitbucketPrCommentReporter(
      CredentialsService credentials,
      BitbucketClientFactory clientFactory,
      @ConfigProperty(name = "titan.public-url", defaultValue = "http://localhost:8080")
          String publicBaseUrl,
      @ConfigProperty(name = "titan.bitbucket.pr-comments.enabled", defaultValue = "true")
          boolean featureEnabled) {
    this.credentials = credentials;
    this.clientFactory = clientFactory;
    this.publicBaseUrl = trimTrailingSlash(publicBaseUrl);
    this.featureEnabled = featureEnabled;
  }

  // ── observer ───────────────────────────────────────────────────────────────

  public void onBuildStateChanged(@Observes @NonNull BuildStateChangedEvent event) {
    if (!featureEnabled) {
      return;
    }
    try {
      report(event);
    } catch (RuntimeException unexpected) {
      LOGGER.log(
          Level.WARNING,
          "[bitbucket-pr-comment] unexpected error reporting build {0} state {1}: {2}",
          new Object[] {event.buildId(), event.newStatus(), unexpected.getMessage()});
    }
  }

  /** Visible for direct invocation from tests. */
  void report(@NonNull BuildStateChangedEvent event) {
    String trigger = event.triggerType();
    if (trigger == null || !trigger.startsWith(BITBUCKET_TRIGGER_PREFIX)) {
      return;
    }
    if (!TERMINAL_STATUSES.contains(event.newStatus())) {
      return;
    }

    @Nullable
    BitbucketPrContext ctx =
        BitbucketPrContext.extract(event.triggerMetaJson(), BitbucketJson.MAPPER);
    if (ctx == null) {
      LOGGER.log(
          Level.FINE,
          "[bitbucket-pr-comment] skipping build {0}: trigger meta missing PR fields",
          event.buildId());
      return;
    }

    Optional<String> tokenOpt =
        credentials.resolvePlaintext(CREDENTIALS_SCOPE, ctx.credentialsId());
    if (tokenOpt.isEmpty() || tokenOpt.get().isEmpty()) {
      LOGGER.log(
          Level.WARNING,
          "[bitbucket-pr-comment] build {0}: credentialsId ''{1}'' did not resolve — skipping",
          new Object[] {event.buildId(), ctx.credentialsId()});
      return;
    }

    BitbucketAuthProvider auth;
    try {
      auth = BitbucketAuthProvider.fromColonPair(tokenOpt.get());
    } catch (IllegalArgumentException badCred) {
      LOGGER.log(
          Level.WARNING,
          "[bitbucket-pr-comment] build {0}: credential ''{1}'' malformed"
              + " (expected username:app_password) — skipping",
          new Object[] {event.buildId(), ctx.credentialsId()});
      return;
    }

    String marker = markerFor(event.jobId());
    String body = renderBody(marker, event.newStatus(), event.buildId(), event.buildNumber());
    upsert(clientFactory.create(), auth, ctx, marker, body, event.buildId());
  }

  // ── http ──────────────────────────────────────────────────────────────────

  private void upsert(
      @NonNull BitbucketClient client,
      @NonNull BitbucketAuthProvider auth,
      @NonNull BitbucketPrContext ctx,
      @NonNull String marker,
      @NonNull String body,
      long buildId) {
    @Nullable Long existingId = findExistingCommentId(client, auth, ctx, marker, buildId);

    String payload = renderCommentPayload(body);
    BitbucketClient.Response resp;
    if (existingId != null) {
      resp = client.send("PUT", ctx.commentsPath() + "/" + existingId, auth, payload);
    } else {
      resp = client.send("POST", ctx.commentsPath(), auth, payload);
    }

    if (resp.isSuccess()) {
      LOGGER.log(
          Level.FINE,
          "[bitbucket-pr-comment] build {0} pr #{1}: {2} summary comment",
          new Object[] {buildId, ctx.prId(), existingId != null ? "edited" : "posted"});
      return;
    }
    logHttpFailure("upsert", resp.status(), buildId, ctx);
  }

  @Nullable
  private Long findExistingCommentId(
      @NonNull BitbucketClient client,
      @NonNull BitbucketAuthProvider auth,
      @NonNull BitbucketPrContext ctx,
      @NonNull String marker,
      long buildId) {
    // Bitbucket Cloud paginates comment listings. A PR with >100 comments returns a `next` cursor;
    // we MUST walk every page or the sticky-marker match silently misses on page 2+, causing a new
    // comment to be POSTed on every rerun instead of editing in place (issue #1117 review).
    String path = ctx.commentsPath() + "?pagelen=100";
    int page = 0;
    while (path != null && page < MAX_COMMENT_PAGES) {
      page++;
      BitbucketClient.Response list = client.send("GET", path, auth, null);
      if (!list.isSuccess()) {
        // Non-fatal: we just can't dedupe → fall through to POST a fresh comment.
        logHttpFailure("list", list.status(), buildId, ctx);
        return null;
      }
      try {
        JsonNode root = BitbucketJson.MAPPER.readTree(list.body());
        for (JsonNode c : root.path("values")) {
          String raw = c.path("content").path("raw").asText("");
          if (raw.contains(marker)) {
            long id = c.path("id").asLong(0);
            if (id > 0) {
              return id;
            }
          }
        }
        path = nextPagePath(root);
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        LOGGER.log(
            Level.FINE,
            "[bitbucket-pr-comment] build {0} pr #{1}: comment list not parseable — posting fresh",
            new Object[] {buildId, ctx.prId()});
        return null;
      }
    }
    if (path != null) {
      // Hit the page cap with a cursor still pending — log so a missed-match on a huge PR is not
      // silently mistaken for "no existing comment".
      LOGGER.log(
          Level.WARNING,
          "[bitbucket-pr-comment] build {0} pr #{1}: comment list exceeded {2} pages —"
              + " stopped paginating; sticky-marker match may be incomplete",
          new Object[] {buildId, ctx.prId(), MAX_COMMENT_PAGES});
    }
    return null;
  }

  /**
   * Extract the next-page request path from a Bitbucket paginated response, or {@code null} when
   * there is no further page. Bitbucket returns {@code next} as an absolute URL; we reduce it to
   * its path + query and re-issue it against the configured base URL. This deliberately ignores the
   * scheme/host of {@code next} so a stray/hostile cursor can never redirect the client off its
   * configured Bitbucket endpoint (SSRF-safe).
   */
  @Nullable
  static String nextPagePath(@NonNull JsonNode root) {
    String next = root.path("next").asText("");
    if (next.isBlank()) {
      return null;
    }
    try {
      java.net.URI u = java.net.URI.create(next);
      String rawPath = u.getRawPath();
      if (rawPath == null || rawPath.isBlank()) {
        return null;
      }
      String rawQuery = u.getRawQuery();
      return rawQuery == null ? rawPath : rawPath + "?" + rawQuery;
    } catch (IllegalArgumentException malformed) {
      return null;
    }
  }

  private void logHttpFailure(
      @NonNull String op, int status, long buildId, @NonNull BitbucketPrContext ctx) {
    if (status == 401 || status == 403) {
      LOGGER.log(
          Level.WARNING,
          "[bitbucket-pr-comment] build {0} pr #{1}: Bitbucket {2} returned HTTP {3}"
              + " — credential rejected (scm post failed)",
          new Object[] {buildId, ctx.prId(), op, status});
    } else if (status == 404) {
      LOGGER.log(
          Level.WARNING,
          "[bitbucket-pr-comment] build {0} pr #{1}: PR not found (HTTP 404, likely closed)"
              + " — build continues",
          new Object[] {buildId, ctx.prId()});
    } else {
      LOGGER.log(
          Level.WARNING,
          "[bitbucket-pr-comment] build {0} pr #{1}: Bitbucket {2} returned HTTP {3} — skipping",
          new Object[] {buildId, ctx.prId(), op, status});
    }
  }

  // ── rendering ─────────────────────────────────────────────────────────────

  @NonNull
  static String markerFor(long jobId) {
    return "<!-- titan:pr-summary:" + jobId + " -->";
  }

  @NonNull
  String renderBody(@NonNull String marker, @NonNull String status, long buildId, int buildNumber) {
    String runUrl = publicBaseUrl + "/builds/" + buildId;
    String emoji = statusEmoji(status);
    return marker
        + "\n### Titan build "
        + emoji
        + " "
        + status
        + "\n\nBuild #"
        + buildNumber
        + " finished with verdict **"
        + status
        + "**.\n\n[View logs and artifacts →]("
        + runUrl
        + ")\n";
  }

  /** Wrap a markdown body in Bitbucket's {@code {"content":{"raw":...}}} comment envelope. */
  @NonNull
  static String renderCommentPayload(@NonNull String body) {
    ObjectNode root = BitbucketJson.MAPPER.createObjectNode();
    ObjectNode content = root.putObject("content");
    content.put("raw", body);
    try {
      return BitbucketJson.MAPPER.writeValueAsString(root);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("bitbucket comment payload serialisation failed", e);
    }
  }

  @NonNull
  static String statusEmoji(@NonNull String status) {
    return switch (status) {
      case "SUCCESS" -> "✅";
      case "FAILED", "CANCELLED" -> "❌";
      case "ABORTED" -> "⛔";
      case "SKIPPED" -> "⏭️";
      case "UNSTABLE" -> "⚠️";
      default -> "❔";
    };
  }

  @NonNull
  private static String trimTrailingSlash(@NonNull String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}

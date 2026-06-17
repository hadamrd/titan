package io.adaptiq.titan.scm.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Observes terminal {@link BuildStateChangedEvent}s and posts <em>line-level review comments</em>
 * on the GitLab merge-request diff for failures that map to a {@code file:line} (issue #1168).
 * Mirrors the role {@link io.adaptiq.titan.scm.github.GithubCheckRunReporter} plays for GitHub
 * (per-stage failure surfacing) but uses GitLab's MR <em>discussion</em> API with a {@code
 * position} object so the note anchors to the exact diff line.
 *
 * <h2>Where {@code file:line} comes from</h2>
 *
 * A failed {@link FlowNodeRow} carries a {@code failureReason} — a concise customer-facing sentence
 * naming the failure (design/45 §4). When that sentence contains a {@code path/to/file.ext:NN}
 * token (the conventional compiler / linter / test-runner shape, e.g. {@code src/app/login.py:88}),
 * we anchor an inline discussion there. Failures with no parseable location (infra errors, dispatch
 * failures) produce no inline comment — the summary note from {@link GitlabMrCommentReporter} still
 * reports them.
 *
 * <h2>Position object</h2>
 *
 * GitLab requires a full {@code position} for a text discussion: {@code position_type=text}, the
 * three {@code diff_refs} SHAs ({@code base_sha} / {@code start_sha} / {@code head_sha}), and
 * {@code new_path} + {@code new_line}. We resolve the {@code diff_refs} from a {@code GET} on the
 * MR; if the MR has no {@code diff_refs} (rare — only before the first diff is computed) we skip
 * rather than POST an invalid position.
 *
 * <h2>Error + security contract</h2>
 *
 * Identical to {@link GitlabStatusReporter} / {@link GitlabMrCommentReporter}: every failure is
 * caught at the observer boundary, logged at {@code WARNING}, and swallowed — a cosmetic review
 * comment MUST NEVER fail a build. The {@code PRIVATE-TOKEN} NEVER appears in any log line.
 *
 * <p>Feature flag: {@code titan.gitlab.mr-reviews.enabled} (default {@code true}).
 */
@ApplicationScoped
public class GitlabMrReviewReporter {

  private static final Logger LOGGER = Logger.getLogger(GitlabMrReviewReporter.class.getName());

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Trigger-type value emitted by {@code GitlabWebhookApi.enqueueBuild}. */
  static final String GITLAB_TRIGGER_PREFIX = "gitlab";

  /** Same credentials scope as {@link GitlabStatusReporter}. */
  static final String CREDENTIALS_SCOPE = "gitlab-webhook";

  /**
   * Matches a {@code path/to/file.ext:LINE} token. The path must contain a dot-extension and at
   * least one path-ish character; the line is a positive integer. Deliberately conservative so
   * arbitrary "12:34" timestamps in a failure sentence don't get mistaken for a location.
   */
  private static final Pattern FILE_LINE = Pattern.compile("([\\w./-]+\\.[A-Za-z0-9]+):(\\d+)");

  /** Terminal statuses that warrant inline review comments (only the failing ones). */
  private static final java.util.Set<String> FAILED_STATUSES =
      java.util.Set.of("FAILED", "UNSTABLE");

  private final TitanStores stores;
  private final CredentialsService credentials;
  private final String gitlabBaseUrl;
  private final boolean featureEnabled;
  private final GitlabApiHttp api;

  @Inject
  public GitlabMrReviewReporter(
      TitanStores stores,
      CredentialsService credentials,
      @ConfigProperty(name = "gitlab.base-url", defaultValue = "https://gitlab.com")
          String gitlabBaseUrl,
      @ConfigProperty(name = "titan.gitlab.mr-reviews.enabled", defaultValue = "true")
          boolean featureEnabled,
      @NonNull GitlabApiHttp api) {
    this.stores = stores;
    this.credentials = credentials;
    this.gitlabBaseUrl = trimTrailingSlash(gitlabBaseUrl);
    this.featureEnabled = featureEnabled;
    this.api = api;
  }

  /**
   * Visible-for-testing constructor accepting an explicit {@link HttpClient}; wraps it in a {@link
   * GitlabApiHttp} so tests keep pointing the reporter at an in-process stub with one line.
   */
  GitlabMrReviewReporter(
      @NonNull TitanStores stores,
      @NonNull CredentialsService credentials,
      @NonNull String gitlabBaseUrl,
      boolean featureEnabled,
      @NonNull HttpClient http) {
    this(stores, credentials, gitlabBaseUrl, featureEnabled, new GitlabApiHttp(http));
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
          "[gitlab-mr-review] unexpected error reporting build {0} state {1}: {2}",
          new Object[] {event.buildId(), event.newStatus(), unexpected.getMessage()});
    }
  }

  /** Visible for direct invocation from tests. */
  void report(@NonNull BuildStateChangedEvent event) {
    String trigger = event.triggerType();
    if (trigger == null || !trigger.startsWith(GITLAB_TRIGGER_PREFIX)) {
      return;
    }
    if (!FAILED_STATUSES.contains(event.newStatus())) {
      return; // only failing terminal builds get inline review comments
    }

    @Nullable GitlabMrMeta meta = GitlabMrMeta.extract(event.triggerMetaJson());
    if (meta == null) {
      LOGGER.log(
          Level.FINE,
          "[gitlab-mr-review] skipping build {0}: not an addressable MR build",
          event.buildId());
      return;
    }

    List<Annotation> annotations = collectAnnotations(event.buildId());
    if (annotations.isEmpty()) {
      LOGGER.log(
          Level.FINE,
          "[gitlab-mr-review] build {0}: no failure mapped to a file:line — no inline comment",
          event.buildId());
      return;
    }

    Optional<String> tokenOpt = credentials.resolvePlaintext(CREDENTIALS_SCOPE, meta.credentialsId);
    if (tokenOpt.isEmpty() || tokenOpt.get().isEmpty()) {
      LOGGER.log(
          Level.WARNING,
          "[gitlab-mr-review] build {0}: credentialsId ''{1}'' did not resolve — skipping",
          new Object[] {event.buildId(), meta.credentialsId});
      return;
    }
    postDiscussions(meta, annotations, tokenOpt.get(), event.buildId());
  }

  // ── annotation extraction ────────────────────────────────────────────────────

  @NonNull
  private List<Annotation> collectAnnotations(long buildId) {
    List<FlowNodeRow> nodes;
    try {
      nodes = stores.flowNodes().listByBuild(buildId);
    } catch (RuntimeException dbFail) {
      LOGGER.log(
          Level.FINE,
          "[gitlab-mr-review] build {0}: flowNodes lookup failed: {1}",
          new Object[] {buildId, dbFail.getMessage()});
      return List.of();
    }
    List<Annotation> out = new ArrayList<>();
    for (FlowNodeRow n : nodes) {
      if (!"FAILED".equals(n.status) || n.failureReason == null) {
        continue;
      }
      @Nullable Annotation a = parseFileLine(n.failureReason);
      if (a != null) {
        out.add(a);
      }
    }
    return out;
  }

  /**
   * Extract the first {@code path:line} location from a failure sentence, or {@code null} when none
   * is present. Pure + side-effect-free so it is exhaustively unit-testable.
   */
  @Nullable
  static Annotation parseFileLine(@NonNull String failureReason) {
    Matcher m = FILE_LINE.matcher(failureReason);
    if (!m.find()) {
      return null;
    }
    String path = m.group(1);
    int line;
    try {
      line = Integer.parseInt(m.group(2));
    } catch (NumberFormatException e) {
      return null;
    }
    if (line <= 0) {
      return null;
    }
    return new Annotation(path, line, failureReason.trim());
  }

  // ── http ──────────────────────────────────────────────────────────────────

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void postDiscussions(
      @NonNull GitlabMrMeta meta,
      @NonNull List<Annotation> annotations,
      @NonNull String token,
      long buildId) {
    try {
      @Nullable DiffRefs refs = fetchDiffRefs(meta, token, buildId);
      if (refs == null) {
        LOGGER.log(
            Level.FINE,
            "[gitlab-mr-review] build {0} mr !{1}: no diff_refs — skipping inline comments",
            new Object[] {buildId, meta.mrIid});
        return;
      }
      String discussionsUrl =
          gitlabBaseUrl
              + "/api/v4/projects/"
              + meta.projectId
              + "/merge_requests/"
              + meta.mrIid
              + "/discussions";
      URI uri = URI.create(discussionsUrl);
      for (Annotation a : annotations) {
        String payload = discussionPayload(refs, a);
        HttpResponse<byte[]> resp = api.send("POST", uri, payload, token);
        int sc = resp.statusCode();
        if (sc < 200 || sc >= 300) {
          // SECURITY: code + body length only — never the token, never the response body.
          LOGGER.log(
              Level.WARNING,
              "[gitlab-mr-review] build {0} mr !{1}: discussion POST returned HTTP {2}"
                  + " (body {3} bytes)",
              new Object[] {buildId, meta.mrIid, sc, resp.body().length});
        }
      }
    } catch (IOException e) {
      LOGGER.log(
          Level.WARNING,
          "[gitlab-mr-review] build {0} mr !{1}: I/O error posting discussions: {2} — skipping",
          new Object[] {buildId, meta.mrIid, e.getMessage()});
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.log(
          Level.WARNING,
          "[gitlab-mr-review] build {0} mr !{1}: interrupted posting discussions — skipping",
          new Object[] {buildId, meta.mrIid});
    }
  }

  @Nullable
  private DiffRefs fetchDiffRefs(@NonNull GitlabMrMeta meta, @NonNull String token, long buildId)
      throws IOException, InterruptedException {
    URI uri =
        URI.create(
            gitlabBaseUrl + "/api/v4/projects/" + meta.projectId + "/merge_requests/" + meta.mrIid);
    HttpResponse<byte[]> resp = api.send("GET", uri, null, token);
    int sc = resp.statusCode();
    if (sc < 200 || sc >= 300) {
      LOGGER.log(
          Level.WARNING,
          "[gitlab-mr-review] build {0} mr !{1}: GET MR returned HTTP {2} (body {3} bytes)",
          new Object[] {buildId, meta.mrIid, sc, resp.body().length});
      return null;
    }
    JsonNode node = MAPPER.readTree(resp.body());
    JsonNode dr = node.path("diff_refs");
    if (!dr.isObject()) {
      return null;
    }
    String base = dr.path("base_sha").asText("");
    String start = dr.path("start_sha").asText("");
    String head = dr.path("head_sha").asText("");
    if (base.isEmpty() || start.isEmpty() || head.isEmpty()) {
      return null;
    }
    return new DiffRefs(base, start, head);
  }

  /** Build the discussion POST body: a human note + the full text {@code position}. */
  @NonNull
  static String discussionPayload(@NonNull DiffRefs refs, @NonNull Annotation a)
      throws IOException {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("body", ":x: " + a.message);
    ObjectNode pos = root.putObject("position");
    pos.put("position_type", "text");
    pos.put("base_sha", refs.baseSha);
    pos.put("start_sha", refs.startSha);
    pos.put("head_sha", refs.headSha);
    pos.put("new_path", a.path);
    pos.put("new_line", a.line);
    return MAPPER.writeValueAsString(root);
  }

  // ── value types ─────────────────────────────────────────────────────────────

  /** A single inline review comment: a diff location + the failure message to anchor there. */
  static final class Annotation {
    @NonNull final String path;
    final int line;
    @NonNull final String message;

    Annotation(@NonNull String path, int line, @NonNull String message) {
      this.path = path;
      this.line = line;
      this.message = message;
    }
  }

  /** The three SHAs GitLab requires on a text {@code position}. */
  static final class DiffRefs {
    @NonNull final String baseSha;
    @NonNull final String startSha;
    @NonNull final String headSha;

    DiffRefs(@NonNull String baseSha, @NonNull String startSha, @NonNull String headSha) {
      this.baseSha = baseSha;
      this.startSha = startSha;
      this.headSha = headSha;
    }
  }

  @NonNull
  private static String trimTrailingSlash(@NonNull String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}

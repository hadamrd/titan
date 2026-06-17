package io.adaptiq.titan.scm.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.JobRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Observes {@link BuildStateChangedEvent} and upserts a single sticky build-status <em>note</em> on
 * the GitLab merge request a build originated from (issue #1168). This brings the GitLab
 * self-hosted adapter to GitHub-adapter parity: {@link
 * io.adaptiq.titan.scm.github.GithubPrCommentReporter} already does this for GitHub PRs; before
 * this class GitLab users saw only the commit pipeline-status dot from {@link GitlabStatusReporter}
 * and nothing on the MR itself.
 *
 * <h2>Why this exists separately from {@link GitlabStatusReporter}</h2>
 *
 * The status reporter writes a {@code pending/running/success/failed} badge onto the commit — the
 * right surface for GitLab's pipeline dot but a poor surface for "which stage failed and where are
 * the logs". The MR note is the canonical place for a stage-by-stage summary (mirrors what CircleCI
 * / GitHub Actions do). Keeping the two reporters separate keeps each focused on one HTTP shape and
 * lets a tenant disable either independently ({@code titan.gitlab.mr-comments.enabled}).
 *
 * <h2>Dedupe — one sticky note per MR build sequence</h2>
 *
 * The note body always starts with the hidden marker {@value #COMMENT_MARKER}. On every reportable
 * transition we {@code GET} the MR's notes, find the one carrying the marker, and {@code PUT}
 * (update) it; only when none exists do we {@code POST} a fresh one. Result: the MR shows ONE
 * Titan-build note that edits in place across pending → running → pass/fail, never N duplicates on
 * re-runs. This mirrors GitHub's {@code <!-- titan-build-comment -->} list-and-match convention —
 * no DB schema change, no note-id column.
 *
 * <h2>Filter</h2>
 *
 * <ul>
 *   <li>{@code triggerType} must start with {@value #GITLAB_TRIGGER_PREFIX} (push / manual / cron
 *       builds never reach here).
 *   <li>{@code newStatus} must map to a reportable note status via {@link #mapStatus(String)};
 *       intermediate states ({@code PENDING}, {@code SLEEPING}, …) are a no-op.
 *   <li>{@code trigger_meta_json} must carry {@code mrIid} + {@code projectId} + {@code
 *       gitlabCredentialsId} (see {@link GitlabMrMeta}; {@code commitSha} is not required to post a
 *       note). A push build omits {@code mrIid} → no-op.
 * </ul>
 *
 * <h2>Error contract — CRITICAL</h2>
 *
 * Same contract as {@link GitlabStatusReporter}: every {@link IOException} / {@link
 * InterruptedException} / non-2xx response / runtime exception is caught at the observer boundary,
 * logged at {@code WARNING}, and swallowed. A cosmetic MR note MUST NEVER fail the build — the
 * status write has already committed before this observer runs.
 *
 * <h2>Security</h2>
 *
 * The {@code PRIVATE-TOKEN} resolved from {@link CredentialsService} NEVER appears in any log line,
 * not even on a {@code 401}. We log only the HTTP status code + response body length, never the
 * token and never the response body (GitLab error bodies occasionally echo header values).
 */
@ApplicationScoped
public class GitlabMrCommentReporter {

  private static final Logger LOGGER = Logger.getLogger(GitlabMrCommentReporter.class.getName());

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Trigger-type value emitted by {@code GitlabWebhookApi.enqueueBuild}. */
  static final String GITLAB_TRIGGER_PREFIX = "gitlab";

  /**
   * Hidden HTML marker placed at the very top of the note body so a {@code body.startsWith(...)}
   * match is cheap + unambiguous. Mirrors GitHub's {@code <!-- titan-build-comment -->}.
   */
  static final String COMMENT_MARKER = "<!-- titan-build-comment -->";

  /** Same credentials scope as {@link GitlabStatusReporter} (shared webhook + API entry, v1). */
  static final String CREDENTIALS_SCOPE = "gitlab-webhook";

  /** STAGE nodeType — filters the flow_nodes table down to the stage-level rollup. */
  private static final String STAGE_NODE_TYPE = "STAGE";

  private final TitanStores stores;
  private final CredentialsService credentials;
  private final String gitlabBaseUrl;
  private final String publicBaseUrl;
  private final boolean featureEnabled;
  private final GitlabApiHttp api;

  @Inject
  public GitlabMrCommentReporter(
      TitanStores stores,
      CredentialsService credentials,
      @ConfigProperty(name = "gitlab.base-url", defaultValue = "https://gitlab.com")
          String gitlabBaseUrl,
      @ConfigProperty(name = "titan.public-url", defaultValue = "http://localhost:8080")
          String publicBaseUrl,
      @ConfigProperty(name = "titan.gitlab.mr-comments.enabled", defaultValue = "true")
          boolean featureEnabled,
      @NonNull GitlabApiHttp api) {
    this.stores = stores;
    this.credentials = credentials;
    this.gitlabBaseUrl = trimTrailingSlash(gitlabBaseUrl);
    this.publicBaseUrl = trimTrailingSlash(publicBaseUrl);
    this.featureEnabled = featureEnabled;
    this.api = api;
  }

  /**
   * Visible-for-testing constructor accepting an explicit {@link HttpClient}; wraps it in a {@link
   * GitlabApiHttp} so tests keep pointing the reporter at an in-process stub with one line.
   */
  GitlabMrCommentReporter(
      @NonNull TitanStores stores,
      @NonNull CredentialsService credentials,
      @NonNull String gitlabBaseUrl,
      @NonNull String publicBaseUrl,
      boolean featureEnabled,
      @NonNull HttpClient http) {
    this(
        stores, credentials, gitlabBaseUrl, publicBaseUrl, featureEnabled, new GitlabApiHttp(http));
  }

  // ── observer ───────────────────────────────────────────────────────────────

  /** CDI observer — same defence-in-depth try/catch as {@link GitlabStatusReporter}. */
  public void onBuildStateChanged(@Observes @NonNull BuildStateChangedEvent event) {
    if (!featureEnabled) {
      return;
    }
    try {
      report(event);
    } catch (RuntimeException unexpected) {
      LOGGER.log(
          Level.WARNING,
          "[gitlab-mr-comment] unexpected error reporting build {0} state {1}: {2}",
          new Object[] {event.buildId(), event.newStatus(), unexpected.getMessage()});
    }
  }

  /** Visible for direct invocation from tests. */
  void report(@NonNull BuildStateChangedEvent event) {
    String trigger = event.triggerType();
    if (trigger == null || !trigger.startsWith(GITLAB_TRIGGER_PREFIX)) {
      return;
    }
    Optional<String> noteStatus = mapStatus(event.newStatus());
    if (noteStatus.isEmpty()) {
      return; // intermediate state we don't report on
    }

    @Nullable GitlabMrMeta meta = GitlabMrMeta.extract(event.triggerMetaJson());
    if (meta == null) {
      LOGGER.log(
          Level.FINE,
          "[gitlab-mr-comment] skipping build {0}: not an addressable MR build"
              + " (missing mrIid / projectId / gitlabCredentialsId)",
          event.buildId());
      return;
    }

    Optional<String> tokenOpt = credentials.resolvePlaintext(CREDENTIALS_SCOPE, meta.credentialsId);
    if (tokenOpt.isEmpty() || tokenOpt.get().isEmpty()) {
      LOGGER.log(
          Level.WARNING,
          "[gitlab-mr-comment] build {0}: credentialsId ''{1}'' did not resolve — skipping",
          new Object[] {event.buildId(), meta.credentialsId});
      return;
    }

    JobRow job = stores.jobs().findById(event.jobId()).orElse(null);
    BuildRow build = stores.builds().findById(event.buildId()).orElse(null);
    List<FlowNodeRow> nodes = listNodesBestEffort(event.buildId());

    String body = renderBody(job, build, nodes, noteStatus.get(), event.buildId());
    upsertNote(meta, body, tokenOpt.get(), event.buildId());
  }

  @NonNull
  private List<FlowNodeRow> listNodesBestEffort(long buildId) {
    try {
      return stores.flowNodes().listByBuild(buildId);
    } catch (RuntimeException dbFail) {
      LOGGER.log(
          Level.FINE,
          "[gitlab-mr-comment] build {0}: flowNodes lookup failed: {1}",
          new Object[] {buildId, dbFail.getMessage()});
      return List.of();
    }
  }

  // ── http ──────────────────────────────────────────────────────────────────

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void upsertNote(
      @NonNull GitlabMrMeta meta, @NonNull String body, @NonNull String token, long buildId) {
    try {
      @Nullable Long existingId = findMarkerNoteId(meta, token, buildId);
      String notesBase =
          gitlabBaseUrl
              + "/api/v4/projects/"
              + meta.projectId
              + "/merge_requests/"
              + meta.mrIid
              + "/notes";
      String payload = MAPPER.writeValueAsString(java.util.Map.of("body", body));
      if (existingId != null) {
        URI uri = URI.create(notesBase + "/" + existingId);
        logIfNotSuccessful("PUT", uri, api.send("PUT", uri, payload, token), buildId);
        LOGGER.log(
            Level.FINE,
            "[gitlab-mr-comment] build {0} mr !{1}: updated note {2}",
            new Object[] {buildId, meta.mrIid, existingId});
      } else {
        URI uri = URI.create(notesBase);
        logIfNotSuccessful("POST", uri, api.send("POST", uri, payload, token), buildId);
        LOGGER.log(
            Level.FINE,
            "[gitlab-mr-comment] build {0} mr !{1}: posted new note",
            new Object[] {buildId, meta.mrIid});
      }
    } catch (IOException e) {
      LOGGER.log(
          Level.WARNING,
          "[gitlab-mr-comment] build {0} mr !{1}: I/O error upserting note: {2} — skipping",
          new Object[] {buildId, meta.mrIid, e.getMessage()});
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.log(
          Level.WARNING,
          "[gitlab-mr-comment] build {0} mr !{1}: interrupted upserting note — skipping",
          new Object[] {buildId, meta.mrIid});
    }
  }

  /**
   * Walk the MR notes (paginated) and return the id of the first note whose body carries {@link
   * #COMMENT_MARKER}, or {@code null} when none exists / the list call fails non-fatally.
   *
   * <p>Pagination is REQUIRED for correct dedupe (issue #1168 review): GitLab caps {@code per_page}
   * at 100, and on a long-lived MR with &gt;100 notes the sticky Titan note falls off page 1. If we
   * stopped at page 1 we would miss it and POST a fresh duplicate on every later build-state event
   * — violating the "never duplicated on re-runs" acceptance criterion. We follow the {@code
   * X-Next-Page} header (bounded by {@link GitlabApiHttp#MAX_PAGES}) and stop early the moment the
   * marker note is found.
   */
  @Nullable
  private Long findMarkerNoteId(@NonNull GitlabMrMeta meta, @NonNull String token, long buildId)
      throws IOException, InterruptedException {
    String notesBase =
        gitlabBaseUrl
            + "/api/v4/projects/"
            + meta.projectId
            + "/merge_requests/"
            + meta.mrIid
            + "/notes";
    int page = 1;
    for (int walked = 0; walked < GitlabApiHttp.MAX_PAGES; walked++) {
      URI uri = URI.create(notesBase + "?per_page=100&page=" + page);
      HttpResponse<byte[]> resp = api.send("GET", uri, null, token);
      int sc = resp.statusCode();
      if (sc < 200 || sc >= 300) {
        // Couldn't list — fall through to POST so the user at least gets a note. Worst case a
        // transient list error spawns a duplicate; logged so it's diagnosable.
        LOGGER.log(
            Level.WARNING,
            "[gitlab-mr-comment] build {0} mr !{1}: list-notes returned HTTP {2} (body {3} bytes)"
                + " — treating as no existing note",
            new Object[] {buildId, meta.mrIid, sc, resp.body().length});
        return null;
      }
      JsonNode arr = MAPPER.readTree(resp.body());
      if (arr != null && arr.isArray()) {
        for (JsonNode note : arr) {
          String nbody = note.path("body").asText("");
          if (nbody.startsWith(COMMENT_MARKER)) {
            long id = note.path("id").asLong(0L);
            if (id > 0L) {
              return id;
            }
          }
        }
      }
      int next = GitlabApiHttp.nextPage(resp);
      if (next <= 0 || next == page) {
        break;
      }
      page = next;
    }
    return null;
  }

  /**
   * SECURITY: on a non-2xx write, log the method + redacted path + status code + body length only.
   * The token MUST NEVER appear; the response body is withheld too because GitLab error responses
   * occasionally echo header bytes.
   */
  private void logIfNotSuccessful(
      @NonNull String method, @NonNull URI uri, @NonNull HttpResponse<byte[]> resp, long buildId) {
    int sc = resp.statusCode();
    if (sc < 200 || sc >= 300) {
      LOGGER.log(
          Level.WARNING,
          "[gitlab-mr-comment] build {0}: {1} {2} returned HTTP {3} (body {4} bytes) — skipping",
          new Object[] {buildId, method, GitlabApiHttp.redactPath(uri), sc, resp.body().length});
    }
  }

  // ── rendering ─────────────────────────────────────────────────────────────

  /**
   * Render the sticky note body. Mirrors {@code GithubPrCommentReporter.renderBody}: marker line,
   * header ({@code Titan / <pipeline> — <emoji> <STATUS> (<duration>)}), a per-stage table, and a
   * deep-link to {@code …/builds/{id}}. {@code noteStatus} is the GitLab-mapped status string
   * (pending / running / success / failed / canceled).
   */
  @NonNull
  String renderBody(
      @Nullable JobRow job,
      @Nullable BuildRow build,
      @NonNull List<FlowNodeRow> nodes,
      @NonNull String noteStatus,
      long buildId) {
    String pipelineName = pipelineName(job);
    String emoji = statusEmoji(noteStatus);
    String duration = formatDuration(build);
    String rigBuildUrl = publicBaseUrl + "/builds/" + buildId;

    StringBuilder sb = new StringBuilder(512);
    sb.append(COMMENT_MARKER).append('\n');
    sb.append("### Titan / ")
        .append(pipelineName)
        .append(" — ")
        .append(emoji)
        .append(' ')
        .append(noteStatus)
        .append(" (")
        .append(duration)
        .append(")\n\n");
    sb.append("| Stage | Status | Duration |\n");
    sb.append("|---|---|---|\n");
    for (FlowNodeRow node : nodes) {
      if (!STAGE_NODE_TYPE.equals(node.nodeType)) {
        continue;
      }
      String stageName =
          node.displayName != null && !node.displayName.isEmpty() ? node.displayName : node.nodeId;
      sb.append("| ")
          .append(escapeCell(stageName))
          .append(" | ")
          .append(nodeStatusEmoji(node.status))
          .append(' ')
          .append(node.status)
          .append(" | ")
          .append(formatNodeDuration(node))
          .append(" |\n");
    }
    sb.append('\n');
    sb.append("[View logs and artifacts →](").append(rigBuildUrl).append(")\n");
    return sb.toString();
  }

  // ── status mapping ──────────────────────────────────────────────────────────

  /**
   * Map a Titan build status onto the GitLab note status string, or {@link Optional#empty()} for an
   * intermediate state we don't surface on the MR. Mirrors {@link
   * GitlabStatusReporter#mapStatus(String)} but distinguishes {@code QUEUED} ("pending") from
   * {@code RUNNING} ("running") so the note body shows the full pending → running → pass/fail
   * progression.
   */
  @NonNull
  static Optional<String> mapStatus(@NonNull String status) {
    return switch (status) {
      case "QUEUED" -> Optional.of("pending");
      case "RUNNING" -> Optional.of("running");
      case "SUCCESS" -> Optional.of("success");
      case "FAILED", "UNSTABLE" -> Optional.of("failed");
      case "ABORTED", "CANCELLED" -> Optional.of("canceled");
      default -> Optional.empty();
    };
  }

  @NonNull
  private static String pipelineName(@Nullable JobRow job) {
    if (job == null) {
      return "build";
    }
    if (job.displayName != null && !job.displayName.isEmpty()) {
      return job.displayName;
    }
    return job.fullName != null ? job.fullName : "build";
  }

  /** Emoji for the top-level GitLab note status string. */
  @NonNull
  static String statusEmoji(@NonNull String noteStatus) {
    return switch (noteStatus) {
      case "success" -> ":white_check_mark:";
      case "failed" -> ":x:";
      case "canceled" -> ":no_entry:";
      case "running" -> ":hourglass_flowing_sand:";
      case "pending" -> ":clock3:";
      default -> ":grey_question:";
    };
  }

  /** Emoji for a per-stage Titan node status (FAILED / SUCCESS / …). */
  @NonNull
  static String nodeStatusEmoji(@Nullable String status) {
    if (status == null) {
      return ":grey_question:";
    }
    return switch (status) {
      case "SUCCESS" -> ":white_check_mark:";
      case "FAILED", "CANCELLED" -> ":x:";
      case "ABORTED" -> ":no_entry:";
      case "SKIPPED" -> ":fast_forward:";
      case "UNSTABLE" -> ":warning:";
      case "RUNNING" -> ":hourglass_flowing_sand:";
      case "QUEUED", "PENDING" -> ":clock3:";
      default -> ":grey_question:";
    };
  }

  @NonNull
  private static String formatDuration(@Nullable BuildRow build) {
    if (build == null) {
      return "—";
    }
    if (build.durationMs != null) {
      return formatMillis(build.durationMs);
    }
    Instant start = build.startedAt;
    Instant end = build.finishedAt;
    if (start == null || end == null) {
      return "—";
    }
    return formatMillis(Duration.between(start, end).toMillis());
  }

  @NonNull
  private static String formatNodeDuration(@NonNull FlowNodeRow node) {
    if (node.durationMs != null) {
      return formatMillis(node.durationMs);
    }
    if (node.startedAt == null || node.completedAt == null) {
      return "—";
    }
    return formatMillis(Duration.between(node.startedAt, node.completedAt).toMillis());
  }

  @NonNull
  static String formatMillis(long ms) {
    if (ms < 0) {
      return "—";
    }
    if (ms < 1000) {
      return ms + "ms";
    }
    long totalSeconds = ms / 1000;
    if (totalSeconds < 60) {
      return totalSeconds + "s";
    }
    long minutes = totalSeconds / 60;
    long seconds = totalSeconds % 60;
    if (minutes < 60) {
      return String.format(Locale.ROOT, "%dm %ds", minutes, seconds);
    }
    long hours = minutes / 60;
    long remMinutes = minutes % 60;
    return String.format(Locale.ROOT, "%dh %dm", hours, remMinutes);
  }

  /** Pipe characters break GitLab's markdown tables — escape them and any stray newlines. */
  @NonNull
  private static String escapeCell(@NonNull String cell) {
    return cell.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
  }

  @NonNull
  private static String trimTrailingSlash(@NonNull String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}

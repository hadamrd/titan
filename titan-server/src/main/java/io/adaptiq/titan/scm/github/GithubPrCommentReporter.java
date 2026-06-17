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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.HttpException;

/**
 * Observes {@link BuildStateChangedEvent} terminal transitions and posts (or updates) a single
 * sticky PR comment summarising the build (closes #966).
 *
 * <h2>Why this exists separately from {@link GithubStatusReporter}</h2>
 *
 * The commit-status reporter writes a pass/fail badge per transition; that is the right surface for
 * a row in GitHub's PR-checks UI but a poor surface for "tell me which stage failed and where the
 * logs are". PR comments are the canonical place for stage-by-stage summaries (CircleCI, Buildkite,
 * GitHub Actions all do this). Splitting the two reporters keeps each one focused on one HTTP shape
 * — and lets a tenant turn either off independently via {@code titan.github.pr-comments.enabled}.
 *
 * <h2>Dedupe — single sticky comment per PR build sequence</h2>
 *
 * The comment body always starts with the hidden marker {@value #COMMENT_MARKER}. On every terminal
 * event we list the PR's issue comments, find the one authored by the App that carries the marker,
 * and PATCH it; only when none exists do we POST a fresh one. Result: a PR thread shows ONE
 * Titan-build comment that edits in place across synchronise pushes, not N stale comments.
 *
 * <h2>Filter</h2>
 *
 * <ul>
 *   <li>{@code triggerMetaJson} must carry a positive {@code prNumber} (push builds → no-op).
 *   <li>{@code newStatus} must be a terminal status (SUCCESS / FAILED / ABORTED / CANCELLED /
 *       UNSTABLE / SKIPPED). Intermediate transitions are ignored.
 *   <li>Job must have a resolvable {@code (owner, repo)} via {@link
 *       io.adaptiq.titan.store.JobDao#findGithubLinkage(long)}.
 *   <li>Feature flag {@code titan.github.pr-comments.enabled} must be true (default true).
 * </ul>
 *
 * <h2>Error contract</h2>
 *
 * Same as {@link GithubStatusReporter}: every {@link IOException} / {@link GithubApiException} /
 * runtime exception is caught at the observer boundary, logged at WARNING, and swallowed. A
 * cosmetic comment MUST NEVER fail a build.
 */
@ApplicationScoped
public class GithubPrCommentReporter {

  private static final Logger LOGGER = Logger.getLogger(GithubPrCommentReporter.class.getName());

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Hidden HTML comment used to identify Titan's PR comment across builds. Lives at the very top of
   * the body so a {@code body.startsWith(...)} check is cheap and unambiguous.
   */
  static final String COMMENT_MARKER = "<!-- titan-build-comment -->";

  /** Terminal statuses that warrant a PR-comment update. */
  private static final java.util.Set<String> TERMINAL_STATUSES =
      java.util.Set.of("SUCCESS", "FAILED", "ABORTED", "CANCELLED", "UNSTABLE", "SKIPPED");

  /** STAGE nodeType — used to filter the flow_nodes table down to the stage-level rollup. */
  private static final String STAGE_NODE_TYPE = "STAGE";

  private final TitanStores stores;
  private final GithubAppService appService;
  private final GithubClientFactory clientFactory;
  private final String publicBaseUrl;
  private final boolean featureEnabled;

  @Inject
  public GithubPrCommentReporter(
      TitanStores stores,
      GithubAppService appService,
      GithubClientFactory clientFactory,
      @ConfigProperty(name = "titan.public-url", defaultValue = "http://localhost:8080")
          String publicBaseUrl,
      @ConfigProperty(name = "titan.github.pr-comments.enabled", defaultValue = "true")
          boolean featureEnabled) {
    this.stores = stores;
    this.appService = appService;
    this.clientFactory = clientFactory;
    this.publicBaseUrl = trimTrailingSlash(publicBaseUrl);
    this.featureEnabled = featureEnabled;
  }

  // ── observer ───────────────────────────────────────────────────────────────

  /** CDI observer — same defence-in-depth try/catch as {@link GithubStatusReporter}. */
  public void onBuildStateChanged(@Observes @NonNull BuildStateChangedEvent event) {
    if (!featureEnabled) {
      return;
    }
    try {
      report(event);
    } catch (RuntimeException unexpected) {
      LOGGER.log(
          Level.WARNING,
          "[github-pr-comment] unexpected error reporting build {0} state {1}: {2}",
          new Object[] {event.buildId(), event.newStatus(), unexpected.getMessage()});
    }
  }

  /** Visible for direct invocation from tests. */
  void report(@NonNull BuildStateChangedEvent event) {
    if (!TERMINAL_STATUSES.contains(event.newStatus())) {
      return; // only post on terminal transitions
    }

    @Nullable Integer prNumber = extractPrNumber(event.triggerMetaJson());
    if (prNumber == null) {
      // Push build, manual build, cron, or otherwise non-PR — nothing to comment on.
      return;
    }

    Optional<JobGithubLinkRow> linkOpt = stores.jobs().findGithubLinkage(event.jobId());
    if (linkOpt.isEmpty()) {
      LOGGER.log(
          Level.FINE,
          "[github-pr-comment] skipping build {0}: job {1} has no GitHub-App linkage",
          new Object[] {event.buildId(), event.jobId()});
      return;
    }
    JobGithubLinkRow link = linkOpt.get();

    Optional<JobRow> jobOpt = stores.jobs().findById(event.jobId());
    Optional<BuildRow> buildOpt = stores.builds().findById(event.buildId());
    List<FlowNodeRow> nodes = stores.flowNodes().listByBuild(event.buildId());

    String body =
        renderBody(
            jobOpt.orElse(null), buildOpt.orElse(null), nodes, event.newStatus(), event.buildId());

    try {
      upsertComment(link, prNumber, body, event.buildId());
    } catch (HttpException e) {
      LOGGER.log(
          Level.WARNING,
          "[github-pr-comment] build {0} pr #{1}: GitHub HTTP {2}: {3} — skipping",
          new Object[] {event.buildId(), prNumber, e.getResponseCode(), e.getMessage()});
    } catch (IOException e) {
      LOGGER.log(
          Level.WARNING,
          "[github-pr-comment] build {0} pr #{1}: I/O error: {2} — skipping",
          new Object[] {event.buildId(), prNumber, e.getMessage()});
    } catch (GithubApiException e) {
      LOGGER.log(
          Level.WARNING,
          "[github-pr-comment] build {0} pr #{1}: install-token mint failed (HTTP {2}): {3} — skipping",
          new Object[] {event.buildId(), prNumber, e.status(), e.getMessage()});
    }
  }

  // ── http ──────────────────────────────────────────────────────────────────

  private void upsertComment(
      @NonNull JobGithubLinkRow link, int prNumber, @NonNull String body, long buildId)
      throws IOException {
    String token = appService.getInstallationToken(link.installId);
    GitHub client = clientFactory.asInstallation(token);
    GHRepository repo = client.getRepository(link.owner + "/" + link.name);
    GHIssue issue = repo.getIssue(prNumber);

    // Iterate the comment list and pick the first one whose body carries our marker.
    @Nullable GHIssueComment existing = null;
    for (GHIssueComment c : issue.listComments()) {
      String cbody = c.getBody();
      if (cbody != null && cbody.startsWith(COMMENT_MARKER)) {
        existing = c;
        break;
      }
    }

    if (existing != null) {
      existing.update(body);
      LOGGER.log(
          Level.FINE,
          "[github-pr-comment] build {0} pr #{1}: patched comment {2}",
          new Object[] {buildId, prNumber, existing.getId()});
    } else {
      GHIssueComment created = issue.comment(body);
      LOGGER.log(
          Level.FINE,
          "[github-pr-comment] build {0} pr #{1}: posted new comment {2}",
          new Object[] {buildId, prNumber, created.getId()});
    }
  }

  // ── rendering ─────────────────────────────────────────────────────────────

  @NonNull
  String renderBody(
      @Nullable JobRow job,
      @Nullable BuildRow build,
      @NonNull List<FlowNodeRow> nodes,
      @NonNull String status,
      long buildId) {
    String pipelineName = pipelineName(job);
    String emoji = statusEmoji(status);
    String duration = formatDuration(build);
    String rigBuildUrl = publicBaseUrl + "/builds/" + buildId;

    StringBuilder sb = new StringBuilder(512);
    sb.append(COMMENT_MARKER).append('\n');
    sb.append("### Titan / ")
        .append(pipelineName)
        .append(" — ")
        .append(emoji)
        .append(' ')
        .append(status)
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
          .append(statusEmoji(node.status))
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

  @NonNull
  static String statusEmoji(@Nullable String status) {
    if (status == null) return ":grey_question:";
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
    if (build == null) return "—";
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
    if (ms < 0) return "—";
    if (ms < 1000) return ms + "ms";
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

  @Nullable
  static Integer extractPrNumber(@Nullable String triggerMetaJson) {
    if (triggerMetaJson == null || triggerMetaJson.isBlank()) {
      return null;
    }
    try {
      JsonNode node = MAPPER.readTree(triggerMetaJson);
      int n = node.path("prNumber").asInt(0);
      return n > 0 ? n : null;
    } catch (IOException e) {
      return null;
    }
  }

  /** Pipe characters break GitHub's markdown tables — escape them and any stray newlines. */
  @NonNull
  private static String escapeCell(@NonNull String cell) {
    return cell.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
  }

  @NonNull
  private static String trimTrailingSlash(@NonNull String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}

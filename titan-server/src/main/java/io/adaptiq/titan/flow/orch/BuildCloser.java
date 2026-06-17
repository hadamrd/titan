package io.adaptiq.titan.flow.orch;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.cache.PipelineModelCache;
import io.adaptiq.titan.flow.NotificationContext;
import io.adaptiq.titan.flow.NotificationDispatcher;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.JobRow;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Single-purpose collaborator that closes a build at terminal state — extracted from {@code
 * TitanOrchestrator#finishIfDone} (design 67 step 5). The terminal write is a chokepoint with five
 * tightly-coupled side effects:
 *
 * <ol>
 *   <li>Persist the terminal {@code status} + {@code finished_at} + {@code duration_ms} via {@code
 *       BuildDao.updateStatus}.
 *   <li>Invalidate {@link PipelineModelCache} for {@code buildId} so a subsequent reader does not
 *       observe a stale parse.
 *   <li>Log the {@code [titan] build N finished: X} terminal line.
 *   <li>Best-effort fire of the {@link BuildStateChangedEvent} via programmatic Arc lookup — the
 *       orchestrator's terminal write bypasses {@code BuildServiceImpl.update} so observers like
 *       {@code GithubStatusReporter} would otherwise never see terminal transitions (#895).
 *   <li>Best-effort fan-out of declarative {@code notify:} pipeline-level hooks (#245).
 * </ol>
 *
 * <p>Idempotence: a build whose row already carries a terminal status (a previous {@code close}
 * call won the race) is a no-op — the second caller skips the write, the cache invalidate, the CDI
 * event, the log line, and the notification fan-out. This pins the contract the orchestrator's
 * reconciler loop relies on: a re-tick after a terminal write must not double-fire side effects.
 *
 * <p>Safety: every step that escapes the engine (CDI event dispatch, notify hooks) is wrapped in a
 * {@code try/catch} that logs at FINE / WARNING. The DB write is intentionally NOT wrapped — a
 * persistence failure must surface to the orchestrator's outer try/catch around the terminal block
 * (the existing recovery semantics).
 */
public final class BuildCloser {

  private static final Logger LOGGER = Logger.getLogger(BuildCloser.class.getName());

  private static final Set<String> TERMINAL_BUILD_STATUSES =
      Set.of("SUCCESS", "FAILED", "ABORTED", "UNSTABLE");

  /**
   * Shared bounded daemon executor for async failure classification (#1105). A single worker thread
   * serializes classification so a correlated failure burst (an upstream outage failing many queued
   * builds at once) can no longer spawn thread-per-event; the bounded queue caps the backlog and
   * silently discards surplus work (failure-cause is best-effort enrichment only).
   */
  private static final ThreadPoolExecutor CLASSIFIER_EXECUTOR =
      new ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(256),
          r -> {
            Thread t = new Thread(r, "titan-failure-classifier");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.DiscardPolicy());

  private final TitanStores daos;
  @Nullable private final NotificationDispatcher notifications;

  /**
   * The {@code titan.public-url} resolved at construction time — used to build the deep-link in the
   * notify-hook payload (#1102). Resolved best-effort from MicroProfile {@link ConfigProvider};
   * {@code null} in unit-test contexts where MP-Config isn't installed, in which case the deep-link
   * is simply omitted from the payload.
   */
  @Nullable private final String publicBaseUrl;

  /**
   * @param daos engine stores (build DAO target).
   * @param notifications hook dispatcher; {@code null} is tolerated — slack/webhook fan-out is
   *     simply skipped (production wiring always passes a real instance, see {@code
   *     TitanOrchestrator}'s default constructor).
   */
  public BuildCloser(@NonNull TitanStores daos, @Nullable NotificationDispatcher notifications) {
    this(daos, notifications, lookupPublicBaseUrl());
  }

  /**
   * Test seam — inject an explicit public-base-url (e.g. {@code http://localhost:0} for a local
   * sink in an IT). Production code uses the default constructor which reads {@code
   * titan.public-url} from MP-Config.
   */
  public BuildCloser(
      @NonNull TitanStores daos,
      @Nullable NotificationDispatcher notifications,
      @Nullable String publicBaseUrl) {
    this.daos = daos;
    this.notifications = notifications;
    this.publicBaseUrl = trimToNull(publicBaseUrl);
  }

  /**
   * Best-effort lookup of the {@code titan.public-url} config — same property the SCM reporters use
   * (#1102 deep-link). Returns {@code null} when MP-Config isn't available (raw JUnit) or the key
   * is unset: the dispatcher then simply omits the {@code url} field from the notify payload.
   */
  @Nullable
  private static String lookupPublicBaseUrl() {
    try {
      return ConfigProvider.getConfig()
          .getOptionalValue("titan.public-url", String.class)
          .map(BuildCloser::trimToNull)
          .orElse(null);
    } catch (RuntimeException e) {
      // MP-Config not registered (raw JUnit) — fine, deep-link is optional.
      return null;
    }
  }

  @Nullable
  private static String trimToNull(@Nullable String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    if (t.isEmpty()) {
      return null;
    }
    // Strip trailing slash so concatenation is stable.
    return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
  }

  /**
   * Persist the terminal status, invalidate the pipeline-model cache, log, fire the CDI state-
   * change event, and fan out declarative notify hooks. Idempotent — a build whose row is already
   * terminal returns immediately without re-firing any side effect.
   *
   * @param buildId build to close.
   * @param result terminal status — {@code SUCCESS} or {@code FAILED} (the orchestrator's two
   *     labels at this chokepoint).
   * @param model the synthesised pipeline model (used for the notify-hook fan-out).
   */
  public void close(long buildId, @NonNull String result, @NonNull PipelineModel model) {
    BuildRow fresh = daos.builds().findById(buildId).orElse(null);
    if (fresh == null) {
      LOGGER.log(
          Level.WARNING,
          "[titan] BuildCloser.close: build {0} not found — terminal write skipped",
          buildId);
      return;
    }
    if (TERMINAL_BUILD_STATUSES.contains(fresh.status)) {
      // Idempotence: a previous close() won the race — the row is already terminal. Skip every
      // side effect (cache invalidate, log line, CDI event, notify hooks) so a re-tick after a
      // terminal write cannot double-fire.
      return;
    }

    // Compute duration from the build's existing started_at (set at bake by
    // BuildDao.activateIfQueued). If started_at is somehow null — a degenerate build that closed
    // without ever transitioning to RUNNING — back-fill it to finishedAt so the UI shows duration
    // = 0 rather than the "—" sentinel for a build that demonstrably ran.
    Instant finishedAt = Instant.now();
    Instant startedAt = fresh.startedAt != null ? fresh.startedAt : finishedAt;
    long durationMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli();

    daos.builds().updateStatus(buildId, result, startedAt, finishedAt, durationMs, null);
    // Terminal write: drop any cached PipelineModel for this build.
    PipelineModelCache.invalidateIfActive(buildId);
    LOGGER.log(Level.INFO, "[titan] build {0} finished: {1}", new Object[] {buildId, result});

    // #835 follow-up: terminal writes bypass BuildServiceImpl.update (where the CDI event is
    // normally fired), so observers like GithubStatusReporter would never see terminal
    // transitions. Until that architectural gap is closed (orchestrator routes through
    // BuildService), fire the event here too via programmatic Arc lookup. Best-effort — never
    // blocks the terminal write.
    fireStateChangedEvent(buildId, result);

    // #1105: diagnose the root cause of a FAILED build off the critical path. Runs on a daemon
    // thread AFTER the terminal write above is durable, so a slow log scan never delays the build
    // closing and a classifier crash can't corrupt build state. Best-effort enrichment only.
    if ("FAILED".equals(result)) {
      classifyFailureAsync(buildId);
    }

    // #245 / #1102: fire declarative `notify:` lifecycle hooks. Best-effort, fire-and-forget —
    // the dispatcher swallows every delivery error so a slow / unreachable sink never aborts the
    // terminal write. Hooks fire regardless of which step ran.
    //
    // #1102 — pre-compute the rich notification context once (previous build outcome, job name,
    // duration, failed-stage, deep-link) and hand it to the dispatcher. The dispatcher never calls
    // back into the DB while iterating hooks, so N hooks cost one DAO round-trip.
    if (notifications != null) {
      try {
        NotificationContext ctx =
            buildNotificationContext(buildId, result, fresh, durationMs, finishedAt);
        notifications.fireBuildHooks(ctx, model);
      } catch (RuntimeException e) {
        LOGGER.log(
            Level.WARNING,
            "[titan] build {0}: notify hook dispatch threw (swallowed)",
            new Object[] {buildId, e});
      }
    }
  }

  /**
   * Resolve the rich notify-context (#1102): previous finished build status (recovery /
   * first-failure detection), job display name, build duration, first failed-stage name, and the
   * deep-link to the build-detail page. Every lookup is wrapped — a DAO miss collapses to a {@code
   * null} field on the context (the dispatcher's payload then omits that key) so a degenerate
   * record never aborts the notify path.
   */
  @NonNull
  private NotificationContext buildNotificationContext(
      long buildId,
      @NonNull String result,
      @NonNull BuildRow fresh,
      long durationMs,
      @NonNull Instant finishedAt) {
    String previousResult = null;
    try {
      previousResult =
          daos.builds()
              .findPreviousFinishedForJob(fresh.jobId, buildId)
              .map(b -> b.status)
              .orElse(null);
    } catch (RuntimeException dbFail) {
      LOGGER.log(
          Level.FINE,
          "[titan] build {0}: previous-build lookup failed for notify context: {1}",
          new Object[] {buildId, dbFail.getMessage()});
    }

    String jobName = null;
    try {
      JobRow job = daos.jobs().findById(fresh.jobId).orElse(null);
      if (job != null) {
        if (job.displayName != null && !job.displayName.isBlank()) {
          jobName = job.displayName;
        } else if (job.fullName != null && !job.fullName.isBlank()) {
          jobName = job.fullName;
        }
      }
    } catch (RuntimeException dbFail) {
      LOGGER.log(
          Level.FINE,
          "[titan] build {0}: job lookup failed for notify context: {1}",
          new Object[] {buildId, dbFail.getMessage()});
    }

    String failedStage = null;
    if ("FAILED".equals(result)) {
      try {
        List<FlowNodeRow> nodes = daos.flowNodes().listByBuild(buildId);
        for (FlowNodeRow n : nodes) {
          if ("FAILED".equals(n.status)) {
            failedStage =
                (n.displayName != null && !n.displayName.isBlank()) ? n.displayName : n.nodeId;
            break;
          }
        }
      } catch (RuntimeException dbFail) {
        LOGGER.log(
            Level.FINE,
            "[titan] build {0}: flow-nodes lookup failed for notify context: {1}",
            new Object[] {buildId, dbFail.getMessage()});
      }
    }

    String deepLink = publicBaseUrl != null ? publicBaseUrl + "/builds/" + buildId : null;
    Long durationOpt = durationMs > 0L ? durationMs : null;
    return NotificationContext.of(
        buildId, result, previousResult, jobName, durationOpt, failedStage, deepLink);
  }

  /**
   * Submit the build-failure root-cause classification (#1105) to the shared bounded daemon
   * executor so it never blocks the orchestrator's terminal transition. The classifier itself
   * swallows its own errors; this wrapper additionally guards submission failures so a
   * misconfigured signatures file degrades to "no cause badge", never an aborted build close.
   */
  private void classifyFailureAsync(long buildId) {
    try {
      CLASSIFIER_EXECUTOR.execute(
          () -> BuildFailureClassifier.defaultInstance().classifyAndStore(daos, buildId));
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.FINE,
          "[titan] build {0}: failure-classifier dispatch failed (swallowed): {1}",
          new Object[] {buildId, e.getMessage()});
    }
  }

  /**
   * Best-effort programmatic-Arc emit of {@link BuildStateChangedEvent}. The lookup is wrapped in a
   * try/catch because the orchestrator runs from a raw JUnit context in unit tests (no CDI
   * container) and {@code Arc.container()} throws {@code IllegalStateException} in that case (PR
   * #895).
   */
  private void fireStateChangedEvent(long buildId, @NonNull String terminalStatus) {
    try {
      daos.builds()
          .findById(buildId)
          .ifPresent(
              fresh -> {
                BuildStateChangedEvent evt =
                    new BuildStateChangedEvent(
                        fresh.id,
                        terminalStatus,
                        fresh.triggerType,
                        fresh.triggerMetaJson,
                        fresh.jobId,
                        fresh.buildNumber);
                io.quarkus.arc.Arc.container()
                    .beanManager()
                    .getEvent()
                    .select(BuildStateChangedEvent.class)
                    .fire(evt);
              });
    } catch (RuntimeException ee) {
      LOGGER.log(
          Level.FINE,
          "[titan] build {0}: terminal state-change event dispatch failed: {1}",
          new Object[] {buildId, ee.getMessage()});
    }
  }
}

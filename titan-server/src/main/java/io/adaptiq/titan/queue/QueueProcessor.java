package io.adaptiq.titan.queue;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.NotificationDispatcher;
import io.adaptiq.titan.flow.TitanOrchestrator;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.PipelineNode;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Phase 1 execution-engine processor — claims {@code ORCHESTRATE} tasks from the {@code
 * rf_task_queue} table using {@code FOR UPDATE SKIP LOCKED} and dispatches them.
 *
 * <p>Cadence: every 500 ms. Each tick claims up to {@value #BATCH_SIZE} tasks from the {@code
 * "default"} queue and routes each one to its {@link QueueMessageHandler} via {@link #handlers}.
 *
 * <p>A build moves through the design/38 §3 phase chain: {@code START_PIPELINE} / {@code
 * SYNTHESIZE} &rarr; {@code BAKE} &rarr; {@code ADVANCE}; each phase is enqueued as its own task so
 * a crash between phases is recovered by ordinary task re-delivery.
 *
 * <p>In {@code titan-server} this is a plain class. The server's {@code App} schedules it via a
 * plain {@link java.util.concurrent.ScheduledExecutorService}.
 *
 * <p>Design 67 step 6: this class no longer owns the action handler bodies — each action is
 * implemented by a dedicated {@link QueueMessageHandler}. The dispatcher only owns the claim loop,
 * the reap sweep, archive sweep, and the action&rarr;handler routing table.
 */
public class QueueProcessor {

  private static final Logger LOGGER = Logger.getLogger(QueueProcessor.class.getName());

  static final int BATCH_SIZE = 20;

  /**
   * Build statuses past which an {@code ORCHESTRATE} task must never run (issue #68). Mirrors
   * {@code BuildAbortService.BUILD_TERMINAL}.
   */
  private static final Set<String> BUILD_TERMINAL =
      Set.of("SUCCESS", "FAILED", "ABORTED", "UNSTABLE");

  /**
   * Parallelism of the per-tick dispatch phase (issue #68). Same-build tasks always run serially,
   * in claim order, on one thread — the parallelism unit is the <em>build</em>, so the engine's
   * per-build ordering invariant is preserved while one slow build can no longer starve every other
   * build's ORCHESTRATE chain (the failure mode behind "no PENDING approval surfaced within
   * 30000ms": a 16-task serial tick took &gt;20s under e2e load, so the ADVANCE pass that parks an
   * approval gate landed past the UI's polling budget). Env knob: {@code
   * TITAN_QUEUE_DISPATCH_THREADS}, default 8, floor 1.
   */
  static final int DISPATCH_THREADS = dispatchThreadsFromEnv();

  private static int dispatchThreadsFromEnv() {
    String raw = System.getenv("TITAN_QUEUE_DISPATCH_THREADS");
    if (raw != null) {
      try {
        return Math.max(1, Integer.parseInt(raw.trim()));
      } catch (NumberFormatException e) {
        LOGGER.log(
            Level.WARNING,
            "[titan] QueueProcessor: invalid TITAN_QUEUE_DISPATCH_THREADS \"{0}\" — using 8",
            raw);
      }
    }
    return 8;
  }

  /**
   * Shared dispatch pool — process-wide (like the schedulers that drive the processor), daemon
   * threads, never shut down. Sized by {@link #DISPATCH_THREADS}. Tests that construct their own
   * {@code QueueProcessor} instances share it safely: a tick blocks until its own groups finish, so
   * no work leaks across tick() calls.
   */
  private static final ExecutorService DISPATCH_POOL =
      Executors.newFixedThreadPool(
          DISPATCH_THREADS,
          new java.util.concurrent.ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
              Thread t = new Thread(r, "titan-queue-dispatch-" + n.incrementAndGet());
              t.setDaemon(true);
              return t;
            }
          });

  /**
   * Visibility-timeout floor used by the per-tick reaper. Set to the <em>largest</em> timeout any
   * task type carries (an {@code ORCHESTRATE/ADVANCE} task — 3600s) so the reaper is strictly
   * conservative.
   */
  private static final int REAP_VISIBILITY_TIMEOUT_SECONDS = 3600;

  /**
   * Heartbeat freshness window under which a task's claimant counts as alive — the reaper spares
   * {@code CLAIMED}/{@code PROCESSING} rows whose {@code claimed_by} worker heartbeated inside this
   * window even past the visibility timeout (issue #49: never reap a live worker's in-flight task).
   * Defaults to 90s (3x the worker's 30s heartbeat tick) and follows the same {@code
   * TITAN_AGENT_REAPER_STALE_SECONDS} knob as {@code AgentReaperScheduler} so "alive" means the
   * same thing to both reapers.
   */
  static final int WORKER_LIVENESS_SECONDS = workerLivenessSecondsFromEnv();

  private static int workerLivenessSecondsFromEnv() {
    String raw = System.getenv("TITAN_AGENT_REAPER_STALE_SECONDS");
    if (raw != null) {
      try {
        return Integer.parseInt(raw.trim());
      } catch (NumberFormatException e) {
        LOGGER.log(
            Level.WARNING,
            "[titan] QueueProcessor: invalid TITAN_AGENT_REAPER_STALE_SECONDS \"{0}\" — using 90",
            raw);
      }
    }
    return 90;
  }

  /**
   * The well-known shared queue every worker polls for {@code SYNTHESIZE} tasks (design/38 Stage
   * 1b). Synthesis is not agent-pinned — any worker may run it.
   */
  static final String SYNTHESIS_QUEUE = "synthesis";

  /**
   * Issue #360: the dispatcher used to fire {@code BAKE_FAILURE} notify hooks on the bake /
   * synthesis crash path. Constructed lazily on first use so unit tests that don't exercise the
   * crash path don't pay the cost; the IT injects a custom HTTP-client-backed dispatcher via {@link
   * #setBakeFailureDispatcherForTest(NotificationDispatcher)}.
   */
  private static volatile NotificationDispatcher bakeFailureDispatcher;

  /** The recommended cadence for callers scheduling this processor (500 ms). */
  public static final long PERIOD_MS = 500L;

  /**
   * Shared support object handed to every handler — task lifecycle + follow-up enqueues +
   * fail-close.
   */
  private final QueueHandlerSupport support;

  /** Action &rarr; handler routing. */
  private final Map<String, QueueMessageHandler> handlers;

  /** Visibility hook for tests + ops — the shared cap guard owned by this processor (#1050). */
  public TransitionCapGuard capGuard() {
    return support.capGuard();
  }

  /**
   * Per-tick sweep that fails QUEUED step tasks whose target queue has zero live workers (issue
   * #1049, V1-shippable-bar #4 "no silent stalls"). Configurable via the {@code
   * LOOP_NO_WORKER_TIMEOUT_S} env var.
   */
  private final NoWorkerTimeoutSweeper noWorkerSweeper;

  public QueueProcessor() {
    this(NoWorkerTimeoutSweeper.fromEnv());
  }

  /** Test seam — inject a configured sweeper (e.g. with a short timeout for unit coverage). */
  QueueProcessor(@NonNull NoWorkerTimeoutSweeper noWorkerSweeper) {
    this(noWorkerSweeper, new QueueHandlerSupport(QueueProcessor::bakeFailureDispatcher), null);
  }

  /**
   * Test seam (issue #1074) — inject the shared {@link QueueHandlerSupport} (e.g. one built with a
   * low-cap {@link TransitionCapGuard}) and, optionally, a non-productive ADVANCE function so an
   * integration test can drive the ORCHESTRATE-umbrella spam-guard path through the real {@link
   * #dispatch} entry point without a live orchestrator finishing/terminating the build and
   * resetting the per-build counters. Passing {@code null} for {@code advanceFn} keeps the
   * production {@link AdvanceHandler}.
   */
  QueueProcessor(
      @NonNull NoWorkerTimeoutSweeper noWorkerSweeper,
      @NonNull QueueHandlerSupport support,
      @edu.umd.cs.findbugs.annotations.Nullable
          BiFunction<TitanStores, Long, TitanOrchestrator.AdvanceResult> advanceFn) {
    this.support = support;
    Map<String, QueueMessageHandler> map = new LinkedHashMap<>();
    QueueMessageHandler synthesize = new SynthesizeHandler(support);
    // START_PIPELINE is the legacy entry action — routes to the same handler as SYNTHESIZE.
    map.put("START_PIPELINE", synthesize);
    map.put("SYNTHESIZE", synthesize);
    map.put("BAKE", new BakeHandler(support));
    map.put(
        "ADVANCE",
        advanceFn == null ? new AdvanceHandler(support) : new AdvanceHandler(support, advanceFn));
    map.put("REPLAY_FROM_NODE", new ReplayFromNodeHandler(support));
    this.handlers = Map.copyOf(map);
    this.noWorkerSweeper = noWorkerSweeper;
  }

  /**
   * Convenience overload — calls {@link #tick(TitanStores, String, int)} with the singleton {@link
   * TitanStores#get()}, the controller id derived from the local hostname, and the default reap
   * timeout.
   */
  public int tick() {
    return tick(TitanStores.get(), defaultControllerId(), REAP_VISIBILITY_TIMEOUT_SECONDS);
  }

  private static String defaultControllerId() {
    try {
      return java.net.InetAddress.getLocalHost().getHostName();
    } catch (java.net.UnknownHostException e) {
      return "controller-unknown";
    }
  }

  public int tick(@NonNull TitanStores daos, @NonNull String controllerId, int reapTimeoutSeconds) {
    // Recover orphaned work before claiming fresh. A controller (or worker) that crashed mid-task
    // leaves its row CLAIMED/PROCESSING under a dead lease; the claim path only ever selects
    // QUEUED rows.
    reapStaleTasks(daos, reapTimeoutSeconds);

    // Reap pipelines whose root-level `timeout:` deadline has passed (issue #244).
    reapOverdueBuilds(daos);

    // Fail-fast QUEUED step tasks whose target queue has no live worker (issue #1049,
    // V1-shippable-bar #4 "no silent stalls"). Best-effort: errors are logged and swallowed.
    try {
      noWorkerSweeper.sweep(daos, support);
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "[titan] QueueProcessor: no-worker sweep failed", e);
    }

    List<TaskQueueRow> batch = new ArrayList<>();
    for (int i = 0; i < BATCH_SIZE; i++) {
      Optional<TaskQueueRow> claimed;
      try {
        claimed = daos.taskQueue().claimTask("default", controllerId);
      } catch (RuntimeException e) {
        LOGGER.log(Level.WARNING, "[release-flow] QueueProcessor: claimTask failed", e);
        break;
      }
      if (claimed.isEmpty()) {
        break;
      }
      batch.add(claimed.get());
    }

    int processed = dispatchBatch(daos, batch);

    if (processed > 0) {
      LOGGER.log(
          Level.INFO, "[release-flow] QueueProcessor: processed {0} task(s) this tick", processed);
    }

    // Archive COMPLETED/FAILED/CANCELLED rows out of task_queue into task_archive.
    archiveCompletedTasks(daos);

    return processed;
  }

  /**
   * Dispatch one claimed batch (issue #68). Tasks are grouped by build id; groups run in parallel
   * on the shared {@link #DISPATCH_POOL} while the tasks <em>within</em> a group run serially in
   * claim order. This keeps the engine's per-build serialisation invariant (two tasks of one build
   * are never processed concurrently by this controller) while collapsing tick latency from the
   * <em>sum</em> of every build's handler time to the <em>max</em> — the root fix for parked
   * approval gates surfacing tens of seconds late under load.
   *
   * <p>Build-less tasks (no {@code build_id}) each form their own group. A single-group batch is
   * dispatched inline on the tick thread — no pool hop, so single-build unit/IT flows are
   * bit-for-bit the old behaviour.
   *
   * @return the number of tasks processed (the whole batch — per-task failures are folded by {@link
   *     #dispatchGroup})
   */
  private int dispatchBatch(@NonNull TitanStores daos, @NonNull List<TaskQueueRow> batch) {
    if (batch.isEmpty()) {
      return 0;
    }
    Map<Long, List<TaskQueueRow>> groups = new LinkedHashMap<>();
    for (TaskQueueRow task : batch) {
      long key = task.buildId != null ? task.buildId : -task.id;
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(task);
    }
    if (groups.size() == 1) {
      dispatchGroup(daos, batch);
      return batch.size();
    }
    List<Future<?>> futures = new ArrayList<>(groups.size());
    for (List<TaskQueueRow> group : groups.values()) {
      futures.add(DISPATCH_POOL.submit(() -> dispatchGroup(daos, group)));
    }
    for (Future<?> f : futures) {
      try {
        f.get();
      } catch (InterruptedException e) {
        // Preserve the interrupt for the scheduler; in-flight groups finish on the pool. Any
        // task left CLAIMED by a torn-down process is recovered by the stale-task reaper.
        Thread.currentThread().interrupt();
        break;
      } catch (ExecutionException e) {
        // dispatchGroup folds per-task failures itself — reaching here means a defect escaped
        // it. Log loudly; the affected tasks stay CLAIMED and are reaped back to QUEUED.
        LOGGER.log(Level.WARNING, "[titan] QueueProcessor: dispatch group failed unexpectedly", e);
      }
    }
    return batch.size();
  }

  /** Run one build's claimed tasks serially, folding each task's failure independently. */
  private void dispatchGroup(@NonNull TitanStores daos, @NonNull List<TaskQueueRow> group) {
    for (TaskQueueRow task : group) {
      try {
        dispatch(daos, task);
      } catch (RuntimeException e) {
        LOGGER.log(
            Level.WARNING,
            "[release-flow] QueueProcessor: dispatch failed for task id={0}",
            new Object[] {task.id, e});
        if (task.buildId != null) {
          support.markBuildFailed(
              daos,
              task.buildId,
              "the build engine hit an unexpected error processing this build: "
                  + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                  + " — this is an internal error; check the controller log.");
        }
        support.failTaskSafely(daos, task, e.getMessage());
      }
    }
  }

  /** Migrate COMPLETED/FAILED/CANCELLED rows out of task_queue into task_archive. Best-effort. */
  private void archiveCompletedTasks(@NonNull TitanStores daos) {
    try {
      int archived = daos.taskQueue().moveCompletedToArchive(BATCH_SIZE);
      if (archived > 0) {
        LOGGER.log(
            Level.FINE,
            "[release-flow] QueueProcessor: archived {0} completed task(s)",
            new Object[] {archived});
      }
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "[release-flow] QueueProcessor: archive sweep failed", e);
    }
  }

  /**
   * Reap stale CLAIMED/PROCESSING tasks; the recovered rows become QUEUED again. Best-effort. Rows
   * whose claimant worker heartbeated within {@link #WORKER_LIVENESS_SECONDS} are spared — a live
   * worker's in-flight task is never reaped, however long it runs (issue #49).
   */
  private void reapStaleTasks(@NonNull TitanStores daos, int reapTimeoutSeconds) {
    try {
      var reaped = daos.taskQueue().reapStale(reapTimeoutSeconds, WORKER_LIVENESS_SECONDS);
      if (reaped.requeued() > 0 || reaped.failed() > 0) {
        LOGGER.log(
            Level.INFO,
            "[release-flow] QueueProcessor: reaped stale tasks — {0} requeued, {1} failed",
            new Object[] {reaped.requeued(), reaped.failed()});
      }
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "[release-flow] QueueProcessor: reapStale sweep failed", e);
    }
  }

  private void dispatch(@NonNull TitanStores daos, @NonNull TaskQueueRow task) {
    // Issue #68 — the terminal-build fuse. Never run an ORCHESTRATE task whose build is already
    // terminal (or deleted): BuildAbortService cancels the task rows it can SEE, but a follow-up
    // enqueued by an in-flight handler survives that sweep and would resurrect the build — on the
    // live rig a SYNTHESIZE poll inserted concurrently with an abort re-dispatched worker
    // synthesis, re-baked the DAG and re-armed ADVANCE loops on an ABORTED build (build 365), and
    // parked/aborted builds kept 300s-backoff ADVANCE rows alive forever. Cancelling at claim time
    // makes cancel-closure idempotent and self-healing: every straggler drains on its next claim
    // and the per-tick archive sweep moves it out of task_queue. Stage-retry and replay are
    // unaffected — both flip their build non-terminal (or mint a fresh QUEUED build) before
    // enqueueing their ORCHESTRATE task.
    if (task.buildId != null && "ORCHESTRATE".equals(task.type)) {
      String buildStatus = daos.builds().findById(task.buildId).map(b -> b.status).orElse(null);
      if (buildStatus == null || BUILD_TERMINAL.contains(buildStatus)) {
        LOGGER.log(
            Level.INFO,
            "[titan] QueueProcessor: cancelling task {0} — build {1} is {2}",
            new Object[] {task.id, task.buildId, buildStatus == null ? "deleted" : buildStatus});
        daos.taskQueue().cancel(task.id);
        return;
      }
    }

    Map<String, Object> payload;
    try {
      payload = QueueHandlerSupport.JSON.readValue(task.payloadJson, QueueHandlerSupport.MAP_TYPE);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      LOGGER.log(
          Level.WARNING,
          "[release-flow] QueueProcessor: invalid payload JSON for task id={0}",
          task.id);
      if (task.buildId != null) {
        support.markBuildFailed(
            daos,
            task.buildId,
            "the build engine received a malformed orchestration task — this is an "
                + "internal error; check the controller log.");
      }
      support.failTaskSafely(daos, task, "Invalid payload JSON: " + e.getMessage());
      return;
    }

    // Issue #1050 — count every ORCHESTRATE-typed dispatch under the umbrella "ORCHESTRATE"
    // kind in addition to the per-action handlers' own counters. The umbrella is the
    // backstop the hard cap can catch even if a new action is added without its own count.
    if (task.buildId != null && "ORCHESTRATE".equals(task.type)) {
      TransitionCapGuard.Outcome umbrella =
          support.recordTransition(daos, task.buildId, "ORCHESTRATE");
      if (umbrella == TransitionCapGuard.Outcome.HARD_HALT) {
        long count = support.capGuard().countFor(task.buildId, "ORCHESTRATE");
        String reason = support.capGuard().haltReason("ORCHESTRATE", count);
        LOGGER.log(
            Level.WARNING,
            "[titan] build {0}: ORCHESTRATE umbrella hard-cap halt at count={1}",
            new Object[] {task.buildId, count});
        support.markBuildFailed(daos, task.buildId, reason);
        support.failTaskSafely(daos, task, reason);
        return;
      }
    }

    String action = payload.get("action") != null ? payload.get("action").toString() : null;
    QueueMessageHandler handler = action != null ? handlers.get(action) : null;
    if (handler == null) {
      LOGGER.log(
          Level.WARNING,
          "[release-flow] QueueProcessor: unknown action \"{0}\" for task id={1}",
          new Object[] {action, task.id});
      if (task.buildId != null) {
        support.markBuildFailed(
            daos,
            task.buildId,
            "the build engine received an unknown orchestration action '"
                + action
                + "' — this is an internal error; check the controller log.");
      }
      support.failTaskSafely(daos, task, "Unknown action: " + action);
      return;
    }
    handler.handle(daos, task, payload);
  }

  /**
   * Collect every flow-node id <em>strictly upstream</em> of {@code targetNodeId} in the pipeline
   * DAG — BFS backwards over {@code dependsOn} edges. For a step's stage we also include every
   * preceding step in the same stage. The target itself is excluded from the result.
   *
   * <p>This helper is consumed by {@link ReplayFromNodeHandler}; kept on {@code QueueProcessor} as
   * its public location (the {@code OrchestratorReplayTest} unit suite pins this exact location).
   */
  @NonNull
  static Set<String> collectUpstream(@NonNull PipelineModel model, @NonNull String targetNodeId) {
    Map<String, PipelineNode> nodeByName = new HashMap<>();
    Map<String, PipelineNode> nodeById = new HashMap<>();
    for (PipelineNode n : model.getAllNodes()) {
      nodeByName.put(n.getName(), n);
      nodeById.put(n.getId(), n);
    }
    Map<String, java.util.List<String>> stageSteps = new LinkedHashMap<>();
    Map<String, String> stepToStage = new HashMap<>();
    for (StageModel stage : model.getStages()) {
      java.util.List<String> ids = new java.util.ArrayList<>();
      for (StepModel s : stage.getSteps()) {
        ids.add(s.getId());
        stepToStage.put(s.getId(), stage.getId());
      }
      stageSteps.put(stage.getId(), ids);
    }

    boolean targetIsStep = stepToStage.containsKey(targetNodeId);
    if (!nodeById.containsKey(targetNodeId) && !targetIsStep) {
      return Set.of();
    }

    Set<String> upstream = new HashSet<>();
    Deque<String> frontier = new ArrayDeque<>();

    if (targetIsStep) {
      String owningStageId = stepToStage.get(targetNodeId);
      upstream.add(owningStageId);
      frontier.add(owningStageId);
      for (String stepId : stageSteps.get(owningStageId)) {
        if (stepId.equals(targetNodeId)) {
          break;
        }
        upstream.add(stepId);
      }
    } else {
      frontier.add(targetNodeId);
    }

    while (!frontier.isEmpty()) {
      String currentId = frontier.poll();
      PipelineNode current = nodeById.get(currentId);
      if (current == null) {
        continue;
      }
      for (String depName : current.getDependsOn()) {
        PipelineNode dep = nodeByName.get(depName);
        if (dep == null) {
          continue;
        }
        if (upstream.add(dep.getId())) {
          frontier.add(dep.getId());
          java.util.List<String> stepsInside = stageSteps.get(dep.getId());
          if (stepsInside != null) {
            upstream.addAll(stepsInside);
          }
        }
      }
    }

    upstream.remove(targetNodeId);
    return upstream;
  }

  /**
   * Reap any {@code RUNNING} build whose pipeline-root deadline has passed (issue #244). Each
   * overdue build is marked {@code FAILED} with a customer-facing reason. Best-effort.
   */
  private void reapOverdueBuilds(@NonNull TitanStores daos) {
    try {
      java.util.List<Long> overdue =
          daos.builds().findOverdueRunningBuilds(java.time.Instant.now());
      for (Long id : overdue) {
        support.markBuildFailed(
            daos,
            id,
            "pipeline timeout exceeded — the pipeline-level 'timeout:' deadline was reached "
                + "before the build finished. The build engine reaped this build.");
        LOGGER.log(Level.WARNING, "[titan] QueueProcessor: reaped overdue build {0}", id);
      }
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "[titan] QueueProcessor: reapOverdueBuilds sweep failed", e);
    }
  }

  // ── Bake-failure NotificationDispatcher (issue #360) — static test seam ────

  @edu.umd.cs.findbugs.annotations.Nullable
  private static NotificationDispatcher bakeFailureDispatcher() {
    NotificationDispatcher d = bakeFailureDispatcher;
    if (d != null) {
      return d;
    }
    try {
      d = new NotificationDispatcher();
      bakeFailureDispatcher = d;
      return d;
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] QueueProcessor: could not construct default NotificationDispatcher — "
              + "BAKE_FAILURE notify dispatch disabled",
          e);
      return null;
    }
  }

  /**
   * Test seam (#360 IT) — inject a custom {@link NotificationDispatcher}. Pass {@code null} to
   * disable bake-failure notify entirely; tests that rely on the default path should reset to
   * {@code null} in {@code @AfterEach}.
   */
  public static void setBakeFailureDispatcherForTest(
      @edu.umd.cs.findbugs.annotations.Nullable NotificationDispatcher dispatcher) {
    bakeFailureDispatcher = dispatcher;
  }
}

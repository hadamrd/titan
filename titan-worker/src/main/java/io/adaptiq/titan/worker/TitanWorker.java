package io.adaptiq.titan.worker;

import io.adaptiq.titan.flow.artifact.ArtifactStore;
import io.adaptiq.titan.flow.artifact.ArtifactStoreRegistry;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Titan Worker — a standalone, pull-based execution agent. It knows only PostgreSQL.
 *
 * <p>Lifecycle: register in {@code titan.agents} → start an independent heartbeat thread → poll
 * {@code titan.task_queue}, claiming {@code EXECUTE_COMMAND} tasks via {@code FOR UPDATE SKIP
 * LOCKED} → run each via {@link ProcessBuilder}, streaming logs to {@code titan.logs} → report the
 * outcome guarded by the claim token.
 *
 * <p><strong>Concurrency.</strong> A worker runs up to {@code TITAN_EXECUTORS} tasks at once. The
 * poll loop is a slot-filling dispatcher: a {@link Semaphore} with {@code numExecutors} permits
 * tracks free executor slots; while slots are free the loop keeps claiming (agent queue first, then
 * synthesis) and hands each claimed task to a fixed platform-thread pool; when no slot is free it
 * blocks on the semaphore until a task finishes; when a claim yields nothing it sleeps {@code
 * pollIntervalMs}. Each task runs {@code markProcessing → run → token-guarded complete} on its own
 * pool thread, independently of every other task and of the heartbeat thread (design/26 Tier C,
 * design/30).
 */
public final class TitanWorker {

  private static final Logger LOG = LoggerFactory.getLogger(TitanWorker.class);

  /** How often the worker reaps the workspaces of finished builds (design/43 W4). */
  private static final long WORKSPACE_REAP_INTERVAL_MS = 600_000L;

  private final WorkerConfig cfg;
  private final WorkerDb db;
  private final TaskExecutor executor;
  private final SynthesisTaskHandler synthesis;
  private final WorkerMetricsSampler metrics;
  private volatile boolean running = true;

  /** Free executor slots — {@code numExecutors} permits, one taken per in-flight task. */
  private final Semaphore slots;

  /** The bounded task pool — exactly {@code numExecutors} platform threads. */
  private final ExecutorService taskPool;

  /**
   * Build the configured {@link ArtifactStore} (design/41 §8.6), or {@code null} when none is set —
   * an {@code archiveArtifacts} step then fails closed at the step, not at boot, so a worker that
   * never archives needs no store config.
   */
  private static ArtifactStore artifactStore(WorkerConfig cfg) {
    if (cfg.artifactStoreKind().isBlank()) {
      LOG.info(
          "no artifact store configured (TITAN_ARTIFACT_STORE unset); "
              + "archiveArtifacts steps will fail closed");
      return null;
    }
    ArtifactStore store =
        ArtifactStoreRegistry.resolve(cfg.artifactStoreKind(), cfg.artifactStoreConfig());
    LOG.info("artifact store: kind='{}'", store.kind());
    return store;
  }

  private TitanWorker(WorkerConfig cfg) {
    this.cfg = cfg;
    this.db = new WorkerDb(cfg);
    this.executor =
        new TaskExecutor(
            db,
            cfg.workspaceRoot(),
            cfg.workspaceHostRoot(),
            cfg.libraryCacheRoot(),
            cfg.agentId(),
            artifactStore(cfg));
    // Synthesis is YAML-only now (design/53) — no arbitrary code, no library fetch.
    this.synthesis = new SynthesisTaskHandler(db);
    // Live-resource sampler (#348) — read on every heartbeat. Cheap (MXBean lookups + one
    // statvfs); a sampler failure must not block liveness so it never throws (sample()
    // returns a record with null fields when the JVM/host can't supply a reading).
    this.metrics = new WorkerMetricsSampler(new File(cfg.workspaceRoot().toString()));

    int executors = Math.max(1, cfg.numExecutors());
    this.slots = new Semaphore(executors);
    // A FIXED platform-thread pool, sized to numExecutors — NOT virtual
    // threads. Executor count is tens at most; a hard ceiling of OS threads
    // is the correct model and bounds concurrency to exactly numExecutors.
    ThreadFactory taskThreads =
        new ThreadFactory() {
          private final AtomicInteger n = new AtomicInteger();

          @Override
          public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "titan-task-" + n.incrementAndGet());
            t.setDaemon(false);
            return t;
          }
        };
    this.taskPool = Executors.newFixedThreadPool(executors, taskThreads);
  }

  public static void main(String[] args) throws Exception {
    // Boot the OTel SDK FIRST (issue #315). Gated on TITAN_OTEL_ENDPOINT —
    // unset → installs a no-op OpenTelemetry, so the rest of the worker can
    // call WorkerTracing.startTaskSpan unconditionally without an env-var
    // branch on every claim. Failures inside init() are swallowed; trace
    // export is best-effort and never breaks task execution.
    WorkerTracing.init();
    new TitanWorker(WorkerConfig.fromEnv()).run();
  }

  private void run() throws Exception {
    LOG.info(
        "Titan Worker starting — agentId={}, queues={}, synthesisQueue={}, executors={}, db={}",
        cfg.agentId(),
        cfg.queueNames(),
        cfg.synthesisQueue(),
        Math.max(1, cfg.numExecutors()),
        cfg.jdbcUrl());
    db.register(cfg);
    LOG.info("registered in titan.agents");

    // Reconcile orphaned containers from a previous worker life before claiming any task —
    // the container analogue of wiping a stale workspace on restart (Chunk 6G, design/30).
    new ContainerReaper(cfg.agentId()).sweepOrphans();

    // Reap the workspaces of finished builds (design/43 W4) — once now, to clear a
    // previous life's leftovers, then on a schedule. Its own daemon scheduler, so a slow
    // rm -rf of a large workspace can never delay a liveness heartbeat.
    WorkspaceReaper workspaceReaper =
        new WorkspaceReaper(
            db::buildStatus,
            cfg.workspaceRoot(),
            WorkspaceReaper.orphanTtlFromEnv(System.getenv()));
    workspaceReaper.sweep();
    ScheduledExecutorService reaper =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "titan-workspace-reaper");
              t.setDaemon(true);
              return t;
            });
    reaper.scheduleAtFixedRate(
        () -> {
          try {
            workspaceReaper.sweep();
          } catch (RuntimeException e) {
            LOG.warn("workspace reap failed", e);
          }
        },
        WORKSPACE_REAP_INTERVAL_MS,
        WORKSPACE_REAP_INTERVAL_MS,
        TimeUnit.MILLISECONDS);

    // Heartbeat on a dedicated scheduler, fully independent of task
    // execution — a long-running step must never block liveness (doc-26 C).
    // Heartbeat self-diagnoses lag (issue #57): a beat later than 2x the
    // interval is WARN-ed loudly instead of surfacing minutes later as a
    // controller-side reap of this worker's in-flight task.
    Heartbeat heartbeat = new Heartbeat(cfg.heartbeatIntervalMs(), this::beat);
    heartbeat.start();

    Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "titan-shutdown"));

    // Slot-filling dispatch loop. Each iteration first blocks until at
    // least one executor slot is free, then claims tasks to fill *every*
    // free slot in a burst (so a worker that just freed several slots
    // re-fills them without N poll-interval sleeps), dispatching each to
    // the task pool. A claim that yields nothing means the queues are
    // empty — release the unused permit and sleep one poll interval.
    while (running) {
      // Block (interruptibly) until an executor slot is available.
      try {
        slots.acquire();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      if (!running) {
        slots.release();
        break;
      }
      boolean dispatched = dispatchOne();
      // Greedily fill any further free slots without sleeping.
      while (dispatched && slots.tryAcquire()) {
        dispatched = dispatchOne();
      }
      if (!dispatched) {
        // dispatchOne already released the permit it could not use.
        try {
          Thread.sleep(cfg.pollIntervalMs());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    heartbeat.close();
    LOG.info("poll loop stopped");
  }

  /**
   * Claim one task and dispatch it to the task pool. The caller has already acquired one
   * executor-slot permit; this method either consumes it (a task was claimed and dispatched — the
   * task thread releases it on completion) or releases it back (no task was claimable).
   *
   * <p>The worker drains two queues — its own agent-id queue (step-execution tasks, agent-pinned)
   * and the shared {@code synthesis} queue (design/38 Stage 1b — synthesis is not agent-pinned, so
   * any worker may run it). The agent queue is tried first so step work is not starved by
   * synthesis.
   *
   * @return {@code true} if a task was claimed and dispatched.
   */
  private boolean dispatchOne() {
    // Drain every step queue this worker subscribes to (#824) before falling back to the
    // shared synthesis pool. cfg.queueNames() is the dedup'd, order-preserving list of
    // labels + explicit override + "default"; step work is tried in that order so an
    // operator-pinned TITAN_QUEUE keeps priority. Synthesis is always last so a synthesis
    // burst cannot starve agent-pinned step work.
    WorkerDb.ClaimedTask task = null;
    String claimedFrom = null;
    for (String queue : cfg.queueNames()) {
      task = claimFrom(queue);
      if (task != null) {
        claimedFrom = queue;
        break;
      }
    }
    boolean isSynthesis = false;
    if (task == null) {
      task = claimFrom(cfg.synthesisQueue());
      if (task != null) {
        claimedFrom = cfg.synthesisQueue();
        isSynthesis = true;
      }
    }
    if (task == null) {
      slots.release();
      return false;
    }

    final WorkerDb.ClaimedTask claimed = task;
    final boolean synth = isSynthesis;
    LOG.info(
        "claimed task {} (token {}, queue {})", claimed.id(), claimed.taskToken(), claimedFrom);
    try {
      taskPool.execute(
          () -> {
            try {
              runTask(claimed, synth);
            } finally {
              // Free the executor slot — even if runTask threw — so the
              // poll loop can claim the next task. A wedged executor slot
              // would silently shrink the worker's concurrency (G5).
              slots.release();
            }
          });
      return true;
    } catch (RuntimeException e) {
      // Pool rejected the task (only possible during shutdown). Release
      // the slot; the claimed task's lease lapses and the reaper re-queues
      // it (design/30) — no double-execution, no lost task.
      LOG.warn("task {} could not be dispatched — releasing slot", claimed.id(), e);
      slots.release();
      return false;
    }
  }

  /**
   * Run one claimed task to completion on a task-pool thread: {@code markProcessing → run →
   * token-guarded complete}. Runs independently of every other in-flight task; each task has its
   * own {@link DbLogSink}, its own workspace dir (keyed by task token, see {@link TaskExecutor}),
   * and its own pooled DB connections.
   */
  private void runTask(WorkerDb.ClaimedTask task, boolean isSynthesis) {
    // Continue the controller-side trace (issue #315). If task.traceParent is
    // null (server enqueued from a non-traced context, or OTel SDK disabled),
    // WorkerTracing#startTaskSpan opens a span under the worker's root context
    // — the call is unconditional so we never need a branch here.
    try (WorkerTracing.TaskSpan span =
        WorkerTracing.startTaskSpan("worker.runTask", task.traceParent())) {
      span.span().setAttribute("titan.task.id", task.id());
      span.span().setAttribute("titan.task.type", task.type());
      span.span().setAttribute("titan.task.synthesis", isSynthesis);
      runTaskInner(task, isSynthesis);
    }
  }

  private void runTaskInner(WorkerDb.ClaimedTask task, boolean isSynthesis) {
    try {
      db.markProcessing(task.id(), task.claimToken());
      boolean synthesisTask = isSynthesis || isSynthesizeAction(task);
      String status;
      String resultJson;
      if (synthesisTask) {
        // design/38 Stage 1b — pipeline synthesis, not a DAG step.
        SynthesisTaskHandler.Result r = synthesis.run(task);
        status = r.success() ? "COMPLETED" : "FAILED";
        resultJson = r.resultJson();
      } else {
        TaskExecutor.Result r = executor.run(task);
        status = r.success() ? "COMPLETED" : "FAILED";
        resultJson = r.resultJson();
      }
      // Token-guarded completion (design/27 G3): if this task's lease was
      // reaped while it ran — because it outran its visibility timeout —
      // and re-claimed by a peer, the stale token is rejected here and the
      // peer's run is authoritative. No double *write*.
      //
      // Event-driven orchestrator wake-up (issue #145): a step verdict must
      // not wait for the controller's 5s delayed ADVANCE re-poll — losing
      // that race cost ~6s PER STEP and blew the failure-triage 30s budget
      // under concurrent load. Steps only: synthesis completion is observed
      // by the SYNTHESIZE poll, whose done-branch enqueues BAKE — an
      // unconditional ADVANCE there could double-BAKE the DAG.
      //
      // #147: completion + wake commit in ONE transaction so the wake row can
      // never be claimable before the completion is visible — provable by
      // construction, not by call ordering. See completeStepAndWake.
      boolean accepted =
          (!synthesisTask && task.buildId() != null)
              ? completeStepAndWake(task, status, resultJson)
              : db.complete(task.id(), task.claimToken(), status, resultJson);
      if (accepted) {
        LOG.info("task {} -> {}", task.id(), status);
      } else {
        // Diagnose the REAL cause (issue #57): only one of the four rejection causes is
        // the reaper; blaming them all on "reaped and re-claimed" mis-directed a whole
        // investigation. Read the row and say what actually happened.
        LOG.warn(
            "task {} completion ({}) REJECTED — {}",
            task.id(),
            status,
            db.completionRejectionCause(task.id(), task.taskToken(), task.claimToken()));
      }
    } catch (Exception e) {
      LOG.error("task {} failed mid-flight", task.id(), e);
    }
  }

  /**
   * Complete a step task and wake the orchestrator atomically (issues #145 + #147) — see {@link
   * WorkerDb#completeAndWakeOrchestrator}. If the atomic write fails (transient DB error), the
   * completion must not be lost with the wake: fall back to the plain token-guarded {@code
   * complete}. The verdict is then observed by the controller's delayed ADVANCE re-poll (#827
   * backoff ladder) — the pre-#145 latency, never a wrong verdict. In the rare case where the
   * atomic path actually committed before the failure surfaced, the fallback's token-guarded UPDATE
   * matches 0 rows and this returns {@code false}; the caller's rejection diagnostics then name the
   * row's real (terminal) state.
   */
  private boolean completeStepAndWake(WorkerDb.ClaimedTask task, String status, String resultJson)
      throws java.sql.SQLException {
    try {
      boolean accepted =
          db.completeAndWakeOrchestrator(
              task.id(), task.claimToken(), status, resultJson, task.buildId(), task.traceParent());
      if (accepted) {
        LOG.debug("task {} completion woke orchestrator for build {}", task.id(), task.buildId());
      }
      return accepted;
    } catch (java.sql.SQLException e) {
      LOG.warn(
          "atomic completion+wake failed for task {} (build {}) — retrying completion without "
              + "the wake; the delayed ADVANCE poll will fold the verdict instead",
          task.id(),
          task.buildId(),
          e);
      return db.complete(task.id(), task.claimToken(), status, resultJson);
    }
  }

  /**
   * Claim one task from {@code queueName}, or {@code null} if the queue is empty / claim failed.
   */
  private WorkerDb.ClaimedTask claimFrom(String queueName) {
    try {
      return db.claim(cfg.agentId(), queueName).orElse(null);
    } catch (Exception e) {
      LOG.error("claim from queue {} failed", queueName, e);
      return null;
    }
  }

  /**
   * True if a task's payload carries {@code "action":"SYNTHESIZE"} — a defensive second check so a
   * synthesis task is routed correctly even if it lands on a non-synthesis queue.
   */
  private static boolean isSynthesizeAction(WorkerDb.ClaimedTask task) {
    String payload = task.payloadJson();
    return payload != null && payload.contains("\"action\"") && payload.contains("SYNTHESIZE");
  }

  private void beat() {
    try {
      WorkerMetricsSampler.Sample s = metrics.sample();
      boolean stamped = db.heartbeat(cfg.agentId(), s.cpuPct(), s.memPct(), s.diskPct());
      if (!stamped) {
        // The titan.agents row is GONE (external cleanup / manual delete). Without it the
        // reaper's liveness join can never match this worker and every in-flight task
        // becomes reapable at the visibility timeout — exactly the #57 failure shape.
        // Self-heal: re-register on the spot.
        LOG.warn(
            "heartbeat found no titan.agents row for '{}' — re-registering "
                + "(row deleted externally?)",
            cfg.agentId());
        db.register(cfg);
      }
    } catch (Exception e) {
      LOG.warn("heartbeat failed", e);
    }
  }

  private void shutdown() {
    LOG.info("shutting down — marking agent OFFLINE");
    running = false;
    // Stop accepting new tasks, then give in-flight tasks a bounded grace
    // window to finish. Any task still running when the window closes is
    // abandoned — its lease lapses and the reaper re-queues it (design/30).
    taskPool.shutdown();
    try {
      if (!taskPool.awaitTermination(20, TimeUnit.SECONDS)) {
        LOG.warn(
            "in-flight tasks did not finish within grace window — "
                + "abandoning (reaper will re-queue)");
        taskPool.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      taskPool.shutdownNow();
    }
    try {
      db.markOffline(cfg.agentId());
    } catch (Exception e) {
      LOG.warn("failed to mark agent offline", e);
    }
    // Close the connection pool last — every DB caller is now done.
    db.close();
  }
}

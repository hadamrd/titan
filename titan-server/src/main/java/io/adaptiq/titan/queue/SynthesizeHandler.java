package io.adaptiq.titan.queue;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles {@code ORCHESTRATE/SYNTHESIZE} (and the legacy {@code START_PIPELINE} entry action) —
 * design/38 §3, Stage 1b. Controller-side dispatch-and-poll coordinator: synthesis itself runs on a
 * worker via a separate {@code EXECUTE_COMMAND/SYNTHESIZE} task on the {@link
 * QueueProcessor#SYNTHESIS_QUEUE} queue. design/38 §11 forbids running user-code synthesis on the
 * controller.
 *
 * <p>One pass does exactly one of: <em>Done</em> (model already written by a worker — enqueue
 * BAKE), <em>Failed</em> (worker task FAILED — fail the build), <em>Dispatch</em> (no worker task
 * in flight — enqueue one + re-poll), or <em>Wait</em> (worker task still running — re-poll).
 */
final class SynthesizeHandler implements QueueMessageHandler {

  private static final Logger LOGGER = Logger.getLogger(SynthesizeHandler.class.getName());

  /** Delay before a re-queued {@code SYNTHESIZE} orchestration task re-checks the worker. */
  private static final int SYNTHESIS_POLL_SECONDS = 2;

  private final QueueHandlerSupport support;
  private final ConcurrencyGate concurrencyGate;

  SynthesizeHandler(@NonNull QueueHandlerSupport support) {
    this.support = support;
    this.concurrencyGate = new ConcurrencyGate(support);
  }

  @Override
  public void handle(
      @NonNull TitanStores daos, @NonNull TaskQueueRow task, @NonNull Map<String, Object> payload) {
    Object buildIdObj = payload.get("buildId");
    if (buildIdObj == null) {
      LOGGER.log(
          Level.WARNING,
          "[release-flow] QueueProcessor: SYNTHESIZE missing buildId, task id={0}",
          task.id);
      support.failTaskSafely(daos, task, "Missing buildId in payload");
      return;
    }

    long buildId = QueueHandlerSupport.toBuildId(buildIdObj);

    TransitionCapGuard.Outcome capOutcome = support.recordTransition(daos, buildId, "SYNTHESIZE");
    if (capOutcome == TransitionCapGuard.Outcome.HARD_HALT) {
      long count = support.capGuard().countFor(buildId, "SYNTHESIZE");
      String reason = support.capGuard().haltReason("SYNTHESIZE", count);
      LOGGER.log(
          Level.WARNING,
          "[titan] build {0}: SYNTHESIZE hard-cap halt at count={1} — fail-closing build",
          new Object[] {buildId, count});
      support.markBuildFailed(daos, buildId, reason);
      support.failTaskSafely(daos, task, reason);
      return;
    }

    BuildRow build = daos.builds().findById(buildId).orElse(null);
    if (build == null) {
      support.failTaskSafely(daos, task, "SYNTHESIZE: build not found: " + buildId);
      return;
    }

    // Done — a worker has already written the model. Hand off to bake.
    if (build.pipelineModelJson != null && !build.pipelineModelJson.isBlank()) {
      LOGGER.log(
          Level.INFO,
          "[titan] QueueProcessor: build {0} synthesized by worker — enqueuing BAKE",
          buildId);
      support.enqueueBake(daos, buildId);
      support.completeTaskSafely(daos, task);
      return;
    }

    JobRow job = daos.jobs().findById(build.jobId).orElse(null);
    if (job == null) {
      support.markBuildFailed(daos, buildId, "job not found: " + build.jobId);
      support.failTaskSafely(daos, task, "SYNTHESIZE: job not found: " + build.jobId);
      return;
    }

    TaskQueueRow workerTask = daos.taskQueue().findLatestSynthesisTask(buildId).orElse(null);

    // Failed — the worker synthesis task gave up (bad pipeline, retries exhausted).
    if (workerTask != null && "FAILED".equals(workerTask.status)) {
      String reason = synthesisFailureReason(workerTask);
      LOGGER.log(
          Level.WARNING,
          "[titan] QueueProcessor: worker synthesis FAILED for build {0}: {1}",
          new Object[] {buildId, reason});
      support.markBuildFailed(daos, buildId, reason);
      support.failTaskSafely(daos, task, "synthesis failed: " + reason);
      return;
    }

    // Dispatch — no synthesis task in flight (or the last one was cancelled). Enqueue one.
    boolean inFlight =
        workerTask != null
            && ("QUEUED".equals(workerTask.status)
                || "CLAIMED".equals(workerTask.status)
                || "PROCESSING".equals(workerTask.status));
    if (!inFlight) {
      // Issue #1101 — per-job concurrency gate. Fires only on the FIRST entry to the dispatch
      // path (when no synthesis worker task has ever been enqueued for this build) so a
      // policy like 'cancel_oldest' does not re-fire on every poll. Re-polls after a DEFER
      // verdict re-evaluate the gate on the next tick and dispatch when the cap clears.
      boolean firstEntry = workerTask == null;
      if (firstEntry) {
        ConcurrencyGate.Verdict verdict = concurrencyGate.evaluate(daos, build, job);
        if (verdict == ConcurrencyGate.Verdict.DEFER) {
          LOGGER.log(
              Level.FINE,
              "[titan] QueueProcessor: build {0} deferred by concurrency gate — re-polling",
              buildId);
          support.enqueueSynthesizePoll(daos, buildId, SYNTHESIS_POLL_SECONDS);
          support.completeTaskSafely(daos, task);
          return;
        }
      }
      support.enqueueWorkerSynthesis(daos, buildId, job.pipelineScript);
      LOGGER.log(
          Level.INFO, "[titan] QueueProcessor: dispatched worker synthesis for build {0}", buildId);
    }

    // Wait — re-queue this orchestration task to re-check after a short delay.
    support.enqueueSynthesizePoll(daos, buildId, SYNTHESIS_POLL_SECONDS);
    support.completeTaskSafely(daos, task);
  }

  /** Extract a human-readable reason from a failed worker synthesis task's result_json. */
  private static String synthesisFailureReason(@NonNull TaskQueueRow workerTask) {
    if (workerTask.resultJson == null || workerTask.resultJson.isBlank()) {
      return "worker synthesis task failed";
    }
    try {
      Map<String, Object> result =
          QueueHandlerSupport.JSON.readValue(workerTask.resultJson, QueueHandlerSupport.MAP_TYPE);
      Object error = result.get("error");
      return error != null ? error.toString() : workerTask.resultJson;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return workerTask.resultJson;
    }
  }
}

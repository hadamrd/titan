package io.adaptiq.titan.flow;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.ApprovalRow;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import io.adaptiq.titan.timer.TimerService;
import jakarta.enterprise.event.Event;
import java.time.Instant;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Aborts an in-flight Titan build — the engine side of the UI "Cancel" button.
 *
 * <p>A {@link io.adaptiq.titan.job.TitanRun} has no executor thread: {@code run()} only pushes a
 * task onto {@code rf_task_queue} and returns. There is no executor thread to interrupt, so this
 * service is the cancel path: it is pure database work, the same compare- and-set discipline the
 * {@link TitanOrchestrator} and {@link GateService} use.
 *
 * <p>Abort is three steps, ordered so the build cannot be re-advanced underneath us:
 *
 * <ol>
 *   <li>Cancel every still-live {@code rf_task_queue} task for the build, so the orchestrator and
 *       workers stop picking work up.
 *   <li>Drive every non-terminal {@code flow_nodes} row to a terminal status — a {@code RUNNING}
 *       node to {@code ABORTED}, a not-yet-started node to {@code SKIPPED}.
 *   <li>Set the build row itself to {@code ABORTED}.
 * </ol>
 *
 * <p>A worker already mid-step keeps running that one step, but its node is now terminal so the
 * orchestrator's reconcile compare-and-set ({@code WHERE status = 'QUEUED'}) no longer matches it —
 * the stray result is dropped. Aborting a build that is already finished is a no-op.
 */
public final class BuildAbortService {

  private static final Logger LOGGER = Logger.getLogger(BuildAbortService.class.getName());

  /** Build statuses from which there is nothing left to abort. */
  private static final Set<String> BUILD_TERMINAL =
      Set.of("SUCCESS", "FAILED", "ABORTED", "UNSTABLE");

  /** Flow-node statuses that will not change again. */
  private static final Set<String> NODE_TERMINAL =
      Set.of("SUCCESS", "FAILED", "ABORTED", "SKIPPED", "UNSTABLE");

  /** Task-queue statuses for a task that is still pending or in flight. */
  private static final Set<String> TASK_LIVE = Set.of("QUEUED", "CLAIMED", "PROCESSING");

  /**
   * Outcome of an {@link #abort} call.
   *
   * @param aborted whether this call moved the build to {@code ABORTED}
   * @param message a human-readable explanation
   */
  public record AbortOutcome(boolean aborted, @NonNull String message) {}

  private BuildAbortService() {}

  /**
   * Abort {@code buildId} on behalf of {@code actor}.
   *
   * @param daos the Titan stores
   * @param buildId the build to abort
   * @param actor the requesting user's id, for the audit trail
   * @return the outcome — {@link AbortOutcome#aborted()} is {@code false} if the build was unknown
   *     or already in a terminal status
   */
  @NonNull
  public static AbortOutcome abort(@NonNull TitanStores daos, long buildId, @NonNull String actor) {
    return abort(daos, buildId, actor, null);
  }

  /**
   * Same as {@link #abort(TitanStores, long, String)} but additionally fires a {@link
   * BuildStateChangedEvent} when the build transitions to {@code ABORTED} so SCM status reporters
   * (issue #1080) see the cancellation. The event channel is supplied by {@code BuildServiceImpl},
   * which is the only production caller; older direct callers (a small handful of tests + replay)
   * can keep using the no-arg form, which dispatches no event.
   */
  @NonNull
  public static AbortOutcome abort(
      @NonNull TitanStores daos,
      long buildId,
      @NonNull String actor,
      @edu.umd.cs.findbugs.annotations.Nullable Event<BuildStateChangedEvent> stateChangedEvent) {
    BuildRow build = daos.builds().findById(buildId).orElse(null);
    if (build == null) {
      return new AbortOutcome(false, "build " + buildId + " not found");
    }
    if (BUILD_TERMINAL.contains(build.status)) {
      return new AbortOutcome(
          false, "build " + buildId + " already finished (" + build.status + ")");
    }

    Instant now = Instant.now();
    String reason = "aborted by " + actor;

    // 0. Cancel any armed durable-sleep timers first — they outlive task_queue rows, so
    //    without this an aborted build's parked node would still fire a stale ADVANCE later.
    new TimerService(daos.timers()).cancelAll(buildId);

    // 1. Stamp the cancel-intent signal (#668) BEFORE we flip terminal status.
    //    Ordering is load-bearing: a worker mid-step polls cancel_requested_at on its own task
    //    row from its heartbeat — that poll uses no claim_token guard, so the worker sees the
    //    intent independently of who owns the row's lease, and can SIGTERM/SIGKILL the running
    //    subprocess then complete the task as CANCELLED through the normal token-guarded path.
    //    Without this stamp the only signal was the status flip below, which races the worker's
    //    own UPDATE and is silently dropped (claim_token guard, doc-27 G3).
    //    Idempotent: only un-stamped live rows are touched (double-cancel = zero rows).
    daos.taskQueue().markCancelRequested(buildId);

    // 2. Cancel live queue tasks so nothing re-advances the build.
    int cancelledTasks = 0;
    for (TaskQueueRow t : daos.taskQueue().listByBuild(buildId)) {
      if (TASK_LIVE.contains(t.status) && daos.taskQueue().cancel(t.id)) {
        cancelledTasks++;
      }
    }

    // 3. Terminalise every node still in flight.
    int terminalisedNodes = 0;
    for (FlowNodeRow n : daos.flowNodes().listByBuild(buildId)) {
      if (!NODE_TERMINAL.contains(n.status)) {
        String to = "RUNNING".equals(n.status) ? "ABORTED" : "SKIPPED";
        daos.flowNodes()
            .compareAndSetStatus(
                buildId, n.nodeId, n.status, to, null, now, null, abortJson(actor));
        terminalisedNodes++;
      }
    }

    // 3b. Close any PENDING approval rows (#68) — a parked approval must not outlive its build.
    //     Without this, an aborted build's PENDING row sat in the /approvals inbox (and the UI
    //     banner list) until the 24h timeout sweep. decideIfPending's status='PENDING' CAS guard
    //     makes this idempotent AND race-safe against a concurrent human decide: whoever wins
    //     keeps its terminal status, the loser is a 0-row no-op.
    int closedApprovals = 0;
    for (ApprovalRow a : daos.approvals().listForBuild(buildId)) {
      if ("PENDING".equals(a.status)
          && daos.approvals().decideIfPending(a.id, "REJECTED", "<aborted by " + actor + ">", now)
              == 1) {
        closedApprovals++;
      }
    }
    if (closedApprovals > 0) {
      LOGGER.log(
          Level.INFO,
          "[titan] abort: closed {0} PENDING approval(s) for build {1}",
          new Object[] {closedApprovals, buildId});
    }

    // 4. Abort the build itself.
    daos.builds().updateStatus(buildId, "ABORTED", build.startedAt, now, null, reason);
    // Terminal write: drop any cached PipelineModel for this build.
    io.adaptiq.titan.cache.PipelineModelCache.invalidateIfActive(buildId);

    // 4b. Second task-cancel sweep, AFTER the terminal flip (#68). A handler in flight during
    //     step 2 may have enqueued a follow-up task between our listByBuild read and the status
    //     write — exactly the race that resurrected build 365 on the live rig (a surviving
    //     SYNTHESIZE poll re-dispatched synthesis + re-baked an ABORTED build). Any such row is
    //     cancelled here; anything that still slips through is cancelled at claim time by
    //     QueueProcessor's terminal-build fuse. Both sweeps are idempotent by construction.
    daos.taskQueue().markCancelRequested(buildId);
    for (TaskQueueRow t : daos.taskQueue().listByBuild(buildId)) {
      if (TASK_LIVE.contains(t.status) && daos.taskQueue().cancel(t.id)) {
        cancelledTasks++;
      }
    }

    // 5. Fan-out the state change to CDI observers (issue #1080 — SCM status reporters need to
    //    see the cancelled→error transition for builds that originated from an SCM webhook).
    //    Best-effort, never throws.
    if (stateChangedEvent != null) {
      try {
        stateChangedEvent.fire(
            new BuildStateChangedEvent(
                buildId,
                "ABORTED",
                build.triggerType,
                build.triggerMetaJson,
                build.jobId,
                build.buildNumber));
      } catch (RuntimeException ee) {
        LOGGER.log(
            Level.FINE,
            "[titan] abort: state-change event dispatch failed for build {0}: {1}",
            new Object[] {buildId, ee.getMessage()});
      }
    }

    LOGGER.log(
        Level.INFO,
        "[titan] build {0} aborted by {1} ({2} task(s) cancelled, {3} node(s) terminalised)",
        new Object[] {buildId, actor, cancelledTasks, terminalisedNodes});
    return new AbortOutcome(true, "build " + buildId + " " + reason);
  }

  private static String abortJson(@NonNull String actor) {
    return "{\"aborted\":true,\"abortedBy\":\"" + actor.replace("\"", "\\\"") + "\"}";
  }
}

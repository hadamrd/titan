package io.adaptiq.titan.queue;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.TitanOrchestrator;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles {@code ORCHESTRATE/ADVANCE} — runs one {@link TitanOrchestrator#advance()} reconcile pass
 * over the build's DAG. Re-enqueues a follow-up ADVANCE if the build is neither finished nor parked
 * (parked builds re-enter the queue via {@code ApprovalService.decide} / {@code sweepTimedOut}).
 *
 * <p>CAS-loss tolerance (issue #911) is preserved bit-for-bit: a benign {@code CasLostException}
 * (recognised by {@link CasLossClassifier#isBenign}) re-enqueues ADVANCE after a 1-second delay,
 * NOT a fail-close.
 *
 * <p><b>Backoff on no-dispatch ticks (issue #827).</b> The fixed 5s re-arm cadence burned O(N) task
 * rows for a build of duration N when the orchestrator was waiting on a long-running step or a
 * queue with no live worker (~37 ORCHESTRATEs in 3 min observed on the rig, with zero forward
 * progress). When a tick produces no state change (zero dispatched, zero reconciled, not parked,
 * not finished) the next ADVANCE is scheduled with exponential backoff: {@code 5s → 15s → 60s →
 * 300s cap}. Any productive tick — a step dispatch, or a finished step folded into {@code
 * flow_nodes} — resets the counter and re-arms at the 5s base. External events (worker
 * step-complete writeback, {@code ApprovalService.decide}, gate winners, timer fires) enqueue a
 * fresh ADVANCE that lands on the queue regardless of our backoff state; that event causes the next
 * tick to be productive ({@code reconciled > 0}) which then resets the ladder.
 */
final class AdvanceHandler implements QueueMessageHandler {

  private static final Logger LOGGER = Logger.getLogger(AdvanceHandler.class.getName());

  /**
   * Exponential backoff ladder (seconds) for ADVANCE re-arm when ticks are unproductive (#827).
   * Indexed by {@code consecutiveNoDispatchTicks - 1}, clamped to the last element. The first
   * no-dispatch tick (count == 1) re-arms at 5s — preserving the legacy cadence for a single stray
   * tick — and only escalates if the build stays unproductive across consecutive ticks. A
   * productive tick resets the counter to 0 and re-arms at {@code BACKOFF_LADDER[0]} (5s).
   */
  static final int[] BACKOFF_LADDER = {5, 15, 60, 300};

  private final QueueHandlerSupport support;
  private final BiFunction<TitanStores, Long, TitanOrchestrator.AdvanceResult> advanceFn;

  /**
   * Per-build counter of consecutive no-dispatch ticks. Evicted on a productive tick, on park, on
   * build-finish, and on the non-benign-runtime fail-close path. Bounded by the number of
   * concurrently-running builds — entries that legitimately stop being ticked (e.g. a build that
   * was parked and never resumed before a controller restart) leak no further than process
   * lifetime. Per-instance (not static) so each {@code QueueProcessor} owns its own counters and
   * tests are isolated from each other.
   */
  private final ConcurrentMap<Long, Integer> noDispatchTicks = new ConcurrentHashMap<>();

  AdvanceHandler(@NonNull QueueHandlerSupport support) {
    this(support, (d, b) -> new TitanOrchestrator(d, b).advance());
  }

  /** Test seam — inject a function that produces an {@link TitanOrchestrator.AdvanceResult}. */
  AdvanceHandler(
      @NonNull QueueHandlerSupport support,
      @NonNull BiFunction<TitanStores, Long, TitanOrchestrator.AdvanceResult> advanceFn) {
    this.support = support;
    this.advanceFn = advanceFn;
  }

  /**
   * Delay (seconds) for the next ADVANCE re-enqueue given the number of consecutive no-dispatch
   * ticks observed so far (1-indexed: count==1 is the first no-dispatch tick). Productive ticks use
   * {@code BACKOFF_LADDER[0]} directly and never enter this function.
   */
  static int delayForNoDispatchCount(int count) {
    if (count <= 0) {
      return BACKOFF_LADDER[0];
    }
    return BACKOFF_LADDER[Math.min(count - 1, BACKOFF_LADDER.length - 1)];
  }

  /** Test/observability: current consecutive no-dispatch count for a build (0 if none tracked). */
  int noDispatchCountFor(long buildId) {
    return noDispatchTicks.getOrDefault(buildId, 0);
  }

  @Override
  public void handle(
      @NonNull TitanStores daos, @NonNull TaskQueueRow task, @NonNull Map<String, Object> payload) {
    Object buildIdObj = payload.get("buildId");
    if (buildIdObj == null) {
      support.failTaskSafely(daos, task, "ADVANCE missing buildId in payload");
      return;
    }
    long buildId = QueueHandlerSupport.toBuildId(buildIdObj);

    TransitionCapGuard.Outcome capOutcome = support.recordTransition(daos, buildId, "ADVANCE");
    if (capOutcome == TransitionCapGuard.Outcome.HARD_HALT) {
      long count = support.capGuard().countFor(buildId, "ADVANCE");
      String reason = support.capGuard().haltReason("ADVANCE", count);
      LOGGER.log(
          Level.WARNING,
          "[titan] build {0}: ADVANCE hard-cap halt at count={1} — fail-closing build",
          new Object[] {buildId, count});
      support.markBuildFailed(daos, buildId, reason);
      support.failTaskSafely(daos, task, reason);
      noDispatchTicks.remove(buildId);
      return;
    }

    try {
      TitanOrchestrator.AdvanceResult result = advanceFn.apply(daos, buildId);
      // Park = no re-tick. ApprovalService.decide / sweepTimedOut each enqueue a fresh ADVANCE
      // on the PENDING→terminal transition (GA/Buildkite shape). Build 14 on the live rig
      // proved that re-ticking a parked build CASes against the SLEEPING node and eventually
      // fail-closes with "compareAndSetStatus failed".
      if (result.buildFinished()) {
        noDispatchTicks.remove(buildId);
        support.capGuard().onBuildTerminal(buildId);
      } else if (result.parked()) {
        noDispatchTicks.remove(buildId);
      } else {
        boolean productive = result.dispatched() > 0 || result.reconciled() > 0;
        int delaySeconds;
        if (productive) {
          noDispatchTicks.remove(buildId);
          delaySeconds = BACKOFF_LADDER[0];
        } else {
          int count = noDispatchTicks.merge(buildId, 1, Integer::sum);
          delaySeconds = delayForNoDispatchCount(count);
          // Log only at backoff transitions to keep the controller log readable on long builds.
          if (count <= BACKOFF_LADDER.length) {
            LOGGER.log(
                Level.FINE,
                "[titan] build {0}: ADVANCE no-dispatch tick #{1} — re-arm in {2}s",
                new Object[] {buildId, count, delaySeconds});
          }
        }
        support.enqueueAdvance(daos, buildId, delaySeconds);
      }
      support.completeTaskSafely(daos, task);
    } catch (RuntimeException e) {
      // #911: CAS-loss is benign per the reconciler contract — see CasLossClassifier javadoc.
      if (CasLossClassifier.isBenign(e)) {
        LOGGER.log(
            Level.INFO,
            "[titan] build {0}: ADVANCE raced ({1}) — re-enqueuing",
            new Object[] {buildId, QueueHandlerSupport.describe(e)});
        // The 1s CAS-loss delay is unchanged by #827 — a CAS race is a fast-retry condition,
        // not the "nothing to do" condition that backoff is for.
        support.enqueueAdvance(daos, buildId, 1);
        support.completeTaskSafely(daos, task);
        return;
      }
      LOGGER.log(
          Level.WARNING,
          "[titan] QueueProcessor: ADVANCE failed for build {0}: {1}",
          new Object[] {buildId, e.getMessage()});
      noDispatchTicks.remove(buildId);
      // An ADVANCE pass that throws leaves the build with no follow-up ADVANCE queued — it
      // would wedge non-terminal and silent. Fail it closed with a console reason.
      support.markBuildFailed(
          daos,
          buildId,
          "the build engine could not advance the pipeline: "
              + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
              + " — this is an internal error; check the controller log.");
      support.failTaskSafely(daos, task, "advance failed: " + e.getMessage());
    }
  }
}

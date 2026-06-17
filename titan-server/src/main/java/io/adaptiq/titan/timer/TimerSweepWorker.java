package io.adaptiq.titan.timer;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import io.adaptiq.titan.store.rows.TimerRow;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The durable-timer firing worker — every ~5 s, reclaims any timer left {@code CLAIMED} by a dead
 * sweep, claims due {@code ARMED} timers, and fires each.
 *
 * <p>It is a plain class; the server's {@link java.util.concurrent.ScheduledExecutorService} drives
 * it at the {@link #PERIOD_MS} cadence.
 *
 * <p>Firing is kind-agnostic: it enqueues one {@code ORCHESTRATE/ADVANCE} task for the timer's
 * build, then marks the timer {@code FIRED}. The kind-specific work is the orchestrator's job on
 * the next {@code advance()} pass.
 *
 * <p>Multi-controller safe: the {@link AtomicBoolean} guard prevents overlapping ticks within one
 * controller. The DAO's token-correlated claim means two controllers sweeping concurrently each
 * claim a disjoint set — every timer fires exactly once.
 */
public final class TimerSweepWorker {

  private static final Logger LOGGER = Logger.getLogger(TimerSweepWorker.class.getName());

  /** Recommended scheduling cadence (5 s). */
  public static final long PERIOD_MS = 5_000L;

  /** Timers claimed per tick. */
  private static final int BATCH = 100;

  /** A {@code CLAIMED} timer older than this is assumed orphaned by a dead sweep. */
  private static final Duration STALE_CLAIM = Duration.ofMinutes(5);

  private final AtomicBoolean running = new AtomicBoolean(false);

  /**
   * Run one sweep cycle against the supplied stores. Safe to call from a scheduled thread; the
   * {@link AtomicBoolean} guard makes it skip-not-stack if a previous sweep is still in progress.
   */
  public void sweep(@NonNull TitanStores daos) {
    if (!running.compareAndSet(false, true)) {
      LOGGER.fine("[titan] previous timer sweep still running — skipping");
      return;
    }
    try {
      int reclaimed = daos.timers().reclaimStale(Instant.now().minus(STALE_CLAIM));
      if (reclaimed > 0) {
        LOGGER.log(Level.INFO, "[titan] timer sweep reclaimed {0} stale timer(s)", reclaimed);
      }
      for (TimerRow timer : daos.timers().claimDue(BATCH)) {
        try {
          fire(daos, timer);
        } catch (RuntimeException e) {
          // One bad timer must not strand the rest of the batch — stays CLAIMED and
          // will be reclaimed by a later sweep.
          LOGGER.log(
              Level.WARNING,
              "[titan] failed to fire timer " + timer.id + " — will be reclaimed",
              e);
        }
      }
      // #715: approval timeout sweep — independent of the timer table because the (build, node)
      // GATE_RESUME timer may have already fired and been marked FIRED before the orchestrator
      // got a chance to advance(). The approvals.expires_at column is the durable source of
      // truth for expiry; a row-level sweep ensures TIMED_OUT eventually flips even if the
      // timer's ADVANCE was lost or the controller restarted mid-tick.
      try {
        io.adaptiq.titan.flow.ApprovalService.sweepTimedOut(daos, Instant.now());
      } catch (RuntimeException e) {
        LOGGER.log(
            Level.WARNING, "[titan] approval timeout sweep threw (swallowed) — will retry", e);
      }
    } finally {
      running.set(false);
    }
  }

  /** Fire one timer: enqueue an ADVANCE for its build, then mark it FIRED. */
  private static void fire(@NonNull TitanStores daos, @NonNull TimerRow timer) {
    enqueueAdvance(daos, timer.buildId);
    if (daos.timers().markFired(timer.id) == 0) {
      LOGGER.log(Level.WARNING, "[titan] timer {0} was not CLAIMED at fire time", timer.id);
    } else {
      LOGGER.log(
          Level.INFO,
          "[titan] timer {0} ({1}) fired for build {2}",
          new Object[] {timer.id, timer.kind, timer.buildId});
    }
  }

  /** Enqueue an {@code ORCHESTRATE/ADVANCE} task so the orchestrator re-evaluates the build. */
  private static void enqueueAdvance(@NonNull TitanStores daos, long buildId) {
    TaskQueueRow advance = new TaskQueueRow();
    advance.type = "ORCHESTRATE";
    advance.queueName = "default";
    advance.status = "QUEUED";
    advance.priority = 0;
    advance.payloadJson = "{\"action\":\"ADVANCE\",\"buildId\":" + buildId + "}";
    advance.attempts = 0;
    advance.maxAttempts = 3;
    advance.visibilityTimeoutSeconds = 3600;
    advance.buildId = buildId;
    advance.availableAt = Instant.now();
    daos.taskQueue().insert(advance);
  }
}

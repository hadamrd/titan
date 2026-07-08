package io.adaptiq.titan.worker;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The worker's liveness pump — a dedicated single-thread scheduler that runs the supplied {@code
 * beat} every {@code intervalMs}, fully independent of task execution (doc-26 Tier C: a
 * long-running step must never delay a beat).
 *
 * <p><b>Self-diagnosing lag (issue #57):</b> every tick measures the start-to-start gap since the
 * previous beat. A gap larger than {@value #LAG_WARN_FACTOR}× the configured interval is WARN-ed as
 * {@code "heartbeat lag"} — so a worker whose beats fall behind (CPU starvation, DB contention, GC
 * pauses, a slow metrics sample) says so loudly in its own log, instead of the failure surfacing
 * minutes later as a controller-side reap of its in-flight task. The reaper's liveness window is
 * 90s against a 10s default beat (9 beats of margin); a single WARN here is an early-warning
 * indicator, repeated WARNs mean liveness is genuinely at risk.
 *
 * <p>The beat body is guarded: a throwing beat is logged and swallowed, never kills the scheduler
 * thread (a {@code scheduleAtFixedRate} task that throws is silently descheduled — the classic
 * "heartbeat died quietly" failure).
 */
final class Heartbeat implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(Heartbeat.class);

  /** A beat later than this factor × interval is WARN-ed as heartbeat lag. */
  static final long LAG_WARN_FACTOR = 2;

  private final long intervalMs;
  private final Runnable beat;
  private final LongSupplier nanoClock;
  private final ScheduledExecutorService scheduler;

  /** Start instant (nanos) of the previous beat, or -1 before the first. */
  private final AtomicLong lastBeatStartNanos = new AtomicLong(-1);

  private final AtomicLong maxObservedGapMs = new AtomicLong();
  private final AtomicLong lagWarnCount = new AtomicLong();

  Heartbeat(long intervalMs, Runnable beat) {
    this(intervalMs, beat, System::nanoTime);
  }

  /** Test seam — inject a deterministic clock. */
  Heartbeat(long intervalMs, Runnable beat, LongSupplier nanoClock) {
    this.intervalMs = intervalMs;
    this.beat = beat;
    this.nanoClock = nanoClock;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "titan-heartbeat");
              t.setDaemon(true);
              return t;
            });
  }

  /** Begin beating — first beat after one interval, then every interval. */
  void start() {
    scheduler.scheduleAtFixedRate(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
  }

  /**
   * One scheduler tick: measure the start-to-start gap, WARN on lag, then run the beat. Visible for
   * deterministic unit tests (driven directly with a fake clock).
   */
  void tick() {
    long now = nanoClock.getAsLong();
    long prev = lastBeatStartNanos.getAndSet(now);
    if (prev >= 0) {
      long gapMs = (now - prev) / 1_000_000L;
      maxObservedGapMs.accumulateAndGet(gapMs, Math::max);
      if (gapMs > LAG_WARN_FACTOR * intervalMs) {
        lagWarnCount.incrementAndGet();
        LOG.warn(
            "heartbeat lag: {} ms since previous beat (configured interval {} ms) — "
                + "if this repeats, the controller may reap this worker's in-flight tasks "
                + "once the liveness window is exceeded",
            gapMs,
            intervalMs);
      }
    }
    try {
      beat.run();
    } catch (RuntimeException e) {
      LOG.warn("heartbeat failed", e);
    }
  }

  /** Largest observed start-to-start beat gap so far, in ms (0 before the second beat). */
  long maxObservedGapMs() {
    return maxObservedGapMs.get();
  }

  /** How many beats were WARN-ed as lagging. */
  long lagWarnCount() {
    return lagWarnCount.get();
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }
}

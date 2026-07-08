package io.adaptiq.titan.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Issue #57 — the worker heartbeat must (a) self-diagnose lag with a WARN-able signal and (b) keep
 * its cadence under full task-executor load, so the controller's 90s liveness window (9 beats at
 * the 10s default interval) is never silently blown.
 */
class HeartbeatTest {

  // ── (a) lag detection — deterministic, fake clock ─────────────────────────

  @Test
  void onTimeBeatsAreNotFlaggedAsLag() {
    AtomicLong clock = new AtomicLong();
    try (Heartbeat hb = new Heartbeat(100, () -> {}, clock::get)) {
      for (int i = 0; i < 5; i++) {
        clock.addAndGet(100_000_000L); // exactly one 100ms interval
        hb.tick();
      }
      assertEquals(0, hb.lagWarnCount(), "on-time beats must not be flagged");
      assertEquals(100, hb.maxObservedGapMs());
    }
  }

  @Test
  void aBeatLaterThanTwiceTheIntervalIsFlaggedAsLag() {
    AtomicLong clock = new AtomicLong();
    try (Heartbeat hb = new Heartbeat(100, () -> {}, clock::get)) {
      hb.tick(); // first beat — establishes the baseline, never flagged
      clock.addAndGet(150_000_000L); // 150ms — late but under the 2x threshold
      hb.tick();
      assertEquals(0, hb.lagWarnCount(), "gap <= 2x interval must not warn");

      clock.addAndGet(450_000_000L); // 450ms — beyond 2x100ms
      hb.tick();
      assertEquals(1, hb.lagWarnCount(), "gap > 2x interval must warn");
      assertEquals(450, hb.maxObservedGapMs());
    }
  }

  @Test
  void aThrowingBeatNeverKillsTheScheduler() throws Exception {
    AtomicInteger beats = new AtomicInteger();
    try (Heartbeat hb =
        new Heartbeat(
            20,
            () -> {
              beats.incrementAndGet();
              throw new IllegalStateException("db hiccup");
            })) {
      hb.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (beats.get() < 3 && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertTrue(
          beats.get() >= 3,
          "beats must keep coming after a beat throws (a scheduleAtFixedRate task that "
              + "propagates is silently descheduled — the classic dead-heartbeat bug); got "
              + beats.get());
    }
  }

  // ── (b) adversarial: cadence under saturated task executors ──────────────

  /**
   * Occupy a fixed task pool (the shape of {@code TitanWorker}'s executor pool) with CPU-burning
   * fake tasks for longer than a scaled liveness cutoff, and assert the observed beat gaps stay
   * well under that cutoff. Scaled 100x from production: 100ms interval against the production
   * ratio's 900ms cutoff (10s beats / 90s window = 9 beats). The margin asserted (gap < cutoff) is
   * intentionally generous so a busy CI box does not flake, while still failing loudly if beats
   * ever run on (or synchronize with) task-execution threads.
   */
  @Test
  void beatsStayWellUnderTheLivenessCutoffWhileExecutorsAreSaturated() throws Exception {
    final long intervalMs = 100;
    final long scaledCutoffMs = 9 * intervalMs; // mirrors 90s cutoff vs 10s beat

    int executors = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    ExecutorService taskPool = Executors.newFixedThreadPool(executors);
    AtomicBoolean stop = new AtomicBoolean(false);
    for (int i = 0; i < executors; i++) {
      taskPool.execute(
          () -> {
            // busy-burn: the synthetic long-running step occupying an executor slot
            double sink = 0;
            while (!stop.get()) {
              sink += Math.sqrt(sink + 1.7);
            }
            if (Double.isNaN(sink)) {
              throw new IllegalStateException("unreachable");
            }
          });
    }

    List<Long> beatNanos = new CopyOnWriteArrayList<>();
    try (Heartbeat hb = new Heartbeat(intervalMs, () -> beatNanos.add(System.nanoTime()))) {
      hb.start();
      Thread.sleep(3_000);
      hb.close();

      assertTrue(
          beatNanos.size() >= 10,
          "expected a steady beat stream under load, got " + beatNanos.size());
      long maxGapMs = 0;
      for (int i = 1; i < beatNanos.size(); i++) {
        maxGapMs = Math.max(maxGapMs, (beatNanos.get(i) - beatNanos.get(i - 1)) / 1_000_000L);
      }
      assertTrue(
          maxGapMs < scaledCutoffMs,
          "heartbeat gap under saturated executors must stay under the scaled liveness "
              + "cutoff ("
              + scaledCutoffMs
              + "ms): observed max "
              + maxGapMs
              + "ms");
    } finally {
      stop.set(true);
      taskPool.shutdownNow();
    }
  }
}

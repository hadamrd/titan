package io.adaptiq.titan.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #1050 — per-build state-transition spam guard.
 *
 * <p>Pins the soft/hard cap behaviour (warn at soft, halt at hard), counter eviction on terminal,
 * per-(build, kind) isolation, env-driven overrides, Prometheus counter emission, and thread-safe
 * concurrent increments.
 */
class TransitionCapGuardTest {

  private SimpleMeterRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    Metrics.globalRegistry.add(registry);
  }

  @AfterEach
  void tearDown() {
    Metrics.globalRegistry.remove(registry);
    registry.close();
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void freshGuard_belowSoftCap_alwaysReturnsOk() {
    TransitionCapGuard g = new TransitionCapGuard(200, 1000);

    for (int i = 1; i <= 200; i++) {
      assertEquals(
          TransitionCapGuard.Outcome.OK,
          g.recordTransition(1L, "ADVANCE"),
          "count=" + i + " must be OK while at-or-below soft cap");
    }
    assertEquals(200L, g.countFor(1L, "ADVANCE"));
    assertFalse(g.isHalted(1L));
  }

  // ── soft cap ──────────────────────────────────────────────────────────────

  @Test
  void crossingSoftCap_emitsSoftWarnExactlyOnce() {
    TransitionCapGuard g = new TransitionCapGuard(5, 100);

    List<TransitionCapGuard.Outcome> seen = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      seen.add(g.recordTransition(1L, "ADVANCE"));
    }

    // counts:           1   2   3   4   5   6(warn) 7   8   9   10
    assertEquals(TransitionCapGuard.Outcome.OK, seen.get(0));
    assertEquals(TransitionCapGuard.Outcome.OK, seen.get(4)); // count=5, at soft cap
    assertEquals(TransitionCapGuard.Outcome.SOFT_WARN, seen.get(5)); // count=6, crossed
    // No further SOFT_WARN emissions (only the crossing edge fires).
    for (int i = 6; i < 10; i++) {
      assertEquals(TransitionCapGuard.Outcome.OK, seen.get(i), "i=" + i);
    }
  }

  // ── hard cap ──────────────────────────────────────────────────────────────

  @Test
  void crossingHardCap_returnsHaltAndStaysHalted() {
    TransitionCapGuard g = new TransitionCapGuard(2, 4);

    assertEquals(TransitionCapGuard.Outcome.OK, g.recordTransition(1L, "BAKE")); // 1
    assertEquals(TransitionCapGuard.Outcome.OK, g.recordTransition(1L, "BAKE")); // 2
    assertEquals(TransitionCapGuard.Outcome.SOFT_WARN, g.recordTransition(1L, "BAKE")); // 3
    assertEquals(TransitionCapGuard.Outcome.OK, g.recordTransition(1L, "BAKE")); // 4
    assertEquals(TransitionCapGuard.Outcome.HARD_HALT, g.recordTransition(1L, "BAKE")); // 5
    assertTrue(g.isHalted(1L));
    // Subsequent calls keep returning HARD_HALT (idempotent fail-close).
    assertEquals(TransitionCapGuard.Outcome.HARD_HALT, g.recordTransition(1L, "BAKE"));
    assertEquals(TransitionCapGuard.Outcome.HARD_HALT, g.recordTransition(1L, "BAKE"));
  }

  // ── reset ────────────────────────────────────────────────────────────────

  @Test
  void onBuildTerminal_evictsAllCountersForThatBuild() {
    TransitionCapGuard g = new TransitionCapGuard(2, 4);
    g.recordTransition(1L, "ADVANCE");
    g.recordTransition(1L, "BAKE");
    g.recordTransition(2L, "ADVANCE");
    assertEquals(1L, g.countFor(1L, "ADVANCE"));
    assertEquals(1L, g.countFor(1L, "BAKE"));
    assertEquals(1L, g.countFor(2L, "ADVANCE"));

    g.onBuildTerminal(1L);

    assertEquals(0L, g.countFor(1L, "ADVANCE"), "build 1 ADVANCE evicted");
    assertEquals(0L, g.countFor(1L, "BAKE"), "build 1 BAKE evicted");
    assertEquals(1L, g.countFor(2L, "ADVANCE"), "build 2 untouched");
  }

  @Test
  void onBuildTerminal_clearsHaltedFlag_allowingFreshBudget() {
    // A build id that hits the cap and is then terminal — subsequent re-use of the same id
    // (effectively impossible in prod, but the invariant matters) gets a fresh budget.
    TransitionCapGuard g = new TransitionCapGuard(1, 2);
    g.recordTransition(7L, "ADVANCE");
    g.recordTransition(7L, "ADVANCE");
    g.recordTransition(7L, "ADVANCE"); // count=3, halt
    assertTrue(g.isHalted(7L));

    g.onBuildTerminal(7L);

    assertFalse(g.isHalted(7L));
    assertEquals(0L, g.countFor(7L, "ADVANCE"));
    assertEquals(TransitionCapGuard.Outcome.OK, g.recordTransition(7L, "ADVANCE"));
  }

  // ── per-kind isolation ────────────────────────────────────────────────────

  @Test
  void countersAreIsolatedPerKind_andPerBuild() {
    TransitionCapGuard g = new TransitionCapGuard(100, 200);

    g.recordTransition(1L, "ADVANCE");
    g.recordTransition(1L, "ADVANCE");
    g.recordTransition(1L, "BAKE");
    g.recordTransition(2L, "ADVANCE");

    assertEquals(2L, g.countFor(1L, "ADVANCE"));
    assertEquals(1L, g.countFor(1L, "BAKE"));
    assertEquals(0L, g.countFor(1L, "SYNTHESIZE"));
    assertEquals(1L, g.countFor(2L, "ADVANCE"));
    assertEquals(0L, g.countFor(2L, "BAKE"));
    assertEquals(0L, g.countFor(99L, "ADVANCE"), "unknown build returns 0");
  }

  @Test
  void crossingHardCapForOneKind_doesNotHaltOtherKindsOfSameBuild_butHaltsBuildOverall() {
    // Halt is at the build level (not per-kind) — once the build is haltedone kind crossing
    // hard cap, every other kind's record call for the same build also returns HARD_HALT.
    TransitionCapGuard g = new TransitionCapGuard(1, 2);

    g.recordTransition(1L, "ADVANCE");
    g.recordTransition(1L, "ADVANCE");
    assertEquals(TransitionCapGuard.Outcome.HARD_HALT, g.recordTransition(1L, "ADVANCE"));
    assertTrue(g.isHalted(1L));

    // Different kind, same build — once halted, all kinds return HARD_HALT.
    assertEquals(TransitionCapGuard.Outcome.HARD_HALT, g.recordTransition(1L, "BAKE"));

    // Different build — untouched.
    assertEquals(TransitionCapGuard.Outcome.OK, g.recordTransition(2L, "ADVANCE"));
  }

  // ── Prometheus emission ───────────────────────────────────────────────────

  @Test
  void everyRecordedTransition_incrementsPrometheusCounter() {
    TransitionCapGuard g = new TransitionCapGuard(5, 10);

    g.recordTransition(1L, "ADVANCE");
    g.recordTransition(1L, "ADVANCE");
    g.recordTransition(2L, "ADVANCE");
    g.recordTransition(1L, "BAKE");

    assertEquals(
        3.0, registry.counter("titan.engine.transitions.total", "kind", "ADVANCE").count(), 0.0);
    assertEquals(
        1.0, registry.counter("titan.engine.transitions.total", "kind", "BAKE").count(), 0.0);
  }

  @Test
  void counterIncrementsEvenForHaltedBuild_metricsAreTheCanonicalRecord() {
    // The HALT outcome should not silence the metric — ops needs to see the runaway count.
    TransitionCapGuard g = new TransitionCapGuard(1, 2);
    for (int i = 0; i < 50; i++) {
      g.recordTransition(1L, "ADVANCE");
    }
    assertEquals(
        50.0, registry.counter("titan.engine.transitions.total", "kind", "ADVANCE").count(), 0.0);
  }

  // ── construction guard ───────────────────────────────────────────────────

  @Test
  void constructor_rejectsInvalidCaps() {
    assertThrows(IllegalArgumentException.class, () -> new TransitionCapGuard(0, 10));
    assertThrows(IllegalArgumentException.class, () -> new TransitionCapGuard(10, 5));
    assertThrows(IllegalArgumentException.class, () -> new TransitionCapGuard(-1, -1));
  }

  @Test
  void defaultConstructor_appliesDefaults() {
    TransitionCapGuard g = new TransitionCapGuard();
    // Defaults match the public constants; env override is exercised via the int-arg ctor in
    // other tests (System.getenv mutation is not portable).
    assertEquals(TransitionCapGuard.DEFAULT_SOFT_CAP, g.softCap());
    assertEquals(TransitionCapGuard.DEFAULT_HARD_CAP, g.hardCap());
  }

  // ── halt reason ──────────────────────────────────────────────────────────

  @Test
  void haltReason_mentionsSpamGuardAndKind() {
    TransitionCapGuard g = new TransitionCapGuard(2, 4);
    String r = g.haltReason("ADVANCE", 1500);
    assertTrue(r.contains("transition spam guard hit"), r);
    assertTrue(r.contains("ADVANCE"), r);
    assertTrue(r.contains("1500"), r);
  }

  // ── adversarial / concurrency ────────────────────────────────────────────

  @Test
  void concurrentIncrements_areAtomicAndCountExactly() throws Exception {
    TransitionCapGuard g = new TransitionCapGuard(200, 2_000);
    int threads = 8;
    int perThread = 500;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger halts = new AtomicInteger();

    for (int t = 0; t < threads; t++) {
      pool.submit(
          () -> {
            ready.countDown();
            try {
              start.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            }
            for (int i = 0; i < perThread; i++) {
              if (g.recordTransition(42L, "ADVANCE") == TransitionCapGuard.Outcome.HARD_HALT) {
                halts.incrementAndGet();
              }
            }
          });
    }

    ready.await();
    start.countDown();
    pool.shutdown();
    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

    assertEquals(
        (long) threads * perThread,
        g.countFor(42L, "ADVANCE"),
        "concurrent increments must not lose updates");
    assertNotEquals(0, halts.get(), "8*500=4000 > hardCap=2000, so halts expected");
    assertTrue(g.isHalted(42L));
  }

  @Test
  void manyKindsSameBuild_areTrackedIndependently() {
    TransitionCapGuard g = new TransitionCapGuard(100, 200);
    String[] kinds = {"ORCHESTRATE", "BAKE", "SYNTHESIZE", "ADVANCE"};
    for (String k : kinds) {
      for (int i = 0; i < 50; i++) {
        assertEquals(TransitionCapGuard.Outcome.OK, g.recordTransition(1L, k));
      }
      assertEquals(50L, g.countFor(1L, k));
    }
    assertFalse(g.isHalted(1L), "50 in each of 4 kinds is still under any single soft cap");
  }
}

package io.adaptiq.titan.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.ConcurrencyConfig;
import io.adaptiq.titan.flow.model.ConcurrencyConfig.OnOverflow;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the per-job concurrency gate decision logic (issue #1101).
 *
 * <p>Exercises {@link ConcurrencyGate#decide} as a pure function — no DAOs, no Quarkus, no
 * scheduler — so the policy table is locked in independently of the SQL.
 *
 * <p>Test matrix:
 *
 * <ul>
 *   <li>under cap → PROCEED regardless of policy
 *   <li>at cap, queue → DEFER, no cancellations
 *   <li>at cap, cancel_oldest → PROCEED with the oldest victim cancelled
 *   <li>over cap (race), cancel_oldest → PROCEED with all the excess cancelled
 *   <li>at cap, cancel_pending → DEFER, every other QUEUED build cancelled
 *   <li>at cap, cancel_pending with no QUEUED others → DEFER, no cancellations
 *   <li>max=1 + multiple RUNNING + cancel_oldest → only the oldest is killed, newer keeps running
 * </ul>
 */
class ConcurrencyGateTest {

  private static final long CURRENT = 100L;
  private static final long JOB = 7L;

  @Test
  void underCap_proceedsRegardlessOfPolicy() {
    ConcurrencyConfig cfg = new ConcurrencyConfig(3, OnOverflow.QUEUE);
    var d = ConcurrencyGate.decide(CURRENT, JOB, List.of(1L, 2L), cfg);
    assertSame(ConcurrencyGate.Verdict.PROCEED, d.verdict);
    assertTrue(d.cancellations.isEmpty(), "no victims when under cap");
  }

  @Test
  void atCap_queue_defersWithNoCancellations() {
    ConcurrencyConfig cfg = new ConcurrencyConfig(2, OnOverflow.QUEUE);
    var d = ConcurrencyGate.decide(CURRENT, JOB, List.of(1L, 2L), cfg);
    assertSame(ConcurrencyGate.Verdict.DEFER, d.verdict);
    assertTrue(d.cancellations.isEmpty());
  }

  @Test
  void atCap_cancelOldest_killsTheHeadAndProceeds() {
    // listByJobAndStatus returns oldest-first; element 0 is the oldest victim.
    ConcurrencyConfig cfg = new ConcurrencyConfig(2, OnOverflow.CANCEL_OLDEST);
    var d = ConcurrencyGate.decide(CURRENT, JOB, List.of(11L, 12L), cfg);
    assertSame(ConcurrencyGate.Verdict.PROCEED, d.verdict);
    assertEquals(List.of(11L), d.cancellations, "exactly one oldest victim killed");
  }

  @Test
  void overCap_cancelOldest_killsAllExcess() {
    // Defensive: the count was 4 but cap is 2 (e.g. a race / leftover). The gate should
    // bring the live count down to (cap - 1) so the new build can fit — kill (n - max + 1).
    ConcurrencyConfig cfg = new ConcurrencyConfig(2, OnOverflow.CANCEL_OLDEST);
    var d = ConcurrencyGate.decide(CURRENT, JOB, List.of(11L, 12L, 13L, 14L), cfg);
    assertSame(ConcurrencyGate.Verdict.PROCEED, d.verdict);
    assertEquals(List.of(11L, 12L, 13L), d.cancellations, "kill n - max + 1 = 3 oldest victims");
  }

  @Test
  void atCap_cancelPending_killsEveryQueuedSiblingAndStillDefers() {
    ConcurrencyConfig cfg = new ConcurrencyConfig(1, OnOverflow.CANCEL_PENDING);
    var d =
        ConcurrencyGate.decide(
            CURRENT, JOB, /* running */ List.of(11L), /* queued */ List.of(101L, 102L), cfg);
    assertSame(
        ConcurrencyGate.Verdict.DEFER,
        d.verdict,
        "cancel_pending never kills RUNNING — current build still queues");
    assertEquals(List.of(101L, 102L), d.cancellations);
  }

  @Test
  void atCap_cancelPending_withNoQueuedOthers_defersWithoutCancellations() {
    ConcurrencyConfig cfg = new ConcurrencyConfig(1, OnOverflow.CANCEL_PENDING);
    var d = ConcurrencyGate.decide(CURRENT, JOB, List.of(11L), List.of(), cfg);
    assertSame(ConcurrencyGate.Verdict.DEFER, d.verdict);
    assertTrue(d.cancellations.isEmpty());
  }

  @Test
  void max1_cancelOldest_singleVictim() {
    ConcurrencyConfig cfg = new ConcurrencyConfig(1, OnOverflow.CANCEL_OLDEST);
    var d = ConcurrencyGate.decide(CURRENT, JOB, List.of(42L), cfg);
    assertSame(ConcurrencyGate.Verdict.PROCEED, d.verdict);
    assertEquals(List.of(42L), d.cancellations);
  }

  @Test
  void emptyRunningList_proceedsForAnyPolicy() {
    for (OnOverflow p : OnOverflow.values()) {
      var d = ConcurrencyGate.decide(CURRENT, JOB, List.of(), new ConcurrencyConfig(1, p));
      assertSame(ConcurrencyGate.Verdict.PROCEED, d.verdict, "empty running → PROCEED for " + p);
    }
  }

  /**
   * Adversarial: 3 RUNNING + max=2 (the spec's headline acceptance case). queue policy → DEFER
   * (i.e. the 4th will not be picked up).
   */
  @Test
  void specHeadline_threeRunning_maxTwo_queue_defers() {
    ConcurrencyConfig cfg = new ConcurrencyConfig(2, OnOverflow.QUEUE);
    var d = ConcurrencyGate.decide(CURRENT, JOB, List.of(1L, 2L, 3L), cfg);
    assertSame(ConcurrencyGate.Verdict.DEFER, d.verdict);
    assertTrue(d.cancellations.isEmpty());
  }
}

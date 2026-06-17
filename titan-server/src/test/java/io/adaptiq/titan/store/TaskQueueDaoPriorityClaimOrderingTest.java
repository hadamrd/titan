package io.adaptiq.titan.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #1100 — task_queue claim ordering must be {@code ORDER BY priority DESC, created_at ASC}.
 *
 * <p>Runs the real production {@link TaskQueueDao} against an in-memory H2 with the production
 * Flyway schema (mirrors {@link TaskQueueDaoReapStaleH2Test}'s setup pattern), and asserts both
 * legs of the acceptance criteria:
 *
 * <ul>
 *   <li>tasks with priority HIGH (10), NORMAL (0), LOW (-10) — enqueued in <em>reverse</em> tier
 *       order (low → normal → high) — are claimed back in priority order (high → normal → low),
 *       proving the queue is priority-first and creation-order is the secondary sort, NOT primary;
 *   <li>three tasks at the SAME priority preserve FIFO — the oldest {@code created_at} is claimed
 *       first.
 * </ul>
 */
class TaskQueueDaoPriorityClaimOrderingTest {

  private TitanStores stores;

  @BeforeEach
  void setUp() throws Exception {
    Class<?> fake = Class.forName("io.adaptiq.titan.api.FakeTitanStores");
    Method create = fake.getDeclaredMethod("create");
    create.setAccessible(true);
    stores = (TitanStores) create.invoke(null);
  }

  // ── priority tier overrides FIFO ──────────────────────────────────────────

  @Test
  void claim_picksHighestPriorityFirst_acrossTiers() {
    // Enqueue in REVERSE priority order so a FIFO-only queue would return them lo→no→hi.
    long lowId = enqueueOrchestrate(-10);
    sleepForCreatedAtOrdering();
    long normId = enqueueOrchestrate(0);
    sleepForCreatedAtOrdering();
    long highId = enqueueOrchestrate(10);

    // First claim: HIGH (priority=10) — even though it was enqueued LAST.
    TaskQueueRow first = claimOrFail();
    assertEquals(highId, first.id, "highest-priority task must be claimed first");
    assertEquals(10, first.priority);

    // Second claim: NORMAL.
    TaskQueueRow second = claimOrFail();
    assertEquals(normId, second.id, "normal-priority task must be claimed second");
    assertEquals(0, second.priority);

    // Third claim: LOW.
    TaskQueueRow third = claimOrFail();
    assertEquals(lowId, third.id, "low-priority task must be claimed last");
    assertEquals(-10, third.priority);

    // Queue now drained.
    assertTrue(
        stores.taskQueue().claimTask("default", "controller-A").isEmpty(),
        "queue must be empty after all three claims");
  }

  // ── same priority preserves FIFO ──────────────────────────────────────────

  @Test
  void claim_preservesFifoWithinSamePriority() {
    long first = enqueueOrchestrate(0);
    sleepForCreatedAtOrdering();
    long second = enqueueOrchestrate(0);
    sleepForCreatedAtOrdering();
    long third = enqueueOrchestrate(0);

    assertEquals(first, claimOrFail().id, "FIFO: oldest same-priority task is claimed first");
    assertEquals(second, claimOrFail().id, "FIFO: second-oldest is claimed second");
    assertEquals(third, claimOrFail().id, "FIFO: newest is claimed last");
  }

  // ── claimed row has a lease token + claimed_by stamp ──────────────────────

  @Test
  void claim_writesLeaseTokenAndClaimedBy() {
    enqueueOrchestrate(5);
    TaskQueueRow row = claimOrFail();
    assertEquals("CLAIMED", row.status);
    assertNotNull(row.claimToken, "claim must write a non-null lease token (doc-27 G3)");
    assertEquals(
        "controller-A", row.claimedBy, "claimedBy must be stamped to the requested controller id");
    assertEquals(1, row.attempts, "attempts must increment on claim");
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private long enqueueOrchestrate(int priority) {
    return stores
        .taskQueue()
        .enqueue(
            "ORCHESTRATE", "default", priority, "{\"action\":\"ADVANCE\"}", 3, 300, null, null);
  }

  private TaskQueueRow claimOrFail() {
    Optional<TaskQueueRow> claimed = stores.taskQueue().claimTask("default", "controller-A");
    assertTrue(claimed.isPresent(), "expected a claimable task");
    return claimed.orElseThrow();
  }

  /**
   * H2's {@code CURRENT_TIMESTAMP} resolution per row in a tight loop can land identical values for
   * consecutive INSERTs — which makes the FIFO secondary-sort test ambiguous. A 5ms sleep is the
   * smallest stall that reliably moves the wall clock past H2's timestamp resolution on every JVM
   * we've seen in CI.
   */
  private static void sleepForCreatedAtOrdering() {
    try {
      Thread.sleep(5);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  // suppress "unused" warning on UUID import — keeps the imports ready for a follow-on test
  @SuppressWarnings("unused")
  private static UUID unused() {
    return UUID.randomUUID();
  }
}

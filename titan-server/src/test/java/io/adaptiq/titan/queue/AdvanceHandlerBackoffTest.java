package io.adaptiq.titan.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.TitanOrchestrator;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #827 — ADVANCE no-dispatch backoff.
 *
 * <p>Pins the exponential backoff ladder ({@code 5s → 15s → 60s → 300s cap}) that prevents the
 * orchestrator from burning O(N) ORCHESTRATE rows for a build of duration N when it is waiting on a
 * long-running step, an approval gate, or a queue with no live worker.
 *
 * <p>Adversarial scenarios covered:
 *
 * <ul>
 *   <li>100 consecutive no-dispatch ticks → ladder + cap (no per-tick blowup);
 *   <li>a productive tick wedged in the middle of a backoff sequence resets the counter;
 *   <li>two interleaved builds keep independent counters (per-build state, not global);
 *   <li>park / build-finished / CAS-loss / non-benign-throw paths are unchanged.
 * </ul>
 *
 * <p>Uses a {@link RecordingSupport} that captures handler calls in-memory — keeps the assertions
 * deterministic and independent of the live Quarkus {@code QueueProcessor} scheduler that would
 * otherwise concurrently consume any QUEUED ADVANCE rows we'd insert into a real DAO.
 */
class AdvanceHandlerBackoffTest {

  private RecordingSupport support;

  @BeforeEach
  void setUp() {
    support = new RecordingSupport();
  }

  // ── ladder math (pure) ─────────────────────────────────────────────────

  @Test
  void delayLadder_climbsAndCaps() {
    assertEquals(5, AdvanceHandler.delayForNoDispatchCount(1));
    assertEquals(15, AdvanceHandler.delayForNoDispatchCount(2));
    assertEquals(60, AdvanceHandler.delayForNoDispatchCount(3));
    assertEquals(300, AdvanceHandler.delayForNoDispatchCount(4));
    assertEquals(300, AdvanceHandler.delayForNoDispatchCount(5));
    assertEquals(300, AdvanceHandler.delayForNoDispatchCount(100));
    assertEquals(5, AdvanceHandler.delayForNoDispatchCount(0));
    assertEquals(5, AdvanceHandler.delayForNoDispatchCount(-1));
  }

  // ── handler integration with recording support ─────────────────────────

  @Test
  void productiveTick_reArmsAtFiveSeconds_andTracksZeroNoDispatch() {
    long buildId = 1L;
    AdvanceHandler h = newHandler(productive());

    runOnce(h, buildId);

    assertEquals(List.of(5), delays(support));
    assertEquals(List.of(buildId), buildIds(support));
    assertEquals(0, h.noDispatchCountFor(buildId));
  }

  @Test
  void threeConsecutiveNoDispatchTicks_followLadder_5_15_60() {
    long buildId = 1L;
    AdvanceHandler h = newHandler(noDispatch());

    runOnce(h, buildId);
    runOnce(h, buildId);
    runOnce(h, buildId);

    assertEquals(List.of(5, 15, 60), delays(support));
    assertEquals(3, h.noDispatchCountFor(buildId));
  }

  @Test
  void backoffCapsAt300_aroundTickFourAndBeyond() {
    long buildId = 1L;
    AdvanceHandler h = newHandler(noDispatch());

    for (int i = 0; i < 6; i++) {
      runOnce(h, buildId);
    }

    assertEquals(List.of(5, 15, 60, 300, 300, 300), delays(support));
  }

  @Test
  void productiveTickAfterBackoff_resetsLadderToFive() {
    long buildId = 1L;
    AtomicReference<TitanOrchestrator.AdvanceResult> next =
        new AtomicReference<>(noDispatchResult());
    AdvanceHandler h = newHandler((d, b) -> next.get());

    runOnce(h, buildId); // 5
    runOnce(h, buildId); // 15
    runOnce(h, buildId); // 60
    assertEquals(3, h.noDispatchCountFor(buildId));

    next.set(productiveResult());
    runOnce(h, buildId); // 5 (reset)
    assertEquals(0, h.noDispatchCountFor(buildId), "productive tick must reset counter");

    next.set(noDispatchResult());
    runOnce(h, buildId); // 5 (first no-dispatch from a reset state)

    assertEquals(List.of(5, 15, 60, 5, 5), delays(support));
  }

  @Test
  void parked_skipsReArm_andEvictsCounter() {
    long buildId = 1L;
    AtomicReference<TitanOrchestrator.AdvanceResult> next =
        new AtomicReference<>(noDispatchResult());
    AdvanceHandler h = newHandler((d, b) -> next.get());

    runOnce(h, buildId);
    runOnce(h, buildId);
    int beforeCount = support.advances.size();
    assertNotEquals(0, h.noDispatchCountFor(buildId), "precondition: counter populated");

    next.set(parkedResult());
    runOnce(h, buildId);

    assertEquals(
        beforeCount, support.advances.size(), "parked tick must NOT enqueue a follow-up ADVANCE");
    assertEquals(0, h.noDispatchCountFor(buildId), "parked must evict the backoff counter");
    assertEquals(3, support.completes.size(), "every tick still completes its current task");
  }

  @Test
  void buildFinished_skipsReArm_andEvictsCounter() {
    long buildId = 1L;
    AtomicReference<TitanOrchestrator.AdvanceResult> next =
        new AtomicReference<>(noDispatchResult());
    AdvanceHandler h = newHandler((d, b) -> next.get());

    runOnce(h, buildId);
    int beforeCount = support.advances.size();

    next.set(finishedResult());
    runOnce(h, buildId);

    assertEquals(
        beforeCount,
        support.advances.size(),
        "buildFinished tick must NOT enqueue a follow-up ADVANCE");
    assertEquals(0, h.noDispatchCountFor(buildId));
  }

  @Test
  void casLoss_reEnqueuesAtOneSecond_unchangedBy827() {
    long buildId = 1L;
    AdvanceHandler h =
        newHandler(
            (d, b) -> {
              throw new io.adaptiq.titan.flow.CasLostException("compareAndSetStatus lost");
            });

    runOnce(h, buildId);

    assertEquals(
        List.of(1), delays(support), "CAS-loss path must re-enqueue at 1s — NOT backoff ladder");
    assertEquals(List.of(buildId), buildIds(support));
    assertEquals(0, support.failedBuilds.size(), "CAS-loss must NOT fail-close the build (#911)");
  }

  @Test
  void missingBuildId_failsTaskWithoutInvokingOrchestrator() {
    AdvanceHandler h =
        new AdvanceHandler(
            support,
            (d, b) -> {
              throw new AssertionError("must not be invoked on missing-buildId path");
            });

    TaskQueueRow t = new TaskQueueRow();
    t.payloadJson = "{\"action\":\"ADVANCE\"}";
    h.handle(null, t, Map.of("action", "ADVANCE"));

    assertEquals(0, support.advances.size(), "no re-enqueue on missing buildId");
    assertEquals(1, support.failedTasks.size(), "task must be failed with a reason");
    assertTrue(support.failedTasks.get(0).reason.contains("missing buildId"));
  }

  @Test
  void hundredNoDispatchTicks_followLadderThenCap_neverLinear() {
    // Adversarial: a step that returns no-dispatch for 100 consecutive ticks must NOT generate
    // 100 ADVANCE rows scheduled 5s apart (the pre-#827 behaviour). Each tick still produces
    // EXACTLY one re-enqueue row — that part is unchanged. What #827 buys us is the climbing
    // delay: the first three ticks walk the ladder (5, 15, 60), every subsequent tick caps at
    // 300s. Total wall-clock window covered by 100 ticks: 5 + 15 + 60 + 97*300 ≈ 8.1 hours,
    // vs. the pre-#827 cadence which covered just 100*5 = ~8 minutes.
    long buildId = 42L;
    AdvanceHandler h = newHandler(noDispatch());

    for (int i = 0; i < 100; i++) {
      runOnce(h, buildId);
    }

    assertEquals(100, support.advances.size(), "still one re-enqueue per tick (1:1 contract)");
    assertEquals(5, support.advances.get(0).delaySeconds);
    assertEquals(15, support.advances.get(1).delaySeconds);
    assertEquals(60, support.advances.get(2).delaySeconds);
    for (int i = 3; i < 100; i++) {
      assertEquals(
          300, support.advances.get(i).delaySeconds, "tick #" + (i + 1) + " must cap at 300s");
    }

    long coveredSeconds = support.advances.stream().mapToLong(r -> r.delaySeconds).sum();
    assertTrue(
        coveredSeconds > 3600,
        "100 backoff ticks must cover more than an hour of wall-time, got " + coveredSeconds);
  }

  @Test
  void twoBuilds_keepIndependentBackoffCounters() {
    long buildA = 1L;
    long buildB = 2L;
    AdvanceHandler h = newHandler(noDispatch());

    runOnce(h, buildA); // A: 5
    runOnce(h, buildA); // A: 15
    assertEquals(0, h.noDispatchCountFor(buildB), "B must not inherit A's counter");

    runOnce(h, buildB); // B: 5 (fresh ladder)
    runOnce(h, buildA); // A: 60
    runOnce(h, buildB); // B: 15

    assertEquals(List.of(buildA, buildA, buildB, buildA, buildB), buildIds(support));
    assertEquals(List.of(5, 15, 5, 60, 15), delays(support));
  }

  @Test
  void nonBenignThrow_evictsBackoffCounter_andFailCloses_noReArm() {
    long buildId = 1L;
    AtomicReference<Boolean> shouldThrow = new AtomicReference<>(false);
    AdvanceHandler h =
        newHandler(
            (d, b) -> {
              if (shouldThrow.get()) {
                throw new RuntimeException("genuine engine bug");
              }
              return noDispatchResult();
            });

    runOnce(h, buildId);
    int beforeCount = support.advances.size();
    assertNotEquals(0, h.noDispatchCountFor(buildId));

    shouldThrow.set(true);
    runOnce(h, buildId);

    assertEquals(beforeCount, support.advances.size(), "fail-close must NOT re-enqueue ADVANCE");
    assertEquals(0, h.noDispatchCountFor(buildId), "fail-close must evict counter");
    assertEquals(1, support.failedBuilds.size());
    assertTrue(support.failedBuilds.get(0).reason.contains("genuine engine bug"));
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private AdvanceHandler newHandler(
      BiFunction<TitanStores, Long, TitanOrchestrator.AdvanceResult> fn) {
    return new AdvanceHandler(support, fn);
  }

  private static BiFunction<TitanStores, Long, TitanOrchestrator.AdvanceResult> productive() {
    return (d, b) -> productiveResult();
  }

  private static BiFunction<TitanStores, Long, TitanOrchestrator.AdvanceResult> noDispatch() {
    return (d, b) -> noDispatchResult();
  }

  private static TitanOrchestrator.AdvanceResult productiveResult() {
    return new TitanOrchestrator.AdvanceResult(1, 0, false, null, false);
  }

  private static TitanOrchestrator.AdvanceResult noDispatchResult() {
    return new TitanOrchestrator.AdvanceResult(0, 0, false, null, false);
  }

  private static TitanOrchestrator.AdvanceResult parkedResult() {
    return new TitanOrchestrator.AdvanceResult(0, 0, false, null, true);
  }

  private static TitanOrchestrator.AdvanceResult finishedResult() {
    return new TitanOrchestrator.AdvanceResult(0, 0, true, "SUCCESS", false);
  }

  private void runOnce(AdvanceHandler h, long buildId) {
    TaskQueueRow t = new TaskQueueRow();
    t.payloadJson = "{\"action\":\"ADVANCE\",\"buildId\":" + buildId + "}";
    t.type = "ORCHESTRATE";
    h.handle(null, t, Map.of("buildId", buildId));
  }

  private static List<Integer> delays(RecordingSupport s) {
    List<Integer> out = new ArrayList<>(s.advances.size());
    for (RecordingSupport.EnqueueAdvance r : s.advances) {
      out.add(r.delaySeconds);
    }
    return out;
  }

  private static List<Long> buildIds(RecordingSupport s) {
    List<Long> out = new ArrayList<>(s.advances.size());
    for (RecordingSupport.EnqueueAdvance r : s.advances) {
      out.add(r.buildId);
    }
    return out;
  }

  /**
   * In-memory recording stand-in for {@link QueueHandlerSupport}. Captures every call to the four
   * handler-facing methods so assertions can be made on what the handler attempted, with no real
   * DAO or Quarkus scheduler in the loop.
   */
  static final class RecordingSupport extends QueueHandlerSupport {
    final List<EnqueueAdvance> advances = new ArrayList<>();
    final List<TaskQueueRow> completes = new ArrayList<>();
    final List<FailedTask> failedTasks = new ArrayList<>();
    final List<FailedBuild> failedBuilds = new ArrayList<>();

    RecordingSupport() {
      super(() -> null);
    }

    @Override
    void enqueueAdvance(TitanStores daos, long buildId, int delaySeconds) {
      advances.add(new EnqueueAdvance(buildId, delaySeconds));
    }

    @Override
    void completeTaskSafely(TitanStores daos, TaskQueueRow task) {
      completes.add(task);
    }

    @Override
    void failTaskSafely(TitanStores daos, TaskQueueRow task, String reason) {
      failedTasks.add(new FailedTask(task, reason));
    }

    @Override
    void markBuildFailed(TitanStores daos, long buildId, String reason) {
      failedBuilds.add(new FailedBuild(buildId, reason));
    }

    static final class EnqueueAdvance {
      final long buildId;
      final int delaySeconds;

      EnqueueAdvance(long buildId, int delaySeconds) {
        this.buildId = buildId;
        this.delaySeconds = delaySeconds;
      }
    }

    static final class FailedTask {
      final TaskQueueRow task;
      final String reason;

      FailedTask(TaskQueueRow task, String reason) {
        this.task = task;
        this.reason = reason;
      }
    }

    static final class FailedBuild {
      final long buildId;
      final String reason;

      FailedBuild(long buildId, String reason) {
        this.buildId = buildId;
        this.reason = reason;
      }
    }
  }
}

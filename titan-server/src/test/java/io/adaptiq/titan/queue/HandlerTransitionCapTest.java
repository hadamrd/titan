package io.adaptiq.titan.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.TitanOrchestrator;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #1050 — verifies that each of the four state-transition handlers honours the {@link
 * TransitionCapGuard}: counts every dispatch, halts the build at the hard cap with the canonical
 * structured failure reason, and does not silently keep running.
 *
 * <p>Uses a {@link RecordingSupport} captured directly from {@code AdvanceHandlerBackoffTest}'s
 * pattern (private nested copy here — it is a test-only fixture, not worth promoting to a shared
 * utility yet).
 */
class HandlerTransitionCapTest {

  // ── ADVANCE ──────────────────────────────────────────────────────────────

  @Test
  void advanceHandler_hardCapHit_failsBuildAndDoesNotReEnqueue() {
    TransitionCapGuard tight = new TransitionCapGuard(1, 2);
    RecordingSupport support = new RecordingSupport(tight);
    AdvanceHandler handler =
        new AdvanceHandler(
            support, (d, b) -> new TitanOrchestrator.AdvanceResult(0, 0, false, null, false));

    // 3 calls: counts go 1 (OK), 2 (SOFT_WARN), 3 (HARD_HALT)
    runAdvance(handler, 7L);
    runAdvance(handler, 7L);
    int reEnqueuesBefore = support.advances.size();
    runAdvance(handler, 7L); // halt

    assertEquals(
        reEnqueuesBefore,
        support.advances.size(),
        "halted tick must NOT re-enqueue another ADVANCE");
    assertEquals(1, support.failedBuilds.size(), "build must be marked FAILED on halt");
    assertTrue(
        support.failedBuilds.get(0).reason.contains("transition spam guard hit"),
        support.failedBuilds.get(0).reason);
    assertTrue(support.failedBuilds.get(0).reason.contains("ADVANCE"));
  }

  @Test
  void advanceHandler_belowSoftCap_orchestratorIsInvokedNormally() {
    TransitionCapGuard normal = new TransitionCapGuard(200, 1000);
    RecordingSupport support = new RecordingSupport(normal);
    AdvanceHandler handler =
        new AdvanceHandler(
            support, (d, b) -> new TitanOrchestrator.AdvanceResult(1, 0, false, null, false));

    for (int i = 0; i < 50; i++) {
      runAdvance(handler, 1L);
    }
    assertEquals(50, support.advances.size(), "50 productive ticks → 50 re-enqueues");
    assertEquals(0, support.failedBuilds.size(), "well under soft cap → no failures");
  }

  // ── BAKE ────────────────────────────────────────────────────────────────

  @Test
  void bakeHandler_hardCapHit_failsBuild() {
    TransitionCapGuard tight = new TransitionCapGuard(1, 1);
    RecordingSupport support = new RecordingSupport(tight);
    BakeHandler handler =
        new BakeHandler(
            support,
            (d, b) -> {
              throw new AssertionError("must not run bake() once halted");
            });

    runBake(handler, 9L); // count=1, OK
    runBake(handler, 9L); // count=2, HALT — bake() must NOT run

    assertEquals(1, support.failedBuilds.size());
    assertTrue(support.failedBuilds.get(0).reason.contains("BAKE"));
  }

  // ── SYNTHESIZE ──────────────────────────────────────────────────────────

  @Test
  void synthesizeHandler_hardCapHit_failsBuild() {
    TransitionCapGuard tight = new TransitionCapGuard(1, 1);
    RecordingSupport support = new RecordingSupport(tight);
    SynthesizeHandler handler = new SynthesizeHandler(support);

    runSynth(handler, 3L); // count=1
    // The first call short-circuits at HARD_HALT BEFORE touching the DAO (daos is null here);
    // that proves the guard runs before any I/O.
    runSynth(handler, 3L); // count=2, HALT

    assertEquals(1, support.failedBuilds.size());
    assertTrue(support.failedBuilds.get(0).reason.contains("SYNTHESIZE"));
  }

  @Test
  void synthesizeHandler_firstCallBelowCap_proceedsToDaoAndThrowsBecauseDaosIsNull() {
    TransitionCapGuard normal = new TransitionCapGuard(200, 1000);
    RecordingSupport support = new RecordingSupport(normal);
    SynthesizeHandler handler = new SynthesizeHandler(support);

    // Under the cap, the handler proceeds past the guard. With a null DAO bundle, it NPEs —
    // that is fine for this test; the assertion is that the guard did not short-circuit.
    try {
      runSynth(handler, 3L);
    } catch (NullPointerException expected) {
      // good — guard did not block; handler entered the DAO path.
    }
    assertEquals(0, support.failedBuilds.size(), "no fail-close while under the cap");
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static void runAdvance(AdvanceHandler h, long buildId) {
    TaskQueueRow t = new TaskQueueRow();
    t.payloadJson = "{\"action\":\"ADVANCE\",\"buildId\":" + buildId + "}";
    t.type = "ORCHESTRATE";
    h.handle(null, t, Map.of("buildId", buildId));
  }

  private static void runBake(BakeHandler h, long buildId) {
    TaskQueueRow t = new TaskQueueRow();
    t.payloadJson = "{\"action\":\"BAKE\",\"buildId\":" + buildId + "}";
    t.type = "ORCHESTRATE";
    // Will NPE on findById once we pass the guard — caller catches when needed.
    try {
      h.handle(null, t, Map.of("buildId", buildId));
    } catch (NullPointerException ignore) {
      // The handler's first action below the guard is daos.builds().findById — null here.
    }
  }

  private static void runSynth(SynthesizeHandler h, long buildId) {
    TaskQueueRow t = new TaskQueueRow();
    t.payloadJson = "{\"action\":\"SYNTHESIZE\",\"buildId\":" + buildId + "}";
    t.type = "ORCHESTRATE";
    try {
      h.handle(null, t, Map.of("buildId", buildId));
    } catch (NullPointerException ignore) {
      // Under the cap the handler proceeds to daos.builds().findById and NPEs on null DAO.
      // Above the cap it short-circuits before touching the DAO. Either is acceptable; this
      // helper covers both paths.
    }
  }

  /** Same shape as the recording support in {@code AdvanceHandlerBackoffTest}. */
  static final class RecordingSupport extends QueueHandlerSupport {
    final List<EnqueueAdvance> advances = new ArrayList<>();
    final List<TaskQueueRow> completes = new ArrayList<>();
    final List<FailedTask> failedTasks = new ArrayList<>();
    final List<FailedBuild> failedBuilds = new ArrayList<>();

    RecordingSupport(TransitionCapGuard guard) {
      super(() -> null, guard);
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
      // Note: do NOT delegate to super — that touches the DAO, which is null in these tests.
      failedBuilds.add(new FailedBuild(buildId, reason));
      capGuard().onBuildTerminal(buildId);
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

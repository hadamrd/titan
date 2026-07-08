package io.adaptiq.titan.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.db.TitanDataException;
import io.adaptiq.titan.flow.CasLostException;
import io.adaptiq.titan.flow.NotificationDispatcher;
import io.adaptiq.titan.flow.TitanOrchestrator;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the per-message handlers extracted from {@code QueueProcessor} (design 67 step 6).
 *
 * <p>Each handler is exercised end-to-end against a real {@link TitanStores} (H2 / Quarkus in-mem),
 * with a stubbed {@link QueueHandlerSupport} where useful (one is constructed with the production
 * dispatcher Supplier — the IT seam — and tests assert against the persisted side effects).
 *
 * <p>The acid-test for the extraction: the CAS-loss tolerance from issue #911 (a benign {@link
 * CasLostException} from inside {@code advance()} must re-enqueue ADVANCE, NOT mark the build
 * FAILED) is pinned by {@link #advanceHandler_benignCasLost_reenqueuesAdvanceNoFailClose}.
 */
@QuarkusTest
class QueueHandlersTest {

  @Inject TitanStores stores;

  private QueueHandlerSupport support;

  @BeforeEach
  void resetDispatcher() {
    // Silence the bake-failure notify path so markBuildFailed has no side dependency.
    QueueProcessor.setBakeFailureDispatcherForTest(null);
    support = new QueueHandlerSupport(() -> null);
  }

  @AfterEach
  void clearDispatcher() {
    QueueProcessor.setBakeFailureDispatcherForTest(null);
  }

  // ── AdvanceHandler ───────────────────────────────────────────────────────

  @Test
  void advanceHandler_missingBuildId_failsTaskNoBuildSideEffect() {
    long taskId = insertOrchestrateTask(null, "{\"action\":\"ADVANCE\"}");
    TaskQueueRow task = stores.taskQueue().findById(taskId).orElseThrow();

    new AdvanceHandler(support, throwingOrchestrator())
        .handle(stores, task, Map.of("action", "ADVANCE"));

    TaskQueueRow after = stores.taskQueue().findById(taskId).orElseThrow();
    assertEquals("FAILED", after.status, "missing buildId must fail the task");
  }

  @Test
  void advanceHandler_benignCasLost_reenqueuesAdvanceNoFailClose() {
    // Acid-test for issue #911 — a CAS race must NOT fail the build. The handler must
    // (a) re-enqueue ADVANCE (b) complete the current task (c) leave the build status alone.
    long buildId = freshBuildWithStatus("RUNNING");
    long taskId =
        insertOrchestrateTask(buildId, "{\"action\":\"ADVANCE\",\"buildId\":" + buildId + "}");
    TaskQueueRow task = stores.taskQueue().findById(taskId).orElseThrow();

    BiFunction<TitanStores, Long, TitanOrchestrator.AdvanceResult> casLoss =
        (s, bid) -> {
          throw new CasLostException("compareAndSetStatus lost race for node X");
        };

    new AdvanceHandler(support, casLoss).handle(stores, task, Map.of("buildId", buildId));

    // (a) ADVANCE re-enqueued for the same build (the original counts as 1; a second exists)
    long advanceCount = countAdvanceTasksFor(buildId);
    assertTrue(advanceCount >= 2, "expected a re-enqueued ADVANCE — found " + advanceCount);
    // (b) original task is COMPLETED (the CAS-loss path completes the row, doesn't fail it)
    TaskQueueRow after = stores.taskQueue().findById(taskId).orElseThrow();
    assertEquals("COMPLETED", after.status);
    // (c) build status untouched
    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("RUNNING", build.status, "CAS-loss must NOT fail-close the build (issue #911)");
    assertNull(build.failureSummary);
  }

  @Test
  void advanceHandler_nonBenignRuntime_failsBuildAndTask() {
    long buildId = freshBuildWithStatus("RUNNING");
    long taskId =
        insertOrchestrateTask(buildId, "{\"action\":\"ADVANCE\",\"buildId\":" + buildId + "}");
    TaskQueueRow task = stores.taskQueue().findById(taskId).orElseThrow();

    BiFunction<TitanStores, Long, TitanOrchestrator.AdvanceResult> boom =
        (s, bid) -> {
          throw new RuntimeException("genuine engine bug");
        };

    new AdvanceHandler(support, boom).handle(stores, task, Map.of("buildId", buildId));

    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("FAILED", build.status, "non-benign throw must fail-close");
    assertNotNull(build.failureSummary);
    assertTrue(build.failureSummary.contains("genuine engine bug"));
  }

  // ── BakeHandler ──────────────────────────────────────────────────────────

  @Test
  void bakeHandler_missingBuildId_failsTaskOnly() {
    long taskId = insertOrchestrateTask(null, "{\"action\":\"BAKE\"}");
    TaskQueueRow task = stores.taskQueue().findById(taskId).orElseThrow();

    new BakeHandler(
            support,
            (s, bid) -> {
              throw new AssertionError("execution factory must not be called");
            })
        .handle(stores, task, Map.of("action", "BAKE"));

    TaskQueueRow after = stores.taskQueue().findById(taskId).orElseThrow();
    assertEquals("FAILED", after.status);
  }

  @Test
  void bakeHandler_buildNotFound_failsTaskOnly() {
    // Task row carries null build_id (FK would block a phantom buildId); the action payload
    // names a buildId that the handler then fails to find in the builds table.
    long taskId = insertOrchestrateTask(null, "{\"action\":\"BAKE\",\"buildId\":999999000}");
    TaskQueueRow task = stores.taskQueue().findById(taskId).orElseThrow();

    new BakeHandler(
            support,
            (s, bid) -> {
              throw new AssertionError("execution factory must not be called");
            })
        .handle(stores, task, Map.of("buildId", 999_999_000L));

    TaskQueueRow after = stores.taskQueue().findById(taskId).orElseThrow();
    assertEquals("FAILED", after.status);
  }

  @Test
  void bakeHandler_wrappedTransactionFailure_persistsRootCauseNotBareWrapper() {
    // Issue #36 regression: a bake blowing up inside a transaction used to persist ONLY the
    // wrapper's message — builds died with an opaque 'Transaction failed'. The persisted
    // error_message/failure_summary must now carry the root cause's class + message.
    long jobId = freshJob();
    long buildId = freshBuild(jobId, "QUEUED", "{\"stages\":[]}");
    long taskId =
        insertOrchestrateTask(buildId, "{\"action\":\"BAKE\",\"buildId\":" + buildId + "}");
    TaskQueueRow task = stores.taskQueue().findById(taskId).orElseThrow();

    new BakeHandler(
            support,
            (s, bid) -> {
              throw new TitanDataException(
                  "Transaction failed",
                  new IllegalStateException(
                      "Stage 'build' has an invalid `when:` expression"
                          + " (steps.unit-test.result == 'success'): unknown variable 'steps'"));
            })
        .handle(stores, task, Map.of("buildId", buildId));

    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("FAILED", build.status);
    // Adversarial oracle: the CONTENT, not just the status. A bare wrapper message is the bug.
    assertNotEquals("Transaction failed", build.failureSummary);
    assertNotEquals("bake failed: Transaction failed", build.failureSummary);
    assertNotEquals("Transaction failed", build.errorMessage);
    assertNotEquals("bake failed: Transaction failed", build.errorMessage);
    assertTrue(
        build.failureSummary.contains("TitanDataException: Transaction failed"),
        "wrapper class+message must be present: " + build.failureSummary);
    assertTrue(
        build.failureSummary.contains("IllegalStateException"),
        "root-cause class must be present: " + build.failureSummary);
    assertTrue(
        build.failureSummary.contains("unknown variable 'steps'"),
        "root-cause message must be present: " + build.failureSummary);
    assertTrue(
        build.errorMessage.contains("unknown variable 'steps'"),
        "error_message must carry the root cause too: " + build.errorMessage);

    TaskQueueRow after = stores.taskQueue().findById(taskId).orElseThrow();
    assertEquals("FAILED", after.status);
    assertTrue(
        after.resultJson.contains("unknown variable 'steps'"),
        "the failed task's result must carry the root cause: " + after.resultJson);
  }

  @Test
  void bakeHandler_causelessRuntime_persistsClassAndMessage() {
    long jobId = freshJob();
    long buildId = freshBuild(jobId, "QUEUED", "{\"stages\":[]}");
    long taskId =
        insertOrchestrateTask(buildId, "{\"action\":\"BAKE\",\"buildId\":" + buildId + "}");
    TaskQueueRow task = stores.taskQueue().findById(taskId).orElseThrow();

    new BakeHandler(
            support,
            (s, bid) -> {
              throw new IllegalStateException("boom without a cause");
            })
        .handle(stores, task, Map.of("buildId", buildId));

    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("FAILED", build.status);
    assertEquals("bake failed: IllegalStateException: boom without a cause", build.failureSummary);
  }

  // ── SynthesizeHandler ────────────────────────────────────────────────────

  @Test
  void synthesizeHandler_modelAlreadyPresent_enqueuesBakeAndCompletes() {
    long jobId = freshJob();
    long buildId =
        freshBuild(
            jobId,
            "QUEUED",
            // a minimal but non-blank model — handler just checks blankness
            "{\"stages\":[]}");
    long taskId =
        insertOrchestrateTask(buildId, "{\"action\":\"SYNTHESIZE\",\"buildId\":" + buildId + "}");
    TaskQueueRow task = stores.taskQueue().findById(taskId).orElseThrow();

    new SynthesizeHandler(support).handle(stores, task, Map.of("buildId", buildId));

    // BAKE enqueued.
    long bakeCount =
        stores.taskQueue().listByBuild(buildId).stream()
            .filter(t -> t.payloadJson != null && t.payloadJson.contains("\"BAKE\""))
            .count();
    assertTrue(bakeCount >= 1, "BAKE must be enqueued — found " + bakeCount);

    TaskQueueRow after = stores.taskQueue().findById(taskId).orElseThrow();
    assertEquals("COMPLETED", after.status);
  }

  @Test
  void synthesizeHandler_buildNotFound_failsTaskOnly() {
    long taskId = insertOrchestrateTask(null, "{\"action\":\"SYNTHESIZE\",\"buildId\":998888777}");
    TaskQueueRow task = stores.taskQueue().findById(taskId).orElseThrow();

    new SynthesizeHandler(support).handle(stores, task, Map.of("buildId", 998_888_777L));

    TaskQueueRow after = stores.taskQueue().findById(taskId).orElseThrow();
    assertEquals("FAILED", after.status);
  }

  // ── ReplayFromNodeHandler ────────────────────────────────────────────────

  @Test
  void replayHandler_missingReplayedFromNodeId_failsBuildAndTask() {
    // A REPLAY_FROM_NODE task aimed at a build without replayed_from_node_id is a programming
    // error — the handler MUST fail-close the build with the controller-internal-error reason.
    long buildId = freshBuildWithStatus("QUEUED");
    long taskId =
        insertOrchestrateTask(
            buildId, "{\"action\":\"REPLAY_FROM_NODE\",\"buildId\":" + buildId + "}");
    TaskQueueRow task = stores.taskQueue().findById(taskId).orElseThrow();

    new ReplayFromNodeHandler(support).handle(stores, task, Map.of("buildId", buildId));

    TaskQueueRow after = stores.taskQueue().findById(taskId).orElseThrow();
    assertEquals("FAILED", after.status);
    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("FAILED", build.status);
    assertNotNull(build.failureSummary);
  }

  @Test
  void replayHandler_buildNotFound_failsTaskOnly() {
    long taskId =
        insertOrchestrateTask(null, "{\"action\":\"REPLAY_FROM_NODE\",\"buildId\":997777666}");
    TaskQueueRow task = stores.taskQueue().findById(taskId).orElseThrow();

    new ReplayFromNodeHandler(support).handle(stores, task, Map.of("buildId", 997_777_666L));

    TaskQueueRow after = stores.taskQueue().findById(taskId).orElseThrow();
    assertEquals("FAILED", after.status);
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private long freshJob() {
    JobRow row = new JobRow();
    row.fullName = "qp-handlers/" + System.nanoTime() + "/" + UUID.randomUUID();
    row.pipelineScript = "stages:\n  - name: s1\n    steps:\n      - sh: echo hi\n";
    row.configJson = "{}";
    row.enabled = true;
    return stores.jobs().insert(row);
  }

  private long freshBuildWithStatus(String status) {
    return freshBuild(freshJob(), status, null);
  }

  private long freshBuild(long jobId, String status, String pipelineModelJson) {
    BuildRow b = new BuildRow();
    b.jobId = jobId;
    b.buildNumber = stores.builds().nextBuildNumber(jobId);
    b.status = status;
    b.queuedAt = Instant.now();
    b.triggeredBy = "test";
    b.triggerType = "manual";
    b.pipelineModelJson = pipelineModelJson;
    return stores.withTransaction(c -> stores.builds().insert(c, b));
  }

  private long insertOrchestrateTask(Long buildId, String payloadJson) {
    TaskQueueRow t = new TaskQueueRow();
    t.type = "ORCHESTRATE";
    t.queueName = "default";
    t.status = "CLAIMED"; // simulate post-claim
    t.priority = 0;
    t.payloadJson = payloadJson;
    t.attempts = 1;
    t.maxAttempts = 3;
    t.visibilityTimeoutSeconds = 3600;
    t.buildId = buildId;
    t.availableAt = Instant.now();
    t.claimToken = UUID.randomUUID();
    t.claimedBy = "test-controller";
    t.claimedAt = Instant.now();
    return stores.taskQueue().insert(t);
  }

  private long countAdvanceTasksFor(long buildId) {
    return stores.taskQueue().listByBuild(buildId).stream()
        .filter(t -> t.payloadJson != null && t.payloadJson.contains("\"ADVANCE\""))
        .count();
  }

  /**
   * Orchestrator factory that throws if invoked — used by sad-path tests that must short-circuit.
   */
  private static BiFunction<TitanStores, Long, TitanOrchestrator.AdvanceResult>
      throwingOrchestrator() {
    return (s, bid) -> {
      throw new AssertionError("orchestrator must not be invoked on the missing-buildId path");
    };
  }

  /** Keep these symbols live for the IDE — the @QuarkusTest discovery imports them. */
  @SuppressWarnings("unused")
  private static List<Object> keepImports() {
    return List.of(new HashMap<>(), new NotificationDispatcher());
  }
}

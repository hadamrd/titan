package io.adaptiq.titan.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.AgentRow;
import io.adaptiq.titan.store.rows.AuditLogRow;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NoWorkerTimeoutSweeper} (issue #1049 — fail-fast on stale step-queue with
 * no worker). Each test exercises the sweeper end-to-end against the real H2-backed {@link
 * TitanStores} the {@code @QuarkusTest} profile wires in — no mock DAOs, no synthetic stores, so
 * the {@code AND status = 'QUEUED'} race-guard, the audit-log insert, and the build fail-close are
 * all the production code paths.
 */
@QuarkusTest
class NoWorkerTimeoutSweeperTest {

  @Inject TitanStores stores;

  private QueueHandlerSupport support;

  @BeforeEach
  void setup() {
    QueueProcessor.setBakeFailureDispatcherForTest(null);
    support = new QueueHandlerSupport(() -> null);
  }

  // ── parseTimeout / clamp — pure helpers ────────────────────────────────

  @Test
  void parseTimeout_default_whenUnset() {
    assertEquals(
        NoWorkerTimeoutSweeper.DEFAULT_TIMEOUT_S, NoWorkerTimeoutSweeper.parseTimeout(null));
    assertEquals(NoWorkerTimeoutSweeper.DEFAULT_TIMEOUT_S, NoWorkerTimeoutSweeper.parseTimeout(""));
    assertEquals(
        NoWorkerTimeoutSweeper.DEFAULT_TIMEOUT_S, NoWorkerTimeoutSweeper.parseTimeout("  "));
  }

  @Test
  void parseTimeout_default_whenNonNumeric() {
    assertEquals(
        NoWorkerTimeoutSweeper.DEFAULT_TIMEOUT_S, NoWorkerTimeoutSweeper.parseTimeout("nope"));
  }

  @Test
  void parseTimeout_clamps_belowMin_aboveMax() {
    assertEquals(NoWorkerTimeoutSweeper.MIN_TIMEOUT_S, NoWorkerTimeoutSweeper.parseTimeout("1"));
    assertEquals(NoWorkerTimeoutSweeper.MIN_TIMEOUT_S, NoWorkerTimeoutSweeper.parseTimeout("0"));
    assertEquals(NoWorkerTimeoutSweeper.MIN_TIMEOUT_S, NoWorkerTimeoutSweeper.parseTimeout("-5"));
    assertEquals(
        NoWorkerTimeoutSweeper.MAX_TIMEOUT_S, NoWorkerTimeoutSweeper.parseTimeout("99999"));
  }

  @Test
  void parseTimeout_acceptsValidValue() {
    assertEquals(120, NoWorkerTimeoutSweeper.parseTimeout("120"));
  }

  // ── coveredQueues — pure helper, no DB ─────────────────────────────────

  @Test
  void coveredQueues_emptyAgentList_emptyCoverage() {
    assertTrue(NoWorkerTimeoutSweeper.coveredQueues(List.of()).isEmpty());
  }

  @Test
  void coveredQueues_singleAgentWithLabels_coversLabelsPlusDefaults() {
    AgentRow agent = new AgentRow();
    agent.agentId = "worker-1";
    agent.labels = "linux,fast";
    Set<String> covered = NoWorkerTimeoutSweeper.coveredQueues(List.of(agent));
    assertTrue(covered.contains("default"));
    assertTrue(covered.contains("synthesis"));
    assertTrue(covered.contains("linux"));
    assertTrue(covered.contains("fast"));
    assertTrue(covered.contains("worker-1"));
    assertFalse(covered.contains("windows"));
  }

  @Test
  void coveredQueues_blankAndNullTokens_skipped() {
    AgentRow agent = new AgentRow();
    agent.agentId = "worker-2";
    agent.labels = "  ,linux,  , ,";
    Set<String> covered = NoWorkerTimeoutSweeper.coveredQueues(List.of(agent));
    assertTrue(covered.contains("linux"));
    assertFalse(covered.contains(""));
    assertFalse(covered.contains(" "));
  }

  // ── Acceptance: stale + uncovered queue → FAILED with clear error ──────

  @Test
  void sweep_taskOnUncoveredQueue_pastTimeout_failsBuildWithUserFacingError() {
    long buildId = freshBuildWithStatus("RUNNING");
    long taskId = insertStepTask(buildId, "windows-only", agedSecondsAgo(120));

    NoWorkerTimeoutSweeper sweeper = new NoWorkerTimeoutSweeper(60);
    int failed = sweeper.sweep(stores, support);

    assertEquals(1, failed);
    TaskQueueRow after = stores.taskQueue().findById(taskId).orElseThrow();
    assertEquals("FAILED", after.status);
    assertNotNull(after.resultJson);
    assertTrue(after.resultJson.contains("no_worker_timeout"));
    assertTrue(after.resultJson.contains("windows-only"));

    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("FAILED", build.status);
    assertNotNull(build.failureSummary);
    assertTrue(
        build.failureSummary.contains("No worker available for queue 'windows-only'"),
        "build failure_summary must surface the user-facing message: " + build.failureSummary);
    assertTrue(build.failureSummary.contains("60s"));
  }

  // ── Acceptance: covered queue → never fails, even past timeout ─────────

  @Test
  void sweep_taskOnCoveredQueue_pastTimeout_doesNotFail() {
    registerOnlineWorker("worker-linux", "linux");
    long buildId = freshBuildWithStatus("RUNNING");
    long taskId = insertStepTask(buildId, "linux", agedSecondsAgo(120));

    int failed = new NoWorkerTimeoutSweeper(60).sweep(stores, support);
    assertEquals(0, failed);

    TaskQueueRow after = stores.taskQueue().findById(taskId).orElseThrow();
    assertEquals("QUEUED", after.status);
    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("RUNNING", build.status);
  }

  // ── Acceptance: still inside the timeout window → never fails ──────────

  @Test
  void sweep_taskFresherThanTimeout_isNotTouched() {
    long buildId = freshBuildWithStatus("RUNNING");
    long taskId = insertStepTask(buildId, "windows-only", agedSecondsAgo(10));

    int failed = new NoWorkerTimeoutSweeper(60).sweep(stores, support);
    assertEquals(0, failed);
    assertEquals("QUEUED", stores.taskQueue().findById(taskId).orElseThrow().status);
  }

  // ── Acceptance: structured audit-log row of action 'no_worker_timeout' ─

  @Test
  void sweep_writesAuditLogRowOfActionNoWorkerTimeout() {
    long buildId = freshBuildWithStatus("RUNNING");
    long taskId = insertStepTask(buildId, "ghost-queue", agedSecondsAgo(120));

    new NoWorkerTimeoutSweeper(60).sweep(stores, support);

    List<AuditLogRow> rows =
        stores.auditLog().findRecent(null, "no_worker_timeout", null, null, 10, 0);
    assertFalse(rows.isEmpty(), "expected an audit row of action='no_worker_timeout'");
    AuditLogRow row =
        rows.stream()
            .filter(r -> String.valueOf(taskId).equals(r.targetId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no audit row for task id=" + taskId));
    assertEquals("no_worker_timeout", row.action);
    assertEquals("task", row.targetType);
    assertNotNull(row.detailsJson);
    assertTrue(row.detailsJson.contains("ghost-queue"));
  }

  // ── Acceptance: registration cancels the timeout cleanly ───────────────
  //
  // The task is enqueued >timeout ago on a queue with NO worker. A worker comes online with the
  // matching label BEFORE the sweep runs (the registration-before-timeout window). The sweep
  // must read the worker as covering the queue and leave the task alone — the worker can then
  // claim and run it normally.

  @Test
  void sweep_workerRegistersBeforeSweep_cancelsTheTimeout() {
    long buildId = freshBuildWithStatus("RUNNING");
    long taskId = insertStepTask(buildId, "kafka", agedSecondsAgo(120));

    // Worker registers just before the sweep runs.
    registerOnlineWorker("kafka-worker-1", "kafka");

    int failed = new NoWorkerTimeoutSweeper(60).sweep(stores, support);
    assertEquals(0, failed, "registration before sweep must cancel the timeout");

    TaskQueueRow after = stores.taskQueue().findById(taskId).orElseThrow();
    assertEquals("QUEUED", after.status);
    assertNull(after.resultJson);
  }

  // ── Adversarial: worker registers, then drops just before pickup ───────
  //
  // The flapping-worker case from the issue's acceptance matrix. Sequence:
  //   t0          : task enqueued on queue 'flaky'
  //   t0+5s       : worker 'flaky-1' (label=flaky) registers ONLINE
  //   t0+10s      : worker drops (status=OFFLINE)
  //   t0+timeout  : sweep runs — must fail-fast (no live worker)
  //
  // The sweeper must not silently swallow the task; the OFFLINE worker is NOT a coverer.

  @Test
  void sweep_workerRegistersThenDropsOffline_taskFailsAtTimeout() {
    long buildId = freshBuildWithStatus("RUNNING");
    long taskId = insertStepTask(buildId, "flaky", agedSecondsAgo(120));

    // Worker registered then dropped (heartbeat-based liveness via status=OFFLINE).
    registerOfflineWorker("flaky-1", "flaky");

    int failed = new NoWorkerTimeoutSweeper(60).sweep(stores, support);
    assertEquals(1, failed);
    assertEquals("FAILED", stores.taskQueue().findById(taskId).orElseThrow().status);
    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("FAILED", build.status);
  }

  // ── Race-safety: failQueuedTask is status-guarded ──────────────────────
  //
  // If a worker claims the task between detection and the fail-write, the row is no longer
  // QUEUED and the UPDATE matches zero rows. The build is NOT failed in that case (we don't
  // step on a freshly-claimed task).

  @Test
  void sweep_taskClaimedBetweenDetectionAndUpdate_doesNotFailBuild() {
    long buildId = freshBuildWithStatus("RUNNING");
    long taskId = insertStepTask(buildId, "uncovered", agedSecondsAgo(120));
    // Simulate the race: the row is already CLAIMED by a worker.
    stores.taskQueue().markClaimed(taskId, "raced-worker", UUID.randomUUID());

    int failed = new NoWorkerTimeoutSweeper(60).sweep(stores, support);
    // The find-query is QUEUED-only, so the now-CLAIMED row is invisible and the sweep is a no-op.
    assertEquals(0, failed);
    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("RUNNING", build.status);
  }

  // ── helpers ────────────────────────────────────────────────────────────

  private long freshJob() {
    JobRow row = new JobRow();
    row.fullName = "noworker/" + System.nanoTime() + "/" + UUID.randomUUID();
    row.pipelineScript = "stages:\n  - name: s1\n    steps:\n      - sh: echo hi\n";
    row.configJson = "{}";
    row.enabled = true;
    return stores.jobs().insert(row);
  }

  private long freshBuildWithStatus(String status) {
    BuildRow b = new BuildRow();
    b.jobId = freshJob();
    b.buildNumber = stores.builds().nextBuildNumber(b.jobId);
    b.status = status;
    b.queuedAt = Instant.now();
    b.triggeredBy = "test";
    b.triggerType = "manual";
    return stores.withTransaction(c -> stores.builds().insert(c, b));
  }

  private long insertStepTask(long buildId, String queueName, Instant availableAt) {
    TaskQueueRow t = new TaskQueueRow();
    t.type = "EXECUTE_COMMAND";
    t.queueName = queueName;
    t.status = "QUEUED";
    t.priority = 0;
    t.payloadJson = "{\"command\":\"echo hi\"}";
    t.attempts = 0;
    t.maxAttempts = 3;
    t.visibilityTimeoutSeconds = 300;
    t.buildId = buildId;
    t.availableAt = availableAt;
    return stores.taskQueue().insert(t);
  }

  private void registerOnlineWorker(String agentId, String labels) {
    stores.agents().register(agentId, agentId, labels, 1);
  }

  private void registerOfflineWorker(String agentId, String labels) {
    stores.agents().register(agentId, agentId, labels, 1);
    stores.agents().markOffline(agentId);
  }

  private static Instant agedSecondsAgo(int seconds) {
    return Instant.now().minusSeconds(seconds);
  }
}

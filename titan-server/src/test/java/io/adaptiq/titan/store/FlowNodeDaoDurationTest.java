package io.adaptiq.titan.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.JobRow;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the {@code duration_ms} stamp on {@link FlowNodeDao#compareAndSetStatus} /
 * {@link FlowNodeDao#updateStatus} (issue #127 — the engine never populated {@code
 * flow_nodes.duration_ms}, so {@code /api/v1/jobs/{id}/stage-timings} was permanently empty for
 * real builds).
 *
 * <p>Backed by the H2 schema-loader the API tests use ({@code FakeTitanStores}, reached via
 * reflection — the same pattern as {@link TestResultDaoTest}). Running the derivation SQL on H2's
 * PostgreSQL mode here, and on real Postgres in the ITs, pins both dialects.
 */
class FlowNodeDaoDurationTest {

  private static final Instant T0 = Instant.parse("2026-07-09T10:00:00Z");

  private TitanStores stores;
  private long buildId;

  @BeforeEach
  void setUp() throws Exception {
    Class<?> fake = Class.forName("io.adaptiq.titan.api.FakeTitanStores");
    Method create = fake.getDeclaredMethod("create");
    create.setAccessible(true);
    stores = (TitanStores) create.invoke(null);

    JobRow j = new JobRow();
    j.fullName = "tests/duration-" + System.nanoTime();
    j.enabled = true;
    j.pipelineScript = "";
    j.configJson = "{}";
    long jobId = stores.jobs().insert(j);

    BuildRow b = new BuildRow();
    b.jobId = jobId;
    b.buildNumber = stores.builds().nextBuildNumber(jobId);
    b.status = "RUNNING";
    b.queuedAt = T0;
    b.triggeredBy = "test";
    b.triggerType = "manual";
    buildId = stores.withTransaction(c -> stores.builds().insert(c, b));
  }

  private FlowNodeRow insertNode(String nodeId, String status) {
    FlowNodeRow n = new FlowNodeRow();
    n.buildId = buildId;
    n.nodeId = nodeId;
    n.nodeType = "STAGE";
    n.displayName = nodeId;
    n.status = status;
    stores.flowNodes().insert(n);
    return n;
  }

  private FlowNodeRow reload(String nodeId) {
    return stores.flowNodes().findByBuildAndNode(buildId, nodeId).orElseThrow();
  }

  // ── the normal executed path: RUNNING stamp, then terminal CAS ────────────

  @Test
  void terminalCasDerivesDurationFromTheNodesOwnStart() {
    insertNode("stage-1", "PENDING");
    // RUNNING transition stamps started_at (no completed_at → no duration yet).
    stores
        .flowNodes()
        .compareAndSetStatus(buildId, "stage-1", "PENDING", "RUNNING", T0, null, null, null);
    assertNull(reload("stage-1").durationMs, "no duration while running");

    // Terminal transition passes only completedAt — the DAO derives from the row's started_at.
    Instant done = T0.plus(1234, ChronoUnit.MILLIS);
    int won =
        stores
            .flowNodes()
            .compareAndSetStatus(buildId, "stage-1", "RUNNING", "SUCCESS", null, done, null, null);

    assertEquals(1, won);
    assertEquals(1234L, reload("stage-1").durationMs);
  }

  @Test
  void failedTerminalTransitionAlsoStampsDuration() {
    insertNode("stage-f", "PENDING");
    stores
        .flowNodes()
        .compareAndSetStatus(buildId, "stage-f", "PENDING", "RUNNING", T0, null, null, null);
    stores
        .flowNodes()
        .compareAndSetStatus(
            buildId, "stage-f", "RUNNING", "FAILED", null, T0.plusSeconds(7), null, null);
    assertEquals(7000L, reload("stage-f").durationMs);
  }

  @Test
  void singleCallStampingBothStartAndCompletionDerivesFromTheBind() {
    insertNode("stage-2", "PENDING");
    // Control-plane steps can go PENDING → terminal in one CAS carrying both instants.
    stores
        .flowNodes()
        .compareAndSetStatus(
            buildId,
            "stage-2",
            "PENDING",
            "SUCCESS",
            T0,
            T0.plus(500, ChronoUnit.MILLIS),
            null,
            null);
    assertEquals(500L, reload("stage-2").durationMs);
  }

  // ── exactly-once + precedence ─────────────────────────────────────────────

  @Test
  void anExplicitDurationBindWinsOverTheDerivation() {
    insertNode("stage-3", "RUNNING");
    stores.flowNodes().updateStatus(buildId, "stage-3", "RUNNING", T0, null, null, null);
    // Replay's SKIPPED-with-zero sentinel must survive (ReplayFromNodeHandler passes 0L).
    stores
        .flowNodes()
        .compareAndSetStatus(
            buildId, "stage-3", "RUNNING", "SKIPPED", null, T0.plusSeconds(9), 0L, null);
    assertEquals(0L, reload("stage-3").durationMs);
  }

  @Test
  void anAlreadyStampedDurationIsNeverOverwritten() {
    insertNode("stage-4", "PENDING");
    stores
        .flowNodes()
        .compareAndSetStatus(buildId, "stage-4", "PENDING", "RUNNING", T0, null, null, null);
    stores
        .flowNodes()
        .compareAndSetStatus(
            buildId, "stage-4", "RUNNING", "SUCCESS", null, T0.plusSeconds(2), null, null);
    assertEquals(2000L, reload("stage-4").durationMs);

    // A later redundant update with a much-later completedAt must not re-derive.
    stores
        .flowNodes()
        .updateStatus(buildId, "stage-4", "SUCCESS", null, T0.plusSeconds(60), null, null);
    assertEquals(2000L, reload("stage-4").durationMs, "first terminal stamp is authoritative");
  }

  @Test
  void aLosingCasStampsNothing() {
    insertNode("stage-5", "PENDING");
    stores
        .flowNodes()
        .compareAndSetStatus(buildId, "stage-5", "PENDING", "RUNNING", T0, null, null, null);
    int won =
        stores
            .flowNodes()
            .compareAndSetStatus(
                buildId,
                "stage-5",
                "QUEUED" /* wrong from */,
                "SUCCESS",
                null,
                T0.plusSeconds(3),
                null,
                null);
    assertEquals(0, won);
    assertNull(reload("stage-5").durationMs);
  }

  // ── backstop: a node that never started keeps NULL ────────────────────────

  @Test
  void skippedNodeThatNeverStartedKeepsNullDuration() {
    insertNode("stage-6", "PENDING");
    // Upstream failed → PENDING node is SKIPPED with a completed_at but no start; duration must
    // stay NULL so JobTimingsDao (duration_ms IS NOT NULL) never sees a fake sample.
    stores
        .flowNodes()
        .compareAndSetStatus(
            buildId, "stage-6", "PENDING", "SKIPPED", null, T0.plusSeconds(1), null, null);
    assertNull(reload("stage-6").durationMs);
  }

  @Test
  void nonTerminalUpdateWithoutCompletionLeavesDurationNull() {
    insertNode("stage-7", "PENDING");
    stores.flowNodes().updateStatus(buildId, "stage-7", "RUNNING", T0, null, null, null);
    assertNull(reload("stage-7").durationMs);
  }

  // ── retry reset ───────────────────────────────────────────────────────────

  @Test
  void retryAttemptResetClearsDurationSoTheFinalAttemptCanStamp() {
    FlowNodeRow n = new FlowNodeRow();
    n.buildId = buildId;
    n.nodeId = "step-r";
    n.nodeType = "STEP";
    n.displayName = "step-r";
    n.status = "QUEUED";
    n.startedAt = T0;
    n.maxAttempts = 3;
    stores.flowNodes().insert(n);

    // Simulate a first attempt that recorded timing before the retry policy re-queued it.
    stores
        .flowNodes()
        .updateStatus(buildId, "step-r", "QUEUED", null, T0.plusSeconds(4), null, null);
    assertEquals(4000L, reload("step-r").durationMs);

    assertEquals(1, stores.flowNodes().compareAndSetRetryAttempt(buildId, "step-r", 1));
    FlowNodeRow reset = reload("step-r");
    assertNull(reset.durationMs, "retry must clear the previous attempt's duration");
    assertNull(reset.startedAt);
    assertNull(reset.completedAt);

    // The fresh attempt's timing stamps cleanly.
    Instant t2 = T0.plusSeconds(10);
    stores.flowNodes().updateStatus(buildId, "step-r", "QUEUED", t2, null, null, null);
    stores
        .flowNodes()
        .compareAndSetStatus(
            buildId, "step-r", "QUEUED", "SUCCESS", null, t2.plusSeconds(6), null, null);
    assertEquals(6000L, reload("step-r").durationMs);
  }
}

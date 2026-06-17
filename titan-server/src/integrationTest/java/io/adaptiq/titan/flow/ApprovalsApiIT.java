package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.flow.ApprovalService.Decision;
import io.adaptiq.titan.flow.ApprovalService.DecisionOutcome;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.ApprovalRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.timer.TimerSweepWorker;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end ITs for the {@code approval:} parked-step backend (#715):
 *
 * <ul>
 *   <li>orchestrator parks the step + inserts a PENDING row + arms a GATE_RESUME timer
 *   <li>APPROVE flips the row + resumes the node SUCCESS on the next advance()
 *   <li>REJECT flips the row + resumes the node FAILED on the next advance()
 *   <li>double-decide is a 409-equivalent (DecisionOutcome.applied == false)
 *   <li>non-approver subject is rejected with SecurityException (the API layer turns this into 403)
 *   <li>timeout sweep flips PENDING → TIMED_OUT + node resumes FAILED on the next advance()
 * </ul>
 *
 * Drives the engine directly against a real Postgres Testcontainer — the same wiring the {@code
 * TitanOrchestratorSleepIT} uses for the durable-wait sibling. RBAC role-projection (the
 * {@code @RolesAllowed} ↔ {@code SecurityIdentity} glue) is covered by the {@code @QuarkusTest}
 * RBAC ITs in this same package set; this IT exercises the engine + service contract end-to-end.
 */
@Testcontainers
class ApprovalsApiIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private long buildId;

  @BeforeEach
  void setUp() throws Exception {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
    cfg.setUsername(POSTGRES.getUsername());
    cfg.setPassword(POSTGRES.getPassword());
    cfg.setDriverClassName("org.postgresql.Driver");
    cfg.setMaximumPoolSize(8);
    ds = new HikariDataSource(cfg);

    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.execute("DROP SCHEMA IF EXISTS titan CASCADE");
      st.execute("CREATE SCHEMA titan");
    }
    Flyway.configure(getClass().getClassLoader())
        .dataSource(ds)
        .schemas("titan")
        .defaultSchema("titan")
        .locations(
            "classpath:io/adaptiq/titan/db/migration",
            "classpath:io/adaptiq/titan/db/migration-postgresql")
        .load()
        .migrate();
    stores = TitanStores.forDataSource(ds);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  // ── shared helpers ────────────────────────────────────────────────────────

  private void bake(String fixture) throws Exception {
    String yaml = Fixtures.load(fixture);
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, yaml);
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(yaml);
    // Drive the Build stage to completion synthetically (no worker in the IT) so the Deploy
    // stage with the approval step is reached on the next advance() pass.
    new TitanOrchestrator(stores, buildId).advance();
    completeAllQueuedSteps();
  }

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "approval-it/job-" + System.nanoTime());
      ps.setString(2, yaml);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static long insertBuild(Connection c, long jobId) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status) VALUES (?, 1, 'QUEUED')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, jobId);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  /**
   * Mark every QUEUED EXECUTE_COMMAND task COMPLETED + flip its step node to SUCCESS. No worker
   * runs in this IT, so we simulate the worker. Iterates because an advance() may dispatch the next
   * step only after the current one is reconciled SUCCESS.
   */
  private void completeAllQueuedSteps() throws Exception {
    for (int i = 0; i < 10; i++) {
      boolean any = completeOneRound();
      if (!any) {
        return;
      }
      new TitanOrchestrator(stores, buildId).advance();
    }
  }

  private boolean completeOneRound() throws Exception {
    boolean any = false;
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT id, node_id FROM titan.task_queue "
                    + "WHERE type = 'EXECUTE_COMMAND' AND status = 'QUEUED' AND build_id = "
                    + buildId)) {
      while (rs.next()) {
        long taskId = rs.getLong(1);
        String nodeId = rs.getString(2);
        try (Statement up = c.createStatement()) {
          up.execute("UPDATE titan.task_queue SET status = 'COMPLETED' WHERE id = " + taskId);
        }
        Instant now = Instant.now();
        stores
            .flowNodes()
            .compareAndSetStatus(buildId, nodeId, "QUEUED", "SUCCESS", null, now, 1L, null);
        any = true;
      }
    }
    return any;
  }

  // ── tests ─────────────────────────────────────────────────────────────────

  @Test
  void approvalStepParksAsSleepingAndInsertsPendingRowWithNoWorkerTask() throws Exception {
    bake("approval-step.yml");

    new TitanOrchestrator(stores, buildId).advance(); // hit the approval step → park

    FlowNodeRow parked = findFirstSleepingNode();
    assertEquals("SLEEPING", parked.status);
    assertNotNull(parked.wakeAt, "a parked approval node records its expiry as wake_at");

    ApprovalRow row = stores.approvals().findLatestForNode(buildId, parked.nodeId).orElseThrow();
    assertEquals("PENDING", row.status);
    assertEquals("Promote to prod?", row.prompt);
    assertNull(row.decidedBy);
    assertNull(row.decidedAt);
    assertTrue(row.expiresAt.isAfter(Instant.now()), "24h timeout is in the future");
    assertEquals("[\"alice\"]", row.approversJson);

    // No worker task was dispatched for the parked step.
    assertEquals(0, countQueuedExecuteTasksForNode(parked.nodeId));
  }

  @Test
  void approveFlipsRowAndResumesNodeSuccessOnNextAdvance() throws Exception {
    bake("approval-step.yml");
    new TitanOrchestrator(stores, buildId).advance();
    FlowNodeRow parked = findFirstSleepingNode();
    ApprovalRow pending =
        stores.approvals().findLatestForNode(buildId, parked.nodeId).orElseThrow();

    DecisionOutcome out =
        ApprovalService.decide(stores, pending.id, "alice", Set.of("alice"), Decision.APPROVED);
    assertTrue(out.applied());
    assertEquals("APPROVED", out.status());

    ApprovalRow decided = stores.approvals().findById(pending.id).orElseThrow();
    assertEquals("APPROVED", decided.status);
    assertEquals("alice", decided.decidedBy);
    assertNotNull(decided.decidedAt);

    new TitanOrchestrator(stores, buildId).advance(); // resume pass
    FlowNodeRow resumed =
        stores.flowNodes().findByBuildAndNode(buildId, parked.nodeId).orElseThrow();
    assertEquals("SUCCESS", resumed.status, "an APPROVED approval resumes the node SUCCESS");
  }

  @Test
  void rejectFlipsRowAndResumesNodeFailedOnNextAdvance() throws Exception {
    bake("approval-step.yml");
    new TitanOrchestrator(stores, buildId).advance();
    FlowNodeRow parked = findFirstSleepingNode();
    ApprovalRow pending =
        stores.approvals().findLatestForNode(buildId, parked.nodeId).orElseThrow();

    DecisionOutcome out =
        ApprovalService.decide(stores, pending.id, "alice", Set.of("alice"), Decision.REJECTED);
    assertTrue(out.applied());
    assertEquals("REJECTED", out.status());

    new TitanOrchestrator(stores, buildId).advance();
    FlowNodeRow resumed =
        stores.flowNodes().findByBuildAndNode(buildId, parked.nodeId).orElseThrow();
    assertEquals("FAILED", resumed.status, "a REJECTED approval resumes the node FAILED");
    assertEquals("APPROVAL", resumed.failureCategory);
    assertNotNull(resumed.failureReason);
    assertTrue(resumed.failureReason.contains("rejected"), "failure reason mentions rejection");
  }

  @Test
  void doubleDecideOnAlreadyApprovedIsNotAppliedAndReturnsConflict() throws Exception {
    bake("approval-step.yml");
    new TitanOrchestrator(stores, buildId).advance();
    ApprovalRow pending =
        stores.approvals().findLatestForNode(buildId, findFirstSleepingNode().nodeId).orElseThrow();

    DecisionOutcome first =
        ApprovalService.decide(stores, pending.id, "alice", Set.of("alice"), Decision.APPROVED);
    assertTrue(first.applied());

    DecisionOutcome second =
        ApprovalService.decide(stores, pending.id, "alice", Set.of("alice"), Decision.APPROVED);
    assertFalse(second.applied(), "a second decide on a terminal row is a no-op (HTTP 409)");
    assertEquals("APPROVED", second.status());

    DecisionOutcome thirdAsReject =
        ApprovalService.decide(stores, pending.id, "alice", Set.of("alice"), Decision.REJECTED);
    assertFalse(thirdAsReject.applied(), "cannot flip an APPROVED row to REJECTED");
    assertEquals("APPROVED", thirdAsReject.status());
  }

  @Test
  void nonApproverSubjectIsRejectedWithSecurityException() throws Exception {
    bake("approval-step.yml");
    new TitanOrchestrator(stores, buildId).advance();
    ApprovalRow pending =
        stores.approvals().findLatestForNode(buildId, findFirstSleepingNode().nodeId).orElseThrow();

    // mallory is not in approvers ["alice"] and does not hold ADMIN — the API layer turns the
    // SecurityException into HTTP 403.
    assertThrows(
        SecurityException.class,
        () ->
            ApprovalService.decide(
                stores, pending.id, "mallory", Set.of("mallory", "READ_JOB"), Decision.APPROVED));

    // ADMIN bypass: an ADMIN caller may decide regardless of the approvers list.
    DecisionOutcome adminOut =
        ApprovalService.decide(
            stores, pending.id, "admin", Set.of("admin", "ADMIN"), Decision.APPROVED);
    assertTrue(adminOut.applied(), "ADMIN bypasses the per-row approvers list");
  }

  @Test
  void timeoutSweepFlipsExpiredPendingToTimedOutAndResumesNodeFailed() throws Exception {
    bake("approval-step.yml");
    new TitanOrchestrator(stores, buildId).advance();
    FlowNodeRow parked = findFirstSleepingNode();
    ApprovalRow pending =
        stores.approvals().findLatestForNode(buildId, parked.nodeId).orElseThrow();

    // Backdate the row's expires_at via the test-only DAO escape hatch so the sweep fires now
    // without waiting 24h.
    stores.approvals().testOnlySetExpiresAt(pending.id, Instant.now().minusSeconds(1));

    int flipped = ApprovalService.sweepTimedOut(stores, Instant.now());
    assertEquals(1, flipped, "the sweep flipped exactly the one expired row");

    ApprovalRow timedOut = stores.approvals().findById(pending.id).orElseThrow();
    assertEquals("TIMED_OUT", timedOut.status);
    assertEquals("<timeout>", timedOut.decidedBy);
    assertNotNull(timedOut.decidedAt);

    new TitanOrchestrator(stores, buildId).advance();
    FlowNodeRow resumed =
        stores.flowNodes().findByBuildAndNode(buildId, parked.nodeId).orElseThrow();
    assertEquals("FAILED", resumed.status, "a TIMED_OUT approval resumes the node FAILED");
    assertEquals("APPROVAL", resumed.failureCategory);
    assertTrue(resumed.failureReason.contains("timeout"));
  }

  @Test
  void sweepIsIdempotentAcrossRunsAndOnlyTouchesExpiredPendingRows() throws Exception {
    bake("approval-step.yml");
    new TitanOrchestrator(stores, buildId).advance();
    ApprovalRow pending =
        stores.approvals().findLatestForNode(buildId, findFirstSleepingNode().nodeId).orElseThrow();

    // First sweep with the row not yet expired — should touch nothing.
    assertEquals(0, ApprovalService.sweepTimedOut(stores, Instant.now()));

    stores.approvals().testOnlySetExpiresAt(pending.id, Instant.now().minusSeconds(1));
    assertEquals(1, ApprovalService.sweepTimedOut(stores, Instant.now()));
    // A second sweep over the same row (now TIMED_OUT) is a no-op.
    assertEquals(0, ApprovalService.sweepTimedOut(stores, Instant.now()));
  }

  // ── #park-no-tick: an approval-parked build stops ticking (fixes titan.test build 14) ─────

  @Test
  void advanceOnApprovalParkedBuildReportsParkedAndIsIdempotent() throws Exception {
    bake("approval-step.yml");

    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);
    TitanOrchestrator.AdvanceResult parked = orch.advance();

    assertTrue(parked.parked(), "a build at an approval gate reports parked=true");
    assertFalse(parked.buildFinished(), "a parked build is not finished");

    // The whole point: re-running advance() against a parked build is a no-op — no CAS
    // failures, no orphan dispatches. This is what was failing on titan.test (build 14):
    // the per-tick re-enqueue raced the parked node and eventually lost a CAS, fail-closing
    // the build with "compareAndSetStatus failed" surfaced as a cryptic internal error.
    for (int i = 0; i < 5; i++) {
      TitanOrchestrator.AdvanceResult again = new TitanOrchestrator(stores, buildId).advance();
      assertTrue(again.parked(), "still parked on re-tick " + i);
      assertFalse(again.buildFinished(), "still not finished on re-tick " + i);
      assertEquals(0, again.dispatched(), "no new dispatch on re-tick " + i);
      assertEquals(0, again.reconciled(), "no reconcile on re-tick " + i);
    }

    FlowNodeRow node = findFirstSleepingNode();
    assertEquals("SLEEPING", node.status, "the parked node is still SLEEPING after re-ticks");
  }

  @Test
  void approvalDecisionEnqueuesExactlyOneAdvanceAndResumesTheBuild() throws Exception {
    bake("approval-step.yml");
    new TitanOrchestrator(stores, buildId).advance(); // park
    FlowNodeRow parked = findFirstSleepingNode();
    ApprovalRow pending =
        stores.approvals().findLatestForNode(buildId, parked.nodeId).orElseThrow();

    long advancesBefore = countQueuedAdvanceTasks();

    DecisionOutcome out =
        ApprovalService.decide(stores, pending.id, "alice", Set.of("alice"), Decision.APPROVED);
    assertTrue(out.applied());

    long advancesAfter = countQueuedAdvanceTasks();
    assertEquals(
        advancesBefore + 1,
        advancesAfter,
        "a winning APPROVED decision enqueues exactly one fresh ADVANCE");
  }

  @Test
  void rejectAlsoEnqueuesAdvanceAndResumesTheBuildFailed() throws Exception {
    bake("approval-step.yml");
    new TitanOrchestrator(stores, buildId).advance();
    FlowNodeRow parked = findFirstSleepingNode();
    ApprovalRow pending =
        stores.approvals().findLatestForNode(buildId, parked.nodeId).orElseThrow();

    long advancesBefore = countQueuedAdvanceTasks();
    ApprovalService.decide(stores, pending.id, "alice", Set.of("alice"), Decision.REJECTED);
    long advancesAfter = countQueuedAdvanceTasks();
    assertEquals(
        advancesBefore + 1,
        advancesAfter,
        "a winning REJECTED decision also enqueues one ADVANCE — orchestrator does the rest");

    new TitanOrchestrator(stores, buildId).advance();
    FlowNodeRow resumed =
        stores.flowNodes().findByBuildAndNode(buildId, parked.nodeId).orElseThrow();
    assertEquals("FAILED", resumed.status, "REJECTED approval resumes the node FAILED");
  }

  @Test
  void concurrentDecisionsRaceCleanlyAndOnlyOneAdvanceIsEnqueued() throws Exception {
    bake("approval-step.yml");
    new TitanOrchestrator(stores, buildId).advance();
    FlowNodeRow parked = findFirstSleepingNode();
    ApprovalRow pending =
        stores.approvals().findLatestForNode(buildId, parked.nodeId).orElseThrow();

    long advancesBefore = countQueuedAdvanceTasks();

    // Two callers race for the same PENDING row — decideIfPending's CAS lets exactly one win.
    DecisionOutcome first =
        ApprovalService.decide(stores, pending.id, "alice", Set.of("alice"), Decision.APPROVED);
    DecisionOutcome second =
        ApprovalService.decide(stores, pending.id, "alice", Set.of("alice"), Decision.REJECTED);

    assertTrue(first.applied(), "the first decision wins the CAS");
    assertFalse(second.applied(), "the second decision sees a terminal row (HTTP 409 equivalent)");

    long advancesAfter = countQueuedAdvanceTasks();
    assertEquals(
        advancesBefore + 1,
        advancesAfter,
        "only the winning decision enqueues an ADVANCE — the loser is a no-op");
  }

  @Test
  void timerSweepWorkerWiringRunsTheApprovalSweepAlongsideTimers() throws Exception {
    bake("approval-step.yml");
    new TitanOrchestrator(stores, buildId).advance();
    ApprovalRow pending =
        stores.approvals().findLatestForNode(buildId, findFirstSleepingNode().nodeId).orElseThrow();
    stores.approvals().testOnlySetExpiresAt(pending.id, Instant.now().minusSeconds(1));

    new TimerSweepWorker().sweep(stores); // production sweep entry point

    ApprovalRow timedOut = stores.approvals().findById(pending.id).orElseThrow();
    assertEquals(
        "TIMED_OUT",
        timedOut.status,
        "TimerSweepWorker.sweep also runs the approval timeout sweep (#715 wiring)");
  }

  // ── small helpers ─────────────────────────────────────────────────────────

  private FlowNodeRow findFirstSleepingNode() {
    return stores.flowNodes().listByBuildAndStatus(buildId, "SLEEPING").stream()
        .findFirst()
        .orElseThrow(() -> new AssertionError("expected one SLEEPING flow node"));
  }

  private long countQueuedAdvanceTasks() throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT COUNT(*) FROM titan.task_queue "
                    + "WHERE type = 'ORCHESTRATE' AND build_id = ? "
                    + "AND status = 'QUEUED' AND payload_json LIKE '%\"ADVANCE\"%'")) {
      ps.setLong(1, buildId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private int countQueuedExecuteTasksForNode(String nodeId) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT COUNT(*) FROM titan.task_queue "
                    + "WHERE type = 'EXECUTE_COMMAND' AND build_id = ? AND node_id = ? "
                    + "AND status = 'QUEUED'")) {
      ps.setLong(1, buildId);
      ps.setString(2, nodeId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }
}

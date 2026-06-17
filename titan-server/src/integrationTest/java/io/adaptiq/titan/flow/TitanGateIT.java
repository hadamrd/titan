package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.ApprovalRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for manual-approval <strong>gate</strong> nodes against real PostgreSQL — Chunk
 * 6F (design/29 §3/§7.1).
 *
 * <p>Drives the {@code moab-ci-pipeline.yml} fixture — a multi-stage migration-shaped pipeline with
 * {@code script} steps and a {@code Release Approval} gate. Proves the design/31 6F done-when for
 * gates: the orchestrator opens the gate ({@code RUNNING} — "awaiting approval") and the build
 * waits there; an unauthorised actor is refused; an authorised approver's decision (via {@link
 * GateService}) resolves the gate and the DAG drives on to {@code SUCCESS}; a rejection blocks the
 * downstream DAG instead.
 */
@Testcontainers
class TitanGateIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String FIXTURE = "moab-ci-pipeline.yml";
  private static final String GATE_NODE = "release-approval";

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
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, Fixtures.load(FIXTURE));
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(Fixtures.load(FIXTURE));
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  /** The build waits at the gate, an authorised approval releases it, and it reaches SUCCESS. */
  @Test
  void gatedPipelineWaitsForApprovalThenCompletes() {
    TitanOrchestrator orchestrator = new TitanOrchestrator(stores, buildId);

    // Drive to steady state — the DAG runs up to the gate and then waits.
    driveToSteadyState(orchestrator);

    FlowNodeRow gate = stores.flowNodes().findByBuildAndNode(buildId, GATE_NODE).orElseThrow();
    assertEquals("RUNNING", gate.status, "the gate must be open and awaiting approval");
    assertEquals(
        "RUNNING",
        stores.builds().findById(buildId).orElseThrow().status,
        "the build must still be running while the gate waits");
    // The downstream Deploy stage has not started.
    assertEquals(
        "PENDING",
        stores.flowNodes().findByBuildAndNode(buildId, "deploy").orElseThrow().status,
        "Deploy must not start before the gate is approved");

    // An actor who is not in the gate's `approvers: [moab-leads]` is refused.
    assertThrows(
        SecurityException.class,
        () ->
            GateService.decide(
                stores,
                buildId,
                GATE_NODE,
                "mallory",
                Set.of("mallory"),
                GateService.Decision.APPROVED),
        "a non-approver must not be able to decide");

    // An authorised approver (holds the `moab-leads` authority) approves.
    GateService.GateOutcome outcome =
        GateService.decide(
            stores,
            buildId,
            GATE_NODE,
            "alice",
            Set.of("alice", "moab-leads"),
            GateService.Decision.APPROVED);
    assertTrue(outcome.applied(), "the approval must be applied: " + outcome.message());
    assertEquals("SUCCESS", outcome.status());

    // The DAG continues past the gate to completion.
    driveToSteadyState(orchestrator);
    assertEquals("SUCCESS", stores.builds().findById(buildId).orElseThrow().status);
    assertEquals(
        "SUCCESS", stores.flowNodes().findByBuildAndNode(buildId, GATE_NODE).orElseThrow().status);
    assertEquals(
        "SUCCESS", stores.flowNodes().findByBuildAndNode(buildId, "deploy").orElseThrow().status);
  }

  /**
   * ADMIN break-glass: an actor who is NOT in the gate's {@code approvers: [moab-leads]} but holds
   * the {@code ADMIN} super-role may still approve. This mirrors the documented authz contract
   * (ApprovalsApi / {@code decideViaApprovalRow}) — without it an ADMIN gets 403 on the gate
   * endpoint, and a gate whose approvers reference a non-existent identity is un-approvable.
   */
  @Test
  void anAdminNotInTheApproversListCanBreakGlassApprove() {
    TitanOrchestrator orchestrator = new TitanOrchestrator(stores, buildId);
    driveToSteadyState(orchestrator);

    FlowNodeRow gate = stores.flowNodes().findByBuildAndNode(buildId, GATE_NODE).orElseThrow();
    assertEquals("RUNNING", gate.status, "the gate must be open and awaiting approval");

    // carol is NOT in `moab-leads` but holds ADMIN — the super-role bypasses the approver list.
    GateService.GateOutcome outcome =
        GateService.decide(
            stores,
            buildId,
            GATE_NODE,
            "carol",
            Set.of("carol", "ADMIN"),
            GateService.Decision.APPROVED);
    assertTrue(
        outcome.applied(), "an ADMIN must be able to break-glass approve: " + outcome.message());
    assertEquals("SUCCESS", outcome.status());

    driveToSteadyState(orchestrator);
    assertEquals("SUCCESS", stores.builds().findById(buildId).orElseThrow().status);
  }

  /** A second decision on an already-resolved gate is a no-op — the gate resolves exactly once. */
  @Test
  void aSecondDecisionOnAResolvedGateIsRejected() {
    TitanOrchestrator orchestrator = new TitanOrchestrator(stores, buildId);
    driveToSteadyState(orchestrator);

    GateService.GateOutcome first =
        GateService.decide(
            stores,
            buildId,
            GATE_NODE,
            "alice",
            Set.of("moab-leads"),
            GateService.Decision.APPROVED);
    assertTrue(first.applied());

    // A re-posted / racing second decision finds the gate no longer RUNNING.
    GateService.GateOutcome second =
        GateService.decide(
            stores, buildId, GATE_NODE, "bob", Set.of("moab-leads"), GateService.Decision.REJECTED);
    assertFalse(second.applied(), "the gate is already resolved — the second decision is a no-op");
  }

  /** Rejecting the gate fails it; under blockOnFailure the downstream DAG is SKIPPED. */
  @Test
  void rejectingTheGateBlocksTheDownstreamDag() {
    TitanOrchestrator orchestrator = new TitanOrchestrator(stores, buildId);
    driveToSteadyState(orchestrator);

    GateService.GateOutcome outcome =
        GateService.decide(
            stores,
            buildId,
            GATE_NODE,
            "alice",
            Set.of("moab-leads"),
            GateService.Decision.REJECTED);
    assertTrue(outcome.applied());
    assertEquals("FAILED", outcome.status());

    driveToSteadyState(orchestrator);
    assertEquals("FAILED", stores.builds().findById(buildId).orElseThrow().status);
    assertEquals(
        "FAILED", stores.flowNodes().findByBuildAndNode(buildId, GATE_NODE).orElseThrow().status);
    assertEquals(
        "SKIPPED",
        stores.flowNodes().findByBuildAndNode(buildId, "deploy").orElseThrow().status,
        "the rejected gate's downstream stage is SKIPPED");
  }

  /**
   * Issue #953: opening the gate inserts a PENDING titan.approvals row mirroring the gate's name +
   * approvers — the UI inbox sees gate-stage approvals alongside step-form approvals with no schema
   * divergence.
   */
  @Test
  void openingTheGateInsertsAPendingApprovalsRow() {
    TitanOrchestrator orchestrator = new TitanOrchestrator(stores, buildId);
    driveToSteadyState(orchestrator);

    ApprovalRow pending =
        stores
            .approvals()
            .findLatestForNode(buildId, GATE_NODE)
            .orElseThrow(
                () ->
                    new AssertionError(
                        "no titan.approvals row was written when the gate opened — UI inbox would be empty"));
    assertEquals("PENDING", pending.status);
    assertEquals(GATE_NODE, pending.flowNodeId);
    assertEquals("Release Approval", pending.prompt);
    assertTrue(
        pending.approversJson != null && pending.approversJson.contains("moab-leads"),
        "approvers JSON must mirror GateModel.approvers: " + pending.approversJson);
  }

  /**
   * Issue #953: re-ticking advance() against the open gate is idempotent — at most one PENDING
   * approvals row per (build, node) survives a re-park, matching the build-22 invariant.
   */
  @Test
  void reTickingAdvanceDoesNotInsertDuplicateGateApprovalRow() {
    TitanOrchestrator orchestrator = new TitanOrchestrator(stores, buildId);
    driveToSteadyState(orchestrator);

    long countBefore = stores.approvals().listForBuild(buildId).size();
    // Three additional ADVANCE passes — none should insert a second PENDING row.
    for (int i = 0; i < 3; i++) {
      orchestrator.advance();
    }
    assertEquals(
        countBefore,
        stores.approvals().listForBuild(buildId).size(),
        "re-ticking the open gate must not create a phantom second approvals row");
  }

  /**
   * Issue #953: when the legacy {@link GateService#decide} CAS-wins the gate, the titan.approvals
   * mirror row flips terminal as well — both surfaces are kept in lock-step so the UI inbox does
   * not show a permanently-PENDING row for a gate that is already SUCCESS.
   */
  @Test
  void legacyDecidePathAlsoClosesTheApprovalsMirrorRow() {
    TitanOrchestrator orchestrator = new TitanOrchestrator(stores, buildId);
    driveToSteadyState(orchestrator);

    GateService.GateOutcome outcome =
        GateService.decide(
            stores,
            buildId,
            GATE_NODE,
            "alice",
            Set.of("moab-leads"),
            GateService.Decision.APPROVED);
    assertTrue(outcome.applied());

    ApprovalRow row = stores.approvals().findLatestForNode(buildId, GATE_NODE).orElseThrow();
    assertEquals("APPROVED", row.status, "the approvals mirror row must be closed");
    assertEquals("alice", row.decidedBy);
  }

  /**
   * Issue #953: deciding the unified approvals row drives the gate's flow_node to terminal +
   * enqueues ADVANCE, the same end-state as the legacy GateService.decide() path.
   */
  @Test
  void decideViaApprovalRowResolvesGateAndDrivesDagForward() {
    TitanOrchestrator orchestrator = new TitanOrchestrator(stores, buildId);
    driveToSteadyState(orchestrator);

    ApprovalRow pending = stores.approvals().findLatestForNode(buildId, GATE_NODE).orElseThrow();

    ApprovalService.DecisionOutcome outcome =
        GateService.decideViaApprovalRow(
            stores, pending, "alice", Set.of("moab-leads"), GateService.Decision.APPROVED);
    assertTrue(outcome.applied(), outcome.message());
    assertEquals("APPROVED", outcome.status());

    // Gate flow_node mirrored to SUCCESS.
    assertEquals(
        "SUCCESS", stores.flowNodes().findByBuildAndNode(buildId, GATE_NODE).orElseThrow().status);

    driveToSteadyState(orchestrator);
    assertEquals("SUCCESS", stores.builds().findById(buildId).orElseThrow().status);
    assertEquals(
        "SUCCESS", stores.flowNodes().findByBuildAndNode(buildId, "deploy").orElseThrow().status);
  }

  /**
   * Issue #953 adversarial: an unauthorised actor calling the unified path is refused — same
   * SecurityException the legacy decide() raises, mapped to HTTP 403 by the API layer.
   */
  @Test
  void decideViaApprovalRowRefusesUnauthorisedActor() {
    TitanOrchestrator orchestrator = new TitanOrchestrator(stores, buildId);
    driveToSteadyState(orchestrator);
    ApprovalRow pending = stores.approvals().findLatestForNode(buildId, GATE_NODE).orElseThrow();

    assertThrows(
        SecurityException.class,
        () ->
            GateService.decideViaApprovalRow(
                stores, pending, "mallory", Set.of("mallory"), GateService.Decision.APPROVED));

    // The row stays PENDING; the gate stays RUNNING.
    assertEquals("PENDING", stores.approvals().findById(pending.id).orElseThrow().status);
    assertEquals(
        "RUNNING", stores.flowNodes().findByBuildAndNode(buildId, GATE_NODE).orElseThrow().status);
  }

  /**
   * Issue #953 adversarial: a second decide against an already-resolved approvals row is a no-op
   * for the loser ({@code applied=false}) — caller turns into HTTP 409.
   */
  @Test
  void decideViaApprovalRowSecondCallIsANoOp() {
    TitanOrchestrator orchestrator = new TitanOrchestrator(stores, buildId);
    driveToSteadyState(orchestrator);
    ApprovalRow pending = stores.approvals().findLatestForNode(buildId, GATE_NODE).orElseThrow();

    GateService.decideViaApprovalRow(
        stores, pending, "alice", Set.of("moab-leads"), GateService.Decision.APPROVED);
    // The caller still holds the stale PENDING row reference — simulate the race.
    ApprovalService.DecisionOutcome second =
        GateService.decideViaApprovalRow(
            stores, pending, "bob", Set.of("moab-leads"), GateService.Decision.REJECTED);
    assertFalse(second.applied(), "second decide on a resolved row must be a no-op");
    assertEquals(
        "APPROVED",
        stores.approvals().findById(pending.id).orElseThrow().status,
        "terminal status of the winner is preserved");
  }

  // ---- the advance + stubbed-worker drive loop ------------------------

  /**
   * Loop {@code advance()} + a stubbed worker until the build finishes or the DAG reaches a steady
   * state — a pass that dispatches nothing, reconciles nothing and completes no task, i.e. it is
   * waiting on the gate.
   */
  private void driveToSteadyState(TitanOrchestrator orchestrator) {
    for (int pass = 0; pass < 40; pass++) {
      TitanOrchestrator.AdvanceResult result = orchestrator.advance();
      if (result.buildFinished()) {
        return;
      }
      int completed = 0;
      // The fixture sets `agent: linux` at the pipeline level, so step tasks are routed to
      // the "linux" queue, not "default" (commit 4e30f2c — "apply titan.agent as the default
      // for stages that omit it"). Drain every distinct queue currently holding QUEUED rows
      // for this build so the stub worker is queue-agnostic.
      for (String queue : queuesWithWork()) {
        Optional<TaskQueueRow> claimed;
        while ((claimed = stores.taskQueue().claim("stub", queue, UUID.randomUUID())).isPresent()) {
          TaskQueueRow t = claimed.get();
          stores.taskQueue().complete(t.id, t.claimToken, "COMPLETED", "{\"exitCode\":0}");
          completed++;
        }
      }
      if (result.dispatched() == 0 && result.reconciled() == 0 && completed == 0) {
        return; // steady — the DAG is waiting on the gate
      }
    }
    throw new AssertionError("the build neither finished nor reached a steady state");
  }

  /** Distinct queue names that currently hold rows for this build — drives the stub claim loop. */
  private java.util.Set<String> queuesWithWork() {
    java.util.Set<String> queues = new java.util.HashSet<>();
    for (TaskQueueRow t : stores.taskQueue().listByBuild(buildId)) {
      if ("QUEUED".equals(t.status) && t.queueName != null) {
        queues.add(t.queueName);
      }
    }
    if (queues.isEmpty()) {
      queues.add("default"); // ensure at least one claim attempt per pass
    }
    return queues;
  }

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "gate/job-" + System.nanoTime());
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
}

package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.queue.QueueProcessor;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.ApprovalRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import io.adaptiq.titan.timer.TimerSweepWorker;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
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
 * Adversarial ITs for issue #68 — the approval-gate half, driven through the REAL {@code
 * QueueProcessor} claim loop (not direct {@code advance()} calls) because the e2e failure was a
 * queue-layer latency defect, not an orchestrator one: specs 25/41 polled {@code
 * /api/v1/approvals?status=PENDING} for 30s and never saw the row, while the ADVANCE pass that
 * would have parked the gate sat behind a &gt;20s serial dispatch tick.
 *
 * <p>What is pinned:
 *
 * <ol>
 *   <li><b>One-tick park:</b> once the upstream stage's worker result is folded, the very next
 *       QueueProcessor tick must leave the approval step parked SLEEPING with its PENDING {@code
 *       titan.approvals} row visible via the same DAO query the API serves ({@code listByStatus}) —
 *       "any state the UI needs must be observable via the API within one queue tick".
 *   <li><b>Park-no-tick:</b> the parked build re-arms NO follow-up ADVANCE (spec-41's no-recreate
 *       floor: exactly one approval row, ever, per (build, node)) — including the stage-level
 *       {@code gate:} park shape that {@code ApprovalParkDetector} missed before #68 (a gate node
 *       parks RUNNING, not SLEEPING, and used to re-arm a 300s-backoff ADVANCE forever).
 *   <li><b>Clean closure on all three exits:</b> approve → build SUCCESS, reject → build FAILED,
 *       timeout (via the production {@link TimerSweepWorker} entry point) → row TIMED_OUT + build
 *       FAILED. Each exit leaves exactly ONE approval row and an empty PENDING list.
 * </ol>
 */
@Testcontainers
class ApprovalGateParkIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  /** The spec-25 pipeline shape, verbatim (25-approval-flow.spec.ts / PIPELINE_LONG). */
  private static final String APPROVAL_PIPELINE =
      """
      stages:
        - stage: build
          steps:
            - sh: echo built
        - stage: deploy
          dependsOn: [build]
          steps:
            - approval:
                prompt: "Deploy to prod?"
                timeout: 24h
            - sh: echo deployed
      """;

  private static final String APPROVAL_NODE = "deploy-s0";
  private static final String CONTROLLER = "approval-park-it";

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
      long jobId = insertJob(c, APPROVAL_PIPELINE);
      buildId = insertRunningBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(APPROVAL_PIPELINE);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  /**
   * The core #68 pin: the QueueProcessor tick that folds the upstream stage ALSO parks the approval
   * step and inserts the PENDING row — one tick, API-observable, no follow-up ADVANCE.
   */
  @Test
  void approvalParksWithinOneTickOfGateActivation_andReArmsNothing() {
    QueueProcessor processor = new QueueProcessor();

    // Tick 1: ADVANCE dispatches build-s0 to the (stubbed) worker.
    enqueueAdvance(0);
    processor.tick(stores, CONTROLLER, 3600);
    assertEquals(
        1, completeWorkerTasks(), "tick 1 must dispatch exactly the build-s0 step to the worker");

    // Tick 1 was productive (it dispatched a step), so the AdvanceHandler legitimately re-armed
    // one 5s-delayed follow-up ADVANCE. Snapshot the queue now: the PARKING tick below must not
    // add anything on top of it.
    long queuedBeforeParkTick = queuedOrchestrateCount();

    // The worker's completion writeback enqueues a fresh ADVANCE (production shape). THE tick
    // under test: it must fold build-s0 SUCCESS, open the deploy stage, and PARK the approval —
    // all in this one pass.
    enqueueAdvance(0);
    processor.tick(stores, CONTROLLER, 3600);

    FlowNodeRow node = stores.flowNodes().findByBuildAndNode(buildId, APPROVAL_NODE).orElseThrow();
    assertEquals("SLEEPING", node.status, "the approval step must be parked in the same tick");

    ApprovalRow row = stores.approvals().findLatestForNode(buildId, APPROVAL_NODE).orElseThrow();
    assertEquals("PENDING", row.status);
    assertEquals("Deploy to prod?", row.prompt);

    // API-observability: the exact query GET /api/v1/approvals?status=PENDING serves.
    assertTrue(
        stores.approvals().listByStatus("PENDING", 200, 0).stream().anyMatch(a -> a.id == row.id),
        "the PENDING row must be visible on the API's listByStatus path within the same tick");

    // Park-no-tick: the PARKING pass itself must re-arm nothing — only tick 1's pre-existing
    // 5s-delayed follow-up may remain on the queue.
    assertEquals(
        queuedBeforeParkTick,
        queuedOrchestrateCount(),
        "the parking tick must not re-arm ADVANCE — resume is event-driven");

    // No-recreate (spec 41): external ADVANCE spam must not mint extra rows or wake the node,
    // and every spammed task must complete without re-arming (parked => no follow-up).
    for (int i = 0; i < 3; i++) {
      enqueueAdvance(0);
    }
    processor.tick(stores, CONTROLLER, 3600);
    assertEquals(
        queuedBeforeParkTick,
        queuedOrchestrateCount(),
        "ADVANCE spam against a parked build must drain without re-arming");
    assertEquals(
        1,
        stores.approvals().listForBuild(buildId).size(),
        "exactly ONE approval row per (build, node), regardless of re-ticks");
    assertEquals(
        "SLEEPING",
        stores.flowNodes().findByBuildAndNode(buildId, APPROVAL_NODE).orElseThrow().status);
  }

  /** Approve path: decide → the build resumes, runs deploy-s1, finishes SUCCESS. One row. */
  @Test
  void approvePath_resumesAndFinishesSuccess() {
    QueueProcessor processor = new QueueProcessor();
    ApprovalRow row = parkAtGate(processor);

    ApprovalService.DecisionOutcome outcome =
        ApprovalService.decide(
            stores, row.id, "alice", Set.of("alice"), ApprovalService.Decision.APPROVED);
    assertTrue(outcome.applied(), outcome.message());

    driveUntilTerminal(processor);
    assertEquals("SUCCESS", stores.builds().findById(buildId).orElseThrow().status);
    assertEquals(
        "SUCCESS",
        stores.flowNodes().findByBuildAndNode(buildId, "deploy-s1").orElseThrow().status,
        "the post-approval step must really run");

    List<ApprovalRow> rows = stores.approvals().listForBuild(buildId);
    assertEquals(1, rows.size(), "no phantom rows post-approve: " + rows.size());
    assertEquals("APPROVED", rows.get(0).status);
    assertEquals(
        0,
        stores.approvals().listByStatus("PENDING", 200, 0).stream()
            .filter(a -> a.buildId == buildId)
            .count(),
        "the PENDING list must be clean after approve");
  }

  /** Reject path: decide → node FAILED → build FAILED. One row, REJECTED, no fresh PENDING. */
  @Test
  void rejectPath_failsBuildCleanly_noPhantomPending() {
    QueueProcessor processor = new QueueProcessor();
    ApprovalRow row = parkAtGate(processor);

    ApprovalService.DecisionOutcome outcome =
        ApprovalService.decide(
            stores, row.id, "alice", Set.of("alice"), ApprovalService.Decision.REJECTED);
    assertTrue(outcome.applied(), outcome.message());

    driveUntilTerminal(processor);
    assertEquals("FAILED", stores.builds().findById(buildId).orElseThrow().status);

    List<ApprovalRow> rows = stores.approvals().listForBuild(buildId);
    assertEquals(1, rows.size(), "reject must not re-insert a PENDING row (build-22 regression)");
    assertEquals("REJECTED", rows.get(0).status);
    FlowNodeRow deploy = stores.flowNodes().findByBuildAndNode(buildId, "deploy-s1").orElseThrow();
    assertEquals("SKIPPED", deploy.status, "downstream of a rejected gate must not run");
  }

  /** Timeout path via the production sweep: row TIMED_OUT, build FAILED, PENDING list clean. */
  @Test
  void timeoutPath_viaTimerSweepWorker_failsBuildAndFlipsRowTimedOut() {
    QueueProcessor processor = new QueueProcessor();
    ApprovalRow row = parkAtGate(processor);

    // Backdate expiry, then run the production sweep entry point — it must flip the row AND
    // enqueue the ADVANCE that resumes the parked node.
    stores.approvals().testOnlySetExpiresAt(row.id, Instant.now().minusSeconds(1));
    new TimerSweepWorker().sweep(stores);

    assertEquals("TIMED_OUT", stores.approvals().findById(row.id).orElseThrow().status);

    driveUntilTerminal(processor);
    assertEquals("FAILED", stores.builds().findById(buildId).orElseThrow().status);
    assertEquals(
        0,
        stores.approvals().listByStatus("PENDING", 200, 0).stream()
            .filter(a -> a.buildId == buildId)
            .count());
    assertTrue(
        stores.approvals().listByStatus("TIMED_OUT", 200, 0).stream().anyMatch(a -> a.id == row.id),
        "the row must surface in the TIMED_OUT list (spec 25 asserts this via the API)");
  }

  /**
   * The stage-level {@code gate:} park shape (rig leak regression): a gate node parks RUNNING — not
   * SLEEPING — with its PENDING approvals row. Before #68, {@code ApprovalParkDetector} only
   * recognised SLEEPING parks, so gate-parked builds re-armed a fresh backoff ADVANCE forever
   * (observed on the live rig as an ever-refreshing set of 300s-delayed task_queue rows, ABORTED
   * builds included). Pin: {@code advance()} reports parked, and an ADVANCE tick against the parked
   * build re-arms NOTHING.
   */
  @Test
  void stageGatePark_reportsParked_andDoesNotReArmAdvance() throws Exception {
    String yaml = Fixtures.load("moab-ci-pipeline.yml");
    long gateBuildId;
    try (Connection c = ds.getConnection()) {
      long gateJobId = insertJob(c, yaml);
      gateBuildId = insertRunningBuild(c, gateJobId);
    }
    new TitanFlowExecution(stores, gateBuildId).bake(yaml);

    // Drive to the open gate with direct advance() + stub-worker passes.
    TitanOrchestrator orchestrator = new TitanOrchestrator(stores, gateBuildId);
    TitanOrchestrator.AdvanceResult result = null;
    for (int i = 0; i < 40; i++) {
      result = orchestrator.advance();
      if (result.parked() || completeWorkerTasks(gateBuildId) == 0 && result.dispatched() == 0) {
        break;
      }
    }
    assertEquals(
        "RUNNING",
        stores.flowNodes().findByBuildAndNode(gateBuildId, "release-approval").orElseThrow().status,
        "pre-condition: the gate must be open (RUNNING, awaiting approval)");
    assertTrue(
        orchestrator.advance().parked(),
        "a RUNNING gate node with a PENDING approvals row IS a park — the pre-#68 detector "
            + "missed it and re-armed ADVANCE forever");

    // Through the real claim loop: the ADVANCE completes and re-arms nothing.
    enqueueAdvance(gateBuildId, 0);
    new QueueProcessor().tick(stores, CONTROLLER, 3600);
    assertEquals(
        0,
        queuedOrchestrateCount(gateBuildId),
        "a gate-parked build must not re-arm ADVANCE — resume is event-driven via decide/sweep");
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /** Drive to the parked-at-gate state and return the PENDING row. */
  @NonNull
  private ApprovalRow parkAtGate(@NonNull QueueProcessor processor) {
    enqueueAdvance(0);
    processor.tick(stores, CONTROLLER, 3600);
    completeWorkerTasks();
    enqueueAdvance(0);
    processor.tick(stores, CONTROLLER, 3600);
    ApprovalRow row = stores.approvals().findLatestForNode(buildId, APPROVAL_NODE).orElseThrow();
    assertEquals("PENDING", row.status, "pre-condition: the gate must be parked PENDING");
    return row;
  }

  /**
   * Tick + stub-worker loop until the build is terminal. Mimics the production event flow (every
   * worker completion enqueues a fresh immediate ADVANCE — the writeback path) and models elapsed
   * wall-clock by promoting the AdvanceHandler's delayed re-arm rows to available-now, so the test
   * does not sleep out the 5s backoff base.
   */
  private void driveUntilTerminal(@NonNull QueueProcessor processor) {
    for (int i = 0; i < 40; i++) {
      promoteDelayedAdvances();
      processor.tick(stores, CONTROLLER, 3600);
      if (completeWorkerTasks() > 0) {
        enqueueAdvance(0);
      }
      String status = stores.builds().findById(buildId).orElseThrow().status;
      if ("SUCCESS".equals(status) || "FAILED".equals(status) || "ABORTED".equals(status)) {
        return;
      }
    }
    throw new AssertionError(
        "build "
            + buildId
            + " never reached terminal — status="
            + stores.builds().findById(buildId).orElseThrow().status);
  }

  /** Time machine: make every delayed QUEUED ORCHESTRATE row for this build claimable now. */
  private void promoteDelayedAdvances() {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE titan.task_queue SET available_at = NOW() "
                    + "WHERE build_id = ? AND status = 'QUEUED' AND type = 'ORCHESTRATE'")) {
      ps.setLong(1, buildId);
      ps.executeUpdate();
    } catch (Exception e) {
      throw new AssertionError("could not promote delayed ADVANCE rows", e);
    }
  }

  /** Stub worker: claim + complete every queued EXECUTE_COMMAND for this build's queues. */
  private int completeWorkerTasks() {
    return completeWorkerTasks(buildId);
  }

  private int completeWorkerTasks(long forBuildId) {
    int completed = 0;
    for (String queue : queuesWithWork(forBuildId)) {
      Optional<TaskQueueRow> claimed;
      while ((claimed = stores.taskQueue().claimExecuteCommand("stub", queue, UUID.randomUUID()))
          .isPresent()) {
        TaskQueueRow t = claimed.get();
        stores.taskQueue().complete(t.id, t.claimToken, "COMPLETED", "{\"exitCode\":0}");
        completed++;
      }
    }
    return completed;
  }

  @NonNull
  private Set<String> queuesWithWork(long forBuildId) {
    Set<String> queues = new HashSet<>();
    for (TaskQueueRow t : stores.taskQueue().listByBuild(forBuildId)) {
      if ("QUEUED".equals(t.status) && "EXECUTE_COMMAND".equals(t.type) && t.queueName != null) {
        queues.add(t.queueName);
      }
    }
    return queues;
  }

  /** QUEUED ORCHESTRATE rows currently on the queue for this build (any availability). */
  private long queuedOrchestrateCount() {
    return queuedOrchestrateCount(buildId);
  }

  private long queuedOrchestrateCount(long forBuildId) {
    return stores.taskQueue().listByBuild(forBuildId).stream()
        .filter(t -> "ORCHESTRATE".equals(t.type) && "QUEUED".equals(t.status))
        .count();
  }

  private void enqueueAdvance(int delaySeconds) {
    enqueueAdvance(buildId, delaySeconds);
  }

  private void enqueueAdvance(long forBuildId, int delaySeconds) {
    TaskQueueRow t = new TaskQueueRow();
    t.type = "ORCHESTRATE";
    t.queueName = "default";
    t.status = "QUEUED";
    t.priority = 0;
    t.payloadJson = "{\"action\":\"ADVANCE\",\"buildId\":" + forBuildId + "}";
    t.attempts = 0;
    t.maxAttempts = 3;
    t.visibilityTimeoutSeconds = 3600;
    t.buildId = forBuildId;
    t.availableAt = Instant.now().plusSeconds(delaySeconds);
    stores.taskQueue().insert(t);
  }

  private static long insertJob(@NonNull Connection c, @NonNull String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "approval-park/job-" + System.nanoTime());
      ps.setString(2, yaml);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static long insertRunningBuild(@NonNull Connection c, long jobId) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, started_at) "
                + "VALUES (?, 1, 'RUNNING', NOW())",
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

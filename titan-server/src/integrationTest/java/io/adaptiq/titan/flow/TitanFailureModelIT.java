package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.LogRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for the design/45 <strong>failure model</strong> against real PostgreSQL —
 * build step 45-T. Each controller-side failure path is driven to its terminal state and the
 * structured field it must write is asserted on the right row:
 *
 * <ul>
 *   <li>a step that exited non-zero → the step node carries {@code failure_category = STEP_EXIT}
 *       and a {@code "exited <code>"} reason;
 *   <li>a precondition not met → the precondition node carries {@code failure_category =
 *       PRECONDITION};
 *   <li>a bake/synthesis failure → the BUILD row carries {@code failure_summary} (no node category
 *       — design/45 §3/§6);
 *   <li>the V5 migration applied: the {@code failure_category}/{@code failure_reason}/{@code
 *       failure_summary} columns exist.
 * </ul>
 *
 * <p>It also drives the console-rendering contract (design/45 §5): a FAILED node's per-node console
 * section ends with a {@code ✗ <reason>} line — for a {@code STEP_EXIT} node after its own step
 * log, for a {@code CREDENTIAL}/{@code DISPATCH} node as its only output — and the whole-build
 * console ends with the {@code ✓/✗ build …} summary line. The console assembly is performed by
 * {@link FlowNodeConsole#nodeLogLines}, which takes the {@link TitanStores} explicitly so the
 * Testcontainers-backed store needs no singleton install.
 *
 * <p>Credential-side paths (CREDENTIAL category, the TITAN_CREDENTIAL_KEY-not-configured DISPATCH
 * reason) drive the {@link CredentialResolver} against the {@link
 * io.adaptiq.titan.credentials.CredentialsService} and are covered by {@code
 * TitanCredentialFailureIT} (Testcontainers + the db-envelope secrets backend).
 *
 * <p><strong>Docker:</strong> Testcontainers needs a Docker daemon. On the Windows host without
 * Docker Desktop this class does not run — execute it under WSL, as the other Titan ITs document.
 */
@Testcontainers
class TitanFailureModelIT {

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

  private void bootstrap(String fixture) throws Exception {
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, Fixtures.load(fixture));
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(Fixtures.load(fixture));
  }

  // ── V5 migration ──────────────────────────────────────────────────────────

  /** The V5__failure_model.sql migration applied: the three new columns exist and are nullable. */
  @Test
  void v5MigrationAddedTheFailureColumns() throws Exception {
    try (Connection c = ds.getConnection()) {
      assertTrue(
          columnExists(c, "flow_nodes", "failure_category"), "flow_nodes.failure_category exists");
      assertTrue(
          columnExists(c, "flow_nodes", "failure_reason"), "flow_nodes.failure_reason exists");
      assertTrue(columnExists(c, "builds", "failure_summary"), "builds.failure_summary exists");
    }
  }

  // ── STEP_EXIT — a step that ran and exited non-zero ───────────────────────

  /**
   * A step that ran and exited non-zero folds to FAILED carrying {@code failure_category =
   * STEP_EXIT} and a one-line {@code "exited <code>"} reason (design/45 §3) — the detail is in its
   * own log, the failure model adds only the summary.
   */
  @Test
  void aNonZeroStepExitRecordsStepExitOnTheNode() throws Exception {
    bootstrap("diamond-dag.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance(); // RUNNING Build, dispatch its step
    failNextClaimedTask("{\"exitCode\":2}");
    orch.advance(); // reconcile -> FAILED

    FlowNodeRow step = node("build-s0");
    assertEquals("FAILED", step.status);
    assertEquals(
        "STEP_EXIT", step.failureCategory, "a step that exited non-zero is categorised STEP_EXIT");
    assertNotNull(step.failureReason, "a STEP_EXIT node carries a one-line reason");
    assertTrue(
        step.failureReason.contains("exited 2"),
        "the reason names the exit code; was: " + step.failureReason);
  }

  /**
   * An infra failure — a FAILED task with no {@code exitCode} (the worker died, the reaper timed it
   * out) — is categorised TIMEOUT, not STEP_EXIT (design/45 §3).
   */
  @Test
  void anInfraFailureWithNoExitCodeRecordsTimeout() throws Exception {
    bootstrap("diamond-dag.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance();
    failNextClaimedTask("{\"error\":\"visibility timeout exceeded\"}");
    orch.advance();

    FlowNodeRow step = node("build-s0");
    assertEquals("FAILED", step.status);
    assertEquals(
        "TIMEOUT", step.failureCategory, "an infra failure (no exitCode) is categorised TIMEOUT");
  }

  /** The structured failure is written BEFORE the node reaches FAILED — reason ready to render. */
  @Test
  void theFailureFieldIsInPlaceWhenTheNodeIsFailed() throws Exception {
    bootstrap("diamond-dag.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance();
    failNextClaimedTask("{\"exitCode\":1}");
    orch.advance();

    FlowNodeRow step = node("build-s0");
    // The node is FAILED — and at the moment it is FAILED both fields are already populated;
    // updateFailure() runs before the QUEUED->FAILED compare-and-set in the orchestrator.
    assertEquals("FAILED", step.status);
    assertNotNull(step.failureCategory, "category set by the time the node is FAILED");
    assertNotNull(step.failureReason, "reason set by the time the node is FAILED");
  }

  // ── PRECONDITION — a precondition gate not met ────────────────────────────

  /** A precondition whose expression is false fails its node with category PRECONDITION. */
  @Test
  void aFailedPreconditionRecordsPreconditionOnItsNode() throws Exception {
    bootstrap("precondition-pipeline.yml");
    drivePrecondition(false);

    FlowNodeRow pre = node("tests-passed");
    assertEquals("FAILED", pre.status);
    assertEquals("PRECONDITION", pre.failureCategory);
    assertNotNull(pre.failureReason, "the precondition node carries a reason");
    assertTrue(
        pre.failureReason.contains("Tests Passed"),
        "the reason names the precondition; was: " + pre.failureReason);
  }

  /** A satisfied precondition records no failure — the fields stay null. */
  @Test
  void aSatisfiedPreconditionRecordsNoFailure() throws Exception {
    bootstrap("precondition-pipeline.yml");
    drivePrecondition(true);

    FlowNodeRow pre = node("tests-passed");
    assertEquals("SUCCESS", pre.status);
    assertNull(pre.failureCategory, "a passed precondition records no category");
    assertNull(pre.failureReason, "a passed precondition records no reason");
  }

  // ── build failure_summary — a pre-node failure ────────────────────────────

  /**
   * A bake / synthesis failure has no failed node to carry the reason — it lives on the build's
   * {@code failure_summary}, and no flow node carries a failure category (design/45 §3/§6). This
   * exercises the DAO contract the QueueProcessor's {@code markBuildFailed} path writes.
   */
  @Test
  void aPreNodeFailureRecordsFailureSummaryOnTheBuildNotANode() throws Exception {
    bootstrap("diamond-dag.yml");
    String reason =
        "Pipeline synthesis failed: unknown step 'deploys' at line 4 — "
            + "check the step name against the Titan step catalogue.";
    stores.builds().updateStatus(buildId, "FAILED", null, java.time.Instant.now(), null, reason);
    stores.builds().updateFailureSummary(buildId, reason);

    assertEquals(
        reason,
        stores.builds().findById(buildId).orElseThrow().failureSummary,
        "the build carries the pre-node failure reason");
    // No flow node carries a failure category — the reason is on the build, not a node.
    for (FlowNodeRow n : stores.flowNodes().listByBuild(buildId)) {
      assertNull(
          n.failureCategory, "node " + n.nodeId + " must carry no category for a pre-node failure");
    }
  }

  // ── DISPATCH — a step that cannot be turned into a runnable task ──────────

  /**
   * A step that cannot be turned into a runnable task fails at dispatch with {@code
   * failure_category = DISPATCH} (design/45 §3). The trigger is deterministic and scaffolding-free:
   * a {@code script} step whose body carries a syntactically broken {@code ${{ … }}} reference.
   * {@link TitanOrchestrator}'s {@code stepPayload} resolves the body's templates while building
   * the {@code EXECUTE_COMMAND} payload — the {@code ExpressionEvaluator} parse of the broken
   * expression throws, {@code dispatchStepTask} catches it in its payload-build {@code catch} and
   * routes it to {@code failStepAtDispatch(step, "DISPATCH", …)}.
   *
   * <p>A dispatch failure produces no worker step log, so the node's {@code failure_reason} is the
   * only place the customer sees why (design/45 §4).
   */
  @Test
  void aStepThatCannotBuildItsPayloadRecordsDispatchOnTheNode() throws Exception {
    bootstrap("dispatch-failure.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance(); // RUNNING the stage, dispatch its step -> payload throws

    FlowNodeRow step = node("dispatch-s0");
    assertEquals("FAILED", step.status, "a step whose payload build throws is failed at dispatch");
    assertEquals(
        "DISPATCH",
        step.failureCategory,
        "a step that cannot be turned into a runnable task is categorised DISPATCH");
    assertNotNull(
        step.failureReason,
        "a DISPATCH failure carries the only copy of the reason — there is no step log");
    assertTrue(
        step.failureReason.contains("dispatch-s0"),
        "the reason names the step; was: " + step.failureReason);
  }

  /**
   * A DISPATCH failure enqueues no {@code EXECUTE_COMMAND} task — the step never became runnable —
   * and the build closes FAILED (design/45 §3: the node, not a synthetic task, carries the why).
   *
   * <p>Note: {@code AdvanceResult.dispatched()} counts dispatch <em>attempts</em>, not successful
   * enqueues — {@code advanceSteps} increments it after the {@code PENDING -> QUEUED} CAS, before
   * {@code dispatchStepTask} reports its {@code -1} payload-build failure — so the meaningful
   * assertion is that the durable queue carries no {@code EXECUTE_COMMAND} row.
   */
  @Test
  void aDispatchFailureEnqueuesNoTaskAndFailsTheBuild() throws Exception {
    bootstrap("dispatch-failure.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance(); // dispatch the step -> payload build throws
    assertTrue(
        stores.taskQueue().listByBuild(buildId).stream()
            .noneMatch(t -> "EXECUTE_COMMAND".equals(t.type)),
        "no EXECUTE_COMMAND task was enqueued for a dispatch-failed step");

    TitanOrchestrator.AdvanceResult last = orch.advance(); // fold the FAILED node into the build
    assertTrue(
        last.buildFinished(), "the build finishes once the DISPATCH-failed node is terminal");
    assertEquals("FAILED", last.buildResult());
  }

  // ── console rendering — design/45 §5 ──────────────────────────────────────

  /**
   * A FAILED STEP_EXIT node's per-node console ends with a {@code ✗ <reason>} line — after the
   * step's own log lines (the failure model is beside the step output, not a replacement).
   */
  @Test
  void stepExitNodeConsoleEndsWithTheFailureReasonAfterTheStepLog() throws Exception {
    bootstrap("diamond-dag.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance();
    // Attach a step log line against the dispatched task's token, then fail it non-zero.
    UUID token = onlyClaimableTaskToken("build-s0");
    appendLog(token, "make: *** [build] Error 2");
    completeClaimed("FAILED", "{\"exitCode\":2}");
    orch.advance();

    List<String> lines = FlowNodeConsole.nodeLogLines(stores, buildId, "build-s0");
    assertTrue(lines.contains("make: *** [build] Error 2"), "the step's own log is present");
    String last = lines.get(lines.size() - 1);
    assertTrue(last.startsWith("✗ "), "the node section ends with a ✗ line; was: " + last);
    assertTrue(last.contains("exited 2"), "the ✗ line carries the reason; was: " + last);
    // The ✗ line follows the step log — the failure summary is beside the output, not before.
    assertTrue(lines.indexOf("make: *** [build] Error 2") < lines.size() - 1);
  }

  /** A non-failed node's console carries no ✗ line — the failure model renders only on FAILED. */
  @Test
  void aSucceededNodeConsoleHasNoFailureLine() throws Exception {
    bootstrap("diamond-dag.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance();
    completeClaimed("COMPLETED", "{\"exitCode\":0}");
    orch.advance();

    List<String> lines = FlowNodeConsole.nodeLogLines(stores, buildId, "build-s0");
    assertTrue(
        lines.stream().noneMatch(l -> l.startsWith("✗ ")),
        "a SUCCESS node renders no ✗ failure line");
  }

  // ── drive helpers ─────────────────────────────────────────────────────────

  private void drivePrecondition(boolean testsPassed) {
    TitanOrchestrator orchestrator = new TitanOrchestrator(stores, buildId);
    for (int pass = 0; pass < 30; pass++) {
      if (orchestrator.advance().buildFinished()) {
        return;
      }
      Optional<TaskQueueRow> claimed;
      while ((claimed = stores.taskQueue().claim("stub", "default", UUID.randomUUID()))
          .isPresent()) {
        TaskQueueRow t = claimed.get();
        String result =
            "build-s0".equals(t.nodeId)
                ? "{\"exitCode\":0,\"outputs\":{\"passed\":" + testsPassed + "}}"
                : "{\"exitCode\":0}";
        stores.taskQueue().complete(t.id, t.claimToken, "COMPLETED", result);
      }
    }
    throw new AssertionError("build did not finish within 30 advance passes");
  }

  private void failNextClaimedTask(String resultJson) {
    completeClaimed("FAILED", resultJson);
  }

  private void completeClaimed(String status, String resultJson) {
    Optional<TaskQueueRow> claimed = stores.taskQueue().claim("stub", "default", UUID.randomUUID());
    assertTrue(claimed.isPresent(), "a dispatched EXECUTE_COMMAND task must be claimable");
    TaskQueueRow t = claimed.get();
    stores.taskQueue().complete(t.id, t.claimToken, status, resultJson);
  }

  /**
   * The {@code task_token} of the (single) currently-claimable task for a node — for log seeding.
   */
  private UUID onlyClaimableTaskToken(String nodeId) {
    for (TaskQueueRow t : stores.taskQueue().listByBuild(buildId)) {
      if (nodeId.equals(t.nodeId) && "EXECUTE_COMMAND".equals(t.type)) {
        return t.taskToken;
      }
    }
    throw new AssertionError("no EXECUTE_COMMAND task for node " + nodeId);
  }

  private void appendLog(UUID taskToken, String line) {
    LogRow row = new LogRow();
    row.taskId = taskToken;
    row.chunkIndex = 0;
    row.stream = "stdout";
    row.data = line;
    row.isFinal = true;
    stores.logs().insert(row);
  }

  private FlowNodeRow node(String nodeId) {
    return stores.flowNodes().findByBuildAndNode(buildId, nodeId).orElseThrow();
  }

  private static boolean columnExists(Connection c, String table, String column) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT 1 FROM information_schema.columns "
                + "WHERE table_schema = 'titan' AND table_name = ? AND column_name = ?")) {
      ps.setString(1, table);
      ps.setString(2, column);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "fail/job-" + System.nanoTime());
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

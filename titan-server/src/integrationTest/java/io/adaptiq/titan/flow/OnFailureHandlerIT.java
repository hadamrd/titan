package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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
 * Integration tests for the design/68 {@code onFailure:} handler-stage scope (#947), driven against
 * a real PostgreSQL: bake, advance, drive worker outcomes, assert handler firing semantics.
 *
 * <p>The fixture {@code on-failure-handler.yml} carries {@code Build → Test → NotifyOps(onFailure:
 * [Build, Test])}. The cases exercise:
 *
 * <ul>
 *   <li>handler fires when an upstream FAILED, build terminal stays FAILED (criterion #1 + #4);
 *   <li>handler SKIPs when every upstream SUCCEEDED (criterion #1);
 *   <li>restart-mid-flight: a fresh {@code TitanOrchestrator} after an upstream FAILED still fires
 *       the handler — the gate reads durable {@code flow_nodes} state, not in-memory advance hooks
 *       (criterion: "durable state, not in-memory advance hook").
 * </ul>
 */
@Testcontainers
class OnFailureHandlerIT {

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

  /**
   * End-to-end criterion #1 + #4: a failure-handler stage advances when an upstream reaches
   * terminal FAILED, runs its steps to SUCCESS, and the build terminal state stays FAILED — the
   * handler is observation/recovery, not absolution.
   */
  @Test
  void handlerFiresOnUpstreamFailedAndBuildStaysFailed() throws Exception {
    bootstrap("on-failure-handler.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    // Build stage: succeeds.
    orch.advance();
    completeClaimed("COMPLETED", "{\"exitCode\":0}");
    // Now Test stage runs.
    orch.advance();
    // Test stage: fails.
    completeClaimed("FAILED", "{\"exitCode\":1}");
    // Reconcile -> Test FAILED -> handler fires
    orch.advance();

    // The handler should be RUNNING with a step dispatched on this pass.
    assertEquals(
        "RUNNING",
        node("notifyops").status,
        "NotifyOps must be RUNNING after Test fails (any-upstream-failed criterion)");

    // Complete the handler's step successfully.
    completeClaimed("COMPLETED", "{\"exitCode\":0}");
    TitanOrchestrator.AdvanceResult last = orch.advance();
    // The handler succeeded, but the build stays FAILED — criterion #4: the handler does not mask
    // the original failure.
    assertTrue(last.buildFinished(), "build should be terminal after handler runs");
    assertEquals(
        "FAILED",
        last.buildResult(),
        "build terminal state stays FAILED — handler is observation, not absolution (criterion #4)");

    assertEquals("SUCCESS", node("notifyops").status, "NotifyOps stage SUCCESS");
    assertEquals("FAILED", node("test").status, "Test stage stays FAILED");
    assertEquals("SUCCESS", node("build").status, "Build stage stayed SUCCESS");
  }

  /**
   * Criterion #1 sad path: if every listed upstream SUCCEEDED, the handler is SKIPPED — its steps
   * are SKIPPED — and the build closes SUCCESS.
   */
  @Test
  void handlerIsSkippedWhenAllUpstreamsSucceed() throws Exception {
    bootstrap("on-failure-handler.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance();
    completeClaimed("COMPLETED", "{\"exitCode\":0}"); // Build OK
    orch.advance();
    completeClaimed("COMPLETED", "{\"exitCode\":0}"); // Test OK
    TitanOrchestrator.AdvanceResult last = orch.advance();

    assertTrue(last.buildFinished(), "build should be terminal");
    assertEquals(
        "SUCCESS", last.buildResult(), "with no failures and handler skipped, build is SUCCESS");
    assertEquals(
        "SKIPPED",
        node("notifyops").status,
        "NotifyOps must be SKIPPED when no upstream FAILED — handler is not fired");
    // Its lone step must also be SKIPPED so any downstream depsSatisfied check sees it as "done".
    assertEquals("SKIPPED", node("notifyops-s0").status, "handler's step must be SKIPPED too");
  }

  /**
   * Adversarial restart-mid-flight: simulate a crashed orchestrator between upstream-FAILED and
   * handler dispatch by constructing a fresh {@link TitanOrchestrator}. The gate reads durable
   * {@code flow_nodes} state — the handler must still fire.
   */
  @Test
  void handlerFiresOnFreshOrchestratorAfterCrash() throws Exception {
    bootstrap("on-failure-handler.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance();
    completeClaimed("COMPLETED", "{\"exitCode\":0}");
    orch.advance();
    completeClaimed("FAILED", "{\"exitCode\":1}");
    orch.advance(); // Test → FAILED + handler RUNNING + step dispatched.

    // Simulate the controller dying right here: discard `orch`, build a new one.
    TitanOrchestrator survivor = new TitanOrchestrator(stores, buildId);
    completeClaimed("COMPLETED", "{\"exitCode\":0}");
    TitanOrchestrator.AdvanceResult last = survivor.advance();

    assertTrue(last.buildFinished(), "survivor orchestrator must drain to terminal");
    assertEquals("FAILED", last.buildResult(), "build terminal still FAILED (criterion #4)");
    assertEquals("SUCCESS", node("notifyops").status, "handler fired and succeeded on survivor");
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void bootstrap(String fixture) throws Exception {
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, Fixtures.load(fixture));
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(Fixtures.load(fixture));
  }

  private void completeClaimed(String status, String resultJson) {
    Optional<TaskQueueRow> claimed = stores.taskQueue().claim("stub", "default", UUID.randomUUID());
    assertTrue(claimed.isPresent(), "a dispatched EXECUTE_COMMAND task must be claimable");
    TaskQueueRow t = claimed.get();
    stores.taskQueue().complete(t.id, t.claimToken, status, resultJson);
  }

  private FlowNodeRow node(String nodeId) {
    return stores.flowNodes().findByBuildAndNode(buildId, nodeId).orElseThrow();
  }

  private BuildRow buildRow() {
    return stores.builds().findById(buildId).orElseThrow();
  }

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "on-failure/job-" + System.nanoTime());
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

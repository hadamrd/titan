package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link TitanOrchestrator#advance()} against real PostgreSQL — Chunk 6D.
 *
 * <p>Drives a baked DAG to completion: each test loops {@code advance()} and a <em>stubbed
 * worker</em> (claims and completes the {@code EXECUTE_COMMAND} tasks the orchestrator dispatches)
 * until the build is terminal. Proves the design/31 6D done-when — a multi-stage fan-out/fan-in DAG
 * completes {@code SUCCESS}, and a step failure stops the DAG with downstream nodes {@code SKIPPED}
 * under {@code blockOnFailure}.
 */
@Testcontainers
class TitanOrchestratorIT {

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
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, Fixtures.load("diamond-dag.yml"));
      buildId = insertBuild(c, jobId);
    }
    // Bake first — the orchestrator advances an already-materialised DAG.
    new TitanFlowExecution(stores, buildId).bake(Fixtures.load("diamond-dag.yml"));
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  /** The diamond DAG (Build → Lint ∥ Test → Deploy) drives to SUCCESS with every step passing. */
  @Test
  void diamondDagFanOutFanInCompletesSuccessfully() {
    TitanOrchestrator.AdvanceResult last = driveToCompletion(0);

    assertTrue(last.buildFinished(), "the build must finish");
    assertEquals("SUCCESS", last.buildResult());
    assertEquals("SUCCESS", stores.builds().findById(buildId).orElseThrow().status);

    Map<String, String> nodes = nodeStatuses();
    // Every stage and step is SUCCESS.
    for (Map.Entry<String, String> n : nodes.entrySet()) {
      assertEquals("SUCCESS", n.getValue(), "node " + n.getKey() + " must be SUCCESS");
    }
    assertEquals(8, nodes.size(), "4 stages + 4 steps");
  }

  /**
   * A failing step stops the DAG: its stage FAILS, downstream stages/steps are SKIPPED
   * (blockOnFailure), and the build ends FAILED — no zombie running nodes.
   */
  @Test
  void aFailedStepBlocksTheDagAndFailsTheBuild() {
    // Fail the Test stage's step; everything downstream of it must stop.
    TitanOrchestrator.AdvanceResult last =
        driveToCompletion(1 /* fail nth claimed step */, "test-s0");

    assertTrue(last.buildFinished());
    assertEquals("FAILED", last.buildResult());
    assertEquals("FAILED", stores.builds().findById(buildId).orElseThrow().status);

    Map<String, String> nodes = nodeStatuses();
    assertEquals("SUCCESS", nodes.get("build"), "the upstream stage still succeeded");
    assertEquals("FAILED", nodes.get("test-s0"), "the failing step is FAILED");
    assertEquals("FAILED", nodes.get("test"), "its stage is FAILED");
    assertEquals("SKIPPED", nodes.get("deploy"), "the downstream stage is SKIPPED");
    assertEquals("SKIPPED", nodes.get("deploy-s0"), "the downstream step is SKIPPED");
    // No node is left non-terminal.
    assertEquals(0, stores.flowNodes().countNonTerminal(buildId));
  }

  /** advance() is idempotent — a redundant pass on a finished build changes nothing. */
  @Test
  void advanceIsIdempotentOnAFinishedBuild() {
    driveToCompletion(0);
    TitanOrchestrator.AdvanceResult again = new TitanOrchestrator(stores, buildId).advance();
    assertEquals(0, again.dispatched());
    assertEquals(0, again.reconciled());
    assertTrue(again.buildFinished());
    assertEquals("SUCCESS", stores.builds().findById(buildId).orElseThrow().status);
  }

  // ---- the advance + stubbed-worker drive loop ------------------------

  private TitanOrchestrator.AdvanceResult driveToCompletion(int failEveryStep) {
    return driveToCompletion(failEveryStep, null);
  }

  /**
   * Loop {@code advance()} and a stubbed worker until the build is terminal. The stub claims each
   * dispatched {@code EXECUTE_COMMAND} task and completes it — successfully, except the task whose
   * node id equals {@code failNodeId} (or, if that is null and {@code failEveryStep} is non-zero,
   * never).
   */
  private TitanOrchestrator.AdvanceResult driveToCompletion(int unused, String failNodeId) {
    TitanOrchestrator orchestrator = new TitanOrchestrator(stores, buildId);
    TitanOrchestrator.AdvanceResult result = null;
    for (int pass = 0; pass < 30; pass++) {
      result = orchestrator.advance();
      if (result.buildFinished()) {
        return result;
      }
      // Stubbed worker — claim and complete every dispatched EXECUTE_COMMAND task.
      Optional<TaskQueueRow> claimed;
      while ((claimed = stores.taskQueue().claim("stub-worker", "default", UUID.randomUUID()))
          .isPresent()) {
        TaskQueueRow t = claimed.get();
        boolean fail = t.nodeId != null && t.nodeId.equals(failNodeId);
        stores
            .taskQueue()
            .complete(t.id, t.claimToken, "COMPLETED", "{\"exitCode\":" + (fail ? 1 : 0) + "}");
      }
    }
    throw new AssertionError("build did not finish within 30 advance passes");
  }

  private Map<String, String> nodeStatuses() {
    return stores.flowNodes().listByBuild(buildId).stream()
        .collect(Collectors.toMap(n -> n.nodeId, n -> n.status));
  }

  // ---- fixtures -------------------------------------------------------

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "orch/job-" + System.nanoTime());
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

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
 * Integration test for step-level {@code when:} runtime evaluation (design/29 §3, PR #260, GH
 * #353).
 *
 * <p>Drives a baked DAG with three stages — one with no {@code when:}, one with {@code when:
 * "false"} (must SKIP at dispatch with no task_queue row produced), and one with {@code when:
 * "true"} (must dispatch and SUCCEED). Proves the orchestrator's step-level guard delegates to the
 * shared {@link io.adaptiq.titan.flow.expr.WhenEvaluator} and that a falsy guard does not block the
 * DAG (downstream stages still run).
 *
 * <p>Mirrors the harness in {@link TitanOrchestratorIT}.
 */
@Testcontainers
class WhenRuntimeIT {

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
      long jobId = insertJob(c, Fixtures.load("when-runtime.yml"));
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(Fixtures.load("when-runtime.yml"));
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  /**
   * The orchestrator dispatches the first and third stages (their {@code when:} is absent / true)
   * and SKIPs the middle step at dispatch time. The skipped step never produces a task_queue row
   * and does not block its stage, so the downstream conditional stage still runs and SUCCEEDS.
   */
  @Test
  void stepLevelWhenSkipsFalsyAndRunsTruthy() {
    driveToCompletion();

    assertEquals("SUCCESS", stores.builds().findById(buildId).orElseThrow().status);

    Map<String, String> nodes = nodeStatuses();
    // Stage 1 — no when:, dispatched & succeeded.
    assertEquals("SUCCESS", nodes.get("always-runs"), "always-runs stage must SUCCEED");
    assertEquals("SUCCESS", nodes.get("always-runs-s0"), "always-runs step must SUCCEED");

    // Stage 2 — step `when: "false"` → step SKIPPED at dispatch, stage SUCCESS (skipped step
    // does not block its stage), and crucially: downstream still runs.
    assertEquals("SKIPPED", nodes.get("skipped-s0"), "step with when:false must be SKIPPED");
    assertTrue(
        "SUCCESS".equals(nodes.get("skipped")) || "SKIPPED".equals(nodes.get("skipped")),
        "stage holding only a skipped step is terminal (SUCCESS or SKIPPED), got "
            + nodes.get("skipped"));

    // Stage 3 — step `when: "true"` → dispatched and SUCCEEDS.
    assertEquals("SUCCESS", nodes.get("conditional"), "conditional stage must SUCCEED");
    assertEquals(
        "SUCCESS", nodes.get("conditional-s0"), "conditional step (when:true) must SUCCEED");

    // No node left running.
    assertEquals(0, stores.flowNodes().countNonTerminal(buildId));
  }

  // ---- the advance + stubbed-worker drive loop ------------------------

  /**
   * Loop {@code advance()} and a stubbed worker until the build is terminal. The stub claims and
   * succeeds every dispatched EXECUTE_COMMAND task — a step the orchestrator skips (when:false)
   * produces no task_queue row, so the stub never sees it.
   */
  private void driveToCompletion() {
    TitanOrchestrator orchestrator = new TitanOrchestrator(stores, buildId);
    for (int pass = 0; pass < 30; pass++) {
      TitanOrchestrator.AdvanceResult result = orchestrator.advance();
      if (result.buildFinished()) {
        return;
      }
      Optional<TaskQueueRow> claimed;
      while ((claimed = stores.taskQueue().claim("stub-worker", "default", UUID.randomUUID()))
          .isPresent()) {
        TaskQueueRow t = claimed.get();
        stores.taskQueue().complete(t.id, t.claimToken, "COMPLETED", "{\"exitCode\":0}");
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
      ps.setString(1, "when/job-" + System.nanoTime());
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

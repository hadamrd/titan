package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
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
 * Integration test for automated <strong>precondition</strong> nodes against real PostgreSQL —
 * Chunk 6F (design/29 §4/§7.1).
 *
 * <p>Drives the {@code precondition-pipeline.yml} fixture — {@code Build} → precondition {@code
 * Tests Passed} → {@code Deploy}. The precondition carries the CEL-subset expression {@code
 * steps['Build'].outputs.passed == true}, evaluated by the orchestrator at run time against the
 * output {@code Build} published. Proves the design/31 6F done-when for preconditions: a satisfied
 * expression passes the node ({@code SUCCESS}) and the DAG runs on; a false one fails it ({@code
 * FAILED}) and — under {@code blockOnFailure} — the downstream stage is {@code SKIPPED}.
 */
@Testcontainers
class TitanPreconditionIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String FIXTURE = "precondition-pipeline.yml";

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

  /** Build publishes {@code passed=true}; the precondition passes and Deploy runs to SUCCESS. */
  @Test
  void aSatisfiedPreconditionLetsTheDagContinue() {
    driveToCompletion(true);

    assertEquals("SUCCESS", stores.builds().findById(buildId).orElseThrow().status);
    assertEquals("SUCCESS", nodeStatus("tests-passed"), "the precondition must pass");
    assertEquals("SUCCESS", nodeStatus("deploy"), "Deploy must run after the precondition passes");
  }

  /** Build publishes {@code passed=false}; the precondition fails and Deploy is SKIPPED. */
  @Test
  void aFailedPreconditionBlocksTheDownstreamDag() {
    driveToCompletion(false);

    assertEquals("FAILED", stores.builds().findById(buildId).orElseThrow().status);
    assertEquals("SUCCESS", nodeStatus("build"), "the upstream stage still succeeded");
    assertEquals("FAILED", nodeStatus("tests-passed"), "a false expression fails the precondition");
    assertEquals("SKIPPED", nodeStatus("deploy"), "the downstream stage is SKIPPED");
    assertEquals(0, stores.flowNodes().countNonTerminal(buildId), "no node left non-terminal");
  }

  // ---- the advance + stubbed-worker drive loop ------------------------

  /**
   * Loop {@code advance()} + a stubbed worker until the build is terminal. The {@code Build} step
   * is completed with {@code outputs.passed} = {@code testsPassed}, so the precondition evaluates
   * against a real published output.
   */
  private void driveToCompletion(boolean testsPassed) {
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

  private String nodeStatus(String nodeId) {
    return stores.flowNodes().findByBuildAndNode(buildId, nodeId).orElseThrow().status;
  }

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "pre/job-" + System.nanoTime());
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

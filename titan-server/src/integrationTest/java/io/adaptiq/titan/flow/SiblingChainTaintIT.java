package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
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
 * Regression IT for issue #945: under the default {@code failurePolicy: blockOnFailure}, a failure
 * in one chain MUST NOT taint an independent sibling chain that shares only a common ancestor.
 *
 * <p>The fixture is the live evidence reproduced from PR #946:
 *
 * <pre>
 *   Checkout (root) -> SUCCESS
 *     ├─ Frontend Lint -> FAILED
 *     │   └─ Frontend Test            (must be SKIPPED — descendant of the failure)
 *     └─ Backend Lint                 (must ADVANCE — independent of the failure)
 *         └─ Backend Test             (must ADVANCE — independent of the failure)
 * </pre>
 *
 * <p>Pre-#945 the orchestrator's "any FAILED node → sweep every non-terminal to SKIPPED" blunder
 * would mark Backend Lint and Backend Test SKIPPED. The fix in {@code
 * TitanOrchestrator#finishIfDone} uses {@code AncestorClosure} to compute per-node ancestry and
 * SKIP only the descendants of the failing node.
 *
 * <p>Testcontainers needed (Docker daemon). Mirrors the {@code TitanFailureModelIT} bootstrap.
 */
@Testcontainers
class SiblingChainTaintIT {

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

    String yaml = Fixtures.load("independent-chains.yml");
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, yaml);
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(yaml);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  /**
   * The #945 contract: Frontend Lint fails, Backend Lint and Backend Test still run to SUCCESS.
   * Pre-fix this test would have observed Backend Lint = SKIPPED on the same pass that failed
   * Frontend Lint.
   */
  @Test
  void siblingChainsKeepRunningAfterAnIndependentChainFails() {
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    // Drive: Checkout succeeds, then the two siblings dispatch in parallel.
    // We claim/complete tasks one at a time: Checkout COMPLETED, then Frontend Lint FAILED,
    // then Backend Lint + Backend Test COMPLETED. Frontend Test must end SKIPPED.
    driveToCompletion(orch);

    assertEquals("SUCCESS", node("checkout").status, "Checkout must succeed");
    assertEquals("FAILED", node("frontend-lint").status, "Frontend Lint must be FAILED");
    assertEquals(
        "SKIPPED",
        node("frontend-test").status,
        "Frontend Test is a descendant of Frontend Lint and MUST be SKIPPED");

    // The #945 assertions.
    assertNotEquals(
        "SKIPPED",
        node("backend-lint").status,
        "Backend Lint shares only Checkout with Frontend Lint — it MUST NOT be SKIPPED");
    assertEquals(
        "SUCCESS",
        node("backend-lint").status,
        "Backend Lint must run to SUCCESS — Checkout succeeded and Frontend Lint is a sibling");
    assertEquals(
        "SUCCESS",
        node("backend-test").status,
        "Backend Test must run to SUCCESS — its chain has no failed ancestor");
  }

  /**
   * The build closes FAILED — the per-node sweep is fixed, but the overall build outcome must still
   * surface that one chain failed (matches GitHub Actions / GitLab CI).
   */
  @Test
  void buildOverallStatusIsFailedEvenThoughSiblingsSucceeded() {
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);
    driveToCompletion(orch);

    String buildStatus = stores.builds().findById(buildId).orElseThrow().status;
    assertEquals("FAILED", buildStatus, "any FAILED node taints the overall build result");
  }

  // ── drive helpers ─────────────────────────────────────────────────────

  private void driveToCompletion(TitanOrchestrator orch) {
    for (int pass = 0; pass < 60; pass++) {
      TitanOrchestrator.AdvanceResult res = orch.advance();
      // Drain whatever was just dispatched.
      Optional<TaskQueueRow> claimed;
      while ((claimed = stores.taskQueue().claim("stub", "default", UUID.randomUUID()))
          .isPresent()) {
        TaskQueueRow t = claimed.get();
        boolean shouldFail = "frontend-lint-s0".equals(t.nodeId);
        stores
            .taskQueue()
            .complete(
                t.id,
                t.claimToken,
                shouldFail ? "FAILED" : "COMPLETED",
                shouldFail ? "{\"exitCode\":1}" : "{\"exitCode\":0}");
      }
      if (res.buildFinished()) {
        return;
      }
    }
    throw new AssertionError("build did not finish within 60 advance passes");
  }

  private FlowNodeRow node(String nodeId) {
    return stores.flowNodes().findByBuildAndNode(buildId, nodeId).orElseThrow();
  }

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "fix945/job-" + System.nanoTime());
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

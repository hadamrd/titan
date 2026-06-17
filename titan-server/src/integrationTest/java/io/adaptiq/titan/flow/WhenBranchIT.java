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
 * Integration test for the structured {@code when: { branch: <glob> }} guard (GH #1093) — the
 * issue's headline scenario: a step guarded on a feature-branch glob <em>fires</em> on a feature
 * branch and is <em>SKIPPED</em> on trunk.
 *
 * <p>Drives a real baked DAG against Postgres twice with different {@code builds.trigger_meta_json}
 * branches, and asserts the orchestrator's dispatch-time {@code StructuredWhenGate} decision: the
 * guarded step is SKIPPED on trunk (no task_queue row) and SUCCEEDS on a feature branch, while the
 * unguarded steps always run. Mirrors the harness in {@link WhenRuntimeIT}.
 */
@Testcontainers
class WhenBranchIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;

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

  @Test
  void branchGuardSkipsGuardedStepOnTrunk() throws Exception {
    Map<String, String> nodes = runWithBranch("trunk");
    assertEquals("SUCCESS", nodes.get("build-s0"), "first (unguarded) step runs");
    assertEquals(
        "SKIPPED", nodes.get("build-s1"), "guarded step (branch: feature/*) must SKIP on trunk");
    assertEquals("SUCCESS", nodes.get("build-s2"), "third (unguarded) step still runs");
    assertEquals("SUCCESS", nodes.get("build"), "stage SUCCEEDS despite the skipped step");
  }

  @Test
  void branchGuardFiresGuardedStepOnFeatureBranch() throws Exception {
    Map<String, String> nodes = runWithBranch("feature/login");
    assertEquals("SUCCESS", nodes.get("build-s0"), "first step runs");
    assertEquals(
        "SUCCESS",
        nodes.get("build-s1"),
        "guarded step (branch: feature/*) must FIRE on a feature branch");
    assertEquals("SUCCESS", nodes.get("build-s2"), "third step runs");
    assertEquals("SUCCESS", nodes.get("build"), "stage SUCCEEDS");
  }

  // ---- harness --------------------------------------------------------

  /** Bake the when-branch fixture for a build on {@code branch}, drive it, return node statuses. */
  private Map<String, String> runWithBranch(String branch) throws Exception {
    long buildId;
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, Fixtures.load("when-branch.yml"));
      buildId = insertBuild(c, jobId, "{\"branch\":\"" + branch + "\"}");
    }
    new TitanFlowExecution(stores, buildId).bake(Fixtures.load("when-branch.yml"));
    driveToCompletion(buildId);
    assertEquals("SUCCESS", stores.builds().findById(buildId).orElseThrow().status);
    return stores.flowNodes().listByBuild(buildId).stream()
        .collect(Collectors.toMap(n -> n.nodeId, n -> n.status));
  }

  private void driveToCompletion(long buildId) {
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

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "when-branch/job-" + System.nanoTime());
      ps.setString(2, yaml);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static long insertBuild(Connection c, long jobId, String triggerMetaJson)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, trigger_meta_json) "
                + "VALUES (?, 1, 'QUEUED', ?)",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, jobId);
      ps.setString(2, triggerMetaJson);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }
}

package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.flow.orch.ControlPlaneSteps;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * GH #805 contract IT: control-plane step descriptors (sleep, waitUntil, approval, setBuildName)
 * are resolved on the controller and MUST NEVER produce a {@code task_queue} row — if they do, the
 * worker has no handler for the keyword and the build sits QUEUED forever (the original #805
 * symptom).
 *
 * <p>This is the standing oracle for the centralised {@link ControlPlaneSteps#IDS} registry: any
 * future resolver added to that set without a matching handler in {@code
 * TitanOrchestrator#advanceSteps} will be caught here. The E2E spec {@code
 * titan-ui/e2e/specs/v3/28-fixture-with-setBuildName.spec.ts} is the integration-rig sibling — it
 * flips green automatically once this fix lands and the rig is rebuilt.
 *
 * <p>Three cases:
 *
 * <ul>
 *   <li>{@code setBuildName} → orchestrator applies + completes SUCCESS, no EXECUTE_COMMAND row
 *   <li>{@code approval} → orchestrator parks SLEEPING, no EXECUTE_COMMAND row
 *   <li>{@code sh} (regression guard) → exactly one EXECUTE_COMMAND row enqueued
 * </ul>
 */
@Testcontainers
class ControlPlaneStepDispatchIT {

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
  }

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "control-plane-it/job-" + System.nanoTime());
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

  private long countExecuteCommandTasks() throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT COUNT(*) FROM titan.task_queue "
                    + "WHERE type = 'EXECUTE_COMMAND' AND build_id = ?")) {
      ps.setLong(1, buildId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private FlowNodeRow nodeByDescriptor(String descriptorId) {
    return stores.flowNodes().listByBuild(buildId).stream()
        .filter(n -> descriptorId.equals(n.stepDescriptor))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no flow_node with stepDescriptor=" + descriptorId));
  }

  // ── tests ─────────────────────────────────────────────────────────────────

  @Test
  void setBuildNameDescriptorDoesNotEnqueueAnyTaskQueueRow() throws Exception {
    bake("set-build-name-single.yml");
    new TitanOrchestrator(stores, buildId).advance();

    FlowNodeRow renameNode = nodeByDescriptor("setBuildName");
    assertEquals(
        "SUCCESS",
        renameNode.status,
        "setBuildName must be resolved synchronously on the controller — never SLEEPING/QUEUED");
    // Only the trailing `sh` step may produce an EXECUTE_COMMAND row; setBuildName must NOT.
    assertEquals(
        1L,
        countExecuteCommandTasks(),
        "exactly one EXECUTE_COMMAND row (the trailing sh); setBuildName must not enqueue");
  }

  @Test
  void approvalDescriptorDoesNotEnqueueAnyTaskQueueRow() throws Exception {
    bake("approval-step.yml");
    // Drive the Build stage forward synthetically so the Deploy/approval stage activates.
    new TitanOrchestrator(stores, buildId).advance();
    completeAllQueuedSteps();

    FlowNodeRow approvalNode = nodeByDescriptor("approval");
    assertEquals(
        "SLEEPING",
        approvalNode.status,
        "approval must park controller-side (SLEEPING) — never QUEUED for a worker");
    // The Build stage's sh produced one EXECUTE_COMMAND row; the approval must NOT add another,
    // and the trailing sh in Deploy must NOT enqueue yet (gated by the SLEEPING approval).
    assertEquals(
        1L,
        countExecuteCommandTasks(),
        "only the Build stage's sh enqueued; approval and the post-approval sh must not");
  }

  @Test
  void normalShStepDoesEnqueue() throws Exception {
    bake("control-plane-mixed-steps.yml");
    new TitanOrchestrator(stores, buildId).advance();

    FlowNodeRow shNode = nodeByDescriptor("sh");
    assertNotEquals(
        "PENDING", shNode.status, "the sh step must transition out of PENDING on the first pass");
    assertEquals(
        1L,
        countExecuteCommandTasks(),
        "a regular worker step (sh) MUST produce exactly one EXECUTE_COMMAND row "
            + "— regression guard so the control-plane gate did not over-trigger");
  }

  // ── helper: synthetic worker — mark every QUEUED EXECUTE_COMMAND as COMPLETED ────────────────

  private void completeAllQueuedSteps() throws Exception {
    for (int i = 0; i < 20; i++) {
      boolean any;
      try (Connection c = ds.getConnection();
          Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT id, node_id FROM titan.task_queue "
                      + "WHERE type = 'EXECUTE_COMMAND' AND status = 'QUEUED' AND build_id = "
                      + buildId)) {
        any = false;
        while (rs.next()) {
          long taskId = rs.getLong(1);
          String nodeId = rs.getString(2);
          try (Statement up = c.createStatement()) {
            up.execute("UPDATE titan.task_queue SET status = 'COMPLETED' WHERE id = " + taskId);
          }
          stores
              .flowNodes()
              .compareAndSetStatus(
                  buildId, nodeId, "QUEUED", "SUCCESS", null, java.time.Instant.now(), 1L, null);
          any = true;
        }
      }
      new TitanOrchestrator(stores, buildId).advance();
      if (!any) {
        return;
      }
    }
  }
}

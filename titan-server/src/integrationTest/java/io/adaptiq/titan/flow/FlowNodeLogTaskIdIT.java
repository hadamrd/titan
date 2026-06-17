package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression for issue #508 — the UI's per-node Logs panel renders empty for SUCCESS builds because
 * {@code titan.flow_nodes.log_task_id} stays NULL on STEP rows. The worker streams logs into {@code
 * titan.logs} keyed by the EXECUTE_COMMAND task's {@code task_token}, but the orchestrator never
 * wires that token through to the flow_nodes row — so the UI cannot join logs to a node.
 *
 * <p>This IT drives a build through the real orchestrator, simulates a worker writing a log row
 * keyed by the dispatched task's {@code task_token}, then asserts <strong>zero</strong> STEP rows
 * have a NULL {@code log_task_id}.
 */
@Testcontainers
class FlowNodeLogTaskIdIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private long buildId;

  /** Minimal one-stage, one-step pipeline. */
  private static final String HELLO_PIPELINE =
      "stages:\n" + "  - stage: hello\n" + "    steps:\n" + "      - sh: echo hello\n";

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
      long jobId = insertJob(c, HELLO_PIPELINE);
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(HELLO_PIPELINE);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  /**
   * Drives the full happy-path through the real orchestrator and asserts the post-fix invariant: no
   * STEP-typed flow_nodes row may have a NULL log_task_id after the build reaches SUCCESS.
   */
  @Test
  void stepFlowNodesCarryTheirExecuteCommandTaskTokenAsLogTaskId() throws Exception {
    // First ADVANCE — orchestrator dispatches the step's EXECUTE_COMMAND.
    new TitanOrchestrator(stores, buildId).advance();

    TaskQueueRow exec = findOnlyQueuedExecCommand();
    assertNotNull(exec, "first ADVANCE must dispatch the step task");
    assertNotNull(exec.taskToken, "task_token must be populated by the DB default");

    // Stub worker: write at least one row into titan.logs keyed by task_token, then mark the
    // task COMPLETED — exactly the shape the live worker produces.
    writeLogChunk(exec.taskToken, "hello\n");
    completeTask(exec.id, 0);

    // Archive sweep — keeps the IT honest by exercising the task_queue ∪ task_archive read.
    stores.taskQueue().moveCompletedToArchive(20);

    // Second ADVANCE — folds the archived completion into flow_nodes AND must stamp log_task_id.
    new TitanOrchestrator(stores, buildId).advance();

    FlowNodeRow step = stores.flowNodes().findByBuildAndNode(buildId, "hello-s0").orElseThrow();
    assertEquals("SUCCESS", step.status, "step must reconcile to SUCCESS");

    // === The bug-class assertion (issue #508). ===
    assertEquals(
        0,
        countStepRowsWithNullLogTaskId(),
        "no STEP flow_nodes row may have NULL log_task_id after reconcile — that is issue #508");

    // Belt-and-braces: the stamped UUID must match the dispatched task's token AND a row in
    // titan.logs must be joinable by that token (the join the UI relies on).
    assertEquals(
        exec.taskToken,
        step.logTaskId,
        "log_task_id must point at the EXECUTE_COMMAND task that streamed the logs");
    assertTrue(
        countLogRowsForToken(step.logTaskId) >= 1,
        "the stamped log_task_id must be joinable to titan.logs (UI assembles the console this"
            + " way)");
  }

  /**
   * Issue #536 — the FAILED-path regression. Live evidence: build 26 hit a failing step, the task
   * was archived with its task_token, titan.logs had the chunks, yet flow_nodes.log_task_id stayed
   * NULL on the STEP row. The original SUCCESS-path IT (above) passed in CI because it seeded the
   * shape that triggered the reconcile sweep — but on the rig the FAILED path closed the build
   * before the reconcile setLogTaskId fired. This test drives a build to FAILED and asserts the
   * same invariant.
   */
  @Test
  void stepFlowNodesCarryLogTaskIdOnTheFailedPathToo() throws Exception {
    // First ADVANCE — dispatches the step's EXECUTE_COMMAND. With the #536 fix, log_task_id is
    // stamped synchronously at dispatch, so even if the build closes before any reconcile pass
    // observes the archived terminal task, the STEP row already carries its token.
    new TitanOrchestrator(stores, buildId).advance();

    TaskQueueRow exec = findOnlyQueuedExecCommand();
    assertNotNull(exec, "first ADVANCE must dispatch the step task");
    assertNotNull(exec.taskToken, "task_token must be populated by the DB default");

    // Stub worker writes the log chunks under task_token, then marks the task FAILED with a
    // non-zero exit — exactly the shape build 26 produced.
    writeLogChunk(exec.taskToken, "boom\n");
    failTask(exec.id, 1);

    // Archive sweep — the live rig archives terminal tasks before / during the next ADVANCE.
    stores.taskQueue().moveCompletedToArchive(20);

    // Second ADVANCE — folds the archived FAILED task and closes the build.
    new TitanOrchestrator(stores, buildId).advance();

    FlowNodeRow step = stores.flowNodes().findByBuildAndNode(buildId, "hello-s0").orElseThrow();
    assertEquals("FAILED", step.status, "step must reconcile to FAILED");

    // === The bug-class assertion (issue #536). ===
    assertEquals(
        0,
        countStepRowsWithNullLogTaskId(),
        "no STEP flow_nodes row may have NULL log_task_id after a FAILED build — that is #536");
    assertEquals(
        exec.taskToken,
        step.logTaskId,
        "log_task_id must point at the EXECUTE_COMMAND task that streamed the logs");
    assertTrue(
        countLogRowsForToken(step.logTaskId) >= 1,
        "the stamped log_task_id must be joinable to titan.logs even on the FAILED path");
  }

  /**
   * Issue #536 — verify log_task_id is stamped at dispatch time, BEFORE any reconcile pass runs.
   * The original PR #510 fix relied on a follow-up ADVANCE to fire the reconcile loop; on the live
   * rig there are paths where that follow-up never observes the task with its token. With the #536
   * fix, the very first ADVANCE that dispatches the step must leave log_task_id stamped.
   */
  @Test
  void logTaskIdIsStampedSynchronouslyAtDispatch() throws Exception {
    new TitanOrchestrator(stores, buildId).advance();

    TaskQueueRow exec = findOnlyQueuedExecCommand();
    assertNotNull(exec, "first ADVANCE must dispatch the step task");

    // No worker has run, no reconcile sweep has fired — and yet log_task_id must already be set.
    FlowNodeRow step = stores.flowNodes().findByBuildAndNode(buildId, "hello-s0").orElseThrow();
    assertEquals(
        exec.taskToken,
        step.logTaskId,
        "log_task_id MUST be stamped synchronously at dispatch, not deferred to a reconcile sweep");
    assertEquals(
        0,
        countStepRowsWithNullLogTaskId(),
        "post-dispatch invariant: zero STEP rows with NULL log_task_id (#536)");
  }

  // ---- helpers --------------------------------------------------------

  private TaskQueueRow findOnlyQueuedExecCommand() {
    for (TaskQueueRow t : stores.taskQueue().listByBuild(buildId)) {
      if ("EXECUTE_COMMAND".equals(t.type) && "QUEUED".equals(t.status)) {
        return t;
      }
    }
    return null;
  }

  private void failTask(long taskId, int exitCode) throws Exception {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.executeUpdate(
          "UPDATE titan.task_queue SET status='FAILED', "
              + "result_json='{\"exitCode\":"
              + exitCode
              + "}', completed_at=CURRENT_TIMESTAMP WHERE id="
              + taskId);
    }
  }

  private void completeTask(long taskId, int exitCode) throws Exception {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.executeUpdate(
          "UPDATE titan.task_queue SET status='COMPLETED', "
              + "result_json='{\"exitCode\":"
              + exitCode
              + "}', completed_at=CURRENT_TIMESTAMP WHERE id="
              + taskId);
    }
  }

  private void writeLogChunk(UUID taskToken, String chunk) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.logs (task_id, chunk_index, stream, data) "
                    + "VALUES (?, 0, 'stdout', ?)")) {
      ps.setObject(1, taskToken);
      ps.setString(2, chunk);
      ps.executeUpdate();
    }
  }

  private int countStepRowsWithNullLogTaskId() throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT COUNT(*) FROM titan.flow_nodes WHERE build_id=? "
                    + "AND node_type='STEP' AND log_task_id IS NULL")) {
      ps.setLong(1, buildId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private int countLogRowsForToken(UUID token) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement("SELECT COUNT(*) FROM titan.logs WHERE task_id=?")) {
      ps.setObject(1, token);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  // ---- fixtures -------------------------------------------------------

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "flow-node-log-task-id/" + System.nanoTime());
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

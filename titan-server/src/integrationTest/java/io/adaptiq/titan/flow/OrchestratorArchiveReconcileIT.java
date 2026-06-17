package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression for issue #493 — when a step's {@code EXECUTE_COMMAND} task completes and gets
 * archived <em>before</em> the next {@code ADVANCE} reconcile pass runs, the reconciler must still
 * see it (via {@code task_queue ∪ task_archive}) and fold the node to {@code SUCCESS}. Pre-fix the
 * reconciler read only {@code task_queue}; the archived terminal task was invisible, the step node
 * stayed {@code QUEUED} forever, and {@code advanceSteps}' "dispatch was lost" self-heal
 * re-enqueued a fresh {@code EXECUTE_COMMAND} on every pass — an infinite ORCHESTRATE loop that
 * filled the archive with hundreds of completed-but-never-folded step attempts.
 *
 * <p>Reproduces by simulating the exact live-rig timing: worker completes the task, then the
 * archive sweep moves it out of {@code task_queue} into {@code task_archive}, then the next ADVANCE
 * runs. The build must reach SUCCESS without re-dispatching the same step multiple times.
 */
@Testcontainers
class OrchestratorArchiveReconcileIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private long buildId;

  /** Minimal one-stage, one-step pipeline — the titan-demo shape that wedged on the rig. */
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
   * The bug: an ADVANCE that runs <em>after</em> the archive sweep has moved a completed step task
   * into {@code task_archive} must still reconcile that completion (not re-dispatch).
   *
   * <p>Drive sequence per pass: (1) advance — orchestrator dispatches step task. (2) worker
   * completes it. (3) archive sweep moves COMPLETED row out of task_queue. (4) NEXT advance must
   * see the archived task and fold the node to SUCCESS — not re-dispatch it.
   */
  @Test
  void advanceFoldsArchivedCompletedTaskAndDoesNotReDispatch() throws Exception {
    // First ADVANCE — dispatches the hello-s0 EXECUTE_COMMAND.
    new TitanOrchestrator(stores, buildId).advance();

    long firstExecId = findOnlyQueuedExecCommand();
    assertTrue(firstExecId > 0, "first ADVANCE must dispatch the step task");

    // Worker completes it (we bypass the claim path the same way BuildArchiveAndStartedAtIT does).
    completeTask(firstExecId, 0);

    // Archive sweep moves the COMPLETED task out of task_queue into task_archive — this is what
    // QueueProcessor.tick() does at the end of every tick, and on the live rig it runs every
    // 500ms while ADVANCE is on a 5s delay. So by the time the next ADVANCE fires, the
    // completed task lives only in task_archive.
    int archived = stores.taskQueue().moveCompletedToArchive(20);
    assertEquals(1, archived, "the completed exec task must have been archived");
    assertEquals(0, countLiveExecTasks(), "task_queue must no longer hold the completed task");
    assertTrue(countArchivedExecTasks() >= 1, "the task must now live in task_archive");

    // Second ADVANCE — pre-fix this re-dispatched a fresh EXECUTE_COMMAND because the reconciler
    // couldn't see the archived completion; post-fix it folds hello-s0 to SUCCESS.
    new TitanOrchestrator(stores, buildId).advance();

    FlowNodeRow step = stores.flowNodes().findByBuildAndNode(buildId, "hello-s0").orElseThrow();
    assertEquals(
        "SUCCESS",
        step.status,
        "reconciler must fold the archived COMPLETED task into the step node (SUCCESS)");

    // And it must NOT have re-dispatched a second EXECUTE_COMMAND for the same node.
    assertEquals(
        0,
        countLiveExecTasks(),
        "the reconciler must not re-dispatch a fresh EXECUTE_COMMAND after the prior one was"
            + " archived — that is the issue-#493 infinite loop");
  }

  // ---- helpers --------------------------------------------------------

  private long findOnlyQueuedExecCommand() {
    for (TaskQueueRow t : stores.taskQueue().listByBuild(buildId)) {
      if ("EXECUTE_COMMAND".equals(t.type) && "QUEUED".equals(t.status)) {
        return t.id;
      }
    }
    return -1;
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

  private int countLiveExecTasks() throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT COUNT(*) FROM titan.task_queue WHERE build_id=? "
                    + "AND type='EXECUTE_COMMAND'")) {
      ps.setLong(1, buildId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private int countArchivedExecTasks() throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT COUNT(*) FROM titan.task_archive WHERE build_id=? "
                    + "AND type='EXECUTE_COMMAND'")) {
      ps.setLong(1, buildId);
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
      ps.setString(1, "orch-archive-recon/" + System.nanoTime());
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

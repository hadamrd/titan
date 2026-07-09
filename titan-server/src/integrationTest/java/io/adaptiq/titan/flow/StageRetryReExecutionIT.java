package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.build.BuildService;
import io.adaptiq.titan.build.BuildServiceImpl;
import io.adaptiq.titan.build.RetryStageOutcome;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression for issue #125 — in-place stage retry must actually <em>re-execute</em> the failed
 * stage's steps. {@code StageRetryApiIT} stops at "reset + ADVANCE enqueued"; this IT crosses the
 * {@code retryStage} ↔ {@code TitanOrchestrator} seam that the bug lived on.
 *
 * <p>Pre-fix shape (live-rig evidence on the issue): the previous attempt's archived FAILED {@code
 * EXECUTE_COMMAND} was still the chronologically-latest task for the reset node, so the very next
 * ADVANCE's reconcile pass folded the stale failure straight back onto the node ({@code
 * startedAt=NULL}, same {@code resultJson}) before the dispatch leg ever considered it — {@code
 * task_archive} showed zero re-dispatches and the build re-completed FAILED ~1s after the retry.
 *
 * <p>Post-fix mechanism pinned here: {@code retryStage} resets step nodes to {@code PENDING} and
 * bumps their {@code attempt} (dispatch generation); every {@code EXECUTE_COMMAND} payload carries
 * the attempt it was dispatched for; the reconciler skips terminal tasks from a superseded
 * generation, so the dispatch leg enqueues a fresh task — exactly once — and the new attempt's
 * verdict recomputes the build. Both retry-then-success and retry-then-fail-again are driven end to
 * end, with the production archive-sweep timing (terminal tasks live in {@code task_archive} by the
 * time the next ADVANCE runs — the exact shape that re-folded pre-fix). The idempotent-reconciler
 * invariant for NON-retried nodes (re-delivered archived tasks must still fold as no-ops, never
 * re-dispatch) is pinned by the extra converged passes at the end of each scenario.
 */
@Testcontainers
class StageRetryReExecutionIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  /** The issue's reproduction shape: green `unit`, then `test` failing on exit 1. */
  private static final String PIPELINE =
      "stages:\n"
          + "  - stage: unit\n"
          + "    steps:\n"
          + "      - sh: echo ok\n"
          + "  - stage: test\n"
          + "    dependsOn: [unit]\n"
          + "    steps:\n"
          + "      - sh: exit 1\n";

  private HikariDataSource ds;
  private TitanStores stores;
  private BuildService svc;
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
    svc = new BuildServiceImpl(stores);
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, PIPELINE);
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(PIPELINE);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  // ── scenarios ───────────────────────────────────────────────────────────────

  /**
   * Retry-then-success: after the retry, the orchestrator must dispatch a SECOND EXECUTE_COMMAND
   * for the failed step (stubbed green worker completes it), the step's startedAt / completedAt /
   * duration must re-stamp from the new attempt, and the build verdict must recompute to SUCCESS.
   */
  @Test
  void retryThenGreenWorker_reExecutesStep_andBuildGoesSuccess() throws Exception {
    failBuildOnTestStage();

    retryTestStage();

    // The ADVANCE the retry enqueued: pre-fix this pass re-folded the archived FAILED task onto
    // the reset node (startedAt=NULL, stale resultJson) and NEVER dispatched. Post-fix it must
    // dispatch a fresh EXECUTE_COMMAND for the current attempt.
    new TitanOrchestrator(stores, buildId).advance();

    FlowNodeRow step = node("test-s0");
    assertEquals(
        "QUEUED",
        step.status,
        "the reconciler must NOT re-fold the superseded archived FAILED task (#125)");
    assertNull(step.resultJson, "the stale attempt's resultJson must not be re-folded");
    assertNotNull(step.startedAt, "the fresh dispatch re-stamps startedAt");
    long secondTaskId = onlyQueuedExecTask("test-s0");
    assertEquals(
        2,
        execTaskCount("test-s0"),
        "task_queue ∪ task_archive must show a SECOND EXECUTE_COMMAND for the retried node");
    TaskQueueRow secondTask = stores.taskQueue().findById(secondTaskId).orElseThrow();
    assertTrue(
        secondTask.payloadJson.contains("\"attempt\":2"),
        "the fresh task must be stamped with the new dispatch generation; payload: "
            + secondTask.payloadJson);

    // No double-dispatch: converging again without completing the task must not enqueue a third.
    new TitanOrchestrator(stores, buildId).advance();
    assertEquals(2, execTaskCount("test-s0"), "re-ticking must not double-dispatch");

    // Stubbed green worker: the second attempt succeeds, then the archive sweep runs (production
    // timing) before the next ADVANCE folds it.
    completeTask(secondTaskId, "COMPLETED", 0);
    stores.taskQueue().moveCompletedToArchive(50);
    new TitanOrchestrator(stores, buildId).advance();

    step = node("test-s0");
    assertEquals("SUCCESS", step.status, "the NEW attempt's verdict must fold onto the node");
    assertNotNull(step.startedAt);
    assertNotNull(step.completedAt, "completedAt re-stamps from the new attempt");
    assertNotNull(step.durationMs, "duration re-derives from the new attempt's timestamps");
    assertTrue(
        step.resultJson != null && step.resultJson.contains("\"exitCode\":0"),
        "resultJson must be the NEW attempt's; got: " + step.resultJson);
    assertNull(step.failureCategory, "the reset cleared the stale failure and success keeps it");
    assertEquals("SUCCESS", node("test").status);

    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("SUCCESS", build.status, "build verdict must recompute per the new outcome");

    assertConvergedAndIdempotent(2);
  }

  /**
   * Retry-then-fail-again: the second attempt runs (a real second EXECUTE_COMMAND) and fails on its
   * own — fresh startedAt (the pre-fix evidence had startedAt=NULL), fresh timestamps, fresh
   * result, build FAILED again.
   */
  @Test
  void retryThenRedWorker_reExecutesStep_andBuildFailsOnTheNewAttempt() throws Exception {
    failBuildOnTestStage();

    retryTestStage();

    new TitanOrchestrator(stores, buildId).advance();
    long secondTaskId = onlyQueuedExecTask("test-s0");
    assertEquals(2, execTaskCount("test-s0"), "a second EXECUTE_COMMAND must be dispatched");

    completeTask(secondTaskId, "FAILED", 1);
    stores.taskQueue().moveCompletedToArchive(50);
    new TitanOrchestrator(stores, buildId).advance();

    FlowNodeRow step = node("test-s0");
    assertEquals("FAILED", step.status);
    assertNotNull(
        step.startedAt,
        "the re-executed attempt must carry its own startedAt (pre-fix evidence: NULL)");
    assertNotNull(step.completedAt);
    assertNotNull(step.durationMs, "duration re-derives on the completing transition (#131)");
    assertEquals("STEP_EXIT", step.failureCategory);
    assertEquals("FAILED", node("test").status);
    assertEquals("FAILED", stores.builds().findById(buildId).orElseThrow().status);

    assertConvergedAndIdempotent(2);
  }

  // ── shared drive ────────────────────────────────────────────────────────────

  /**
   * Drive the build to its first FAILED verdict with production archive timing: unit succeeds,
   * test-s0 exits 1, every terminal task is swept into {@code task_archive} before the next
   * ADVANCE.
   */
  private void failBuildOnTestStage() throws Exception {
    // Pass 1..n: unit-s0 dispatch → green completion → archived → fold; then test-s0 dispatch.
    new TitanOrchestrator(stores, buildId).advance();
    completeTask(onlyQueuedExecTask("unit-s0"), "COMPLETED", 0);
    stores.taskQueue().moveCompletedToArchive(50);
    new TitanOrchestrator(stores, buildId).advance();
    new TitanOrchestrator(stores, buildId).advance();

    long firstTestTask = onlyQueuedExecTask("test-s0");
    completeTask(firstTestTask, "FAILED", 1);
    stores.taskQueue().moveCompletedToArchive(50);
    new TitanOrchestrator(stores, buildId).advance();

    assertEquals("FAILED", node("test-s0").status, "fixture: first attempt must fold FAILED");
    assertEquals("FAILED", node("test").status);
    assertEquals("SUCCESS", node("unit").status);
    assertEquals("FAILED", stores.builds().findById(buildId).orElseThrow().status);
    assertEquals(1, execTaskCount("test-s0"), "fixture: exactly one execution so far");
  }

  private void retryTestStage() {
    RetryStageOutcome outcome = svc.retryStage(buildId, "test", "it-user");
    assertTrue(outcome instanceof RetryStageOutcome.Applied, "retry must apply");
    FlowNodeRow step = node("test-s0");
    assertEquals("PENDING", step.status, "step reset to the pristine dispatchable state");
    assertEquals(2, step.attempt, "step reset bumps the dispatch generation (#125)");
    assertNull(step.startedAt);
    assertEquals("RUNNING", stores.builds().findById(buildId).orElseThrow().status);
  }

  /**
   * Idempotent-reconciler pin: with the build terminal and every task archived, re-delivered
   * ADVANCE passes must fold the (retried AND non-retried) nodes as no-ops — no status flip, no
   * re-dispatch. Guards the union-view sad path for NON-retried nodes: unit-s0's archived COMPLETED
   * task (dispatch generation 1, matching its node) is re-observed on every pass and must keep
   * folding as a no-op — never be mistaken for superseded.
   */
  private void assertConvergedAndIdempotent(int expectedTestExecTasks) throws Exception {
    String unitBefore = node("unit-s0").status;
    String testBefore = node("test-s0").status;
    new TitanOrchestrator(stores, buildId).advance();
    new TitanOrchestrator(stores, buildId).advance();
    assertEquals("SUCCESS", unitBefore);
    assertEquals(unitBefore, node("unit-s0").status, "non-retried node must stay folded");
    assertEquals(1, node("unit-s0").attempt, "non-retried node keeps generation 1");
    assertEquals(testBefore, node("test-s0").status);
    assertEquals(1, execTaskCount("unit-s0"), "non-retried node must never re-dispatch");
    assertEquals(
        expectedTestExecTasks, execTaskCount("test-s0"), "retried node re-dispatches exactly once");
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private FlowNodeRow node(String nodeId) {
    return stores.flowNodes().findByBuildAndNode(buildId, nodeId).orElseThrow();
  }

  /** The single currently-QUEUED EXECUTE_COMMAND for {@code nodeId} — fails if 0 or >1. */
  private long onlyQueuedExecTask(String nodeId) {
    List<TaskQueueRow> hits =
        stores.taskQueue().listByBuild(buildId).stream()
            .filter(t -> "EXECUTE_COMMAND".equals(t.type))
            .filter(t -> "QUEUED".equals(t.status))
            .filter(t -> nodeId.equals(t.nodeId))
            .toList();
    assertEquals(1, hits.size(), "expected exactly one QUEUED EXECUTE_COMMAND for " + nodeId);
    return hits.get(0).id;
  }

  /** EXECUTE_COMMAND tasks for a node across task_queue ∪ task_archive — total executions. */
  private int execTaskCount(String nodeId) {
    return (int)
        stores.taskQueue().listByBuildIncludingArchive(buildId).stream()
            .filter(t -> "EXECUTE_COMMAND".equals(t.type))
            .filter(t -> nodeId.equals(t.nodeId))
            .count();
  }

  /** Stubbed worker: terminal-complete a task the same way BuildArchiveAndStartedAtIT does. */
  private void completeTask(long taskId, String status, int exitCode) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE titan.task_queue SET status = ?, result_json = ?, "
                    + "completed_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      ps.setString(1, status);
      ps.setString(2, "{\"exitCode\":" + exitCode + "}");
      ps.setLong(3, taskId);
      ps.executeUpdate();
    }
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "stage-retry-reexec/" + System.nanoTime());
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

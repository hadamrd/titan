package io.adaptiq.titan.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.AuditLogRow;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Issue #1075 (re-spec of #1049) — fail-fast on stale step-queue with no worker, driven against a
 * real PostgreSQL via Testcontainers + the full Flyway migration set.
 *
 * <p>{@link NoWorkerTimeoutSweeperTest} already covers the matrix against the in-process H2 the
 * QuarkusTest profile wires in. This IT pins the same behavior on the production database engine —
 * proving the SQL emitted by {@link
 * io.adaptiq.titan.store.TaskQueueDao#findExecuteCommandQueuedBefore}, {@link
 * io.adaptiq.titan.store.TaskQueueDao#failQueuedTask} and the audit-row insert all parse + execute
 * against postgres-16 (the rig's actual engine), and that the timestamp comparison used by the
 * sweep behaves identically across H2 and Postgres for the `available_at` cutoff clock.
 */
@Testcontainers
class NoWorkerTimeoutIT {

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

  /**
   * End-to-end the acceptance flow: schedule → no-worker → fail.
   *
   * <p>An {@code EXECUTE_COMMAND} task is queued on {@code windows-only}, no worker covers that
   * queue, and the row's {@code available_at} is older than the configured timeout. One sweep tick
   * must transition the task to {@code FAILED}, fail the parent build with the user-facing message,
   * and emit a {@code no_worker_timeout} audit row.
   */
  @Test
  void schedule_noWorker_failFlow_endsInFailedBuildWithUserFacingMessage() throws Exception {
    long buildId;
    long taskId;
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, "noworker-it/job-" + System.nanoTime());
      buildId = insertRunningBuild(c, jobId);
      taskId = insertQueuedStepTask(c, buildId, "windows-only", Instant.now().minusSeconds(120));
    }

    NoWorkerTimeoutSweeper sweeper = new NoWorkerTimeoutSweeper(60);
    int failed = sweeper.sweep(stores, new QueueHandlerSupport(() -> null));
    assertEquals(1, failed, "exactly one stale task must transition to FAILED");

    TaskQueueRow taskAfter = stores.taskQueue().findById(taskId).orElseThrow();
    assertEquals("FAILED", taskAfter.status);
    assertNotNull(taskAfter.resultJson);
    assertTrue(
        taskAfter.resultJson.contains("no_worker_timeout"),
        "result_json must carry kind=no_worker_timeout: " + taskAfter.resultJson);
    assertTrue(
        taskAfter.resultJson.contains("windows-only"),
        "result_json must name the queue: " + taskAfter.resultJson);

    BuildRow buildAfter = stores.builds().findById(buildId).orElseThrow();
    assertEquals("FAILED", buildAfter.status, "parent build must transition to FAILED");
    assertNotNull(buildAfter.failureSummary);
    assertTrue(
        buildAfter.failureSummary.contains("No worker available for queue 'windows-only'"),
        "user-facing message must surface in failure_summary: " + buildAfter.failureSummary);
    assertTrue(
        buildAfter.failureSummary.contains("60s"),
        "the message must mention the configured timeout: " + buildAfter.failureSummary);

    List<AuditLogRow> audit =
        stores.auditLog().findRecent(null, "no_worker_timeout", "task", null, 10, 0);
    assertTrue(
        audit.stream().anyMatch(r -> String.valueOf(taskId).equals(r.targetId)),
        "an audit row of action=no_worker_timeout must exist for task " + taskId);
  }

  /**
   * Inverse: a live worker covering the queue must immunize the task. The sweep is a no-op even
   * past the timeout — the task stays QUEUED for the real worker to claim. Pins the
   * registration-cancels-the-timeout acceptance criterion against Postgres.
   */
  @Test
  void schedule_workerCoversQueue_taskNotFailedEvenPastTimeout() throws Exception {
    long buildId;
    long taskId;
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, "noworker-it/job-" + System.nanoTime());
      buildId = insertRunningBuild(c, jobId);
      taskId = insertQueuedStepTask(c, buildId, "linux", Instant.now().minusSeconds(120));
    }
    stores.agents().register("worker-linux", "worker-linux", "linux", 1);

    int failed = new NoWorkerTimeoutSweeper(60).sweep(stores, new QueueHandlerSupport(() -> null));
    assertEquals(0, failed, "a covered queue must never fail-fast");

    TaskQueueRow taskAfter = stores.taskQueue().findById(taskId).orElseThrow();
    assertEquals("QUEUED", taskAfter.status);
    assertNull(taskAfter.resultJson);
    BuildRow buildAfter = stores.builds().findById(buildId).orElseThrow();
    assertEquals("RUNNING", buildAfter.status);
  }

  /**
   * Adversarial: the worker registered then dropped just before pickup (heartbeat lapse → {@code
   * markOffline}). The OFFLINE worker must NOT count as coverage; the task must fail on the next
   * sweep. This pins the "worker registers then drops" entry from the issue test matrix against
   * Postgres semantics.
   */
  @Test
  void schedule_workerRegistersThenDropsOffline_taskFailsOnNextSweep() throws Exception {
    long buildId;
    long taskId;
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, "noworker-it/job-" + System.nanoTime());
      buildId = insertRunningBuild(c, jobId);
      taskId = insertQueuedStepTask(c, buildId, "flaky", Instant.now().minusSeconds(120));
    }
    stores.agents().register("flaky-1", "flaky-1", "flaky", 1);
    stores.agents().markOffline("flaky-1");

    int failed = new NoWorkerTimeoutSweeper(60).sweep(stores, new QueueHandlerSupport(() -> null));
    assertEquals(1, failed);
    assertEquals("FAILED", stores.taskQueue().findById(taskId).orElseThrow().status);
    assertEquals("FAILED", stores.builds().findById(buildId).orElseThrow().status);
  }

  // ── helpers ────────────────────────────────────────────────────────────

  private static long insertJob(Connection c, String fullName) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) "
                + "VALUES (?, 'stages: []', '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, fullName);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static long insertRunningBuild(Connection c, long jobId) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, started_at) "
                + "VALUES (?, 1, 'RUNNING', CURRENT_TIMESTAMP)",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, jobId);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private long insertQueuedStepTask(
      Connection c, long buildId, String queueName, Instant availableAt) {
    TaskQueueRow t = new TaskQueueRow();
    t.type = "EXECUTE_COMMAND";
    t.queueName = queueName;
    t.status = "QUEUED";
    t.priority = 0;
    t.payloadJson = "{\"command\":\"echo hi\"}";
    t.attempts = 0;
    t.maxAttempts = 3;
    t.visibilityTimeoutSeconds = 300;
    t.buildId = buildId;
    t.availableAt = availableAt;
    return stores.taskQueue().insert(t);
  }
}

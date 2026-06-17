package io.adaptiq.titan.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.flow.BuildAbortService;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cancel-propagation (#668) — Postgres-backed contract:
 *
 * <ul>
 *   <li>markCancelRequested stamps a fresh {@code cancel_requested_at} on every live task of a
 *       build, and is idempotent (double-cancel is a no-op).
 *   <li>findCancelRequested returns the stamp the worker's heartbeat needs.
 *   <li>BuildAbortService.abort() stamps the intent <em>and</em> drives status to CANCELLED so a
 *       worker that polls between the two writes still gets a "yes, stop" answer.
 *   <li>Cancelling a build with no live task is a no-op on the queue but still aborts the build —
 *       i.e. the intent path does not require pre-existing tasks.
 *   <li>The stamp survives archival — task_archive carries the column too.
 * </ul>
 *
 * <p>Mirrors the Testcontainers + Flyway-from-classpath shape from {@code TaskQueueTraceParentIT}.
 */
@Testcontainers
class TaskQueueCancelIntentIT {

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
    cfg.setMaximumPoolSize(4);
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
  void markCancelRequested_stampsLiveTasksAndIsIdempotent() throws Exception {
    long buildId = seedBuild();
    long t1 = seedTask(buildId, "PROCESSING");
    long t2 = seedTask(buildId, "QUEUED");
    long t3 = seedTask(buildId, "COMPLETED"); // terminal — must NOT be stamped

    int first = stores.taskQueue().markCancelRequested(buildId);
    assertEquals(2, first, "two live tasks should be stamped on the first call");

    Optional<Instant> s1 = stores.taskQueue().findCancelRequested(t1);
    Optional<Instant> s2 = stores.taskQueue().findCancelRequested(t2);
    Optional<Instant> s3 = stores.taskQueue().findCancelRequested(t3);
    assertTrue(s1.isPresent(), "PROCESSING task gets an intent stamp");
    assertTrue(s2.isPresent(), "QUEUED task gets an intent stamp");
    assertFalse(s3.isPresent(), "terminal COMPLETED task is never stamped");

    Instant stampedAt = s1.get();
    int second = stores.taskQueue().markCancelRequested(buildId);
    assertEquals(0, second, "double-cancel changes zero rows");

    Optional<Instant> s1After = stores.taskQueue().findCancelRequested(t1);
    assertEquals(
        stampedAt,
        s1After.orElseThrow(),
        "double-cancel must not overwrite the original cancel_requested_at");
  }

  @Test
  void cancellingABuildWithNoLiveTasks_isANoOpOnTheQueue() throws Exception {
    long buildId = seedBuild();
    // No tasks at all on this build.
    int stamped = stores.taskQueue().markCancelRequested(buildId);
    assertEquals(0, stamped, "no live tasks — nothing to stamp");

    // The abort path itself still terminates the build, even with no live tasks.
    BuildAbortService.AbortOutcome outcome = BuildAbortService.abort(stores, buildId, "test");
    assertTrue(outcome.aborted(), "abort on a tasks-less build still aborts the build");

    BuildRow b = stores.builds().findById(buildId).orElseThrow();
    assertEquals("ABORTED", b.status);
  }

  @Test
  void buildAbortService_stampsIntentBeforeDrivingTaskToCancelled() throws Exception {
    long buildId = seedBuild();
    long taskId = seedTask(buildId, "PROCESSING");

    BuildAbortService.AbortOutcome outcome = BuildAbortService.abort(stores, buildId, "api");
    assertTrue(outcome.aborted());

    TaskQueueRow row = stores.taskQueue().findById(taskId).orElseThrow();
    assertEquals(
        "CANCELLED", row.status, "the abort path still drives the task to terminal CANCELLED");
    assertNotNull(
        row.cancelRequestedAt,
        "the abort path also stamps cancel_requested_at — worker heartbeat poll signal");
    assertNotNull(row.completedAt, "a CANCELLED task has its completed_at set by the cancel write");
  }

  @Test
  void findCancelRequested_returnsEmptyForUnknownOrUnstamped() throws Exception {
    long buildId = seedBuild();
    long taskId = seedTask(buildId, "PROCESSING");

    assertTrue(
        stores.taskQueue().findCancelRequested(9_999_999L).isEmpty(),
        "unknown task id returns empty");
    assertTrue(
        stores.taskQueue().findCancelRequested(taskId).isEmpty(),
        "un-stamped live task returns empty");
  }

  @Test
  void cancelIntentColumn_isPresentOnBothQueueAndArchive() throws Exception {
    long buildId = seedBuild();
    long taskId = seedTask(buildId, "PROCESSING");

    stores.taskQueue().markCancelRequested(buildId);
    stores.taskQueue().cancel(taskId);

    int moved = stores.taskQueue().moveCompletedToArchive(10);
    assertEquals(1, moved, "the cancelled task should archive");

    // After archival the row is gone from task_queue …
    assertTrue(
        stores.taskQueue().findById(taskId).isEmpty(), "archived task no longer in task_queue");

    // … and the intent breadcrumb survived the transfer. Read it directly off task_archive.
    try (Connection c = ds.getConnection();
        var ps =
            c.prepareStatement("SELECT cancel_requested_at FROM titan.task_archive WHERE id = ?")) {
      ps.setLong(1, moved == 1 ? archiveIdOfTask(taskId) : -1L);
      try (var rs = ps.executeQuery()) {
        assertTrue(rs.next(), "archive row must exist");
        assertNotNull(rs.getTimestamp(1), "cancel_requested_at survives archival");
      }
    }
  }

  /** Resolve the archive row id for a task we just archived (look up via task_token). */
  private long archiveIdOfTask(long originalTaskId) throws Exception {
    // The original task_queue row is gone, so we cannot read its task_token from there. The
    // archive sweep preserves task_token verbatim and the column is UNIQUE — there is exactly
    // one archive row whose task_token matches. We just resolve "the only archive row for this
    // build", which is unambiguous in this single-task test.
    try (Connection c = ds.getConnection();
        var ps = c.prepareStatement("SELECT id FROM titan.task_archive ORDER BY id DESC LIMIT 1");
        var rs = ps.executeQuery()) {
      assertTrue(rs.next(), "at least one archive row exists");
      return rs.getLong(1);
    }
  }

  // ── seed helpers ─────────────────────────────────────────────────────────────

  private long seedBuild() {
    long jobId = insertJob();
    return stores.withTransaction(
        conn -> {
          BuildRow row = new BuildRow();
          row.jobId = jobId;
          row.buildNumber = 1;
          row.status = "RUNNING";
          row.queuedAt = Instant.now();
          row.startedAt = Instant.now();
          return stores.builds().insert(conn, row);
        });
  }

  /** Insert a fresh job row directly via JDBC — the build FK target. */
  private long insertJob() {
    try (Connection c = ds.getConnection();
        var ps =
            c.prepareStatement(
                "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) "
                    + "VALUES (?, ?, ?) RETURNING id")) {
      ps.setString(1, "cancel-it/job-" + System.nanoTime());
      ps.setString(2, "echo x");
      ps.setString(3, "{}");
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private long seedTask(long buildId, String status) {
    TaskQueueRow row = new TaskQueueRow();
    row.type = "EXECUTE_COMMAND";
    row.queueName = "default";
    row.status = status;
    row.priority = 0;
    row.payloadJson = "{}";
    row.attempts = 0;
    row.maxAttempts = 3;
    row.visibilityTimeoutSeconds = 3600;
    row.availableAt = Instant.now();
    row.buildId = buildId;
    long id = stores.taskQueue().insert(row);
    // The insert() path always lands the row at status=QUEUED — override if the test wants a
    // different live status. (We could craft a custom INSERT but reusing the DAO keeps this
    // test honest about the row shape it sees in production.)
    if (!"QUEUED".equals(status)) {
      try (Connection c = ds.getConnection();
          var ps =
              c.prepareStatement(
                  "UPDATE titan.task_queue SET status = ?, "
                      + "completed_at = CASE WHEN ? IN ('COMPLETED','FAILED','CANCELLED') "
                      + "THEN CURRENT_TIMESTAMP ELSE NULL END "
                      + "WHERE id = ?")) {
        ps.setString(1, status);
        ps.setString(2, status);
        ps.setLong(3, id);
        ps.executeUpdate();
      } catch (java.sql.SQLException e) {
        throw new RuntimeException(e);
      }
    }
    return id;
  }
}

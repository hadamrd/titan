package io.adaptiq.titan.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.worker.step.LogSink;
import io.adaptiq.titan.worker.step.exec.LocalProcessExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end worker cancellation: a controller-side {@code task_queue} cancel on a PROCESSING task
 * is observed by the worker's {@code WorkerDb.isCancelled} poll and kills the real subprocess.
 */
@Testcontainers
class WorkerCancellationIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final LogSink SINK = (stream, text) -> {};

  private WorkerConfig cfg;
  private WorkerDb db;

  @BeforeEach
  void setUp() throws Exception {
    // Apply the real engine schema — every V*.sql migration from titan-db-core,
    // in version order — so this test can never drift from the production
    // schema. Container is shared across methods so we reset the schema each time.
    Path migrations = Path.of("../titan-db-core/src/main/resources/io/adaptiq/titan/db/migration");
    assertTrue(
        Files.isDirectory(migrations),
        "engine migrations not found at " + migrations.toAbsolutePath());
    try (Connection c =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      TestMigrations.resetAndApply(c, migrations);
    }

    Path workspace = Files.createTempDirectory("titan-cancel-it-ws");
    Path libraries = Files.createTempDirectory("titan-cancel-it-lib");
    cfg =
        new WorkerConfig(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            "cancel-it-worker",
            "Cancel IT Worker",
            "linux",
            1,
            "/titan",
            "NORMAL",
            "default",
            "synthesis",
            workspace,
            "",
            libraries,
            1000,
            10000,
            "",
            java.util.Map.of());
    db = new WorkerDb(cfg);
  }

  @AfterEach
  void tearDown() {
    if (db != null) {
      db.close();
    }
  }

  /**
   * Seed a job + build + a PROCESSING EXECUTE_COMMAND task; return the task id.
   *
   * <p>The task is seeded directly into PROCESSING status so the test bypasses the
   * claim/markProcessing cycle — we want to exercise the cancel path, not the claim path.
   */
  private long seedProcessingTask() throws SQLException {
    try (Connection c =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = c.createStatement()) {
      st.executeUpdate(
          "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) "
              + "VALUES ('cancel-it/job', 'echo x', '{}')");
      st.executeUpdate(
          "INSERT INTO titan.builds (job_id, build_number, status) "
              + "VALUES ((SELECT MAX(id) FROM titan.jobs), 1, 'QUEUED')");
      st.executeUpdate(
          "INSERT INTO titan.task_queue "
              + "(type, queue_name, status, priority, payload_json, attempts, max_attempts, "
              + "visibility_timeout_seconds, available_at, build_id) "
              + "VALUES ('EXECUTE_COMMAND', 'default', 'PROCESSING', 0, '{}', 1, 3, 3600, "
              + "CURRENT_TIMESTAMP, (SELECT MAX(id) FROM titan.builds))");
      try (var rs = st.executeQuery("SELECT MAX(id) FROM titan.task_queue")) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** Cancel a task row directly via JDBC — the "controller side" in this test. */
  private boolean cancelTask(long taskId) throws SQLException {
    try (Connection c =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.PreparedStatement ps =
            c.prepareStatement(
                "UPDATE titan.task_queue SET status = 'CANCELLED', "
                    + "completed_at = CURRENT_TIMESTAMP "
                    + "WHERE id = ? AND status IN ('QUEUED','CLAIMED','PROCESSING')")) {
      ps.setLong(1, taskId);
      return ps.executeUpdate() == 1;
    }
  }

  @Test
  void aControllerCancelKillsTheWorkersRunningProcessEndToEnd() throws Exception {
    long taskId = seedProcessingTask();
    Path workDir = Files.createTempDirectory("titan-cancel-it");

    // The worker's real cancel signal — a live DB query, exactly as TaskExecutor binds it.
    LocalProcessExecutor executor =
        new LocalProcessExecutor(
            () -> {
              try {
                return db.isCancelled(taskId);
              } catch (SQLException e) {
                return false;
              }
            });

    AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
    Thread worker =
        new Thread(
            () -> {
              try {
                exitCode.set(
                    executor.run(
                        List.of("sh", "-c", "sleep 60"), workDir, Map.of(), SINK, "sleep 60"));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });

    long start = System.nanoTime();
    worker.start();
    Thread.sleep(500L); // let the process start
    assertTrue(cancelTask(taskId), "the controller cancel must affect the row");

    worker.join(20_000L);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertFalse(worker.isAlive(), "the executor thread must have returned");
    assertTrue(
        elapsedMs < 15_000,
        "the worker must kill `sleep 60` shortly after the cancel, took " + elapsedMs + "ms");
    assertNotEquals(0, exitCode.get(), "a killed process must not report success");
  }
}

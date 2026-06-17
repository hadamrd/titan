package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TimerRow;
import io.adaptiq.titan.timer.TimerSweepWorker;
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

/** Orchestrator ITs for the timeout: scope (timer subsystem, Phase 3). */
@Testcontainers
class TitanOrchestratorTimeoutIT {

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

  private void bake(String fixture) throws Exception {
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, Fixtures.load(fixture));
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(Fixtures.load(fixture));
  }

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "timeout-it/job-" + System.nanoTime());
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

  private String timeoutStepId() {
    return stores.flowNodes().listByBuild(buildId).stream()
        .filter(n -> "STEP".equals(n.nodeType))
        .findFirst()
        .orElseThrow()
        .nodeId;
  }

  private String taskStatusFor(String nodeId) {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT status FROM titan.task_queue WHERE type = 'EXECUTE_COMMAND' "
                    + "AND build_id = "
                    + buildId
                    + " AND node_id = '"
                    + nodeId
                    + "'")) {
      return rs.next() ? rs.getString(1) : null;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Mark the node's EXECUTE_COMMAND task COMPLETED with the given exit code. */
  private void completeTask(String nodeId, int exitCode) {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.executeUpdate(
          "UPDATE titan.task_queue SET status = 'COMPLETED', "
              + "result_json = '{\"exitCode\":"
              + exitCode
              + "}', "
              + "completed_at = CURRENT_TIMESTAMP "
              + "WHERE type = 'EXECUTE_COMMAND' AND build_id = "
              + buildId
              + " AND node_id = '"
              + nodeId
              + "'");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void dispatchingATimeoutStepArmsATimeoutTimer() throws Exception {
    bake("timeout-step.yml");

    new TitanOrchestrator(stores, buildId).advance();

    List<TimerRow> timers = stores.timers().listByBuild(buildId);
    assertEquals(1, timers.size(), "one TIMEOUT timer is armed when the step is dispatched");
    assertEquals("TIMEOUT", timers.get(0).kind);
    assertEquals("ARMED", timers.get(0).status);
    assertTrue(timers.get(0).fireAt.isAfter(Instant.now()), "timeout: 1h is in the future");
  }

  @Test
  void aRealSweptTimeoutFailsTheNodeAndCancelsItsTask() throws Exception {
    bake("timeout-2s.yml");
    new TitanOrchestrator(stores, buildId).advance(); // dispatch + arm a TIMEOUT timer
    String stepId = timeoutStepId();
    assertEquals("QUEUED", taskStatusFor(stepId), "the step's task is in flight");

    // Real elapsed time — wait past the 2s deadline, then run the real sweeper.
    Thread.sleep(2_500L);
    new TimerSweepWorker().sweep(stores);
    assertEquals(
        "FIRED",
        stores.timers().listByBuild(buildId).get(0).status,
        "the real TimerSweepWorker fired the overdue TIMEOUT timer");

    new TitanOrchestrator(stores, buildId).advance(); // enforce pass

    assertEquals(
        "FAILED",
        stores.flowNodes().findByBuildAndNode(buildId, stepId).orElseThrow().status,
        "an overrun step is failed");
    assertEquals("CANCELLED", taskStatusFor(stepId), "its EXECUTE_COMMAND task is cancelled");
  }

  @Test
  void aTimedOutStepIsNotRetriedEvenUnderARetryPolicy() throws Exception {
    bake("timeout-retry.yml"); // the step carries timeout: 2s AND retry: maxAttempts 3
    new TitanOrchestrator(stores, buildId).advance(); // dispatch + arm
    String stepId = timeoutStepId();

    Thread.sleep(2_500L);
    new TimerSweepWorker().sweep(stores);
    new TitanOrchestrator(stores, buildId).advance(); // enforce
    new TitanOrchestrator(stores, buildId)
        .advance(); // a second pass — a retry would re-dispatch here

    assertEquals(
        "FAILED",
        stores.flowNodes().findByBuildAndNode(buildId, stepId).orElseThrow().status,
        "a timed-out step is terminally FAILED");
    assertEquals(
        "TIMEOUT",
        stores.flowNodes().findByBuildAndNode(buildId, stepId).orElseThrow().failureCategory,
        "failed with category TIMEOUT");
    assertEquals(
        1,
        executeTaskCount(),
        "the step is NOT retried — exactly one EXECUTE_COMMAND task was ever dispatched");
  }

  @Test
  void aTimeoutTimerIsCancelledWhenTheStepFinishesInTime() throws Exception {
    bake("timeout-step.yml");
    new TitanOrchestrator(stores, buildId).advance(); // dispatch + arm
    completeTask(timeoutStepId(), 0); // the worker finished the step, in time

    new TitanOrchestrator(stores, buildId).advance(); // reconcile pass

    assertEquals(
        "CANCELLED",
        stores.timers().listByBuild(buildId).get(0).status,
        "a step that finishes in time has its TIMEOUT timer cancelled");
  }

  private int executeTaskCount() {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COUNT(*) FROM titan.task_queue "
                    + "WHERE type = 'EXECUTE_COMMAND' AND build_id = "
                    + buildId)) {
      rs.next();
      return rs.getInt(1);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}

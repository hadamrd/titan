package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.FlowNodeRow;
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

/** Orchestrator ITs for durable {@code sleep} / {@code waitUntil} (timer subsystem, Phase 2). */
@Testcontainers
class TitanOrchestratorSleepIT {

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
      ps.setString(1, "sleep-it/job-" + System.nanoTime());
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

  private int executeTasks() {
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

  @Test
  void sleepStepParksTheNodeAsSleepingAndArmsATimerWithNoWorkerTask() throws Exception {
    bake("sleep-step.yml");

    new TitanOrchestrator(stores, buildId).advance();

    FlowNodeRow waitStep =
        stores.flowNodes().listByBuildAndStatus(buildId, "SLEEPING").stream()
            .findFirst()
            .orElseThrow();
    assertEquals("SLEEPING", waitStep.status);
    assertNotNull(waitStep.wakeAt, "a parked node records its wake instant");
    assertTrue(waitStep.wakeAt.isAfter(Instant.now()), "sleep: 1h is in the future");
    assertEquals(0, executeTasks(), "a durable sleep dispatches no EXECUTE_COMMAND task");

    List<TimerRow> timers = stores.timers().listByBuild(buildId);
    assertEquals(1, timers.size());
    assertEquals("SLEEP", timers.get(0).kind);
    assertEquals("ARMED", timers.get(0).status);
  }

  @Test
  void aSleepingNodeWakesViaTheRealSweeperOnceItsDurationElapses() throws Exception {
    bake("sleep-2s.yml");
    new TitanOrchestrator(stores, buildId).advance(); // park the node, arm a SLEEP timer

    assertEquals(
        1,
        stores.flowNodes().listByBuildAndStatus(buildId, "SLEEPING").size(),
        "the sleep:2s node is parked");

    // Real elapsed time — wait past the 2s duration, then run the real sweeper.
    Thread.sleep(2_500L);
    new TimerSweepWorker().sweep(stores);

    assertEquals(
        "FIRED",
        stores.timers().listByBuild(buildId).get(0).status,
        "the real TimerSweepWorker fired the due SLEEP timer");

    new TitanOrchestrator(stores, buildId).advance(); // wake pass

    assertTrue(
        stores.flowNodes().listByBuildAndStatus(buildId, "SLEEPING").isEmpty(),
        "no node is left SLEEPING after the duration elapsed");
    assertEquals(1, executeTasks(), "the After stage's sh step dispatches once the sleep is done");
  }

  @Test
  void waitUntilParksTheNodeUntilItsAbsoluteInstant() throws Exception {
    bake("wait-until.yml");

    new TitanOrchestrator(stores, buildId).advance();

    FlowNodeRow holdStep =
        stores.flowNodes().listByBuildAndStatus(buildId, "SLEEPING").stream()
            .findFirst()
            .orElseThrow();
    assertEquals("SLEEPING", holdStep.status);
    assertNotNull(holdStep.wakeAt, "a waitUntil node records its wake instant");
    assertTrue(holdStep.wakeAt.isAfter(Instant.now()), "the 2026-12-31 instant is in the future");
    assertEquals(0, executeTasks(), "a parked waitUntil dispatches no worker task");
  }

  @Test
  void aMalformedSleepDurationFailsTheNode() throws Exception {
    String yaml =
        "titan:\n  stages:\n    - stage: Wait\n      steps:\n        - sleep: notaduration\n";
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, yaml);
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(yaml);

    new TitanOrchestrator(stores, buildId).advance();

    FlowNodeRow waitStep =
        stores.flowNodes().listByBuild(buildId).stream()
            .filter(n -> "STEP".equals(n.nodeType))
            .findFirst()
            .orElseThrow();
    assertEquals("FAILED", waitStep.status, "a malformed sleep duration fails the node");
    assertNotNull(waitStep.failureReason);
  }
}

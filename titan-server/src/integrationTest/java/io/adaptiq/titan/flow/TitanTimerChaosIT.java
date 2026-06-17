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
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Chaos ITs for the timer subsystem — controller restart, concurrent sweepers, abort-mid-wait. */
@Testcontainers
class TitanTimerChaosIT {

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
      ps.setString(1, "chaos-it/job-" + System.nanoTime());
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

  /** Count ORCHESTRATE/ADVANCE tasks enqueued (the sweeper enqueues one per fired timer). */
  private int advanceTaskCount() {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COUNT(*) FROM titan.task_queue "
                    + "WHERE type = 'ORCHESTRATE' AND payload_json LIKE '%\"action\":\"ADVANCE\"%'")) {
      rs.next();
      return rs.getInt(1);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void aDurableSleepSurvivesAControllerOutageAndResumes() throws Exception {
    bake("sleep-2s.yml");
    new TitanOrchestrator(stores, buildId).advance(); // park + arm a SLEEP timer

    // The controller is "down" — nobody sweeps for well past the 2s duration.
    Thread.sleep(3_000L);

    // A fresh TimerSweepWorker (the restarted controller) picks it up — timers are pure DB state.
    new TimerSweepWorker().sweep(stores);
    assertEquals(
        "FIRED",
        stores.timers().listByBuild(buildId).get(0).status,
        "a fresh sweeper fires the timer that came due during the outage");

    new TitanOrchestrator(stores, buildId).advance();
    assertTrue(
        stores.flowNodes().listByBuildAndStatus(buildId, "SLEEPING").isEmpty(),
        "the node wakes after the outage — the wait resumed, it did not restart");
  }

  @Test
  void twoConcurrentSweepersFireEachTimerExactlyOnce() throws Exception {
    bake("sleep-2s.yml"); // a real build row to satisfy the timers FK
    // Arm 12 already-due timers directly — fire_at in the past is precondition setup;
    // the real sweeper still does the claim/fire.
    Instant past = Instant.now().minus(10, ChronoUnit.SECONDS);
    for (int i = 0; i < 12; i++) {
      stores.timers().armIfAbsent(buildId, "chaos-node-" + i, "SLEEP", past, null);
    }

    Runnable sweep = () -> new TimerSweepWorker().sweep(stores);
    Thread a = new Thread(sweep);
    Thread b = new Thread(sweep);
    a.start();
    b.start();
    a.join(10_000L);
    b.join(10_000L);

    List<TimerRow> timers = stores.timers().listByBuild(buildId);
    long fired = timers.stream().filter(t -> "FIRED".equals(t.status)).count();
    assertEquals(timers.size(), fired, "every timer ends FIRED — none left ARMED/CLAIMED");
    assertEquals(
        timers.size(),
        advanceTaskCount(),
        "exactly one ADVANCE per timer — no double-fire under the concurrent claim");
  }

  @Test
  void abortingABuildMidWaitCancelsItsTimerSoASweepNeverFiresIt() throws Exception {
    bake("sleep-step.yml"); // sleep: 1h — the node parks and stays parked
    new TitanOrchestrator(stores, buildId).advance(); // park + arm
    assertEquals("ARMED", stores.timers().listByBuild(buildId).get(0).status);

    BuildAbortService.abort(stores, buildId, "chaos-tester");

    assertEquals(
        "CANCELLED",
        stores.timers().listByBuild(buildId).get(0).status,
        "abort cancels the build's armed timer");

    // A subsequent real sweep must not fire a CANCELLED timer.
    new TimerSweepWorker().sweep(stores);
    assertEquals(
        "CANCELLED",
        stores.timers().listByBuild(buildId).get(0).status,
        "the cancelled timer stays CANCELLED — the sweeper never fires it");
    assertEquals(0, advanceTaskCount(), "no ADVANCE was enqueued for the aborted build");
  }
}

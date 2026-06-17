package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.api.dto.DurationTrendPointDto;
import io.adaptiq.titan.store.TitanStores;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
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

/**
 * Postgres-backed IT for {@link DurationTrendHandler} (closes #1096).
 *
 * <p>Exercises the duration-trend window end-to-end against real Postgres: the test-matrix
 * "sparkline-data API on a job with mixed pass/fail", plus the adversarial windows (RUNNING
 * excluded, cap-to-n, fresh job empty).
 */
@Testcontainers
class DurationTrendApiIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private DurationTrendHandler handler;

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
    handler = new DurationTrendHandler(stores);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  // ── test matrix: sparkline-data on a job with mixed pass/fail ────────────────

  @Test
  void durationTrend_mixedPassFail_returnsPointsOldestToNewestInSeconds() throws Exception {
    long jobId;
    Instant now = Instant.now();
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/mixed", "Mixed");
      // 4 builds, alternating SUCCESS/FAILED, durations 1000ms,2000ms,3000ms,4000ms.
      // queued_at increases with build_number so oldest→newest == build 1..4.
      insertFinishedBuild(c, jobId, 1, "SUCCESS", 1000L, now.minus(4, ChronoUnit.HOURS));
      insertFinishedBuild(c, jobId, 2, "FAILED", 2000L, now.minus(3, ChronoUnit.HOURS));
      insertFinishedBuild(c, jobId, 3, "SUCCESS", 3000L, now.minus(2, ChronoUnit.HOURS));
      insertFinishedBuild(c, jobId, 4, "FAILED", 4000L, now.minus(1, ChronoUnit.HOURS));
    }

    List<DurationTrendPointDto> trend = handler.durationTrend(Long.toString(jobId), 30);

    assertEquals(4, trend.size(), "all four finished builds in the window");
    // Oldest→newest ordering: durations must be ascending as inserted.
    assertEquals(1.0, trend.get(0).durationS(), 1e-9, "ms→s conversion (1000ms → 1.0s)");
    assertEquals(2.0, trend.get(1).durationS(), 1e-9);
    assertEquals(3.0, trend.get(2).durationS(), 1e-9);
    assertEquals(4.0, trend.get(3).durationS(), 1e-9);
    // Mixed status carried through verbatim (pass AND fail both present).
    assertEquals("SUCCESS", trend.get(0).status());
    assertEquals("FAILED", trend.get(1).status());
    assertEquals("SUCCESS", trend.get(2).status());
    assertEquals("FAILED", trend.get(3).status());
    // Each point carries its finished_at timestamp, strictly increasing oldest→newest.
    for (int i = 1; i < trend.size(); i++) {
      assertTrue(
          trend.get(i - 1).ts().isBefore(trend.get(i).ts()),
          "points ordered oldest→newest by finished_at");
    }
  }

  // ── adversarial windows ──────────────────────────────────────────────────────

  @Test
  void durationTrend_runningBuilds_excluded() throws Exception {
    long jobId;
    Instant now = Instant.now();
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/running", "Running");
      insertFinishedBuild(c, jobId, 1, "SUCCESS", 1000L, now.minus(2, ChronoUnit.HOURS));
      // An in-flight build: RUNNING, no finished_at, no duration_ms. Must not appear.
      insertRunningBuild(c, jobId, 2, now.minus(1, ChronoUnit.HOURS));
    }
    List<DurationTrendPointDto> trend = handler.durationTrend(Long.toString(jobId), 30);
    assertEquals(1, trend.size(), "RUNNING build with no duration excluded from the trend");
    assertEquals("SUCCESS", trend.get(0).status());
  }

  @Test
  void durationTrend_capsToMostRecentN() throws Exception {
    long jobId;
    Instant now = Instant.now();
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/cap", "Cap");
      // 5 builds with durations 1000..5000ms; request n=3 → only builds 3,4,5.
      for (int i = 1; i <= 5; i++) {
        insertFinishedBuild(
            c, jobId, i, "SUCCESS", 1000L * i, now.minus(10L - i, ChronoUnit.HOURS));
      }
    }
    List<DurationTrendPointDto> trend = handler.durationTrend(Long.toString(jobId), 3);
    assertEquals(3, trend.size());
    // Most recent 3 = builds 3,4,5 → 3.0s,4.0s,5.0s (oldest→newest within the window).
    assertEquals(3.0, trend.get(0).durationS(), 1e-9, "older builds (1,2) excluded by the cap");
    assertEquals(4.0, trend.get(1).durationS(), 1e-9);
    assertEquals(5.0, trend.get(2).durationS(), 1e-9);
  }

  @Test
  void durationTrend_freshJob_returnsEmptyList() throws Exception {
    long jobId;
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/fresh", "Fresh");
    }
    List<DurationTrendPointDto> trend = handler.durationTrend(Long.toString(jobId), 30);
    assertTrue(trend.isEmpty(), "no finished builds → empty list, not null, not 404");
  }

  @Test
  void durationTrend_invalidN_returns400() throws Exception {
    long jobId;
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/badn", "BadN");
    }
    final String jid = Long.toString(jobId);
    assertThrows(ApiBadRequestException.class, () -> handler.durationTrend(jid, 0));
    assertThrows(ApiBadRequestException.class, () -> handler.durationTrend(jid, -1));
    assertThrows(ApiBadRequestException.class, () -> handler.durationTrend(jid, 1000));
  }

  @Test
  void durationTrend_invalidJobId_returns400() {
    assertThrows(ApiBadRequestException.class, () -> handler.durationTrend("not-a-number", 30));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static long insertJob(Connection c, String fullName, String displayName)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, display_name, pipeline_script, config_json) "
                + "VALUES (?, ?, '', '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, fullName);
      ps.setString(2, displayName);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static void insertFinishedBuild(
      Connection c, long jobId, int buildNumber, String status, long durationMs, Instant queuedAt)
      throws Exception {
    Instant started = queuedAt.plusSeconds(1);
    Instant finished = started.plusMillis(durationMs);
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, queued_at, "
                + "started_at, finished_at, duration_ms) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
      ps.setLong(1, jobId);
      ps.setInt(2, buildNumber);
      ps.setString(3, status);
      ps.setTimestamp(4, Timestamp.from(queuedAt));
      ps.setTimestamp(5, Timestamp.from(started));
      ps.setTimestamp(6, Timestamp.from(finished));
      ps.setLong(7, durationMs);
      ps.executeUpdate();
    }
  }

  private static void insertRunningBuild(
      Connection c, long jobId, int buildNumber, Instant queuedAt) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, queued_at, started_at) "
                + "VALUES (?, ?, 'RUNNING', ?, ?)")) {
      ps.setLong(1, jobId);
      ps.setInt(2, buildNumber);
      ps.setTimestamp(3, Timestamp.from(queuedAt));
      ps.setTimestamp(4, Timestamp.from(queuedAt.plusSeconds(1)));
      ps.executeUpdate();
    }
  }
}

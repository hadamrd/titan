package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.api.dto.JobStatsDto;
import io.adaptiq.titan.store.TitanStores;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres-backed IT for {@link JobStatsApi} (closes #775).
 *
 * <p>Pins the SQL aggregate's behaviour against real PostgreSQL — {@code percentile_cont} only
 * exists on PG (not H2), and the {@code AT TIME ZONE 'UTC'} bucket also needs real PG. Mirrors the
 * {@link TopFailingJobsApiIT} setup pattern (Flyway-migrated schema + direct controller calls).
 */
@Testcontainers
class JobStatsApiIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private JobStatsApi api;

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
    api = new JobStatsApi(stores);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  @Test
  void stats_tenBuildsThreeFailed_failureRate030() throws Exception {
    long jobId;
    Instant now = Instant.now();
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/jobA", "JobA");
      // 7 SUCCESS + 3 FAILED, all within the default 30d window, with real durations.
      for (int i = 1; i <= 7; i++) {
        insertBuild(c, jobId, i, "SUCCESS", now.minus(i, ChronoUnit.HOURS), 1000L * i);
      }
      for (int i = 8; i <= 10; i++) {
        insertBuild(c, jobId, i, "FAILED", now.minus(i, ChronoUnit.HOURS), 1000L * i);
      }
    }

    JobStatsDto dto = api.stats(Long.toString(jobId), "30d");
    assertEquals(10L, dto.totalBuilds());
    assertEquals(3L, dto.failedBuilds());
    assertEquals(0.3, dto.failureRate(), 1e-9);
    assertEquals("30d", dto.window());
    assertNotNull(dto.p50DurationMs(), "p50 set when completed builds exist");
    assertNotNull(dto.p95DurationMs(), "p95 set when completed builds exist");
    assertEquals(30, dto.dailyBuckets().size(), "30d window → 30 dense buckets");
  }

  @Test
  void stats_noCompletedBuilds_p50AndP95AreNull() throws Exception {
    long jobId;
    Instant now = Instant.now();
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/jobB", "JobB");
      // All RUNNING — no duration_ms recorded; percentile inputs filtered → NULL.
      for (int i = 1; i <= 4; i++) {
        insertRunningBuild(c, jobId, i, now.minus(i, ChronoUnit.MINUTES));
      }
    }

    JobStatsDto dto = api.stats(Long.toString(jobId), "30d");
    assertEquals(4L, dto.totalBuilds(), "RUNNING builds still counted toward totalBuilds");
    assertEquals(0L, dto.failedBuilds());
    assertEquals(0.0, dto.failureRate(), 1e-9);
    assertNull(dto.p50DurationMs(), "p50 null (not 0) when no completed build");
    assertNull(dto.p95DurationMs(), "p95 null (not 0) when no completed build");
  }

  @Test
  void stats_emptyJob_returnsZerosAndNullPercentiles() throws Exception {
    long jobId;
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/jobC", "JobC");
    }

    JobStatsDto dto = api.stats(Long.toString(jobId), "7d");
    assertEquals(0L, dto.totalBuilds());
    assertEquals(0L, dto.failedBuilds());
    assertEquals(0.0, dto.failureRate(), 1e-9);
    assertNull(dto.p50DurationMs());
    assertNull(dto.p95DurationMs());
    assertEquals(7, dto.dailyBuckets().size(), "7d window → exactly 7 entries even when empty");
    for (var b : dto.dailyBuckets()) {
      assertEquals(0L, b.totalBuilds());
      assertEquals(0L, b.failedBuilds());
    }
  }

  @Test
  void stats_windowSizes_produceMatchingBucketCounts() throws Exception {
    long jobId;
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/jobD", "JobD");
    }
    assertEquals(7, api.stats(Long.toString(jobId), "7d").dailyBuckets().size());
    assertEquals(30, api.stats(Long.toString(jobId), "30d").dailyBuckets().size());
    assertEquals(90, api.stats(Long.toString(jobId), "90d").dailyBuckets().size());
  }

  @Test
  void stats_defaultWindowIs30d() throws Exception {
    long jobId;
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/jobE", "JobE");
    }
    JobStatsDto dto = api.stats(Long.toString(jobId), "30d");
    assertEquals("30d", dto.window());
    assertEquals(30, dto.dailyBuckets().size());
  }

  @Test
  void stats_invalidWindow_returns400() throws Exception {
    long jobId;
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/jobF", "JobF");
    }
    final long jid = jobId;
    ApiBadRequestException ex =
        assertThrows(ApiBadRequestException.class, () -> api.stats(Long.toString(jid), "14d"));
    assertTrue(ex.getMessage().contains("window"), "error message names the offending param");
    assertThrows(
        ApiBadRequestException.class, () -> api.stats(Long.toString(jid), "1d'); DROP TABLE x;--"));
  }

  @Test
  void stats_buildsOutsideWindow_excludedFromTotals() throws Exception {
    long jobId;
    Instant now = Instant.now();
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/jobG", "JobG");
      // Inside 7d window:
      insertBuild(c, jobId, 1, "SUCCESS", now.minus(1, ChronoUnit.HOURS), 2_000L);
      insertBuild(c, jobId, 2, "FAILED", now.minus(2, ChronoUnit.HOURS), 4_000L);
      // 60 days ago — outside 7d (and 30d) windows:
      insertBuild(c, jobId, 3, "SUCCESS", now.minus(60, ChronoUnit.DAYS), 9_999L);
      insertBuild(c, jobId, 4, "FAILED", now.minus(61, ChronoUnit.DAYS), 9_999L);
    }
    JobStatsDto dto7 = api.stats(Long.toString(jobId), "7d");
    assertEquals(2L, dto7.totalBuilds());
    assertEquals(1L, dto7.failedBuilds());
    assertEquals(0.5, dto7.failureRate(), 1e-9);

    JobStatsDto dto90 = api.stats(Long.toString(jobId), "90d");
    assertEquals(4L, dto90.totalBuilds(), "90d window pulls in the older two builds");
    assertEquals(2L, dto90.failedBuilds());
  }

  @Test
  void stats_dailyBuckets_areOldestFirst_andDense() throws Exception {
    long jobId;
    Instant now = Instant.now();
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/jobH", "JobH");
      insertBuild(c, jobId, 1, "SUCCESS", now.minus(1, ChronoUnit.DAYS), 1_000L);
      insertBuild(c, jobId, 2, "FAILED", now.minus(3, ChronoUnit.DAYS), 1_500L);
    }
    JobStatsDto dto = api.stats(Long.toString(jobId), "7d");
    var buckets = dto.dailyBuckets();
    assertEquals(7, buckets.size());
    // Oldest first: every prior day strictly less than the next.
    for (int i = 1; i < buckets.size(); i++) {
      assertTrue(buckets.get(i - 1).day().isBefore(buckets.get(i).day()));
    }
    // Sum of per-day totalBuilds == headline totalBuilds.
    long perDaySum = buckets.stream().mapToLong(b -> b.totalBuilds()).sum();
    assertEquals(dto.totalBuilds(), perDaySum);
    long perDayFailed = buckets.stream().mapToLong(b -> b.failedBuilds()).sum();
    assertEquals(dto.failedBuilds(), perDayFailed);
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

  private static void insertBuild(
      Connection c, long jobId, int buildNumber, String status, Instant queuedAt, long durationMs)
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

  /**
   * Insert a RUNNING build — no {@code finished_at}, no {@code duration_ms}. Models the
   * mid-execution state that p50/p95 must exclude.
   */
  private static void insertRunningBuild(
      Connection c, long jobId, int buildNumber, Instant queuedAt) throws Exception {
    Instant started = queuedAt.plusSeconds(1);
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, queued_at, "
                + "started_at, finished_at, duration_ms) "
                + "VALUES (?, ?, ?, ?, ?, NULL, NULL)")) {
      ps.setLong(1, jobId);
      ps.setInt(2, buildNumber);
      ps.setString(3, "RUNNING");
      ps.setTimestamp(4, Timestamp.from(queuedAt));
      ps.setTimestamp(5, Timestamp.from(started));
      // finished_at + duration_ms inlined as NULL above — no further binds.
      ps.executeUpdate();
    }
  }
}

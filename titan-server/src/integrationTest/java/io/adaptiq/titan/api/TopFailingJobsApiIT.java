package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.api.dto.TopFailingJobDto;
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
 * Postgres-backed IT for {@link TopFailingJobsApi} via {@link TopFailingJobDao} (closes #769).
 *
 * <p>Tests pin the SQL aggregate's behaviour against real PostgreSQL (matters for {@code NULLIF} +
 * double-precision cast) — and verify the API guard logic for {@code since} / {@code limit} via
 * direct controller calls (no full HTTP wiring needed; matches the {@code StatsApiIT} pattern).
 */
@Testcontainers
class TopFailingJobsApiIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private TopFailingJobsApi api;

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
    // Constructor is package-private; we're in the same package so direct call is fine.
    api = new TopFailingJobsApi(stores);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  @Test
  void topFailing_ranksByFailureRateDescending_andFiltersBelowThreshold() throws Exception {
    // jobA: 4 builds (3 FAILED, 1 SUCCESS) → failure rate 0.75
    // jobB: 5 builds (2 FAILED, 3 SUCCESS) → failure rate 0.40
    // jobC: 1 build (FAILED) → BELOW 3-build threshold; must be excluded
    // jobD: 3 builds (all SUCCESS) → no FAILED; HAVING filter excludes
    long jobA;
    long jobB;
    long jobC;
    long jobD;
    Instant now = Instant.now();
    try (Connection c = ds.getConnection()) {
      jobA = insertJob(c, "ns/jobA", "JobA");
      jobB = insertJob(c, "ns/jobB", "JobB");
      jobC = insertJob(c, "ns/jobC", "JobC");
      jobD = insertJob(c, "ns/jobD", "JobD");

      int n = 1;
      insertBuild(c, jobA, n++, "FAILED", now.minus(2, ChronoUnit.HOURS));
      insertBuild(c, jobA, n++, "FAILED", now.minus(3, ChronoUnit.HOURS));
      insertBuild(c, jobA, n++, "FAILED", now.minus(4, ChronoUnit.HOURS));
      insertBuild(c, jobA, n++, "SUCCESS", now.minus(5, ChronoUnit.HOURS));

      insertBuild(c, jobB, 1, "FAILED", now.minus(1, ChronoUnit.HOURS));
      insertBuild(c, jobB, 2, "FAILED", now.minus(2, ChronoUnit.HOURS));
      insertBuild(c, jobB, 3, "SUCCESS", now.minus(3, ChronoUnit.HOURS));
      insertBuild(c, jobB, 4, "SUCCESS", now.minus(4, ChronoUnit.HOURS));
      insertBuild(c, jobB, 5, "SUCCESS", now.minus(5, ChronoUnit.HOURS));

      insertBuild(c, jobC, 1, "FAILED", now.minus(1, ChronoUnit.HOURS));

      insertBuild(c, jobD, 1, "SUCCESS", now.minus(1, ChronoUnit.HOURS));
      insertBuild(c, jobD, 2, "SUCCESS", now.minus(2, ChronoUnit.HOURS));
      insertBuild(c, jobD, 3, "SUCCESS", now.minus(3, ChronoUnit.HOURS));
    }

    var rows = api.topFailing("24h", 5);

    assertEquals(2, rows.size(), "jobC (below threshold) and jobD (no failures) must be excluded");
    assertEquals(jobA, rows.get(0).jobId(), "jobA has the higher failure rate (0.75)");
    assertEquals(4L, rows.get(0).totalBuilds());
    assertEquals(3L, rows.get(0).failedBuilds());
    assertEquals(0.75, rows.get(0).failureRate(), 1e-9);
    assertNotNull(
        rows.get(0).lastFailedBuildId(), "lastFailedBuildId populated when FAILED exists");
    assertEquals("JobA", rows.get(0).jobName());

    assertEquals(jobB, rows.get(1).jobId(), "jobB ranks second with 0.40 failure rate");
    assertEquals(5L, rows.get(1).totalBuilds());
    assertEquals(2L, rows.get(1).failedBuilds());
    assertEquals(0.40, rows.get(1).failureRate(), 1e-9);
  }

  @Test
  void topFailing_emptyWindow_returnsEmptyList() {
    List<TopFailingJobDto> rows = api.topFailing("24h", 5);
    assertEquals(0, rows.size(), "empty DB → empty list, never null");
    assertNotNull(rows);
  }

  @Test
  void topFailing_invalidSince_returns400() {
    ApiBadRequestException ex =
        assertThrows(ApiBadRequestException.class, () -> api.topFailing("48h", 5));
    assertTrue(ex.getMessage().contains("since"), "error message names the offending param");
  }

  @Test
  void topFailing_sinceSqlInjection_treatedAsInvalidString_safe() {
    // The discriminator parser rejects anything that isn't '24h' or '7d' — so a
    // would-be injection payload surfaces as a 400 before ever touching SQL. This
    // pins the parameterisation guarantee at the boundary that the user can reach.
    String payload = "24h'); DROP TABLE titan.builds; --";
    ApiBadRequestException ex =
        assertThrows(ApiBadRequestException.class, () -> api.topFailing(payload, 5));
    assertTrue(ex.getMessage().contains("since"));
    // And — defensively — the table still exists.
    var rows = api.topFailing("24h", 5);
    assertNotNull(rows);
  }

  @Test
  void topFailing_invalidLimit_returns400() {
    assertThrows(ApiBadRequestException.class, () -> api.topFailing("24h", 0));
    assertThrows(ApiBadRequestException.class, () -> api.topFailing("24h", 21));
    assertThrows(ApiBadRequestException.class, () -> api.topFailing("24h", -1));
  }

  @Test
  void topFailing_buildsOutsideWindow_excluded() throws Exception {
    // jobE: 5 builds, all FAILED, but all queued > 7 days ago. With since=24h,
    // nothing is counted; with since=7d, also nothing (boundary > 7d).
    long jobE;
    Instant longAgo = Instant.now().minus(10, ChronoUnit.DAYS);
    try (Connection c = ds.getConnection()) {
      jobE = insertJob(c, "ns/jobE", "JobE");
      for (int i = 1; i <= 5; i++) {
        insertBuild(c, jobE, i, "FAILED", longAgo.minus(i, ChronoUnit.HOURS));
      }
    }

    assertEquals(0, api.topFailing("24h", 5).size());
    assertEquals(0, api.topFailing("7d", 5).size());
    // Reference the jobE id so the compiler keeps the insert in the trace on failure.
    assertTrue(jobE > 0);
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
      Connection c, long jobId, int buildNumber, String status, Instant queuedAt) throws Exception {
    Instant started = queuedAt.plusSeconds(2);
    Instant finished = started.plusSeconds(10);
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
      ps.setLong(7, 10_000L);
      ps.executeUpdate();
    }
  }
}

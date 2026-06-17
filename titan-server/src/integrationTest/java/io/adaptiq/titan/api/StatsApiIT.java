package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.StatsDao;
import io.adaptiq.titan.store.TitanStores;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres-backed integration test for {@link io.adaptiq.titan.api.StatsApi} via {@link StatsDao}
 * (closes #346).
 *
 * <p>Seeds three builds — one SUCCESS today, one FAILED today, one SUCCESS yesterday — and asserts
 * the aggregate query returns the expected KPIs: {@code buildsToday = 2}, {@code successRate =
 * 0.5}, {@code medianDurationMs > 0}.
 *
 * <p>The "API path" here is the DAO method — we hit the JDBI SqlObject directly, which is also what
 * {@code StatsApi} calls. The OIDC / JAX-RS glue is unit-test territory (handled by {@code
 * StatsApiTest}). The contract this IT pins down is the SQL — specifically that {@code
 * PERCENTILE_CONT(0.5) WITHIN GROUP} runs on real PostgreSQL, not just H2.
 */
@Testcontainers
class StatsApiIT {

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

  @Test
  void overview_aggregatesBuildsToday_successRate_andMedianDuration() throws Exception {
    Instant now = Instant.now();
    Instant startOfDayUtc =
        now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant since = now.minus(24, ChronoUnit.HOURS);

    long jobId;
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "stats-it/job-" + System.nanoTime());

      // 1) SUCCESS today — queued + finished within the UTC day and 24h window. Duration: 5s.
      Instant aQueued = startOfDayUtc.plus(2, ChronoUnit.HOURS);
      Instant aStarted = aQueued.plusSeconds(10);
      Instant aFinished = aStarted.plusSeconds(5);
      insertBuild(c, jobId, 1, "SUCCESS", aQueued, aStarted, aFinished);

      // 2) FAILED today — same UTC day, also inside 24h window. Duration: 15s (median pivot).
      Instant bQueued = startOfDayUtc.plus(3, ChronoUnit.HOURS);
      Instant bStarted = bQueued.plusSeconds(10);
      Instant bFinished = bStarted.plusSeconds(15);
      insertBuild(c, jobId, 2, "FAILED", bQueued, bStarted, bFinished);

      // 3) SUCCESS yesterday — outside today + outside the 24h window. Must not influence KPIs.
      Instant cQueued = startOfDayUtc.minus(30, ChronoUnit.HOURS);
      Instant cStarted = cQueued.plusSeconds(10);
      Instant cFinished = cStarted.plusSeconds(60);
      insertBuild(c, jobId, 3, "SUCCESS", cQueued, cStarted, cFinished);
    }

    StatsDao.StatsRow row = stores.stats().overview(startOfDayUtc, since);

    assertEquals(2, row.buildsToday(), "two builds queued since start-of-UTC-day");
    // 1 success / 2 terminal in the last 24h = 0.5
    assertEquals(
        0.5, row.successRate(), 1e-9, "1 of 2 terminal builds in the 24h window succeeded");
    // Median of {5000, 15000} = 10000ms; PERCENTILE_CONT interpolates between the two.
    assertEquals(
        10_000L,
        row.medianDurationMs(),
        "median of {5s, 15s} durations is 10s under PERCENTILE_CONT");
    assertTrue(row.medianDurationMs() > 0, "median duration is positive");
  }

  @Test
  void overview_emptyStoreReturnsZeros() {
    Instant now = Instant.now();
    Instant startOfDayUtc =
        now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant since = now.minus(24, ChronoUnit.HOURS);

    StatsDao.StatsRow row = stores.stats().overview(startOfDayUtc, since);

    assertEquals(0, row.buildsToday());
    assertEquals(0.0, row.successRate(), 0.0);
    assertEquals(0L, row.medianDurationMs());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static long insertJob(Connection c, String fullName) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) "
                + "VALUES (?, '', '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, fullName);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static void insertBuild(
      Connection c,
      long jobId,
      int buildNumber,
      String status,
      Instant queuedAt,
      Instant startedAt,
      Instant finishedAt)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, queued_at, "
                + "started_at, finished_at, duration_ms) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
      ps.setLong(1, jobId);
      ps.setInt(2, buildNumber);
      ps.setString(3, status);
      ps.setTimestamp(4, Timestamp.from(queuedAt));
      ps.setTimestamp(5, Timestamp.from(startedAt));
      ps.setTimestamp(6, Timestamp.from(finishedAt));
      ps.setLong(7, finishedAt.toEpochMilli() - startedAt.toEpochMilli());
      ps.executeUpdate();
    }
  }
}

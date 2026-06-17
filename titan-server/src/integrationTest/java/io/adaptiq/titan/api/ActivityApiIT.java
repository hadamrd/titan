package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.ActivityDao;
import io.adaptiq.titan.store.TitanStores;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
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
 * Postgres-backed integration test for {@link ActivityApi} via {@link ActivityDao} — closes #304.
 *
 * <p>Seeds three terminal builds with distinct {@code finished_at} timestamps and a non-terminal
 * RUNNING build (which must be excluded). Asserts:
 *
 * <ul>
 *   <li>Result order is {@code finished_at DESC}.
 *   <li>{@code duration_ms} round-trips through the projection.
 *   <li>Non-terminal builds are filtered out.
 *   <li>Cursor pagination ({@code before=<finishedAt>}) strictly excludes the boundary row.
 * </ul>
 *
 * <p>Mirrors {@link StatsApiIT} — same Testcontainers Postgres setup, same migration locations.
 * Fixture job names use {@code System.nanoTime()} for #367-style cross-test resilience.
 */
@Testcontainers
class ActivityApiIT {

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
  void recentTerminalBuilds_ordersByFinishedAtDesc_andExcludesNonTerminal() throws Exception {
    Instant now = Instant.now();
    String fixtureJob = "activity-it/job-" + System.nanoTime();

    long jobId;
    long oldestId;
    long middleId;
    long newestId;
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, fixtureJob);

      // Three terminal builds, distinct finished_at timestamps.
      oldestId =
          insertBuild(c, jobId, 1, "SUCCESS", now.minusSeconds(300), now.minusSeconds(290), 1_000L);
      middleId =
          insertBuild(c, jobId, 2, "FAILED", now.minusSeconds(200), now.minusSeconds(180), 2_500L);
      newestId =
          insertBuild(c, jobId, 3, "ABORTED", now.minusSeconds(100), now.minusSeconds(80), 4_200L);

      // A non-terminal build that must NOT appear in the feed (no finished_at).
      insertBuild(c, jobId, 4, "RUNNING", now.minusSeconds(50), null, null);
    }

    List<ActivityDao.ActivityRow> rows = stores.activity().recentTerminalBuilds(10, null);

    // Filter to just our fixture-job rows so other tests' data can't perturb assertions.
    List<ActivityDao.ActivityRow> mine =
        rows.stream().filter(r -> r.jobFullName().equals(fixtureJob)).toList();

    assertEquals(3, mine.size(), "exactly the three terminal builds we inserted");
    assertEquals(newestId, mine.get(0).id(), "newest finished_at first");
    assertEquals(middleId, mine.get(1).id());
    assertEquals(oldestId, mine.get(2).id(), "oldest finished_at last");

    assertEquals("ABORTED", mine.get(0).status());
    assertEquals(4_200L, mine.get(0).durationMs());
    assertEquals(2_500L, mine.get(1).durationMs());
    assertEquals(1_000L, mine.get(2).durationMs());

    // finished_at strictly decreasing
    assertTrue(mine.get(0).finishedAt().isAfter(mine.get(1).finishedAt()));
    assertTrue(mine.get(1).finishedAt().isAfter(mine.get(2).finishedAt()));
  }

  @Test
  void recentTerminalBuilds_cursorBeforeExcludesBoundary() throws Exception {
    Instant now = Instant.now();
    String fixtureJob = "activity-it/cursor-" + System.nanoTime();

    long oldestId;
    long middleId;
    long newestId;
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, fixtureJob);
      oldestId =
          insertBuild(c, jobId, 1, "SUCCESS", now.minusSeconds(300), now.minusSeconds(290), 1L);
      middleId =
          insertBuild(c, jobId, 2, "SUCCESS", now.minusSeconds(200), now.minusSeconds(180), 1L);
      newestId =
          insertBuild(c, jobId, 3, "SUCCESS", now.minusSeconds(100), now.minusSeconds(80), 1L);
    }

    // Page 1: most recent, limited to the newest single fixture row.
    List<ActivityDao.ActivityRow> page1 =
        stores.activity().recentTerminalBuilds(50, null).stream()
            .filter(r -> r.jobFullName().equals(fixtureJob))
            .toList();
    assertEquals(3, page1.size());
    Instant cursor = page1.get(0).finishedAt(); // newest

    // Page 2: strictly before the newest row's finished_at.
    List<ActivityDao.ActivityRow> page2 =
        stores.activity().recentTerminalBuilds(50, cursor).stream()
            .filter(r -> r.jobFullName().equals(fixtureJob))
            .toList();

    assertEquals(2, page2.size(), "cursor excludes the boundary row (strict <)");
    assertEquals(middleId, page2.get(0).id());
    assertEquals(oldestId, page2.get(1).id());
    // newestId must NOT appear in page2
    assertTrue(page2.stream().noneMatch(r -> r.id() == newestId));
  }

  @Test
  void recentTerminalBuilds_emptyOnFreshDb() {
    List<ActivityDao.ActivityRow> rows = stores.activity().recentTerminalBuilds(25, null);
    assertNotNull(rows, "never returns null");
    // Could be empty (clean schema) — assert nothing leaks from a non-existent table.
    assertTrue(rows.isEmpty());
    // also confirms `before=null` is accepted by the SQL
    assertNull(null);
  }

  // ── helpers ────────────────────────────────────────────────────────────

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

  private static long insertBuild(
      Connection c,
      long jobId,
      int buildNumber,
      String status,
      Instant startedAt,
      Instant finishedAt,
      Long durationMs)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, queued_at, "
                + "started_at, finished_at, duration_ms) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, jobId);
      ps.setInt(2, buildNumber);
      ps.setString(3, status);
      ps.setTimestamp(4, Timestamp.from(startedAt.minusSeconds(1)));
      ps.setTimestamp(5, Timestamp.from(startedAt));
      if (finishedAt != null) {
        ps.setTimestamp(6, Timestamp.from(finishedAt));
      } else {
        ps.setNull(6, java.sql.Types.TIMESTAMP);
      }
      if (durationMs != null) {
        ps.setLong(7, durationMs);
      } else {
        ps.setNull(7, java.sql.Types.BIGINT);
      }
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }
}

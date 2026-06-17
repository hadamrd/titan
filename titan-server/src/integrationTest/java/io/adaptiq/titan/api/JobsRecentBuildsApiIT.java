package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.api.dto.BuildDto;
import io.adaptiq.titan.store.TitanStores;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres-backed integration test for {@link JobsRecentBuildsApi} via the bulk DAO method (closes
 * #650). Bypasses the JAX-RS layer (covered by unit tests) and pins the load-bearing contract: one
 * SQL pass returns {@code N * limit} rows max, partitioned by job and ordered newest-first within
 * each partition.
 *
 * <p>Adversarial cases (per ticket brief):
 *
 * <ul>
 *   <li>Empty jobIds list → resource returns {@code {}} without hitting the DAO (the IN () would be
 *       invalid SQL on Postgres).
 *   <li>50-job request → response carries 50 entries, none truncated.
 *   <li>Unknown job id absent from response map (no error, no null value).
 *   <li>{@code limit} actually caps per-job — not the total result set.
 * </ul>
 */
@Testcontainers
class JobsRecentBuildsApiIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private JobsRecentBuildsApi api;

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
    api = new JobsRecentBuildsApi(stores);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  @Test
  void emptyJobIds_returnsEmptyMap_withoutHittingDao() {
    // Null and blank are both no-ops — the DAO is never asked, so even a closed DB would not throw.
    assertEquals(Map.of(), api.recentBuilds(null, 20));
    assertEquals(Map.of(), api.recentBuilds("", 20));
    assertEquals(Map.of(), api.recentBuilds("   ", 20));
  }

  @Test
  void fiftyJobIds_returnFiftyEntries_noneTruncated() throws Exception {
    List<Long> jobIds = new ArrayList<>(50);
    Instant now = Instant.now();
    try (Connection c = ds.getConnection()) {
      for (int i = 0; i < 50; i++) {
        long jobId = insertJob(c, "bulk-it/job-" + i + "-" + System.nanoTime());
        jobIds.add(jobId);
        // Each job: 3 builds, newest = build_number 3.
        insertBuild(c, jobId, 1, "SUCCESS", now.minusSeconds(300L + i));
        insertBuild(c, jobId, 2, "FAILED", now.minusSeconds(200L + i));
        insertBuild(c, jobId, 3, "SUCCESS", now.minusSeconds(100L + i));
      }
    }
    String csv = jobIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElseThrow();

    Map<Long, List<BuildDto>> result = api.recentBuilds(csv, 20);

    assertEquals(50, result.size(), "every requested jobId must appear in the response");
    for (Long jobId : jobIds) {
      List<BuildDto> builds = result.get(jobId);
      assertNotNull(builds, "jobId " + jobId + " missing from response");
      assertEquals(3, builds.size(), "every job has 3 builds; none were truncated");
      // Window function returns newest-first within each partition.
      assertEquals(3, builds.get(0).buildNumber());
      assertEquals(2, builds.get(1).buildNumber());
      assertEquals(1, builds.get(2).buildNumber());
    }
  }

  @Test
  void unknownJobId_absentFromResponse_notError() throws Exception {
    long realJobId;
    Instant now = Instant.now();
    try (Connection c = ds.getConnection()) {
      realJobId = insertJob(c, "bulk-it/real-" + System.nanoTime());
      insertBuild(c, realJobId, 1, "SUCCESS", now);
    }
    long bogus = 9_999_999L;

    Map<Long, List<BuildDto>> result = api.recentBuilds(realJobId + "," + bogus, 20);

    assertEquals(1, result.size(), "only the real job produced a row");
    assertNotNull(result.get(realJobId));
    assertEquals(1, result.get(realJobId).size());
    assertFalse(result.containsKey(bogus), "unknown id must NOT appear as null/empty entry");
  }

  @Test
  void limitCapsPerJob_notTotal() throws Exception {
    List<Long> jobIds = new ArrayList<>(2);
    Instant now = Instant.now();
    try (Connection c = ds.getConnection()) {
      for (int i = 0; i < 2; i++) {
        long jobId = insertJob(c, "bulk-it/limit-" + i + "-" + System.nanoTime());
        jobIds.add(jobId);
        for (int n = 1; n <= 10; n++) {
          // queued_at staggered so ORDER BY is deterministic.
          insertBuild(c, jobId, n, "SUCCESS", now.minusSeconds(1000L - n));
        }
      }
    }

    Map<Long, List<BuildDto>> result = api.recentBuilds(jobIds.get(0) + "," + jobIds.get(1), 3);

    assertEquals(2, result.size());
    for (Long jobId : jobIds) {
      List<BuildDto> b = result.get(jobId);
      assertEquals(3, b.size(), "limit=3 caps PER JOB, not across the whole result");
      // Newest first → build_number 10, 9, 8.
      assertEquals(10, b.get(0).buildNumber());
      assertEquals(9, b.get(1).buildNumber());
      assertEquals(8, b.get(2).buildNumber());
    }
  }

  @Test
  void nonNumericTokenSurfacesAsBadRequest() {
    try {
      api.recentBuilds("1,abc,3", 20);
    } catch (ApiBadRequestException expected) {
      assertTrue(expected.getMessage().contains("abc"));
      return;
    }
    throw new AssertionError("expected ApiBadRequestException on non-numeric token");
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
      Connection c, long jobId, int buildNumber, String status, Instant queuedAt) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, queued_at) "
                + "VALUES (?, ?, ?, ?)")) {
      ps.setLong(1, jobId);
      ps.setInt(2, buildNumber);
      ps.setString(3, status);
      ps.setTimestamp(4, Timestamp.from(queuedAt));
      ps.executeUpdate();
    }
  }
}

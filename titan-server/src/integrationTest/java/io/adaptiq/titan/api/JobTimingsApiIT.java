package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.api.dto.StageTimingsDto;
import io.adaptiq.titan.api.dto.StageTimingsDto.StageTimingDto;
import io.adaptiq.titan.store.TitanStores;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres-backed IT for {@link JobTimingsHandler} (closes #1095).
 *
 * <p>Pins the {@code percentile_cont} aggregate against real Postgres — H2 lacks the function and
 * the math drift between linear-interpolation flavours is exactly what an SRE would notice on the
 * UI. Each test synthesises a controlled flow-node distribution and asserts the handler returns the
 * same values that NumPy's {@code percentile(method='linear')} would produce on identical input.
 * The reference helper for that math lives in {@link JobTimingsHandlerTest}.
 */
@Testcontainers
class JobTimingsApiIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private JobTimingsHandler handler;

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
    handler = new JobTimingsHandler(stores);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void stageTimings_thirtyBuildsTwoStages_returnsPercentilesAndSamples() throws Exception {
    // Synthesise 30 SUCCESS builds for one job. Each build has two stages:
    //   "build"  → duration = 100ms .. 3000ms (100..1000 * 3, varying)
    //   "test"   → duration = (build duration) * 2
    // The test asserts percentile_cont produces the same values as NumPy's
    // percentile(method='linear') on the same array (computed by hand below).
    long jobId;
    Instant now = Instant.now();
    List<Long> buildDurations = new ArrayList<>(30);
    List<Long> testDurations = new ArrayList<>(30);
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/perfA", "PerfA");
      for (int i = 1; i <= 30; i++) {
        long buildId = insertBuild(c, jobId, i, "SUCCESS", now.minus(31 - i, ChronoUnit.HOURS));
        long buildStageMs = 100L * i; // 100, 200, ... 3000
        long testStageMs = buildStageMs * 2L;
        insertStage(c, buildId, "stage-build-" + i, "build", "SUCCESS", buildStageMs);
        insertStage(c, buildId, "stage-test-" + i, "test", "SUCCESS", testStageMs);
        buildDurations.add(buildStageMs);
        testDurations.add(testStageMs);
      }
    }

    StageTimingsDto dto = handler.stageTimings(Long.toString(jobId), 30);
    assertEquals(30, dto.n());
    assertEquals(30L, dto.buildsConsidered());
    assertEquals(2, dto.stages().size(), "two distinct stage names → two rows");

    StageTimingDto buildStage = findStage(dto, "build");
    assertEquals(30L, buildStage.sampleCount());
    // numpy.percentile([100..3000 step 100], 50) = 1550.0; percentile_cont returns
    // the same value cast to BIGINT. Postgres' CAST(double AS bigint) ROUNDS (half-
    // away-from-zero), which is why numpyPercentileAsBigint() rounds too — p95 of this
    // distribution is 2854.999… (IEEE-754) → 2855, not a truncated 2854.
    assertEquals(numpyPercentileAsBigint(buildDurations, 0.50), buildStage.p50Ms());
    assertEquals(numpyPercentileAsBigint(buildDurations, 0.95), buildStage.p95Ms());
    assertEquals(numpyPercentileAsBigint(buildDurations, 0.99), buildStage.p99Ms());
    assertEquals(30, buildStage.samples().size(), "every build contributes one sample");
    // Samples are oldest→newest by build_number ASC.
    for (int i = 1; i < buildStage.samples().size(); i++) {
      assertTrue(
          buildStage.samples().get(i - 1).buildNumber() < buildStage.samples().get(i).buildNumber(),
          "samples ordered oldest→newest");
    }

    StageTimingDto testStage = findStage(dto, "test");
    assertEquals(numpyPercentileAsBigint(testDurations, 0.50), testStage.p50Ms());
    assertEquals(numpyPercentileAsBigint(testDurations, 0.95), testStage.p95Ms());
    assertEquals(numpyPercentileAsBigint(testDurations, 0.99), testStage.p99Ms());

    // Slowest-p50 first ordering (test = 2x build).
    assertEquals("test", dto.stages().get(0).stageName());
    assertEquals("build", dto.stages().get(1).stageName());
  }

  @Test
  void stageTimings_singleSample_p50EqualsP95EqualsP99() throws Exception {
    long jobId;
    Instant now = Instant.now();
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/perfB", "PerfB");
      long buildId = insertBuild(c, jobId, 1, "SUCCESS", now);
      insertStage(c, buildId, "stage-1", "deploy", "SUCCESS", 12345L);
    }
    StageTimingsDto dto = handler.stageTimings(Long.toString(jobId), 30);
    assertEquals(1, dto.stages().size());
    StageTimingDto stage = dto.stages().get(0);
    assertEquals(1L, stage.sampleCount());
    assertEquals(Long.valueOf(12345L), stage.p50Ms());
    assertEquals(Long.valueOf(12345L), stage.p95Ms());
    assertEquals(Long.valueOf(12345L), stage.p99Ms());
  }

  // ── empty / sad paths ─────────────────────────────────────────────────────

  @Test
  void stageTimings_freshJob_returnsEmptyStagesAndZeroBuildsConsidered() throws Exception {
    long jobId;
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/perfC", "PerfC");
    }
    StageTimingsDto dto = handler.stageTimings(Long.toString(jobId), 30);
    assertEquals(0L, dto.buildsConsidered(), "no finished builds yet");
    assertTrue(dto.stages().isEmpty(), "empty stages list, not a null");
  }

  @Test
  void stageTimings_runningBuildsOnly_excludedFromPercentiles() throws Exception {
    long jobId;
    Instant now = Instant.now();
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/perfD", "PerfD");
      // A QUEUED/RUNNING build with a partially-recorded stage (status RUNNING, no duration_ms).
      // Must NOT contribute to the percentile — otherwise the in-flight build would drag p99.
      long buildId = insertRunningBuild(c, jobId, 1, now);
      insertRunningStage(c, buildId, "stage-1", "build");
    }
    StageTimingsDto dto = handler.stageTimings(Long.toString(jobId), 30);
    assertEquals(0L, dto.buildsConsidered(), "RUNNING builds excluded from finished-window count");
    assertTrue(dto.stages().isEmpty());
  }

  @Test
  void stageTimings_invalidN_returns400() throws Exception {
    long jobId;
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/perfE", "PerfE");
    }
    final String jid = Long.toString(jobId);
    assertThrows(ApiBadRequestException.class, () -> handler.stageTimings(jid, 0));
    assertThrows(ApiBadRequestException.class, () -> handler.stageTimings(jid, -1));
    assertThrows(ApiBadRequestException.class, () -> handler.stageTimings(jid, 1000));
  }

  @Test
  void stageTimings_invalidJobId_returns400() {
    assertThrows(ApiBadRequestException.class, () -> handler.stageTimings("not-a-number", 30));
  }

  @Test
  void stageTimings_clickThroughSampleCarriesBuildId() throws Exception {
    long jobId;
    long expectedBuildId;
    Instant now = Instant.now();
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/perfF", "PerfF");
      expectedBuildId = insertBuild(c, jobId, 7, "SUCCESS", now.minus(1, ChronoUnit.HOURS));
      insertStage(c, expectedBuildId, "stage-1", "package", "SUCCESS", 5000L);
    }
    StageTimingsDto dto = handler.stageTimings(Long.toString(jobId), 30);
    assertEquals(1, dto.stages().size());
    var sample = dto.stages().get(0).samples().get(0);
    assertEquals(expectedBuildId, sample.buildId(), "click target = actual build id");
    assertEquals(7, sample.buildNumber());
    assertEquals(5000L, sample.durationMs());
  }

  @Test
  void stageTimings_capsToMostRecentN() throws Exception {
    long jobId;
    Instant now = Instant.now();
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "ns/perfG", "PerfG");
      // 40 builds; only the most recent 10 should be considered when n=10.
      for (int i = 1; i <= 40; i++) {
        long buildId = insertBuild(c, jobId, i, "SUCCESS", now.minus(50L - i, ChronoUnit.HOURS));
        insertStage(c, buildId, "stage-1", "build", "SUCCESS", 1000L * i);
      }
    }
    StageTimingsDto dto = handler.stageTimings(Long.toString(jobId), 10);
    assertEquals(10, dto.n());
    assertEquals(10L, dto.buildsConsidered());
    StageTimingDto stage = dto.stages().get(0);
    assertEquals(10L, stage.sampleCount());
    // Most recent 10 = builds 31..40 → durations 31000..40000.
    long minSample = stage.samples().stream().mapToLong(s -> s.durationMs()).min().orElseThrow();
    long maxSample = stage.samples().stream().mapToLong(s -> s.durationMs()).max().orElseThrow();
    assertEquals(31000L, minSample, "older builds (1..30) excluded");
    assertEquals(40000L, maxSample);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static StageTimingDto findStage(StageTimingsDto dto, String name) {
    return dto.stages().stream()
        .filter(s -> s.stageName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing stage " + name));
  }

  /**
   * Mirror of {@code numpy.percentile(method='linear')} cast to a BIGINT — exactly what {@code
   * percentile_cont} produces on the SQL side. Inlined (not delegated to the unit-test class)
   * because the integrationTest source-set's classpath does not include {@code src/test/java}. Pure
   * linear interpolation: sample at fractional index {@code (n - 1) * p}.
   *
   * <p><strong>Rounding.</strong> PostgreSQL's {@code CAST(double precision AS bigint)} <em>rounds
   * half-away-from-zero</em> — it does NOT truncate. The reference must round too: p95 of {@code
   * 100..3000} is {@code 2854.999…} thanks to IEEE-754's representation of {@code 0.95}, which
   * would truncate to {@code 2854} on the Java side while Postgres yields {@code 2855}. {@link
   * Math#round} (half-up) matches half-away for the non-negative durations this domain produces.
   */
  private static Long numpyPercentileAsBigint(List<Long> samples, double p) {
    List<Long> sorted = new ArrayList<>(samples);
    sorted.sort(Long::compareTo);
    double idx = (sorted.size() - 1) * p;
    int lo = (int) Math.floor(idx);
    int hi = (int) Math.ceil(idx);
    double value =
        lo == hi
            ? (double) sorted.get(lo)
            : sorted.get(lo) + (idx - lo) * (sorted.get(hi) - sorted.get(lo));
    return Math.round(value);
  }

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

  private static long insertBuild(
      Connection c, long jobId, int buildNumber, String status, Instant queuedAt) throws Exception {
    Instant started = queuedAt.plusSeconds(1);
    Instant finished = started.plusSeconds(60);
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, queued_at, "
                + "started_at, finished_at, duration_ms) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, jobId);
      ps.setInt(2, buildNumber);
      ps.setString(3, status);
      ps.setTimestamp(4, Timestamp.from(queuedAt));
      ps.setTimestamp(5, Timestamp.from(started));
      ps.setTimestamp(6, Timestamp.from(finished));
      ps.setLong(7, 60_000L);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static long insertRunningBuild(
      Connection c, long jobId, int buildNumber, Instant queuedAt) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, queued_at, started_at) "
                + "VALUES (?, ?, 'RUNNING', ?, ?)",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, jobId);
      ps.setInt(2, buildNumber);
      ps.setTimestamp(3, Timestamp.from(queuedAt));
      ps.setTimestamp(4, Timestamp.from(queuedAt.plusSeconds(1)));
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static void insertStage(
      Connection c, long buildId, String nodeId, String displayName, String status, long durationMs)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.flow_nodes (build_id, node_id, node_type, display_name, "
                + "status, duration_ms, attempt, max_attempts) "
                + "VALUES (?, ?, 'STAGE', ?, ?, ?, 1, 1)")) {
      ps.setLong(1, buildId);
      ps.setString(2, nodeId);
      ps.setString(3, displayName);
      ps.setString(4, status);
      ps.setLong(5, durationMs);
      ps.executeUpdate();
    }
  }

  private static void insertRunningStage(
      Connection c, long buildId, String nodeId, String displayName) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.flow_nodes (build_id, node_id, node_type, display_name, "
                + "status, duration_ms, attempt, max_attempts) "
                + "VALUES (?, ?, 'STAGE', ?, 'RUNNING', NULL, 1, 1)")) {
      ps.setLong(1, buildId);
      ps.setString(2, nodeId);
      ps.setString(3, displayName);
      ps.executeUpdate();
    }
  }
}

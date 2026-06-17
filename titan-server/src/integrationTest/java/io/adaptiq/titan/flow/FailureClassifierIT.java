package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.api.dto.BuildDto;
import io.adaptiq.titan.flow.orch.BuildFailureClassifier;
import io.adaptiq.titan.flow.orch.FailureCause;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.LogRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration coverage for the build-failure root-cause classifier (issue #1105) against
 * real PostgreSQL + real Flyway migrations.
 *
 * <p>Drives a step to FAILED through the orchestrator (so a real FAILED flow node + a real worker
 * log exist), runs {@link BuildFailureClassifier#classifyAndStore} synchronously, and asserts the
 * diagnosed cause + matching snippet land on {@code titan.builds.failure_cause}/{@code
 * failure_cause_detail} — and that the {@link BuildDto} the UI consumes carries them. Crosses the
 * classifier ↔ FlowNodeConsole ↔ BuildDao ↔ DTO boundaries the unit tests stub out.
 *
 * <p><strong>Docker:</strong> Testcontainers needs a Docker daemon — run under WSL like the other
 * Titan ITs.
 */
@Testcontainers
class FailureClassifierIT {

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

  /** The V43 migration applied: the two new columns exist and are nullable. */
  @Test
  void v43MigrationAddedTheFailureCauseColumns() throws Exception {
    try (Connection c = ds.getConnection()) {
      assertTrue(columnExists(c, "builds", "failure_cause"), "builds.failure_cause exists");
      assertTrue(
          columnExists(c, "builds", "failure_cause_detail"), "builds.failure_cause_detail exists");
    }
  }

  /**
   * Happy path: a step that emits an OOM line then exits non-zero is classified {@code oom}, the
   * snippet is the matching line, and the {@link BuildDto} the UI reads carries both — the
   * end-to-end "badge updates" contract.
   */
  @Test
  void aFailedOomStepIsClassifiedAndSurfacedOnTheBuildDto() throws Exception {
    bootstrap("diamond-dag.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance(); // RUNNING build, dispatch step
    UUID token = onlyClaimableTaskToken("build-s0");
    appendLog(token, "[INFO] Running tests");
    appendLog(token, "Exception in thread \"main\" java.lang.OutOfMemoryError: Java heap space");
    completeClaimed("FAILED", "{\"exitCode\":137}");
    orch.advance(); // fold FAILED node into the build

    // Run the classifier synchronously (production fires it on a daemon thread post-close).
    BuildFailureClassifier.defaultInstance().classifyAndStore(stores, buildId);

    var row = stores.builds().findById(buildId).orElseThrow();
    assertEquals(FailureCause.OOM.wire(), row.failureCause, "OOM signature wins");
    assertNotNull(row.failureCauseDetail, "the matching log line is stored as the tooltip snippet");
    assertTrue(
        row.failureCauseDetail.contains("OutOfMemoryError"),
        "snippet names the matching line; was: " + row.failureCauseDetail);

    // UI contract: the DTO the build-detail page consumes carries the cause + detail.
    BuildDto dto = BuildDto.from(row);
    assertEquals("oom", dto.failureCause());
    assertTrue(dto.failureCauseDetail().contains("OutOfMemoryError"));
  }

  /**
   * Adversarial: a FAILED build whose log carries no known signature classifies {@code unknown}
   * with a {@code null} detail — the classifier degrades gracefully, never crashes or mis-labels.
   */
  @Test
  void aFailedStepWithNoKnownSignatureIsClassifiedUnknown() throws Exception {
    bootstrap("diamond-dag.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance();
    UUID token = onlyClaimableTaskToken("build-s0");
    appendLog(token, "[INFO] packaging artifact");
    appendLog(token, "[INFO] step exited with code 7");
    completeClaimed("FAILED", "{\"exitCode\":7}");
    orch.advance();

    BuildFailureClassifier.defaultInstance().classifyAndStore(stores, buildId);

    var row = stores.builds().findById(buildId).orElseThrow();
    assertEquals(FailureCause.UNKNOWN.wire(), row.failureCause, "no signature matched → unknown");
    assertNull(row.failureCauseDetail, "UNKNOWN carries no snippet");
  }

  /**
   * Exit-code path (issue #1105 acceptance: "log + exit code"): a step OOM-killed with exit 137 but
   * NO {@code OutOfMemoryError} log line is still classified {@code oom} — proving the failing
   * node's exit code is read from its result JSON and threaded into the classifier end-to-end, not
   * just keyed on log text.
   */
  @Test
  void anOomKilledStepWithExit137ButNoOomLogIsClassifiedOom() throws Exception {
    bootstrap("diamond-dag.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance();
    UUID token = onlyClaimableTaskToken("build-s0");
    appendLog(token, "[INFO] Running integration tests");
    appendLog(token, "[INFO] worker process terminated"); // no OutOfMemoryError line
    completeClaimed("FAILED", "{\"exitCode\":137}");
    orch.advance();

    BuildFailureClassifier.defaultInstance().classifyAndStore(stores, buildId);

    var row = stores.builds().findById(buildId).orElseThrow();
    assertEquals(
        FailureCause.OOM.wire(), row.failureCause, "exit 137 → oom via exit-code fallback");
    assertNotNull(row.failureCauseDetail, "exit-code match stores a snippet");
    assertTrue(
        row.failureCauseDetail.contains("137"),
        "snippet names the exit code; was: " + row.failureCauseDetail);
  }

  // ── drive helpers (mirrors TitanFailureModelIT) ────────────────────────────

  private void bootstrap(String fixture) throws Exception {
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, Fixtures.load(fixture));
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(Fixtures.load(fixture));
  }

  private void completeClaimed(String status, String resultJson) {
    Optional<TaskQueueRow> claimed = stores.taskQueue().claim("stub", "default", UUID.randomUUID());
    assertTrue(claimed.isPresent(), "a dispatched EXECUTE_COMMAND task must be claimable");
    TaskQueueRow t = claimed.get();
    stores.taskQueue().complete(t.id, t.claimToken, status, resultJson);
  }

  private UUID onlyClaimableTaskToken(String nodeId) {
    for (TaskQueueRow t : stores.taskQueue().listByBuild(buildId)) {
      if (nodeId.equals(t.nodeId) && "EXECUTE_COMMAND".equals(t.type)) {
        return t.taskToken;
      }
    }
    throw new AssertionError("no EXECUTE_COMMAND task for node " + nodeId);
  }

  private void appendLog(UUID taskToken, String line) {
    LogRow row = new LogRow();
    row.taskId = taskToken;
    row.chunkIndex = 0;
    row.stream = "stdout";
    row.data = line;
    row.isFinal = true;
    stores.logs().insert(row);
  }

  private static boolean columnExists(Connection c, String table, String column) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT 1 FROM information_schema.columns "
                + "WHERE table_schema = 'titan' AND table_name = ? AND column_name = ?")) {
      ps.setString(1, table);
      ps.setString(2, column);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "fail/job-" + System.nanoTime());
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
}

package io.adaptiq.titan.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Issue #145 — the event-driven orchestrator wake-up on step completion, against real PostgreSQL
 * via Testcontainers (same harness as {@link WorkerConcurrencyTest}).
 *
 * <p>Failure shape being pinned: step completion used to be observed only by the controller's
 * <em>delayed</em> ADVANCE re-poll (5s base cadence). A step that finished just after a poll
 * checked waited a full extra cycle (~6s) before its verdict propagated — per step — which pushed
 * the failure-triage smoke's trigger→FAILURE past its 30s budget under concurrent e2e load. The
 * worker now enqueues an immediately-claimable {@code ORCHESTRATE/ADVANCE} row when it completes a
 * step task, so the controller's next 500ms tick folds the verdict.
 */
@Testcontainers
class AdvanceWakeupTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private WorkerDb db;
  private Path workspace;
  private Path libraries;

  @BeforeEach
  void setUp() throws Exception {
    Path migrations = Path.of("../titan-db-core/src/main/resources/io/adaptiq/titan/db/migration");
    assertTrue(
        Files.isDirectory(migrations),
        "engine migrations not found at " + migrations.toAbsolutePath());
    try (Connection c =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      TestMigrations.resetAndApply(c, migrations);
    }
    workspace = Files.createTempDirectory("titan-ws-wake");
    libraries = Files.createTempDirectory("titan-lib-wake");
    WorkerConfig cfg =
        new WorkerConfig(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            "wake-worker",
            "Wakeup Worker",
            "linux",
            1,
            "/titan",
            "NORMAL",
            "default",
            "synthesis",
            workspace,
            "",
            libraries,
            1000,
            10000,
            "",
            java.util.Map.of());
    db = new WorkerDb(cfg);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (db != null) {
      db.close();
    }
    for (Path root : new Path[] {workspace, libraries}) {
      if (root == null) {
        continue;
      }
      try (var paths = Files.walk(root)) {
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }

  private Connection conn() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /** Insert a real job + build pair ({@code task_queue.build_id} is FK-constrained to builds). */
  private long insertBuild(String jobName) throws Exception {
    try (Connection c = conn()) {
      long jobId;
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO titan.jobs (full_name, pipeline_script) VALUES (?, 'steps: []') "
                  + "RETURNING id")) {
        ps.setString(1, jobName);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          jobId = rs.getLong(1);
        }
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO titan.builds (job_id, build_number, status) "
                  + "VALUES (?, 1, 'RUNNING') RETURNING id")) {
        ps.setLong(1, jobId);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          return rs.getLong(1);
        }
      }
    }
  }

  private long enqueueStepTask(Long buildId, String traceParent) throws Exception {
    try (Connection c = conn();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.task_queue "
                    + "(type, queue_name, status, payload_json, build_id, trace_parent) "
                    + "VALUES ('EXECUTE_COMMAND', 'default', 'QUEUED', ?, ?, ?) RETURNING id")) {
      ps.setString(1, "{\"stepDescriptor\":\"sh\",\"arguments\":{\"script\":\"exit 0\"}}");
      if (buildId == null) {
        ps.setNull(2, java.sql.Types.BIGINT);
      } else {
        ps.setLong(2, buildId);
      }
      ps.setString(3, traceParent);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** The claim carries the row's {@code build_id} so the completion path can wake that build. */
  @Test
  void claimExposesBuildId() throws Exception {
    long buildId = insertBuild("wake/claim-exposes-build-id");
    enqueueStepTask(buildId, null);
    WorkerDb.ClaimedTask task = db.claim("wake-worker", "default").orElseThrow();
    assertEquals(buildId, task.buildId(), "claim must surface the task row's build_id");
  }

  /** A build-less row claims with a null buildId — the wake-up is skipped for those. */
  @Test
  void claimExposesNullBuildIdForBuildlessTask() throws Exception {
    enqueueStepTask(null, null);
    WorkerDb.ClaimedTask task = db.claim("wake-worker", "default").orElseThrow();
    assertNull(task.buildId(), "a build-less task must claim with buildId == null");
  }

  /**
   * The wake-up row is exactly what the controller's claim loop + AdvanceHandler consume: {@code
   * ORCHESTRATE} on the {@code default} queue, {@code QUEUED}, immediately claimable ({@code
   * available_at <= now} — the whole point: no 5s poll delay), an {@code
   * {"action":"ADVANCE","buildId":N}} payload, the build id on the row for the terminal-build fuse
   * and per-build dispatch grouping, and the trace context carried across the hop.
   */
  @Test
  void completedStepEnqueuesImmediateAdvanceForItsBuild() throws Exception {
    String traceParent = "00-11111111111111111111111111111111-2222222222222222-01";
    long buildId = insertBuild("wake/completed-step-advances");
    long stepId = enqueueStepTask(buildId, traceParent);
    WorkerDb.ClaimedTask task = db.claim("wake-worker", "default").orElseThrow();
    db.markProcessing(task.id(), task.claimToken());
    // A FAILED verdict must wake the orchestrator too — that IS the failure-triage path.
    assertTrue(db.complete(task.id(), task.claimToken(), "FAILED", "{\"exitCode\":1}"));

    db.enqueueAdvanceWakeup(task.buildId(), task.traceParent());

    try (Connection c = conn();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT type, queue_name, status, payload_json, build_id, trace_parent, "
                    + "(available_at <= CURRENT_TIMESTAMP) AS claimable_now "
                    + "FROM titan.task_queue WHERE type='ORCHESTRATE' AND build_id=?")) {
      ps.setLong(1, buildId);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "step completion must have enqueued an ORCHESTRATE wake-up row");
        assertEquals("default", rs.getString("queue_name"));
        assertEquals("QUEUED", rs.getString("status"));
        assertEquals(
            "{\"action\":\"ADVANCE\",\"buildId\":" + buildId + "}", rs.getString("payload_json"));
        assertEquals(buildId, rs.getLong("build_id"));
        assertEquals(traceParent, rs.getString("trace_parent"));
        assertTrue(
            rs.getBoolean("claimable_now"),
            "the wake-up must be claimable immediately — a delayed row would just be "
                + "the old 5s re-poll race again");
        assertTrue(!rs.next(), "exactly one wake-up row per completion");
      }
    }

    // The step row itself is untouched by the wake-up.
    try (Connection c = conn();
        PreparedStatement ps =
            c.prepareStatement("SELECT status FROM titan.task_queue WHERE id=?")) {
      ps.setLong(1, stepId);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals("FAILED", rs.getString(1));
      }
    }
  }
}

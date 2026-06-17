package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.queue.QueueProcessor;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for the two #489 follow-up gaps observed on the live rig:
 *
 * <ol>
 *   <li><b>task_archive population</b> — when an {@code EXECUTE_COMMAND} task COMPLETES, the live
 *       {@code titan.task_queue} row must be migrated into {@code titan.task_archive} preserving
 *       its {@code task_token}, so {@link io.adaptiq.titan.api.BuildLogsSse} (which UNIONs the two
 *       tables in {@link io.adaptiq.titan.store.TaskQueueDao#logTokensForBuild}) can still find the
 *       tokens that key the build's {@code titan.logs} chunks. Pre-fix the archive was always empty
 *       and the console streamed "No log output yet" for builds that demonstrably ran.
 *   <li><b>build.started_at preservation</b> — the orchestrator's terminal write ({@link
 *       TitanOrchestrator#finishIfDone}) used to pass {@code null} for {@code startedAt} into
 *       {@link io.adaptiq.titan.store.BuildDao#updateStatus}, which overwrote the value set at bake
 *       to NULL. The fixed SQL COALESCEs and the orchestrator passes the existing value through
 *       plus a computed {@code duration_ms}.
 * </ol>
 *
 * <p>Both fixes are exercised by driving the same diamond DAG used by {@link TitanOrchestratorIT}
 * to completion through a {@link QueueProcessor#tick(TitanStores, String, int)} loop + a stubbed
 * worker — the real engine path, not a synthetic write.
 */
@Testcontainers
class BuildArchiveAndStartedAtIT {

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
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, Fixtures.load("diamond-dag.yml"));
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(Fixtures.load("diamond-dag.yml"));
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  /**
   * After a full orchestrator-driven build, every COMPLETED task row must be archived (gone from
   * {@code task_queue}, present in {@code task_archive}) and the {@code task_token} preserved so
   * {@link io.adaptiq.titan.store.TaskQueueDao#logTokensForBuild} still finds them.
   */
  @Test
  void completedTasksMigrateToArchivePreservingTaskToken() throws Exception {
    driveToCompletion(0, null);

    int archived = countTaskArchive(buildId);
    int liveCompleted = countLiveCompleted(buildId);
    assertTrue(archived >= 1, "expected at least one archived task row, got " + archived);
    assertEquals(0, liveCompleted, "no COMPLETED rows should be left in task_queue");

    // The UNION read path the SSE uses must find the archived tokens.
    int tokens = stores.taskQueue().logTokensForBuild(buildId).size();
    assertEquals(
        archived + countLiveTasks(buildId),
        tokens,
        "every task row (archive + live) must contribute a token");
  }

  /**
   * Regression for the V19 fix: if {@code task_archive} already contains rows whose {@code id}
   * collides with the next {@code task_queue.id} (the live-rig pattern — {@code
   * rig/local/seed-data.sh} pre-seeds {@code task_archive} rows at ids 1..N, then a fresh build's
   * queue rows land at overlapping ids after a rig reset), the archive sweep must still succeed:
   * the destination's own id sequence mints a fresh key and the source task_token is preserved.
   *
   * <p>Pre-fix the sweep threw {@code duplicate key value violates unique constraint
   * "task_archive_pkey"} and the build's logs were unreachable via {@link
   * io.adaptiq.titan.store.TaskQueueDao#logTokensForBuild}.
   */
  @Test
  void archiveSweepSurvivesPreSeededIdCollision() throws Exception {
    // Pre-seed task_archive with rows whose ids cover every id task_queue could possibly mint
    // during this test. We INSERT id explicitly (the way the rig seed scripts do) so the
    // collision space is real.
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      for (long fakeId = 1; fakeId <= 200; fakeId++) {
        st.executeUpdate(
            "INSERT INTO titan.task_archive "
                + "(id, type, queue_name, status, priority, payload_json, attempts, "
                + " max_attempts, visibility_timeout_seconds, available_at, build_id, "
                + " task_token, created_at) "
                + "VALUES ("
                + fakeId
                + ", 'EXECUTE_COMMAND', 'default', 'COMPLETED', 0, '{}', 1, 3, 3600, "
                + " CURRENT_TIMESTAMP, -1, gen_random_uuid(), CURRENT_TIMESTAMP)");
      }
    }

    driveToCompletion(0, null);

    int archived = countTaskArchive(buildId);
    assertTrue(
        archived >= 1,
        "archive sweep must succeed despite pre-seeded id collisions, got " + archived);
    assertEquals(0, countLiveCompleted(buildId), "no COMPLETED rows should be left in task_queue");

    // The SSE UNION read path must still find the archived tokens for THIS build.
    int tokens = stores.taskQueue().logTokensForBuild(buildId).size();
    assertEquals(archived + countLiveTasks(buildId), tokens);
  }

  /**
   * Running the archive sweep twice on a build whose tasks are already in {@code task_archive} is a
   * no-op — no duplicate rows, no extra deletes. Idempotency guard.
   */
  @Test
  void archiveSweepIsIdempotent() throws Exception {
    driveToCompletion(0, null);

    int firstCount = countTaskArchive(buildId);
    assertTrue(firstCount >= 1);

    QueueProcessor proc = new QueueProcessor();
    proc.tick(stores, "stub-controller", 3600);
    proc.tick(stores, "stub-controller", 3600);

    int secondCount = countTaskArchive(buildId);
    assertEquals(firstCount, secondCount, "re-running the sweep must not duplicate archive rows");
  }

  /**
   * The build's {@code started_at} survives the terminal write and {@code duration_ms} is non-null,
   * matching {@code finished_at - started_at}. Pre-fix {@code started_at} was clobbered to NULL by
   * {@link io.adaptiq.titan.store.BuildDao#updateStatus} and {@code duration_ms} stayed NULL — the
   * UI rendered Duration as the "—" sentinel for builds that demonstrably ran.
   */
  @Test
  void buildStartedAtSurvivesTerminalWriteAndDurationIsComputed() throws Exception {
    // Before any advance — started_at was set at bake (activateIfQueued).
    BuildRow beforeFinish = stores.builds().findById(buildId).orElseThrow();
    assertNotNull(beforeFinish.startedAt, "started_at must be set at bake");
    assertEquals("RUNNING", beforeFinish.status);
    assertNull(beforeFinish.finishedAt);

    driveToCompletion(0, null);

    BuildRow done = stores.builds().findById(buildId).orElseThrow();
    assertEquals("SUCCESS", done.status);
    assertNotNull(done.startedAt, "started_at must be preserved across the terminal write");
    assertNotNull(done.finishedAt, "finished_at must be set on terminal write");
    assertNotNull(done.durationMs, "duration_ms must be computed on terminal write");
    long expectedMs = done.finishedAt.toEpochMilli() - done.startedAt.toEpochMilli();
    assertEquals(
        expectedMs, done.durationMs.longValue(), "duration_ms must equal finishedAt - startedAt");
  }

  // ---- drive loop (mirrors TitanOrchestratorIT but uses the QueueProcessor) ---

  /**
   * Drive the build to completion the same way the rig does: run real {@link
   * QueueProcessor#tick(TitanStores, String, int)} passes that handle ORCHESTRATE/ADVANCE tasks,
   * with a stubbed worker that claims and completes every dispatched EXECUTE_COMMAND.
   *
   * <p>An initial ADVANCE task is seeded so the processor has work on its first tick.
   */
  private void driveToCompletion(int unused, String failNodeId) throws Exception {
    seedAdvance(buildId);

    QueueProcessor proc = new QueueProcessor();
    for (int pass = 0; pass < 60; pass++) {
      proc.tick(stores, "stub-controller", 3600);

      // Stubbed worker — directly complete every QUEUED EXECUTE_COMMAND task. We bypass the
      // claim path (which uses FOR UPDATE SKIP LOCKED inside a Hikari-managed transaction and
      // can race with the orchestrator's own listByBuild reads in this single-threaded test)
      // and write the terminal state directly, the same shortcut TitanOrchestratorTimeoutIT
      // uses.
      try (Connection c = ds.getConnection();
          Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT id, node_id FROM titan.task_queue WHERE build_id = "
                      + buildId
                      + " AND type = 'EXECUTE_COMMAND' AND status = 'QUEUED'")) {
        java.util.List<long[]> toComplete = new java.util.ArrayList<>();
        java.util.List<String> nodeIds = new java.util.ArrayList<>();
        while (rs.next()) {
          toComplete.add(new long[] {rs.getLong(1)});
          nodeIds.add(rs.getString(2));
        }
        for (int i = 0; i < toComplete.size(); i++) {
          long tid = toComplete.get(i)[0];
          String nodeId = nodeIds.get(i);
          int exitCode = (nodeId != null && nodeId.equals(failNodeId)) ? 1 : 0;
          try (Statement upd = c.createStatement()) {
            upd.executeUpdate(
                "UPDATE titan.task_queue SET status = 'COMPLETED', "
                    + "result_json = '{\"exitCode\":"
                    + exitCode
                    + "}', completed_at = CURRENT_TIMESTAMP "
                    + "WHERE id = "
                    + tid);
          }
        }
      }

      // Make any delayed ADVANCE tasks immediately claimable so the test does not have to wait
      // for wall-clock seconds between passes.
      try (Connection c = ds.getConnection();
          Statement st = c.createStatement()) {
        st.executeUpdate(
            "UPDATE titan.task_queue SET available_at = CURRENT_TIMESTAMP "
                + "WHERE status = 'QUEUED' AND available_at > CURRENT_TIMESTAMP");
      }

      String status = stores.builds().findById(buildId).orElseThrow().status;
      if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
        // One more tick so the archive sweep runs after the final completion.
        proc.tick(stores, "stub-controller", 3600);
        return;
      }
    }
    // Diagnostic dump
    StringBuilder diag = new StringBuilder("build did not finish within 60 passes\n");
    diag.append("build status: ")
        .append(stores.builds().findById(buildId).orElseThrow().status)
        .append("\n");
    diag.append("tasks:\n");
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT id, type, queue_name, status, available_at, node_id FROM "
                    + "titan.task_queue WHERE build_id = "
                    + buildId
                    + " ORDER BY id")) {
      while (rs.next()) {
        diag.append("  id=")
            .append(rs.getLong(1))
            .append(" type=")
            .append(rs.getString(2))
            .append(" q=")
            .append(rs.getString(3))
            .append(" status=")
            .append(rs.getString(4))
            .append(" available_at=")
            .append(rs.getString(5))
            .append(" node=")
            .append(rs.getString(6))
            .append("\n");
      }
    }
    diag.append("flow_nodes:\n");
    for (FlowNodeRow n : stores.flowNodes().listByBuild(buildId)) {
      diag.append("  ").append(n.nodeId).append(" -> ").append(n.status).append("\n");
    }
    throw new AssertionError(diag.toString());
  }

  private void seedAdvance(long buildId) {
    stores
        .taskQueue()
        .enqueue(
            "ORCHESTRATE",
            "default",
            0,
            "{\"action\":\"ADVANCE\",\"buildId\":" + buildId + "}",
            3,
            3600,
            buildId,
            null);
  }

  // ---- helpers --------------------------------------------------------

  private int countTaskArchive(long buildId) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement("SELECT COUNT(*) FROM titan.task_archive WHERE build_id = ?")) {
      ps.setLong(1, buildId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private int countLiveCompleted(long buildId) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT COUNT(*) FROM titan.task_queue WHERE build_id = ? "
                    + "AND status IN ('COMPLETED','FAILED','CANCELLED')")) {
      ps.setLong(1, buildId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private int countLiveTasks(long buildId) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement("SELECT COUNT(*) FROM titan.task_queue WHERE build_id = ?")) {
      ps.setLong(1, buildId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  // ---- fixtures (mirror TitanOrchestratorIT) --------------------------

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "archive/job-" + System.nanoTime());
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

package io.adaptiq.titan.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TaskQueueDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Issue #49 — the task reaper reaped LIVE tasks mid-execution ("claim token stale" completion
 * rejections on a single-worker rig).
 *
 * <p>Root cause: the worker heartbeat refreshes {@code titan.agents.last_heartbeat}, but the
 * reaper's staleness predicate checked only {@code task_queue.claimed_at} — stamped once at claim
 * and never refreshed — so a task whose execution outlived the reap window was requeued (lease
 * token cleared) even though its claimant demonstrably lived; the task then ran twice and the
 * first, real completion was rejected as stale.
 *
 * <p>This IT drives the production reap path ({@link QueueProcessor#tick} and {@link
 * TaskQueueDao#reapStale}) against a real PostgreSQL via Testcontainers + the full Flyway set:
 *
 * <ul>
 *   <li><b>Happy path:</b> a task whose execution outlives the reap window on a heartbeating worker
 *       completes EXACTLY once, its completion is ACCEPTED, zero stale rejections — asserted on the
 *       {@code task_queue} rows and the build verdict.
 *   <li><b>Sad path (pins existing recovery):</b> a worker whose heartbeat genuinely stops has its
 *       task reaped, re-claimed by a peer, and the zombie's late completion rejected.
 *   <li><b>Idempotent-reaper bar:</b> every reap pass over unchanged state is a no-op.
 * </ul>
 */
@Testcontainers
class ReaperLiveTaskIT {

  /** Production reap window floor (mirrors QueueProcessorScheduler). */
  private static final int REAP_WINDOW_SECONDS = 3600;

  /** Heartbeat freshness window under which a claimant counts as alive (agent-reaper default). */
  private static final int WORKER_LIVENESS_SECONDS = 90;

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

  /**
   * Happy path: a step task whose execution outlives the reap window, on a worker that keeps
   * heartbeating, must survive every reap pass with its lease intact and complete EXACTLY once with
   * its completion ACCEPTED — zero "claim token stale" rejections.
   */
  @Test
  void longRunningTaskOnHeartbeatingWorker_completesExactlyOnce_completionAccepted()
      throws Exception {
    long buildId;
    long taskId;
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, "reaper-it/live-" + System.nanoTime());
      buildId = insertRunningBuild(c, jobId);
      taskId = insertQueuedStepTask(c, buildId, "linux");
    }

    // The worker registers (stamps last_heartbeat=now) and claims the task.
    stores.agents().register("worker-live", "worker-live", "linux", 1);
    UUID token = UUID.randomUUID();
    TaskQueueRow claimed =
        stores.taskQueue().claimExecuteCommand("worker-live", "linux", token).orElseThrow();
    assertEquals(taskId, claimed.id);
    assertEquals(1, stores.taskQueue().markProcessing(taskId, token));

    // Execution outlives the reap window: backdate claimed_at 2h — twice the 3600s floor.
    backdateClaimedAt(taskId, Instant.now().minusSeconds(2 * REAP_WINDOW_SECONDS));
    // …but the worker demonstrably lives: heartbeat is fresh.
    stores.agents().heartbeat("worker-live");

    // Drive the DAO reap primitive twice (idempotent-reaper bar) …
    TaskQueueDao.ReapResult first =
        stores.taskQueue().reapStale(REAP_WINDOW_SECONDS, WORKER_LIVENESS_SECONDS);
    TaskQueueDao.ReapResult second =
        stores.taskQueue().reapStale(REAP_WINDOW_SECONDS, WORKER_LIVENESS_SECONDS);
    assertEquals(0, first.requeued(), "live worker's task must NOT be requeued (#49)");
    assertEquals(0, first.failed(), "live worker's task must NOT be failed (#49)");
    assertEquals(0, second.requeued());
    assertEquals(0, second.failed());

    // … and the full production controller tick (reap + no-worker sweep + archive sweep).
    new QueueProcessor().tick(stores, "controller-it", REAP_WINDOW_SECONDS);

    // The lease survived every pass: same token, same claimant, still the FIRST attempt.
    TaskQueueRow mid = stores.taskQueue().findById(taskId).orElseThrow();
    assertEquals("PROCESSING", mid.status);
    assertEquals(token, mid.claimToken, "lease token must survive while the worker heartbeats");
    assertEquals("worker-live", mid.claimedBy);
    assertEquals(1, mid.attempts, "the task must never have been re-claimed");

    // The worker finishes: token-guarded completion is ACCEPTED — no stale rejection.
    int accepted = stores.taskQueue().complete(taskId, token, "COMPLETED", "{\"exitCode\":0}");
    assertEquals(1, accepted, "completion must be ACCEPTED — a rejection is the #49 bug");

    // Exactly once, across live queue + archive: one row for this build, COMPLETED, attempts=1.
    List<TaskQueueRow> allRows = stores.taskQueue().listByBuildIncludingArchive(buildId);
    assertEquals(1, allRows.size(), "exactly one task row — the task must not have been re-run");
    assertEquals("COMPLETED", allRows.get(0).status);
    assertEquals(1, allRows.get(0).attempts);
    assertNotNull(allRows.get(0).completedAt);

    // Build verdict: untouched by the reaper — still RUNNING for the orchestrator to fold.
    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("RUNNING", build.status, "the reaper must not have failed the build");
  }

  /**
   * Sad path (pins the pre-existing recovery contract): a worker whose heartbeat genuinely stops
   * MUST have its stale task reaped and re-claimable; the zombie's late completion is rejected by
   * the token guard and the peer's run is authoritative.
   */
  @Test
  void workerHeartbeatStops_taskIsReapedAndReclaimed_zombieCompletionRejected() throws Exception {
    long buildId;
    long taskId;
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, "reaper-it/dead-" + System.nanoTime());
      buildId = insertRunningBuild(c, jobId);
      taskId = insertQueuedStepTask(c, buildId, "linux");
    }

    stores.agents().register("worker-dead", "worker-dead", "linux", 1);
    UUID zombieToken = UUID.randomUUID();
    stores.taskQueue().claimExecuteCommand("worker-dead", "linux", zombieToken).orElseThrow();
    assertEquals(1, stores.taskQueue().markProcessing(taskId, zombieToken));

    // Claim outlives the window AND the heartbeat lapsed 10min ago — genuinely dead.
    backdateClaimedAt(taskId, Instant.now().minusSeconds(2 * REAP_WINDOW_SECONDS));
    backdateHeartbeat("worker-dead", Instant.now().minusSeconds(600));

    TaskQueueDao.ReapResult reaped =
        stores.taskQueue().reapStale(REAP_WINDOW_SECONDS, WORKER_LIVENESS_SECONDS);
    assertEquals(1, reaped.requeued(), "a dead worker's task MUST be reaped back to QUEUED");
    assertEquals(0, reaped.failed());

    TaskQueueRow requeued = stores.taskQueue().findById(taskId).orElseThrow();
    assertEquals("QUEUED", requeued.status);
    assertNull(requeued.claimToken, "the dead lease must be cleared");
    assertNull(requeued.claimedBy);

    // Idempotent: a second pass over the already-requeued state is a no-op.
    TaskQueueDao.ReapResult again =
        stores.taskQueue().reapStale(REAP_WINDOW_SECONDS, WORKER_LIVENESS_SECONDS);
    assertEquals(0, again.requeued());
    assertEquals(0, again.failed());

    // A peer re-claims and completes; the zombie's late completion is rejected (doc-27 G3).
    stores.agents().register("worker-peer", "worker-peer", "linux", 1);
    UUID peerToken = UUID.randomUUID();
    TaskQueueRow reclaimed =
        stores.taskQueue().claimExecuteCommand("worker-peer", "linux", peerToken).orElseThrow();
    assertEquals(taskId, reclaimed.id);
    assertEquals(2, reclaimed.attempts, "re-claim must count a second attempt");

    assertEquals(
        0,
        stores.taskQueue().complete(taskId, zombieToken, "COMPLETED", "{}"),
        "the zombie's stale token must be rejected");
    assertEquals(
        1,
        stores.taskQueue().complete(taskId, peerToken, "COMPLETED", "{\"exitCode\":0}"),
        "the peer's completion is authoritative");
    assertEquals("COMPLETED", stores.taskQueue().findById(taskId).orElseThrow().status);
  }

  // ── helpers ────────────────────────────────────────────────────────────

  private static long insertJob(Connection c, String fullName) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) "
                + "VALUES (?, 'stages: []', '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, fullName);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static long insertRunningBuild(Connection c, long jobId) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, started_at) "
                + "VALUES (?, 1, 'RUNNING', CURRENT_TIMESTAMP)",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, jobId);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private long insertQueuedStepTask(Connection c, long buildId, String queueName) {
    TaskQueueRow t = new TaskQueueRow();
    t.type = "EXECUTE_COMMAND";
    t.queueName = queueName;
    t.status = "QUEUED";
    t.priority = 0;
    t.payloadJson = "{\"command\":\"npm run build\"}";
    t.attempts = 0;
    t.maxAttempts = 3;
    t.visibilityTimeoutSeconds = 300;
    t.buildId = buildId;
    t.availableAt = Instant.now();
    return stores.taskQueue().insert(t);
  }

  private void backdateClaimedAt(long taskId, Instant claimedAt) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement("UPDATE titan.task_queue SET claimed_at = ? WHERE id = ?")) {
      ps.setTimestamp(1, Timestamp.from(claimedAt));
      ps.setLong(2, taskId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void backdateHeartbeat(String agentId, Instant lastHeartbeat) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement("UPDATE titan.agents SET last_heartbeat = ? WHERE agent_id = ?")) {
      ps.setTimestamp(1, Timestamp.from(lastHeartbeat));
      ps.setString(2, agentId);
      assertEquals(1, ps.executeUpdate());
    }
  }
}

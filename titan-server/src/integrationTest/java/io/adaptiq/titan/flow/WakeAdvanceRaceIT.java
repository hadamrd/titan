package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.queue.QueueProcessor;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Issue #147 — the worker's wake-ADVANCE (#146) must never let a FAILED step fold as SKIPPED.
 *
 * <p>Reproduces the failure-triage fixture shape (install → unit-test [fails] → build) with the
 * REAL {@link QueueProcessor} claim/dispatch/archive loop and a simulated worker that mimics {@code
 * TitanWorker.runTaskInner}: claim → markProcessing → complete (token-guarded) → wake enqueue.
 * Deliberately adversarial: the simulated worker issues the completion and the wake as TWO separate
 * auto-commit statements — the pre-#147 (weaker) ordering — so the orchestrator's fold discipline
 * is proven safe even WITHOUT the atomic completion+wake the production worker now uses ({@code
 * WorkerDb.completeAndWakeOrchestrator}). The wake makes "an ADVANCE runs within milliseconds of a
 * completion" the routine case, so this drives many paired builds through that window and asserts
 * the invariant from the #147 smoke signature: the unit-test stage/step terminal status is FAILED —
 * never SKIPPED — and the downstream build stage is SKIPPED, with the build FAILED.
 *
 * <p>Two topologies are exercised: the local rig's single serialized controller, and the
 * multi-controller topology the chaos rig pins (SKIP LOCKED contention — two claimable ADVANCE rows
 * for one build CAN be processed concurrently by two controllers).
 */
@Testcontainers
class WakeAdvanceRaceIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  /** The failure-triage fixture shape: install → unit-test (FAILS) → build (must be SKIPPED). */
  private static final String TRIAGE_PIPELINE =
      "stages:\n"
          + "  - stage: install\n"
          + "    steps:\n"
          + "      - sh: echo ok\n"
          + "  - stage: unit-test\n"
          + "    dependsOn: [install]\n"
          + "    steps:\n"
          + "      - sh: exit 1\n"
          + "  - stage: build\n"
          + "    dependsOn: [unit-test]\n"
          + "    steps:\n"
          + "      - sh: echo built\n";

  private HikariDataSource ds;
  private TitanStores stores;

  @BeforeEach
  void setUp() throws Exception {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
    cfg.setUsername(POSTGRES.getUsername());
    cfg.setPassword(POSTGRES.getPassword());
    cfg.setDriverClassName("org.postgresql.Driver");
    cfg.setMaximumPoolSize(16);
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
    // A live ONLINE agent serving "default" — keeps UnschedulableStepGuard and
    // NoWorkerTimeoutSweeper quiet, exactly like the healthy rig.
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.executeUpdate(
          "INSERT INTO titan.agents (agent_id, display_name, labels, num_executors, remote_fs, "
              + "usage_mode, status, registered_at, last_heartbeat) VALUES "
              + "('race-worker', 'Race Worker', 'default', 4, '/titan', 'NORMAL', 'ONLINE', "
              + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  /**
   * Local-rig topology: ONE controller ticking the real {@link QueueProcessor}, one simulated
   * worker with completion+wake. Every build must fold unit-test FAILED — never SKIPPED.
   */
  @Test
  void failedStepNeverFoldsSkippedSingleController() throws Exception {
    runRace(1, 12);
  }

  /**
   * Multi-controller topology (k3s / chaos-rig shape, design/26 Tier B): the wake row and the
   * delayed re-poll row can be claimed by DIFFERENT controllers in the same instant, so two
   * advance() passes for one build run concurrently. The CAS discipline must still never fold a
   * FAILED step (or its stage) as SKIPPED.
   */
  @Test
  void failedStepNeverFoldsSkippedTwoControllers() throws Exception {
    runRace(2, 12);
  }

  private void runRace(int controllers, int rounds) throws Exception {
    AtomicBoolean running = new AtomicBoolean(true);
    AtomicReference<Throwable> workerFailure = new AtomicReference<>();

    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < controllers; i++) {
      QueueProcessor processor = new QueueProcessor();
      String id = "race-ctl-" + i;
      Thread ctl =
          new Thread(
              () -> {
                while (running.get()) {
                  try {
                    processor.tick(stores, id, 3600);
                  } catch (RuntimeException e) {
                    // tick is self-healing; keep driving
                  }
                  try {
                    new io.adaptiq.titan.timer.TimerSweepWorker().sweep(stores);
                  } catch (RuntimeException e) {
                    // ditto
                  }
                  sleepQuiet(25);
                }
              },
              id);
      ctl.setDaemon(true);
      threads.add(ctl);
    }
    // Two worker threads ≈ the rig's multi-executor worker under paired-spec contention.
    for (int i = 0; i < 2; i++) {
      Thread w = new Thread(this::workerLoop, "race-worker-" + i);
      w.setDaemon(true);
      w.setUncaughtExceptionHandler((t, e) -> workerFailure.compareAndSet(null, e));
      threads.add(w);
    }
    threads.forEach(Thread::start);

    try {
      for (int round = 0; round < rounds; round++) {
        // The smoke failure fired with the two golden-path specs PAIRED — two builds of the
        // same fixture in the same second, contending for the worker slots. Mirror that.
        long[] buildIds = {seedBuild(round * 2), seedBuild(round * 2 + 1)};
        for (long buildId : buildIds) {
          new TitanFlowExecution(stores, buildId).bake(TRIAGE_PIPELINE);
          enqueueInitialAdvance(buildId);
        }

        for (long buildId : buildIds) {
          long deadline = System.currentTimeMillis() + 60_000L;
          String status;
          while (true) {
            status = stores.builds().findById(buildId).orElseThrow().status;
            if ("SUCCESS".equals(status) || "FAILED".equals(status) || "ABORTED".equals(status)) {
              break;
            }
            if (System.currentTimeMillis() > deadline) {
              fail(
                  "round "
                      + round
                      + ": build "
                      + buildId
                      + " not terminal within 60s (status="
                      + status
                      + ") — nodes: "
                      + describeNodes(buildId));
            }
            sleepQuiet(20);
          }
          if (workerFailure.get() != null) {
            throw new AssertionError("simulated worker died", workerFailure.get());
          }

          assertEquals(
              "FAILED",
              status,
              "round "
                  + round
                  + ": the fixture has an intentionally failing unit-test — build must be FAILED."
                  + " nodes: "
                  + describeNodes(buildId));
          assertNode(buildId, round, "unit-test", "FAILED");
          assertNode(buildId, round, "unit-test-s0", "FAILED");
          assertNode(buildId, round, "install", "SUCCESS");
          assertNode(buildId, round, "install-s0", "SUCCESS");
          assertNode(buildId, round, "build", "SKIPPED");
        }
      }
    } finally {
      running.set(false);
      for (Thread t : threads) {
        t.join(5_000L);
      }
    }
  }

  private void assertNode(long buildId, int round, String nodeId, String expected) {
    FlowNodeRow node = stores.flowNodes().findByBuildAndNode(buildId, nodeId).orElseThrow();
    assertEquals(
        expected,
        node.status,
        "round "
            + round
            + ": node '"
            + nodeId
            + "' expected "
            + expected
            + " but was "
            + node.status
            + " — nodes: "
            + describeNodes(buildId));
  }

  private String describeNodes(long buildId) {
    StringBuilder sb = new StringBuilder();
    for (FlowNodeRow n : stores.flowNodes().listByBuild(buildId)) {
      sb.append(n.nodeId).append('=').append(n.status).append(' ');
    }
    return sb.toString();
  }

  /**
   * The simulated worker — bit-for-bit the TitanWorker.runTaskInner order: claim (SKIP LOCKED,
   * lease token) → markProcessing → jittered "execution" → token-guarded complete → #146 wake
   * enqueue. The unit-test step exits 1; everything else exits 0.
   */
  private void workerLoop() {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        Optional<TaskQueueRow> claimed =
            stores.taskQueue().claimExecuteCommand("race-worker", "default", UUID.randomUUID());
        if (claimed.isEmpty()) {
          sleepQuiet(3);
          if (!stillWanted()) {
            return;
          }
          continue;
        }
        TaskQueueRow t = claimed.get();
        stores.taskQueue().markProcessing(t.id, t.claimToken);
        // Simulated subprocess time — mirrors the rig's execution:tick ratio (vitest 2-5s
        // against a 1s tick ≈ 100-250ms against the 25ms tick here), with jitter so the
        // completion lands in every phase of the controller's tick (claim, dispatch, archive).
        sleepQuiet(ThreadLocalRandom.current().nextInt(30, 220));
        int exit = t.payloadJson != null && t.payloadJson.contains("exit 1") ? 1 : 0;
        boolean accepted =
            stores
                    .taskQueue()
                    .complete(t.id, t.claimToken, "COMPLETED", "{\"exitCode\":" + exit + "}")
                == 1;
        if (accepted && t.buildId != null) {
          enqueueAdvanceWakeup(t.buildId, t.traceParent);
        }
        heartbeat();
      } catch (RuntimeException e) {
        if (!stillWanted()) {
          return;
        }
        sleepQuiet(10);
      } catch (Exception e) {
        throw new IllegalStateException("worker loop failed", e);
      }
    }
  }

  private boolean stillWanted() {
    return !Thread.currentThread().isInterrupted() && ds != null && !ds.isClosed();
  }

  /** Same INSERT as {@code WorkerDb.enqueueAdvanceWakeup} — the #146 wake row. */
  private void enqueueAdvanceWakeup(long buildId, String traceParent) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.task_queue (type, queue_name, status, priority, "
                    + "payload_json, attempts, max_attempts, visibility_timeout_seconds, "
                    + "build_id, trace_parent) "
                    + "VALUES ('ORCHESTRATE', 'default', 'QUEUED', 0, ?, 0, 3, 3600, ?, ?)")) {
      ps.setString(1, "{\"action\":\"ADVANCE\",\"buildId\":" + buildId + "}");
      ps.setLong(2, buildId);
      ps.setString(3, traceParent);
      ps.executeUpdate();
    }
  }

  private void heartbeat() throws Exception {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.executeUpdate(
          "UPDATE titan.agents SET last_heartbeat=CURRENT_TIMESTAMP WHERE agent_id='race-worker'");
    }
  }

  private void enqueueInitialAdvance(long buildId) {
    TaskQueueRow advance = new TaskQueueRow();
    advance.type = "ORCHESTRATE";
    advance.queueName = "default";
    advance.status = "QUEUED";
    advance.priority = 0;
    advance.payloadJson = "{\"action\":\"ADVANCE\",\"buildId\":" + buildId + "}";
    advance.attempts = 0;
    advance.maxAttempts = 3;
    advance.visibilityTimeoutSeconds = 3600;
    advance.buildId = buildId;
    stores.taskQueue().insert(advance);
  }

  private static void sleepQuiet(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private long seedBuild(int round) throws Exception {
    try (Connection c = ds.getConnection()) {
      long jobId;
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) "
                  + "VALUES (?, ?, '{}')",
              Statement.RETURN_GENERATED_KEYS)) {
        ps.setString(1, "wake-race/" + round + "/" + System.nanoTime());
        ps.setString(2, TRIAGE_PIPELINE);
        ps.executeUpdate();
        try (ResultSet keys = ps.getGeneratedKeys()) {
          keys.next();
          jobId = keys.getLong(1);
        }
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO titan.builds (job_id, build_number, status, started_at) "
                  + "VALUES (?, ?, 'RUNNING', CURRENT_TIMESTAMP)",
              Statement.RETURN_GENERATED_KEYS)) {
        ps.setLong(1, jobId);
        ps.setInt(2, round + 1);
        ps.executeUpdate();
        try (ResultSet keys = ps.getGeneratedKeys()) {
          keys.next();
          return keys.getLong(1);
        }
      }
    }
  }
}

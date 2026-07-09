package io.adaptiq.titan.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.BuildAbortService;
import io.adaptiq.titan.flow.TitanOrchestrator;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.ApprovalRow;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Adversarial ITs for issue #68 — the cancel-closure half: after a build is aborted, {@code
 * titan.task_queue} must drain to ZERO live rows and STAY drained, even when a straggler
 * ORCHESTRATE task raced the abort sweep.
 *
 * <p>Live-rig post-mortem this pins (build 365, 2026-07-08): {@code BuildAbortService.abort}
 * cancelled the task rows it could see, but a SYNTHESIZE poll inserted concurrently by an in-flight
 * handler survived — and because no handler checked the build's terminal status, that one surviving
 * task re-dispatched worker synthesis, re-baked the DAG and re-armed ADVANCE loops on the ABORTED
 * build. Spec 51's "task_queue still has live rows for build N after queued-cancel" was the visible
 * symptom. The fix is two-layered and both layers are asserted here:
 *
 * <ol>
 *   <li>{@code QueueProcessor.dispatch}'s terminal-build fuse — an ORCHESTRATE task claimed for a
 *       terminal (or deleted) build is CANCELLED at claim time, never handled, so it can spawn no
 *       follow-ups (idempotent, self-healing drain).
 *   <li>{@code BuildAbortService}'s second cancel sweep after the terminal status flip — closes the
 *       insert-race window the first sweep cannot see.
 * </ol>
 *
 * <p>Also pinned: abort closes PENDING approval rows (zombie-inbox fix) and the per-build
 * serialisation invariant of the new grouped-parallel dispatch (#68 throughput fix).
 */
@Testcontainers
class CancelClosureIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String SLEEP_PIPELINE =
      "stages:\n  - stage: slow\n    steps:\n      - sh: |\n          sleep 60\n";

  private static final Set<String> TASK_LIVE = Set.of("QUEUED", "CLAIMED", "PROCESSING");

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
   * The spec-51 queued-cancel shape: a build cancelled while still QUEUED (its ORCHESTRATE
   * SYNTHESIZE task not yet claimed) must go straight to ABORTED with {@code startedAt} null, and
   * one QueueProcessor tick later there must be ZERO rows — live or terminal — left in {@code
   * task_queue} for the build (terminal rows are archived by the per-tick sweep).
   */
  @Test
  void queuedCancel_leavesZeroTaskQueueRows_andBuildNeverStarts() throws Exception {
    long jobId = insertJob(SLEEP_PIPELINE);
    long buildId = insertBuild(jobId, "QUEUED");
    enqueueOrchestrate(buildId, "SYNTHESIZE", 0);

    BuildAbortService.AbortOutcome outcome = BuildAbortService.abort(stores, buildId, "e2e-51");
    assertTrue(outcome.aborted(), outcome.message());

    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("ABORTED", build.status);
    assertNull(build.startedAt, "queued-cancel must never pass through RUNNING");

    new QueueProcessor().tick(stores, "cancel-closure-it", 3600);

    assertEquals(
        0,
        stores.taskQueue().listByBuild(buildId).size(),
        "task_queue must be fully drained (cancelled rows archived) after one tick: "
            + describeRows(stores.taskQueue().listByBuild(buildId)));
  }

  /**
   * The build-365 resurrection race: ORCHESTRATE tasks inserted AFTER the abort (simulating an
   * in-flight handler's follow-up enqueues that beat the first cancel sweep) must be cancelled at
   * claim time by the terminal-build fuse — never handled. Handled would mean: a worker synthesis
   * EXECUTE_COMMAND appears, a BAKE follow-up appears, ADVANCE re-arms. None may happen, and the
   * queue must drain to zero rows for the build.
   */
  @Test
  void stragglerOrchestrateTasksAfterAbort_areCancelledAtClaim_neverResurrectTheBuild()
      throws Exception {
    long jobId = insertJob(SLEEP_PIPELINE);
    long buildId = insertBuild(jobId, "RUNNING");

    BuildAbortService.AbortOutcome outcome = BuildAbortService.abort(stores, buildId, "e2e-51");
    assertTrue(outcome.aborted(), outcome.message());

    // Adversarial: the racing follow-ups land AFTER both abort sweeps completed.
    enqueueOrchestrate(buildId, "SYNTHESIZE", 0);
    enqueueOrchestrate(buildId, "ADVANCE", 0);
    enqueueOrchestrate(buildId, "BAKE", 0);

    // Two ticks: the first cancels the stragglers at claim + archives; the second proves the
    // drain is terminal (no handler ran, so nothing re-armed).
    QueueProcessor processor = new QueueProcessor();
    processor.tick(stores, "cancel-closure-it", 3600);
    processor.tick(stores, "cancel-closure-it", 3600);

    List<TaskQueueRow> all = stores.taskQueue().listByBuildIncludingArchive(buildId);
    assertTrue(
        all.stream().noneMatch(t -> "EXECUTE_COMMAND".equals(t.type)),
        "no worker synthesis / step task may be dispatched for an aborted build: "
            + describeRows(all));
    assertEquals(
        3,
        all.stream().filter(t -> "ORCHESTRATE".equals(t.type)).count(),
        "the fuse must not spawn follow-up ORCHESTRATE tasks: " + describeRows(all));
    assertTrue(
        all.stream()
            .filter(t -> "ORCHESTRATE".equals(t.type))
            .allMatch(t -> "CANCELLED".equals(t.status)),
        "every straggler must be CANCELLED, not COMPLETED/FAILED (it was never handled): "
            + describeRows(all));
    assertEquals(
        0,
        stores.taskQueue().listByBuild(buildId).stream()
            .filter(t -> TASK_LIVE.contains(t.status))
            .count(),
        "zero live task rows after the drain");
    assertEquals(
        "ABORTED",
        stores.builds().findById(buildId).orElseThrow().status,
        "the fuse must never touch the build's terminal status");
  }

  /** Abort must close PENDING approval rows — and stay idempotent on a double-abort. */
  @Test
  void abort_closesPendingApprovals_andDoubleAbortIsANoOp() throws Exception {
    long jobId = insertJob(SLEEP_PIPELINE);
    long buildId = insertBuild(jobId, "RUNNING");
    long approvalId = insertPendingApproval(buildId, "deploy-s0");

    BuildAbortService.AbortOutcome first = BuildAbortService.abort(stores, buildId, "alice");
    assertTrue(first.aborted());

    ApprovalRow row = stores.approvals().findById(approvalId).orElseThrow();
    assertEquals("REJECTED", row.status, "abort must terminally close the PENDING approval");
    assertTrue(
        row.decidedBy != null && row.decidedBy.contains("aborted by alice"),
        "the closure must record the aborting actor: " + row.decidedBy);
    assertEquals(
        0,
        stores.approvals().listForBuild(buildId).stream()
            .filter(a -> "PENDING".equals(a.status))
            .count(),
        "no PENDING approval may outlive its build");

    BuildAbortService.AbortOutcome second = BuildAbortService.abort(stores, buildId, "mallory");
    assertFalse(second.aborted(), "double-abort must be a no-op");
    assertEquals(
        "REJECTED",
        stores.approvals().findById(approvalId).orElseThrow().status,
        "the no-op abort must not rewrite the closed row");
  }

  /**
   * An abort must NOT clobber an approval a human already decided — the decideIfPending CAS guard
   * makes the human's terminal status win.
   */
  @Test
  void abort_neverRewritesAnAlreadyDecidedApproval() throws Exception {
    long jobId = insertJob(SLEEP_PIPELINE);
    long buildId = insertBuild(jobId, "RUNNING");
    long approvalId = insertPendingApproval(buildId, "deploy-s0");
    assertEquals(
        1, stores.approvals().decideIfPending(approvalId, "APPROVED", "bob", Instant.now()));

    BuildAbortService.abort(stores, buildId, "alice");

    ApprovalRow row = stores.approvals().findById(approvalId).orElseThrow();
    assertEquals("APPROVED", row.status, "the human decision must survive the abort sweep");
    assertEquals("bob", row.decidedBy);
  }

  /** The terminal fuse must be inert for live builds — the SYNTHESIZE chain still runs. */
  @Test
  void terminalFuse_doesNotEatTasksOfLiveBuilds() throws Exception {
    long jobId = insertJob(SLEEP_PIPELINE);
    long buildId = insertBuild(jobId, "QUEUED");
    enqueueOrchestrate(buildId, "SYNTHESIZE", 0);

    new QueueProcessor().tick(stores, "cancel-closure-it", 3600);

    assertTrue(
        stores.taskQueue().findLatestSynthesisTask(buildId).isPresent(),
        "a live build's SYNTHESIZE must dispatch worker synthesis, not be cancelled");
  }

  /**
   * The #68 grouped-parallel dispatch: same-build tasks are handled serially on ONE thread (per-
   * build ordering invariant), while the whole seeded batch is processed in a single tick.
   */
  @Test
  void parallelDispatch_preservesPerBuildSerialisation() throws Exception {
    long jobId = insertJob(SLEEP_PIPELINE);
    long[] builds = {
      insertBuild(jobId, "RUNNING"), insertBuild(jobId, "RUNNING"), insertBuild(jobId, "RUNNING")
    };
    for (long b : builds) {
      for (int i = 0; i < 3; i++) {
        enqueueOrchestrate(b, "ADVANCE", 0);
      }
    }

    Map<Long, Set<String>> threadsPerBuild = new ConcurrentHashMap<>();
    Map<Long, AtomicInteger> countPerBuild = new ConcurrentHashMap<>();
    QueueProcessor processor =
        new QueueProcessor(
            NoWorkerTimeoutSweeper.fromEnv(),
            new QueueHandlerSupport(() -> null),
            (daos, b) -> {
              threadsPerBuild
                  .computeIfAbsent(b, k -> ConcurrentHashMap.newKeySet())
                  .add(Thread.currentThread().getName());
              countPerBuild.computeIfAbsent(b, k -> new AtomicInteger()).incrementAndGet();
              // parked => no re-arm, keeps the queue clean for the drain assertion.
              return new TitanOrchestrator.AdvanceResult(0, 0, false, null, true);
            });

    int processed = processor.tick(stores, "cancel-closure-it", 3600);
    assertEquals(9, processed, "one tick must process the whole 9-task batch");
    for (long b : builds) {
      assertEquals(3, countPerBuild.get(b).get(), "every task of build " + b + " must be handled");
      assertEquals(
          1,
          threadsPerBuild.get(b).size(),
          "build " + b + " must be serialised on one thread, saw: " + threadsPerBuild.get(b));
    }
    assertEquals(
        0,
        stores.taskQueue().listByBuild(builds[0]).size()
            + stores.taskQueue().listByBuild(builds[1]).size()
            + stores.taskQueue().listByBuild(builds[2]).size(),
        "parked builds re-arm nothing — the queue drains in the same tick");
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private long insertJob(@NonNull String pipelineYaml) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) "
                    + "VALUES (?, ?, '{}')",
                Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "cancel-closure/job-" + System.nanoTime());
      ps.setString(2, pipelineYaml);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private final AtomicInteger buildNumbers = new AtomicInteger();

  private long insertBuild(long jobId, @NonNull String status) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.builds (job_id, build_number, status) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, jobId);
      ps.setInt(2, buildNumbers.incrementAndGet());
      ps.setString(3, status);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private void enqueueOrchestrate(long buildId, @NonNull String action, int delaySeconds) {
    TaskQueueRow t = new TaskQueueRow();
    t.type = "ORCHESTRATE";
    t.queueName = "default";
    t.status = "QUEUED";
    t.priority = 0;
    t.payloadJson = "{\"action\":\"" + action + "\",\"buildId\":" + buildId + "}";
    t.attempts = 0;
    t.maxAttempts = 3;
    t.visibilityTimeoutSeconds = 3600;
    t.buildId = buildId;
    t.availableAt = Instant.now().plusSeconds(delaySeconds);
    stores.taskQueue().insert(t);
  }

  private long insertPendingApproval(long buildId, @NonNull String nodeId) {
    ApprovalRow row = new ApprovalRow();
    row.buildId = buildId;
    row.flowNodeId = nodeId;
    row.prompt = "Deploy to prod?";
    row.approversJson = "[]";
    row.expiresAt = Instant.now().plusSeconds(86_400);
    return stores.approvals().insertPending(row);
  }

  @NonNull
  private static String describeRows(@NonNull List<TaskQueueRow> rows) {
    StringBuilder sb = new StringBuilder("[");
    for (TaskQueueRow t : rows) {
      sb.append("{id=")
          .append(t.id)
          .append(" type=")
          .append(t.type)
          .append(" status=")
          .append(t.status)
          .append(" payload=")
          .append(t.payloadJson)
          .append("} ");
    }
    return sb.append("]").toString();
  }
}

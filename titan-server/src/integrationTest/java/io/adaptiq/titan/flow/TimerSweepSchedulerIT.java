package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.queue.QueueProcessor;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.ApprovalRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import io.adaptiq.titan.timer.TimerSweepScheduler;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Adversarial ITs for issue #81 — approval timeouts driven through the PRODUCTION scheduling
 * surface, {@link TimerSweepScheduler#tick()}, the exact method Quarkus invokes every 5s.
 *
 * <p>Why not call {@code ApprovalService.sweepTimedOut} (or even {@code TimerSweepWorker.sweep})
 * directly, like the pre-#81 ITs did? Because that is precisely what let the rot go invisible: the
 * sweep logic was green in every IT while NOTHING in production ever scheduled it, so {@code
 * approval: { timeout: 5s }} gates parked PENDING forever on every rig (spec 25's timeout path).
 * This IT enters through the scheduler bean so the whole production chain — scheduler tick → worker
 * sweep → DAO CAS → ADVANCE enqueue → orchestrator resume — is under test.
 *
 * <p>Pinned:
 *
 * <ol>
 *   <li><b>Timeout flips through the scheduler:</b> a parked PENDING approval whose deadline has
 *       passed goes TIMED_OUT on the next {@code tick()}, and the build fails through the normal
 *       queue loop. A second tick is a no-op (idempotent — repo bar for reapers).
 *   <li><b>Human decision wins:</b> an approval decided a moment before the deadline is NOT flipped
 *       by a later sweep — the {@code WHERE status = 'PENDING'} CAS (#72) protects it, and the
 *       build completes SUCCESS.
 *   <li><b>Empty-table safety:</b> ticking with zero approvals and zero timers is a clean no-op.
 * </ol>
 */
@Testcontainers
class TimerSweepSchedulerIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  /** The spec-25 pipeline shape, verbatim (25-approval-flow.spec.ts / PIPELINE_LONG). */
  private static final String APPROVAL_PIPELINE =
      """
      stages:
        - stage: build
          steps:
            - sh: echo built
        - stage: deploy
          dependsOn: [build]
          steps:
            - approval:
                prompt: "Deploy to prod?"
                timeout: 24h
            - sh: echo deployed
      """;

  private static final String APPROVAL_NODE = "deploy-s0";
  private static final String CONTROLLER = "timer-sweep-scheduler-it";

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
      long jobId = insertJob(c, APPROVAL_PIPELINE);
      buildId = insertRunningBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(APPROVAL_PIPELINE);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  /**
   * The #81 pin: deadline passes → the PRODUCTION scheduler tick flips the row TIMED_OUT and the
   * build fails through the normal queue loop. Re-ticking flips nothing twice.
   */
  @Test
  void expiredApproval_flipsTimedOutAndFailsBuild_viaProductionSchedulerTick() {
    QueueProcessor processor = new QueueProcessor();
    TimerSweepScheduler scheduler = new TimerSweepScheduler(stores);
    ApprovalRow row = parkAtGate(processor);

    // Deadline passes (backdated so the IT does not sleep out a real timeout).
    stores.approvals().testOnlySetExpiresAt(row.id, Instant.now().minusSeconds(1));

    // THE production entry point — the method Quarkus @Scheduled invokes. Not sweepTimedOut().
    scheduler.tick();

    ApprovalRow after = stores.approvals().findById(row.id).orElseThrow();
    assertEquals("TIMED_OUT", after.status, "the scheduler tick must flip the expired row");
    assertEquals("<timeout>", after.decidedBy, "the synthetic decider marks a sweep flip");

    // Idempotent: a second tick against the already-flipped row is a no-op (no double-decide,
    // no extra ADVANCE churn beyond what the queue loop drains anyway).
    scheduler.tick();
    ApprovalRow again = stores.approvals().findById(row.id).orElseThrow();
    assertEquals("TIMED_OUT", again.status);
    assertEquals(after.decidedAt, again.decidedAt, "re-sweep must not re-stamp the decision");

    driveUntilTerminal(processor);
    assertEquals(
        "FAILED",
        stores.builds().findById(buildId).orElseThrow().status,
        "a timed-out approval must fail the build through the normal queue loop");
    assertTrue(
        stores.approvals().listByStatus("TIMED_OUT", 200, 0).stream().anyMatch(a -> a.id == row.id),
        "the row must surface in the TIMED_OUT list (spec 25's API oracle)");
    assertEquals(
        0,
        stores.approvals().listByStatus("PENDING", 200, 0).stream()
            .filter(a -> a.buildId == buildId)
            .count(),
        "the PENDING list must be clean after the sweep");
  }

  /**
   * Sad path: a human decides a moment BEFORE the deadline; the sweep that runs after the deadline
   * must NOT overwrite the decision — the {@code WHERE status = 'PENDING'} CAS (#72) means the
   * human decision wins and the build completes SUCCESS.
   */
  @Test
  void approvalDecidedBeforeDeadline_isNotFlippedByLaterSweep() {
    QueueProcessor processor = new QueueProcessor();
    TimerSweepScheduler scheduler = new TimerSweepScheduler(stores);
    ApprovalRow row = parkAtGate(processor);

    // Human decides first...
    ApprovalService.DecisionOutcome outcome =
        ApprovalService.decide(
            stores, row.id, "alice", Set.of("alice"), ApprovalService.Decision.APPROVED);
    assertTrue(outcome.applied(), outcome.message());

    // ...then the deadline passes and the production sweep runs.
    stores.approvals().testOnlySetExpiresAt(row.id, Instant.now().minusSeconds(1));
    scheduler.tick();

    ApprovalRow after = stores.approvals().findById(row.id).orElseThrow();
    assertEquals("APPROVED", after.status, "the sweep must never overwrite a human decision");
    assertEquals("alice", after.decidedBy, "the human stays the decider — not '<timeout>'");

    driveUntilTerminal(processor);
    assertEquals(
        "SUCCESS",
        stores.builds().findById(buildId).orElseThrow().status,
        "an approved build must complete SUCCESS even if a sweep ran past the old deadline");
    assertEquals(
        "SUCCESS",
        stores.flowNodes().findByBuildAndNode(buildId, "deploy-s1").orElseThrow().status,
        "the post-approval step must really run");
  }

  /** Empty-table safety: ticking with nothing to sweep must be a clean no-op. */
  @Test
  void tickWithNoApprovalsAndNoTimers_isANoOp() {
    // Note: setUp baked a build but nothing is parked yet — approvals and timers are both empty.
    stores.approvals().deleteByBuild(buildId);
    new TimerSweepScheduler(stores).tick();
    assertEquals(0, stores.approvals().countByStatus("TIMED_OUT"));
  }

  // ── helpers (mirrors ApprovalGateParkIT) ───────────────────────────────────

  /** Drive to the parked-at-gate state and return the PENDING row. */
  @NonNull
  private ApprovalRow parkAtGate(@NonNull QueueProcessor processor) {
    enqueueAdvance(0);
    processor.tick(stores, CONTROLLER, 3600);
    completeWorkerTasks();
    enqueueAdvance(0);
    processor.tick(stores, CONTROLLER, 3600);
    ApprovalRow row = stores.approvals().findLatestForNode(buildId, APPROVAL_NODE).orElseThrow();
    assertEquals("PENDING", row.status, "pre-condition: the gate must be parked PENDING");
    return row;
  }

  /**
   * Tick + stub-worker loop until the build is terminal, promoting delayed re-arm rows to
   * available-now so the test does not sleep out the 5s backoff base.
   */
  private void driveUntilTerminal(@NonNull QueueProcessor processor) {
    for (int i = 0; i < 40; i++) {
      promoteDelayedAdvances();
      processor.tick(stores, CONTROLLER, 3600);
      if (completeWorkerTasks() > 0) {
        enqueueAdvance(0);
      }
      String status = stores.builds().findById(buildId).orElseThrow().status;
      if ("SUCCESS".equals(status) || "FAILED".equals(status) || "ABORTED".equals(status)) {
        return;
      }
    }
    throw new AssertionError(
        "build "
            + buildId
            + " never reached terminal — status="
            + stores.builds().findById(buildId).orElseThrow().status);
  }

  /** Time machine: make every delayed QUEUED ORCHESTRATE row for this build claimable now. */
  private void promoteDelayedAdvances() {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE titan.task_queue SET available_at = NOW() "
                    + "WHERE build_id = ? AND status = 'QUEUED' AND type = 'ORCHESTRATE'")) {
      ps.setLong(1, buildId);
      ps.executeUpdate();
    } catch (Exception e) {
      throw new AssertionError("could not promote delayed ADVANCE rows", e);
    }
  }

  /** Stub worker: claim + complete every queued EXECUTE_COMMAND for this build's queues. */
  private int completeWorkerTasks() {
    int completed = 0;
    for (String queue : queuesWithWork()) {
      Optional<TaskQueueRow> claimed;
      while ((claimed = stores.taskQueue().claimExecuteCommand("stub", queue, UUID.randomUUID()))
          .isPresent()) {
        TaskQueueRow t = claimed.get();
        stores.taskQueue().complete(t.id, t.claimToken, "COMPLETED", "{\"exitCode\":0}");
        completed++;
      }
    }
    return completed;
  }

  @NonNull
  private Set<String> queuesWithWork() {
    Set<String> queues = new HashSet<>();
    for (TaskQueueRow t : stores.taskQueue().listByBuild(buildId)) {
      if ("QUEUED".equals(t.status) && "EXECUTE_COMMAND".equals(t.type) && t.queueName != null) {
        queues.add(t.queueName);
      }
    }
    return queues;
  }

  private void enqueueAdvance(int delaySeconds) {
    TaskQueueRow t = new TaskQueueRow();
    t.type = "ORCHESTRATE";
    t.queueName = "default";
    t.status = "QUEUED";
    t.priority = 0;
    t.payloadJson = "{\"action\":\"ADVANCE\",\"buildId\":" + buildId + "}";
    t.attempts = 0;
    t.maxAttempts = 3;
    t.visibilityTimeoutSeconds = 3600;
    t.buildId = buildId;
    t.availableAt = Instant.now().plusSeconds(delaySeconds);
    stores.taskQueue().insert(t);
  }

  private static long insertJob(@NonNull Connection c, @NonNull String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "timer-sweep-scheduler/job-" + System.nanoTime());
      ps.setString(2, yaml);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static long insertRunningBuild(@NonNull Connection c, long jobId) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, started_at) "
                + "VALUES (?, 1, 'RUNNING', NOW())",
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

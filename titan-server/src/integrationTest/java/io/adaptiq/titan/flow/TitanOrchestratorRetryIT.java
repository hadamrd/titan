package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.flow.model.RetryPolicy;
import io.adaptiq.titan.flow.orch.StepRetryPolicy;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
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
 * Integration tests for {@link TitanOrchestrator}'s {@code retry:} step-failure path against real
 * PostgreSQL — build step 44-T B (design/44 §4, §5).
 *
 * <p>Drives a single-step pipeline whose step carries a {@link RetryPolicy} through {@code
 * advance()} loops and a stubbed worker. Each test proves a leg of design/44:
 *
 * <ul>
 *   <li>a FAILED task with attempts remaining re-enqueues a fresh {@code EXECUTE_COMMAND} task and
 *       increments the node {@code attempt} — the node stays {@code QUEUED}, not folded to FAILED;
 *   <li>the re-enqueued task carries a future {@code available_at} computed by {@code
 *       backoffMillis(k) = min(initial*multiplier^(k-1), max)};
 *   <li>attempts exhausted → the node folds to {@code FAILED} as today;
 *   <li>{@code retryableExitCodes} — a non-allowlisted exit fails fast, an allowlisted one retries;
 *   <li>a step that succeeds on a later attempt → {@code SUCCESS};
 *   <li>an infra failure (no {@code exitCode} in result_json) is retryable, bounded by maxAttempts;
 *   <li>the V4 migration applies cleanly and {@code attempt}/{@code max_attempts} are populated.
 * </ul>
 */
@Testcontainers
class TitanOrchestratorRetryIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private long buildId;

  private void bootstrap(String fixture) throws Exception {
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, Fixtures.load(fixture));
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(Fixtures.load(fixture));
  }

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

  // ── V4 migration ──────────────────────────────────────────────────────────

  /** The V4__retry.sql migration applies and max_attempts is copied onto the step at bake. */
  @Test
  void v4MigrationAppliesAndBakePopulatesAttemptColumns() throws Exception {
    bootstrap("retry-step.yml");
    FlowNodeRow step = stepNode();
    assertEquals(1, step.attempt, "a freshly baked node starts on attempt 1");
    assertEquals(3, step.maxAttempts, "the policy's maxAttempts is copied onto the node at bake");
  }

  // ── retry on a retryable failure ──────────────────────────────────────────

  /**
   * A FAILED step with attempts remaining re-enqueues a fresh EXECUTE_COMMAND task and increments
   * the node attempt — it is NOT folded to FAILED, and the node stays QUEUED ("retrying").
   */
  @Test
  void aFailedStepWithAttemptsRemainingIsReDispatchedNotFailed() throws Exception {
    bootstrap("retry-step.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance(); // dispatch attempt 1
    failNextClaimedTask(1); // worker fails attempt 1 with exit 1
    orch.advance(); // reconcile: should retry, not fail

    FlowNodeRow step = stepNode();
    assertEquals("QUEUED", step.status, "the retrying node stays QUEUED, not FAILED");
    assertEquals(2, step.attempt, "the node attempt incremented to 2");
    assertEquals(2, executeTaskCount(), "a fresh EXECUTE_COMMAND task was re-enqueued");
  }

  /**
   * The re-enqueued retry task carries a future available_at — the backoff delay {@code
   * min(initial*multiplier^(k-1), max)} applied via the queue's delayed delivery.
   */
  @Test
  void theReDispatchedTaskCarriesABackedOffAvailableAt() throws Exception {
    bootstrap("retry-step.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance();
    Instant failedAt = Instant.now();
    failNextClaimedTask(1);
    orch.advance(); // re-enqueues the retry

    TaskQueueRow retry = latestExecuteTask();
    // policy: initial 1s, multiplier 2.0 — attempt 2's delay is min(1000*2^0, 2000) = 1000ms.
    long delayMs = retry.availableAt.toEpochMilli() - failedAt.toEpochMilli();
    assertTrue(
        delayMs >= 500,
        "retry available_at is in the future (>= ~initial backoff); was " + delayMs + "ms");
  }

  /** backoffMillis follows min(initial*multiplier^(k-1), max) and caps at max. */
  @Test
  void backoffMillisIsExponentialAndCapped() {
    RetryPolicy policy = new RetryPolicy();
    RetryPolicy.Backoff b = new RetryPolicy.Backoff();
    b.setInitialMillis(10_000L);
    b.setMultiplier(2.0);
    b.setMaxMillis(300_000L);
    policy.setBackoff(b);

    assertEquals(10_000L, StepRetryPolicy.backoffMillis(policy, 1), "attempt 1 retry: +10s");
    assertEquals(20_000L, StepRetryPolicy.backoffMillis(policy, 2), "attempt 2 retry: +20s");
    assertEquals(40_000L, StepRetryPolicy.backoffMillis(policy, 3), "attempt 3 retry: +40s");
    assertEquals(300_000L, StepRetryPolicy.backoffMillis(policy, 10), "capped at max (5m)");
  }

  /** A step that succeeds on a later attempt drives the node — and the build — to SUCCESS. */
  @Test
  void aStepThatSucceedsOnALaterAttemptEndsSuccess() throws Exception {
    bootstrap("retry-step.yml");
    TitanOrchestrator.AdvanceResult last = drive(2 /* fail the first 2 attempts */);

    assertTrue(last.buildFinished(), "the build finished");
    assertEquals("SUCCESS", last.buildResult());
    FlowNodeRow step = stepNode();
    assertEquals("SUCCESS", step.status);
    assertEquals(3, step.attempt, "succeeded on attempt 3 of 3");
  }

  /** Attempts exhausted (attempt >= maxAttempts) — the node folds to FAILED, as today. */
  @Test
  void attemptsExhaustedFoldsTheNodeToFailed() throws Exception {
    bootstrap("retry-step.yml");
    TitanOrchestrator.AdvanceResult last = drive(99 /* fail every attempt */);

    assertTrue(last.buildFinished());
    assertEquals("FAILED", last.buildResult());
    FlowNodeRow step = stepNode();
    assertEquals("FAILED", step.status);
    assertEquals(3, step.attempt, "all 3 attempts were used");
  }

  // ── retry-reset — design/45 + design/44: no stale ✗ on a green node ───────

  /**
   * The design/45 retry-reset fix: {@link
   * io.adaptiq.titan.store.FlowNodeDao#compareAndSetRetryAttempt} NULLs {@code failure_category} /
   * {@code failure_reason} along with the other previous-attempt fields. A step that fails an
   * attempt and then SUCCEEDS on a later one must not carry a stale {@code ✗} failure into its
   * green terminal state.
   *
   * <p>The orchestrator writes the failure fields only on a terminal {@code FAILED}, never before a
   * retry — so to prove the reset actually clears them this test seeds a stale {@code
   * failure_category}/{@code failure_reason} on the node directly (simulating a field left over
   * from an earlier engine version, or a future path that records pre-retry diagnostics), then
   * drives the retry. After the retrying {@code advance()} the fields must be back to NULL.
   */
  @Test
  void aRetryClearsStaleFailureFieldsFromTheNode() throws Exception {
    bootstrap("retry-step.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance(); // dispatch attempt 1
    failNextClaimedTask(1); // worker fails attempt 1
    // Simulate a stale ✗ on the node before the retry CAS runs.
    stores
        .flowNodes()
        .updateFailure(
            buildId,
            "flaky-s0",
            "STEP_EXIT",
            "Step exited 1 — see the step log above for the failure detail.");
    FlowNodeRow seeded = stepNode();
    assertEquals("STEP_EXIT", seeded.failureCategory, "stale failure category was seeded");

    orch.advance(); // reconcile: retry — compareAndSetRetryAttempt must reset

    FlowNodeRow retried = stepNode();
    assertEquals("QUEUED", retried.status, "the retrying node stays QUEUED");
    assertEquals(2, retried.attempt, "the attempt incremented — the retry CAS won");
    assertNull(
        retried.failureCategory,
        "the retry reset cleared the stale failure_category — no ✗ on the fresh attempt");
    assertNull(retried.failureReason, "the retry reset cleared the stale failure_reason");
  }

  /**
   * End to end: a step that fails an attempt and then succeeds on a retry ends {@code SUCCESS}
   * carrying NO failure category or reason — the retry-reset fix proven through the real
   * orchestrator retry path, not a direct DAO call. A green terminal node never shows a stale ✗.
   */
  @Test
  void aStepThatSucceedsAfterARetryCarriesNoFailureFields() throws Exception {
    bootstrap("retry-step.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance(); // dispatch attempt 1
    failNextClaimedTask(1); // attempt 1 fails
    // A stale ✗ on the node — the reset must scrub it before the node reaches its green state.
    stores.flowNodes().updateFailure(buildId, "flaky-s0", "STEP_EXIT", "Step exited 1.");

    TitanOrchestrator.AdvanceResult last = drive(0 /* every remaining attempt succeeds */);

    assertTrue(last.buildFinished(), "the build finished");
    assertEquals("SUCCESS", last.buildResult());
    FlowNodeRow step = stepNode();
    assertEquals("SUCCESS", step.status, "the step succeeded on the retry");
    assertNull(
        step.failureCategory, "a node that succeeded on a retry carries no stale failure_category");
    assertNull(
        step.failureReason, "a node that succeeded on a retry carries no stale failure_reason");
  }

  // ── retryableExitCodes ────────────────────────────────────────────────────

  /** A non-retryable exit code (not in a non-empty retryableExitCodes list) fails fast. */
  @Test
  void aNonRetryableExitCodeFailsFastWithNoRetry() throws Exception {
    bootstrap("retry-exit-codes.yml"); // retryableExitCodes: [2, 75]
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance();
    failNextClaimedTaskWithExit(1); // exit 1 — NOT in the allowlist
    orch.advance();

    FlowNodeRow step = stepNode();
    assertEquals("FAILED", step.status, "a non-allowlisted exit fails fast");
    assertEquals(1, step.attempt, "no retry — still on attempt 1");
    assertEquals(1, executeTaskCount(), "no fresh task was enqueued");
  }

  /** A retryable exit code (in the allowlist) retries. */
  @Test
  void anAllowlistedExitCodeRetries() throws Exception {
    bootstrap("retry-exit-codes.yml"); // retryableExitCodes: [2, 75]
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance();
    failNextClaimedTaskWithExit(2); // exit 2 — IS in the allowlist
    orch.advance();

    FlowNodeRow step = stepNode();
    assertEquals("QUEUED", step.status, "an allowlisted exit retries");
    assertEquals(2, step.attempt);
    assertEquals(2, executeTaskCount());
  }

  // ── infra failure (no exitCode) ───────────────────────────────────────────

  /**
   * An infra failure — the task FAILED with a result_json carrying no exitCode (the worker died,
   * the visibility timeout lapsed) — is retryable, bounded by maxAttempts (design/44 §3).
   */
  @Test
  void anInfraFailureWithNoExitCodeIsRetryableBoundedByMaxAttempts() throws Exception {
    bootstrap("retry-step.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance();
    failNextClaimedTaskRaw("{\"error\":\"visibility timeout exceeded\"}");
    orch.advance();

    FlowNodeRow step = stepNode();
    assertEquals("QUEUED", step.status, "an infra failure (no exitCode) is retried");
    assertEquals(2, step.attempt);
  }

  // ── no retry policy — unchanged behaviour ─────────────────────────────────

  /** A step with no retry: policy folds straight to FAILED on a failed task — today's behaviour. */
  @Test
  void aStepWithNoRetryPolicyFoldsToFailedOnFailure() throws Exception {
    bootstrap("diamond-dag.yml"); // no retry: anywhere
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    orch.advance(); // RUNNING Build, dispatch its step
    Optional<TaskQueueRow> claimed = stores.taskQueue().claim("stub", "default", UUID.randomUUID());
    assertTrue(claimed.isPresent());
    stores
        .taskQueue()
        .complete(claimed.get().id, claimed.get().claimToken, "FAILED", "{\"exitCode\":1}");
    orch.advance();

    FlowNodeRow buildStep =
        stores.flowNodes().findByBuildAndNode(buildId, "build-s0").orElseThrow();
    assertEquals("FAILED", buildStep.status, "no retry policy — fold straight to FAILED");
    assertEquals(1, buildStep.attempt);
    assertEquals(1, buildStep.maxAttempts, "max_attempts default 1 for a non-retrying step");
  }

  // ── drive loop + worker stub ──────────────────────────────────────────────

  /**
   * Loop advance() and a stubbed worker until the build is terminal. The stub fails the first
   * {@code failCount} attempts of the step (exit 1) and completes any later attempt. Because a
   * retry task carries a future available_at, the loop sleeps briefly so the delayed task becomes
   * claimable.
   */
  private TitanOrchestrator.AdvanceResult drive(int failCount) throws Exception {
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);
    TitanOrchestrator.AdvanceResult result = null;
    int completed = 0;
    for (int pass = 0; pass < 40; pass++) {
      result = orch.advance();
      if (result.buildFinished()) {
        return result;
      }
      Optional<TaskQueueRow> claimed;
      boolean anyClaimed = false;
      while ((claimed = stores.taskQueue().claim("stub", "default", UUID.randomUUID()))
          .isPresent()) {
        anyClaimed = true;
        TaskQueueRow t = claimed.get();
        boolean fail = completed < failCount;
        completed++;
        stores
            .taskQueue()
            .complete(
                t.id,
                t.claimToken,
                fail ? "FAILED" : "COMPLETED",
                "{\"exitCode\":" + (fail ? 1 : 0) + "}");
      }
      if (!anyClaimed) {
        // a retry task is enqueued but not yet claimable (future available_at) — wait it out
        Thread.sleep(500);
      }
    }
    throw new AssertionError("build did not finish within 40 advance passes");
  }

  private void failNextClaimedTask(int unused) {
    failNextClaimedTaskRaw("{\"exitCode\":1}");
  }

  private void failNextClaimedTaskWithExit(int exitCode) {
    failNextClaimedTaskRaw("{\"exitCode\":" + exitCode + "}");
  }

  private void failNextClaimedTaskRaw(String resultJson) {
    Optional<TaskQueueRow> claimed = stores.taskQueue().claim("stub", "default", UUID.randomUUID());
    assertTrue(claimed.isPresent(), "a dispatched EXECUTE_COMMAND task must be claimable");
    TaskQueueRow t = claimed.get();
    stores.taskQueue().complete(t.id, t.claimToken, "FAILED", resultJson);
  }

  private FlowNodeRow stepNode() {
    return stores.flowNodes().findByBuildAndNode(buildId, "flaky-s0").orElseThrow();
  }

  private long executeTaskCount() {
    return stores.taskQueue().listByBuild(buildId).stream()
        .filter(t -> "EXECUTE_COMMAND".equals(t.type))
        .count();
  }

  private TaskQueueRow latestExecuteTask() {
    List<TaskQueueRow> tasks = stores.taskQueue().listByBuild(buildId);
    TaskQueueRow latest = null;
    for (TaskQueueRow t : tasks) {
      if ("EXECUTE_COMMAND".equals(t.type)) {
        latest = t; // listByBuild orders by created_at — last wins
      }
    }
    assertNotNull(latest, "an EXECUTE_COMMAND task exists");
    return latest;
  }

  // ── fixtures ──────────────────────────────────────────────────────────────

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "retry/job-" + System.nanoTime());
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

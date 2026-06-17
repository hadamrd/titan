package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.build.BuildService;
import io.adaptiq.titan.build.BuildServiceImpl;
import io.adaptiq.titan.build.RetryStageOutcome;
import io.adaptiq.titan.build.RetryStagePreconditionException;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.AuditLogRow;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres-backed IT for stage-retry-in-place (closes #744 backend half) — the new {@link
 * io.adaptiq.titan.api.StageRetryApi} and the {@link BuildService#retryStage} engine seam.
 *
 * <p>Drives {@link BuildServiceImpl} directly against a real Postgres Testcontainer (same setup as
 * {@link io.adaptiq.titan.build.BuildServiceReplayFromFailedIT}). The RBAC corner — a caller
 * without {@code REPLAY_BUILD} or {@code ADMIN} must be 403'd — is covered by the
 * {@code @QuarkusTest} sibling {@link RbacStageRetryIT}.
 *
 * <p>Scenarios pinned:
 *
 * <ul>
 *   <li>retry a FAILED stage → 200, stage + descendants flipped to QUEUED, build flipped to
 *       RUNNING, audit row STAGE_RETRIED emitted, ORCHESTRATE/ADVANCE enqueued
 *   <li>retry a SUCCEEDED stage → {@link RetryStagePreconditionException} (409 at the REST layer)
 *   <li>retry a RUNNING stage → {@link RetryStagePreconditionException} (409)
 *   <li>retry on unknown build → {@link ApiNotFoundException} (404)
 *   <li>retry on unknown stage id → {@link ApiNotFoundException} (404)
 *   <li>double-retry is idempotent: first wins, second 409s (status already moved out of FAILED)
 * </ul>
 */
@Testcontainers
class StageRetryApiIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private BuildService svc;

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
    svc = new BuildServiceImpl(stores);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  // ── happy path ─────────────────────────────────────────────────────────────

  @Test
  void retryFailedStage_resetsStageAndDescendants_flipsBuildRunning_enqueuesAdvance_audits() {
    // Build: stage-1 (SUCCESS) → stage-2 (FAILED) → step-2a (FAILED, child of stage-2).
    // Retrying stage-2 must reset stage-2 + step-2a to QUEUED but leave stage-1 SUCCESS.
    long buildId = freshFailedBuild();
    seedStageNode(buildId, "stage-1", "SUCCESS", null);
    seedStageNode(buildId, "stage-2", "FAILED", null);
    seedStepNode(buildId, "step-2a", "FAILED", "stage-2");
    // A downstream stage that was SKIPPED because stage-2 failed — must also reset.
    seedStageNode(buildId, "stage-3", "SKIPPED", "stage-2");

    RetryStageOutcome outcome = svc.retryStage(buildId, "stage-2", "alice");

    assertTrue(outcome instanceof RetryStageOutcome.Applied);
    RetryStageOutcome.Applied applied = (RetryStageOutcome.Applied) outcome;
    assertEquals("applied", applied.type());
    assertEquals(buildId, applied.buildId());
    assertEquals("stage-2", applied.stageId());
    // stage-2 + step-2a + stage-3 reset; stage-1 NOT reset. Order within descendants is BFS-
    // determined and not part of the contract (the DAG has no canonical left-to-right) — assert as
    // a set, with the root always first.
    assertEquals(
        "stage-2", applied.resetNodeIds().get(0), "root stage must be first in resetNodeIds");
    assertEquals(
        java.util.Set.of("stage-2", "step-2a", "stage-3"),
        new java.util.HashSet<>(applied.resetNodeIds()));

    // Stage-1 untouched.
    assertEquals(
        "SUCCESS", stores.flowNodes().findByBuildAndNode(buildId, "stage-1").orElseThrow().status);
    // Stage-2 + descendants reset to QUEUED with cleared timing/result.
    FlowNodeRow s2 = stores.flowNodes().findByBuildAndNode(buildId, "stage-2").orElseThrow();
    assertEquals("QUEUED", s2.status);
    assertNull(s2.startedAt);
    assertNull(s2.completedAt);
    assertNull(s2.failureCategory);
    assertNull(s2.failureReason);
    assertEquals(
        "QUEUED", stores.flowNodes().findByBuildAndNode(buildId, "step-2a").orElseThrow().status);
    assertEquals(
        "QUEUED", stores.flowNodes().findByBuildAndNode(buildId, "stage-3").orElseThrow().status);

    // Build flipped FAILED → RUNNING.
    BuildRow b = stores.builds().findById(buildId).orElseThrow();
    assertEquals("RUNNING", b.status);
    assertNull(b.finishedAt, "finishedAt cleared on retry");

    // Exactly one ORCHESTRATE/ADVANCE task enqueued.
    List<TaskQueueRow> tasks = stores.taskQueue().listByBuild(buildId);
    long advanceCount =
        tasks.stream()
            .filter(t -> "ORCHESTRATE".equals(t.type) && t.payloadJson.contains("\"ADVANCE\""))
            .count();
    assertEquals(1L, advanceCount, "expected exactly one ORCHESTRATE/ADVANCE; got " + tasks);

    // Audit row written: STAGE_RETRIED with actor=alice, target=build, details has stageId.
    List<AuditLogRow> audits =
        stores.auditLog().findRecent(null, "STAGE_RETRIED", null, null, 10, 0);
    assertEquals(1, audits.size());
    AuditLogRow audit = audits.get(0);
    assertEquals("alice", audit.actor);
    assertEquals("STAGE_RETRIED", audit.action);
    assertEquals("BUILD", audit.targetType);
    assertEquals(String.valueOf(buildId), audit.targetId);
    assertNotNull(audit.detailsJson);
    assertTrue(audit.detailsJson.contains("\"stage-2\""), "audit details must name the stageId");
  }

  // ── sad paths ──────────────────────────────────────────────────────────────

  @Test
  void retrySucceededStage_throwsPreconditionConflict() {
    long buildId = freshFailedBuild();
    seedStageNode(buildId, "stage-1", "SUCCESS", null);

    RetryStagePreconditionException ex =
        assertThrows(
            RetryStagePreconditionException.class,
            () -> svc.retryStage(buildId, "stage-1", "alice"));
    assertEquals("stage is not in FAILED state", ex.getMessage());
  }

  @Test
  void retryRunningStage_throwsPreconditionConflict() {
    long buildId = freshFailedBuild();
    seedStageNode(buildId, "stage-1", "RUNNING", null);

    RetryStagePreconditionException ex =
        assertThrows(
            RetryStagePreconditionException.class,
            () -> svc.retryStage(buildId, "stage-1", "alice"));
    assertEquals("stage is not in FAILED state", ex.getMessage());
  }

  @Test
  void retryOnUnknownBuild_throwsNotFound() {
    ApiNotFoundException ex =
        assertThrows(
            ApiNotFoundException.class, () -> svc.retryStage(999_999_999L, "stage-1", "alice"));
    assertTrue(ex.getMessage().contains("999999999"));
  }

  @Test
  void retryOnUnknownStageId_throwsNotFound() {
    long buildId = freshFailedBuild();
    seedStageNode(buildId, "stage-1", "FAILED", null);

    ApiNotFoundException ex =
        assertThrows(
            ApiNotFoundException.class,
            () -> svc.retryStage(buildId, "stage-does-not-exist", "alice"));
    assertTrue(
        ex.getMessage().contains("stage-does-not-exist"),
        "404 message must name the missing stage; got: " + ex.getMessage());
  }

  @Test
  void doubleRetry_isIdempotent_firstWinsSecond409s() {
    long buildId = freshFailedBuild();
    seedStageNode(buildId, "stage-1", "FAILED", null);

    RetryStageOutcome first = svc.retryStage(buildId, "stage-1", "alice");
    assertTrue(first instanceof RetryStageOutcome.Applied);

    // First call moved stage-1 out of FAILED → QUEUED. A second call must see QUEUED and 409.
    RetryStagePreconditionException ex =
        assertThrows(
            RetryStagePreconditionException.class,
            () -> svc.retryStage(buildId, "stage-1", "alice"));
    assertEquals("stage is not in FAILED state", ex.getMessage());
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private long freshJob() {
    JobRow row = new JobRow();
    row.fullName = "stage-retry-it/" + System.nanoTime();
    row.pipelineScript = "";
    row.configJson = "{}";
    row.enabled = true;
    return stores.jobs().insert(row);
  }

  private long freshFailedBuild() {
    long jobId = freshJob();
    BuildRow b = new BuildRow();
    b.jobId = jobId;
    b.buildNumber = stores.builds().nextBuildNumber(jobId);
    b.status = "FAILED";
    b.queuedAt = Instant.now().minusSeconds(60);
    b.startedAt = Instant.now().minusSeconds(50);
    b.finishedAt = Instant.now().minusSeconds(10);
    b.durationMs = 40_000L;
    b.triggeredBy = "test";
    b.triggerType = "manual";
    return stores.withTransaction(c -> stores.builds().insert(c, b));
  }

  private void seedStageNode(long buildId, String nodeId, String status, String parentId) {
    seedNode(buildId, nodeId, "STAGE", status, parentId);
  }

  private void seedStepNode(long buildId, String nodeId, String status, String parentId) {
    seedNode(buildId, nodeId, "STEP", status, parentId);
  }

  private void seedNode(long buildId, String nodeId, String type, String status, String parentId) {
    FlowNodeRow n = new FlowNodeRow();
    n.buildId = buildId;
    n.nodeId = nodeId;
    n.nodeType = type;
    n.displayName = nodeId;
    n.status = status;
    n.parentIds = parentId;
    if ("FAILED".equals(status)) {
      n.startedAt = Instant.now().minusSeconds(30);
      n.completedAt = Instant.now().minusSeconds(20);
      n.durationMs = 10_000L;
      n.failureCategory = "STEP_EXIT";
      n.failureReason = "step exited 1";
    } else if ("SUCCESS".equals(status)) {
      n.startedAt = Instant.now().minusSeconds(40);
      n.completedAt = Instant.now().minusSeconds(35);
      n.durationMs = 5_000L;
    } else if ("RUNNING".equals(status)) {
      n.startedAt = Instant.now().minusSeconds(5);
    }
    stores.flowNodes().insert(n);
  }
}

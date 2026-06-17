package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.api.dto.BuildDto;
import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end ITs for the controller-native {@code setBuildName:} step (#762):
 *
 * <ul>
 *   <li>a single {@code setBuildName:} populates {@code titan.builds.display_name} on the first
 *       orchestrator pass — no worker task is dispatched for it
 *   <li>two consecutive {@code setBuildName:} steps: last write wins on the column; <em>both</em>
 *       emit a {@code BUILD_RENAMED} audit row
 *   <li>a name longer than 200 chars FAILs the step and the build (no silent truncation)
 *   <li>a malformed {@code ${{ params.MISSING }}} template FAILs the step with a helpful error
 * </ul>
 *
 * <p>Mirrors {@link ApprovalsApiIT} — drives the orchestrator directly against a real Postgres
 * Testcontainer, no Quarkus boot. We pass {@code null} for the audit service in the orchestrator
 * path (no CDI scope) and verify audit rows via the {@code stores.auditLog()} DAO, exercised
 * through a direct {@link io.adaptiq.titan.flow.SetBuildNameResolver#apply} call for the
 * audit-counting tests.
 */
@Testcontainers
class SetBuildNameStepIT {

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

  // ── shared helpers ────────────────────────────────────────────────────────

  private void bake(String fixture) throws Exception {
    String yaml = Fixtures.load(fixture);
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, yaml);
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(yaml);
  }

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "setbuildname-it/job-" + System.nanoTime());
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

  /**
   * Walk the build forward synthetically — every QUEUED EXECUTE_COMMAND task is marked COMPLETED +
   * its step flipped SUCCESS, then advance() runs again. Bounded loop so a bug cannot spin.
   */
  private void completeAllQueuedSteps() throws Exception {
    for (int i = 0; i < 20; i++) {
      boolean any;
      try (Connection c = ds.getConnection();
          Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT id, node_id FROM titan.task_queue "
                      + "WHERE type = 'EXECUTE_COMMAND' AND status = 'QUEUED' AND build_id = "
                      + buildId)) {
        any = false;
        while (rs.next()) {
          long taskId = rs.getLong(1);
          String nodeId = rs.getString(2);
          try (Statement up = c.createStatement()) {
            up.execute("UPDATE titan.task_queue SET status = 'COMPLETED' WHERE id = " + taskId);
          }
          stores
              .flowNodes()
              .compareAndSetStatus(
                  buildId, nodeId, "QUEUED", "SUCCESS", null, Instant.now(), 1L, null);
          any = true;
        }
      }
      new TitanOrchestrator(stores, buildId).advance();
      if (!any) {
        return;
      }
    }
  }

  private long countAuditRows() throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT COUNT(*) FROM titan.audit_log "
                    + "WHERE action = ? AND target_type = 'BUILD' AND target_id = ?")) {
      ps.setString(1, AuditAction.BUILD_RENAMED.name());
      ps.setString(2, String.valueOf(buildId));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  // ── tests ─────────────────────────────────────────────────────────────────

  @Test
  void singleSetBuildNamePopulatesDisplayNameOnBuildRow() throws Exception {
    bake("set-build-name-single.yml");

    // Advance once: the orchestrator should apply the setBuildName step synchronously and dispatch
    // the next sh step in the same pass (controller-native: no SLEEPING).
    new TitanOrchestrator(stores, buildId).advance();

    BuildRow afterFirstAdvance = stores.builds().findById(buildId).orElseThrow();
    assertEquals(
        "renamed-build",
        afterFirstAdvance.displayName,
        "setBuildName must stamp the column on the first orchestrator pass");

    // Finish the trailing sh step so the build closes cleanly.
    completeAllQueuedSteps();
    BuildDto post = readDto();
    assertEquals("renamed-build", post.displayName());
  }

  @Test
  void singleSetBuildNameDoesNotDispatchAnExecuteCommandTaskForItself() throws Exception {
    bake("set-build-name-single.yml");
    new TitanOrchestrator(stores, buildId).advance();

    // The renamer node is SUCCESS, but it was never dispatched as an EXECUTE_COMMAND — only the
    // trailing `sh` step lands in task_queue.
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT COUNT(*) FROM titan.task_queue "
                    + "WHERE type = 'EXECUTE_COMMAND' AND build_id = ?")) {
      ps.setLong(1, buildId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertEquals(
            1L,
            rs.getLong(1),
            "exactly one EXECUTE_COMMAND task (the trailing sh) — the setBuildName step is "
                + "controller-native and never enqueues");
      }
    }
  }

  @Test
  void twoSetBuildNameStepsLastWriteWinsAndBothEmitAuditRows() throws Exception {
    bake("set-build-name-twice.yml");

    // Drive both setBuildName steps explicitly via the resolver so we exercise the audit emit
    // path through a wired AuditService stub. The orchestrator path uses a null audit (no CDI),
    // so the audit-counting assertion here exercises the resolver directly — same code path.
    io.adaptiq.titan.flow.model.PipelineModel model =
        io.adaptiq.titan.cache.PipelineModelCache.loadOrFresh(stores, buildId);
    var nodes =
        io.adaptiq.titan.flow.orch.PipelineNodes.byId(stores.flowNodes().listByBuild(buildId));
    java.util.Map<String, Object> ctx =
        new io.adaptiq.titan.flow.orch.OutputContext(stores, buildId).build(model, nodes);

    var renameStage = model.getStage("rename");
    var step1 = renameStage.getSteps().get(0);
    var step2 = renameStage.getSteps().get(1);

    SetBuildNameResolver.AuditEmitter emitter = recordingEmitter();

    SetBuildNameResolver.Outcome first =
        SetBuildNameResolver.apply(stores, emitter, buildId, step1, ctx);
    assertTrue(first.ok());
    assertEquals("first-name", first.resolvedName());

    BuildRow afterFirst = stores.builds().findById(buildId).orElseThrow();
    assertEquals("first-name", afterFirst.displayName);

    SetBuildNameResolver.Outcome second =
        SetBuildNameResolver.apply(stores, emitter, buildId, step2, ctx);
    assertTrue(second.ok());
    assertEquals("second-name", second.resolvedName());

    BuildRow afterSecond = stores.builds().findById(buildId).orElseThrow();
    assertEquals("second-name", afterSecond.displayName, "last write wins on the column");

    assertEquals(2L, countAuditRows(), "both setBuildName applies emit a BUILD_RENAMED row");
  }

  @Test
  void nameOver200CharsFailsTheStepAndTheBuild() throws Exception {
    bake("set-build-name-too-long.yml");

    new TitanOrchestrator(stores, buildId).advance();

    FlowNodeRow renameStep = findStepByDescriptor("setBuildName");
    assertEquals("FAILED", renameStep.status, "over-length name fails the step (no truncation)");
    assertEquals("CONFIG", renameStep.failureCategory);
    assertNotNull(renameStep.failureReason);
    assertTrue(
        renameStep.failureReason.contains("exceeds the maximum"),
        "the failure reason mentions the cap: " + renameStep.failureReason);

    BuildRow row = stores.builds().findById(buildId).orElseThrow();
    assertNull(
        row.displayName, "the column MUST NOT be written when the step failed pre-persistence");

    // BLOCK_ON_FAILURE (the default) cascades: the build closes FAILED on the next advance.
    new TitanOrchestrator(stores, buildId).advance();
    BuildRow closed = stores.builds().findById(buildId).orElseThrow();
    assertEquals("FAILED", closed.status, "an over-length setBuildName must fail the build");
  }

  @Test
  void malformedTemplateFailsTheStepWithAHelpfulError() throws Exception {
    bake("set-build-name-bad-template.yml");

    new TitanOrchestrator(stores, buildId).advance();

    FlowNodeRow renameStep = findStepByDescriptor("setBuildName");
    assertEquals(
        "FAILED",
        renameStep.status,
        "a malformed ${{ params.MISSING }} reference must FAIL the step, not blank-substitute");
    assertEquals("CONFIG", renameStep.failureCategory);
    assertNotNull(renameStep.failureReason);
    assertTrue(
        renameStep.failureReason.toLowerCase(java.util.Locale.ROOT).contains("template")
            || renameStep.failureReason.toLowerCase(java.util.Locale.ROOT).contains("missing")
            || renameStep.failureReason.toLowerCase(java.util.Locale.ROOT).contains("resolve"),
        "the failure reason should point at the broken reference: " + renameStep.failureReason);
  }

  @Test
  void replayingTheSameStepWritesTheSameValueIdempotently() throws Exception {
    bake("set-build-name-single.yml");
    new TitanOrchestrator(stores, buildId).advance();

    BuildRow after1 = stores.builds().findById(buildId).orElseThrow();
    assertEquals("renamed-build", after1.displayName);

    // Re-apply the resolver against the same step → same value, no exception, idempotent.
    io.adaptiq.titan.flow.model.PipelineModel model =
        io.adaptiq.titan.cache.PipelineModelCache.loadOrFresh(stores, buildId);
    var nodes =
        io.adaptiq.titan.flow.orch.PipelineNodes.byId(stores.flowNodes().listByBuild(buildId));
    java.util.Map<String, Object> ctx =
        new io.adaptiq.titan.flow.orch.OutputContext(stores, buildId).build(model, nodes);
    var step = model.getStage("rename").getSteps().get(0);
    SetBuildNameResolver.Outcome out = SetBuildNameResolver.apply(stores, buildId, step, ctx);
    assertTrue(out.ok());
    assertEquals("renamed-build", out.resolvedName(), "replay writes the same resolved value");

    BuildRow after2 = stores.builds().findById(buildId).orElseThrow();
    assertEquals("renamed-build", after2.displayName);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private FlowNodeRow findStepByDescriptor(String descriptorId) {
    io.adaptiq.titan.flow.model.PipelineModel model =
        io.adaptiq.titan.cache.PipelineModelCache.loadOrFresh(stores, buildId);
    for (var stage : model.getStages()) {
      for (var step : stage.getSteps()) {
        if (descriptorId.equals(step.getDescriptorId())) {
          return stores.flowNodes().findByBuildAndNode(buildId, step.getId()).orElseThrow();
        }
      }
    }
    throw new AssertionError("no step with descriptor " + descriptorId + " in build " + buildId);
  }

  private BuildDto readDto() {
    return BuildDto.from(stores.builds().findById(buildId).orElseThrow());
  }

  /**
   * Build a {@link SetBuildNameResolver.AuditEmitter} that writes a {@code BUILD_RENAMED} row
   * directly through {@link io.adaptiq.titan.store.AuditLogDao#insert}. Lets the IT verify the
   * audit emit path without spinning a CDI scope or a {@code SecurityIdentity}.
   */
  private SetBuildNameResolver.AuditEmitter recordingEmitter() {
    return (id, name) -> {
      io.adaptiq.titan.store.rows.AuditLogRow row = new io.adaptiq.titan.store.rows.AuditLogRow();
      row.actor = "pipeline";
      row.action = AuditAction.BUILD_RENAMED.name();
      row.targetType = io.adaptiq.titan.audit.AuditTargetType.BUILD.name();
      row.targetId = String.valueOf(id);
      // No secrets — buildId + the resolved name only.
      String safe = name.replace("\\", "\\\\").replace("\"", "\\\"");
      row.detailsJson = "{\"buildId\":" + id + ",\"displayName\":\"" + safe + "\"}";
      stores.auditLog().insert(row);
    };
  }
}

package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.api.dto.StageTimingsDto;
import io.adaptiq.titan.flow.Fixtures;
import io.adaptiq.titan.flow.TitanFlowExecution;
import io.adaptiq.titan.flow.TitanOrchestrator;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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
 * Engine-vs-fixture regression IT for issue #127: {@code flow_nodes.duration_ms} must be stamped by
 * the <em>engine itself</em>, not just by seed scripts.
 *
 * <p>{@link JobTimingsApiIT} pins the percentile math on hand-inserted rows — exactly the
 * fixture-vs-engine drift that let #127 ship hollow (the orchestrator passed {@code durationMs =
 * null} on every transition, so {@code /api/v1/jobs/{id}/stage-timings} was empty for every real
 * build). This IT closes that gap: it drives a real two-stage build ({@code linear-success.yml})
 * through {@link TitanOrchestrator#advance()} with a stubbed worker against real PostgreSQL — the
 * same drive loop as {@code TitanOrchestratorIT} — and asserts the terminal transitions stamped
 * plausible durations end-to-end into the stage-timings endpoint.
 */
@Testcontainers
class EngineStageTimingsIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private long jobId;
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
    String yaml = Fixtures.load("linear-success.yml");
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, yaml);
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(yaml);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  @Test
  void engineExecutedBuildStampsStageDurationsAndFeedsStageTimings() throws Exception {
    // ── drive the two-stage build to SUCCESS (advance + stubbed worker) ────
    TitanOrchestrator orchestrator = new TitanOrchestrator(stores, buildId);
    TitanOrchestrator.AdvanceResult result = null;
    for (int pass = 0; pass < 30; pass++) {
      result = orchestrator.advance();
      if (result.buildFinished()) {
        break;
      }
      // Make each stage's execution window measurably non-zero — a same-millisecond
      // claim/complete round-trip would make "duration > 0" flaky.
      Thread.sleep(10);
      Optional<TaskQueueRow> claimed;
      while ((claimed = stores.taskQueue().claim("stub-worker", "default", UUID.randomUUID()))
          .isPresent()) {
        TaskQueueRow t = claimed.get();
        stores.taskQueue().complete(t.id, t.claimToken, "COMPLETED", "{\"exitCode\":0}");
      }
    }
    assertNotNull(result);
    assertTrue(result.buildFinished(), "the build must finish within 30 advance passes");
    assertEquals("SUCCESS", result.buildResult());

    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("SUCCESS", build.status);
    assertNotNull(build.durationMs, "BuildCloser stamps the build duration");

    // ── every executed node carries a plausible engine-stamped duration ────
    List<FlowNodeRow> nodes = stores.flowNodes().listByBuild(buildId);
    List<FlowNodeRow> stages = nodes.stream().filter(n -> "STAGE".equals(n.nodeType)).toList();
    assertEquals(2, stages.size(), "linear-success.yml has two stages");
    for (FlowNodeRow n : nodes) {
      assertEquals("SUCCESS", n.status, "node " + n.nodeId);
      assertNotNull(
          n.durationMs, "node " + n.nodeId + " must have an engine-stamped duration (issue #127)");
      assertTrue(n.durationMs > 0, "node " + n.nodeId + " duration must be > 0ms");
      assertTrue(
          n.durationMs <= build.durationMs,
          "node "
              + n.nodeId
              + " duration ("
              + n.durationMs
              + "ms) cannot exceed the build's ("
              + build.durationMs
              + "ms)");
    }

    // ── and the public stage-timings surface is no longer hollow ───────────
    StageTimingsDto dto = new JobTimingsHandler(stores).stageTimings(Long.toString(jobId), 30);
    assertEquals(1L, dto.buildsConsidered());
    assertFalse(dto.stages().isEmpty(), "stage-timings must not be empty for a real build");
    assertEquals(2, dto.stages().size(), "one timing row per stage");
    for (StageTimingsDto.StageTimingDto stage : dto.stages()) {
      assertEquals(1L, stage.sampleCount());
      assertNotNull(stage.p50Ms());
      assertTrue(stage.p50Ms() > 0, "stage " + stage.stageName() + " p50 must be > 0ms");
      assertEquals(1, stage.samples().size());
      assertTrue(stage.samples().get(0).durationMs() > 0);
    }
  }

  // ── fixtures (same shape as TitanOrchestratorIT) ──────────────────────────

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "timings/job-" + System.nanoTime());
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
            "INSERT INTO titan.builds (job_id, build_number, status, queued_at) "
                + "VALUES (?, 1, 'QUEUED', NOW())",
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

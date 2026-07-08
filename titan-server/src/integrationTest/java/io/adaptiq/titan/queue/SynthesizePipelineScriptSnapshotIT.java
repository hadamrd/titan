package io.adaptiq.titan.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression IT for issue #61 (spec 24) — the SYNTHESIZE orchestration pass must snapshot the job's
 * {@code pipeline_script} onto {@code titan.builds.pipeline_script} at worker-synthesis dispatch
 * time, so {@code GET /api/v1/builds/{id}} can answer "what YAML did THIS build bake from?" even
 * after the job's mutable script is edited.
 *
 * <p>Before this fix, the build carried no script snapshot at all and the build-detail DTO silently
 * returned no {@code pipelineScript} — the e2e oracle (vendored fixture YAML) compared against an
 * empty string. Adversarial pairing:
 *
 * <ol>
 *   <li>Happy path: dispatch pass writes the snapshot AND enqueues the worker synthesis task whose
 *       payload carries the same bytes (the two must never diverge — the snapshot IS the synthesis
 *       source).
 *   <li>Mutation-after-dispatch: editing the job's script after dispatch must NOT change the
 *       build's snapshot — the snapshot is per-build provenance, not a live view.
 *   <li>Already-synthesized build (the replay shape): the handler hands off to BAKE without ever
 *       dispatching synthesis — the snapshot stays NULL and no fabricated provenance appears.
 * </ol>
 */
@Testcontainers
class SynthesizePipelineScriptSnapshotIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String PIPELINE_YAML =
      """
      agent: linux

      stages:
        - stage: Build
          steps:
            - sh: "echo hello > out.txt"
      """;

  private HikariDataSource ds;
  private TitanStores stores;
  private QueueHandlerSupport support;

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
    support = new QueueHandlerSupport(() -> null);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  @Test
  void synthesizeDispatch_snapshotsPipelineScriptOntoBuild_andPayloadCarriesSameBytes()
      throws Exception {
    long jobId = insertJob(PIPELINE_YAML);
    long buildId = insertQueuedBuild(jobId);
    TaskQueueRow task = claimedSynthesizeTask(buildId);

    new SynthesizeHandler(support).handle(stores, task, Map.of("buildId", buildId));

    // 1. The snapshot is on the build row — byte-identical to the job's script.
    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals(
        PIPELINE_YAML,
        build.pipelineScript,
        "SYNTHESIZE dispatch must snapshot the job's pipeline_script onto the build");

    // 2. The worker synthesis task was enqueued and its payload carries the SAME script — the
    //    snapshot and the synthesis source must never diverge.
    Optional<TaskQueueRow> synth = stores.taskQueue().findLatestSynthesisTask(buildId);
    assertTrue(synth.isPresent(), "the dispatch pass must enqueue a worker synthesis task");
    assertNotNull(synth.get().payloadJson);
    assertTrue(
        synth.get().payloadJson.contains("echo hello > out.txt"),
        "worker synthesis payload must carry the snapshotted script, got: "
            + synth.get().payloadJson);
  }

  @Test
  void editingJobScriptAfterDispatch_doesNotRewriteTheBuildSnapshot() throws Exception {
    long jobId = insertJob(PIPELINE_YAML);
    long buildId = insertQueuedBuild(jobId);
    new SynthesizeHandler(support)
        .handle(stores, claimedSynthesizeTask(buildId), Map.of("buildId", buildId));

    // Operator edits the job AFTER the build was dispatched.
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement("UPDATE titan.jobs SET pipeline_script = ? WHERE id = ?")) {
      ps.setString(1, "stages:\n  - stage: Edited\n    steps:\n      - sh: echo edited\n");
      ps.setLong(2, jobId);
      ps.executeUpdate();
    }

    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals(
        PIPELINE_YAML,
        build.pipelineScript,
        "the per-build snapshot is provenance — a later job edit must not rewrite it");
  }

  @Test
  void alreadySynthesizedBuild_replayShape_neverFabricatesASnapshot() throws Exception {
    // A replay build reuses the parent's pipeline_model_json; the handler's "Done" branch hands
    // straight to BAKE without dispatching synthesis. It must NOT invent a snapshot (the job's
    // CURRENT script may have drifted from what the parent actually ran).
    long jobId = insertJob("stages:\n  - stage: Drifted\n    steps:\n      - sh: echo drifted\n");
    long buildId = insertQueuedBuild(jobId);
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement("UPDATE titan.builds SET pipeline_model_json = ? WHERE id = ?")) {
      ps.setString(1, "{\"stages\":[]}");
      ps.setLong(2, buildId);
      ps.executeUpdate();
    }

    new SynthesizeHandler(support)
        .handle(stores, claimedSynthesizeTask(buildId), Map.of("buildId", buildId));

    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertNull(
        build.pipelineScript,
        "an already-synthesized build never dispatches synthesis — no snapshot may be fabricated");
    long bakeCount =
        stores.taskQueue().listByBuild(buildId).stream()
            .filter(t -> t.payloadJson != null && t.payloadJson.contains("\"BAKE\""))
            .count();
    assertTrue(bakeCount >= 1, "the Done branch must still hand off to BAKE");
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private long insertJob(@NonNull String pipelineYaml) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) "
                    + "VALUES (?, ?, '{}')",
                Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "synth-snapshot/job-" + System.nanoTime());
      ps.setString(2, pipelineYaml);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private long insertQueuedBuild(long jobId) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
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

  /** A claimed ORCHESTRATE/SYNTHESIZE task row, as the QueueProcessor would hold it. */
  @NonNull
  private TaskQueueRow claimedSynthesizeTask(long buildId) {
    TaskQueueRow t = new TaskQueueRow();
    t.type = "ORCHESTRATE";
    t.queueName = "default";
    t.status = "CLAIMED";
    t.priority = 0;
    t.payloadJson = "{\"action\":\"SYNTHESIZE\",\"buildId\":" + buildId + "}";
    t.attempts = 1;
    t.maxAttempts = 3;
    t.visibilityTimeoutSeconds = 3600;
    t.buildId = buildId;
    t.availableAt = java.time.Instant.now();
    t.claimToken = java.util.UUID.randomUUID();
    t.claimedBy = "synth-snapshot-it";
    t.claimedAt = java.time.Instant.now();
    long id = stores.taskQueue().insert(t);
    return stores.taskQueue().findById(id).orElseThrow();
  }
}

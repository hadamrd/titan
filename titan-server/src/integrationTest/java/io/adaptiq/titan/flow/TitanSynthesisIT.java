package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.flow.parser.PipelineParseException;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
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
 * Integration tests for the design/38 §3 phase split — synthesis and bake as two distinct phases of
 * {@link TitanFlowExecution}, against real PostgreSQL.
 *
 * <p>Proves the design/38 Stage 1 contract: {@link TitanFlowExecution#synthesize} parses the
 * pipeline definition into the immutable {@code pipeline_model_json} (the trivial YAML-identity
 * synthesis) and writes nothing else; {@link TitanFlowExecution#bake} consumes that <em>stored</em>
 * model — never re-parsing the script — to materialise the {@code flow_nodes} DAG; and both phases
 * are retry-safe under task re-delivery.
 */
@Testcontainers
class TitanSynthesisIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private long jobId;

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
      jobId = insertJob(c, Fixtures.load("reference-pipeline.yml"));
    }
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  /** Synthesis parses the pipeline definition and persists the model — and nothing else. */
  @Test
  void synthesizeStoresTheModelAndOnlyTheModel() throws Exception {
    long buildId = insertBuild(jobId, "{\"runSmokeTests\": true}");

    TitanFlowExecution.SynthesisResult result =
        new TitanFlowExecution(stores, buildId).synthesize(Fixtures.load("reference-pipeline.yml"));
    assertEquals(TitanFlowExecution.SynthesisResult.SYNTHESIZED, result);

    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertNotNull(build.pipelineModelJson, "synthesis writes the immutable model");
    assertTrue(build.pipelineModelJson.contains("QA Approval"));
    // Synthesis does not materialise the DAG and does not move the build off QUEUED.
    assertEquals(0, stores.flowNodes().countByBuild(buildId), "synthesis does not bake");
    assertEquals("QUEUED", build.status, "synthesis does not activate the build");
    assertNull(build.startedAt);
  }

  /** A re-delivered SYNTHESIZE task finds the stored model and is a no-op. */
  @Test
  void synthesizeIsRetrySafe() throws Exception {
    long buildId = insertBuild(jobId, "{\"runSmokeTests\": true}");
    String yaml = Fixtures.load("reference-pipeline.yml");

    assertEquals(
        TitanFlowExecution.SynthesisResult.SYNTHESIZED,
        new TitanFlowExecution(stores, buildId).synthesize(yaml));
    String firstModel = stores.builds().findById(buildId).orElseThrow().pipelineModelJson;

    // A second delivery (the reaper re-queued the task) detects the stored model and skips.
    assertEquals(
        TitanFlowExecution.SynthesisResult.ALREADY_SYNTHESIZED,
        new TitanFlowExecution(stores, buildId).synthesize(yaml));
    assertEquals(
        firstModel,
        stores.builds().findById(buildId).orElseThrow().pipelineModelJson,
        "a re-synthesis must not overwrite the stored model");
  }

  /** Bake consumes the synthesized model and materialises the DAG — without re-parsing. */
  @Test
  void bakeConsumesPreSynthesizedModel() throws Exception {
    long buildId = insertBuild(jobId, "{\"runSmokeTests\": true}");

    // Phase 1: synthesize.
    new TitanFlowExecution(stores, buildId).synthesize(Fixtures.load("reference-pipeline.yml"));

    // Phase 2: bake — a fresh instance, no pipeline script handed in. It can only succeed by
    // reading the model that synthesis stored.
    TitanFlowExecution.BakeResult result = new TitanFlowExecution(stores, buildId).bake();
    assertEquals(TitanFlowExecution.BakeResult.BAKED, result);

    // 3 stages + 4 steps + 1 gate.
    assertEquals(8, stores.flowNodes().countByBuild(buildId), "every node is materialised");
    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("RUNNING", build.status, "bake activates the build");
    assertNotNull(build.startedAt);
  }

  /** Bake with no synthesized model fails loudly — synthesis must run first. */
  @Test
  void bakeWithoutSynthesisFails() throws Exception {
    long buildId = insertBuild(jobId, "{}");

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class, () -> new TitanFlowExecution(stores, buildId).bake());
    assertTrue(ex.getMessage().contains("no synthesized model"));
    assertEquals(0, stores.flowNodes().countByBuild(buildId), "no DAG without synthesis");
  }

  /** A re-delivered BAKE task finds the materialised DAG and is a no-op. */
  @Test
  void bakeIsRetrySafeAfterSplit() throws Exception {
    long buildId = insertBuild(jobId, "{\"runSmokeTests\": true}");
    new TitanFlowExecution(stores, buildId).synthesize(Fixtures.load("reference-pipeline.yml"));

    assertEquals(
        TitanFlowExecution.BakeResult.BAKED, new TitanFlowExecution(stores, buildId).bake());
    int afterFirst = stores.flowNodes().countByBuild(buildId);

    // A second BAKE delivery detects the existing flow_nodes and skips — it cannot key off
    // pipeline_model_json (synthesis wrote that), so it keys off the materialised DAG.
    assertEquals(
        TitanFlowExecution.BakeResult.ALREADY_BAKED,
        new TitanFlowExecution(stores, buildId).bake());
    assertEquals(
        afterFirst,
        stores.flowNodes().countByBuild(buildId),
        "a re-bake must not duplicate flow nodes");
  }

  /** Invalid YAML fails at synthesis — fast, before the build, with no DB write. */
  @Test
  void invalidPipelineFailsAtSynthesis() throws Exception {
    long buildId = insertBuild(jobId, "{}");
    String broken =
        "titan:\n  stages:\n"
            + "    - stage: A\n      dependsOn: [B]\n      steps: []\n"
            + "    - stage: B\n      dependsOn: [A]\n      steps: []\n"; // a cycle

    assertThrows(
        PipelineParseException.class,
        () -> new TitanFlowExecution(stores, buildId).synthesize(broken));

    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertNull(build.pipelineModelJson, "a failed synthesis persists no model");
    assertEquals("QUEUED", build.status, "a failed synthesis does not move the build");
    assertEquals(0, stores.flowNodes().countByBuild(buildId));
  }

  /** The bake(script) convenience runs both phases and stays retry-safe end to end. */
  @Test
  void bakeFromScriptConvenienceRunsBothPhases() throws Exception {
    long buildId = insertBuild(jobId, "{\"runSmokeTests\": true}");
    String yaml = Fixtures.load("reference-pipeline.yml");

    assertEquals(
        TitanFlowExecution.BakeResult.BAKED, new TitanFlowExecution(stores, buildId).bake(yaml));
    assertEquals(8, stores.flowNodes().countByBuild(buildId));

    // A full re-delivery: synthesis skips (model stored), bake skips (DAG materialised).
    assertEquals(
        TitanFlowExecution.BakeResult.ALREADY_BAKED,
        new TitanFlowExecution(stores, buildId).bake(yaml));
    assertEquals(8, stores.flowNodes().countByBuild(buildId), "no duplicate nodes");
  }

  /**
   * design/38 Stage 1b queue routing: a worker SYNTHESIZE task lands on the shared {@code
   * synthesis} queue (not the {@code default} controller queue, not an agent-id queue), and {@link
   * io.adaptiq.titan.store.TaskQueueDao#findLatestSynthesisTask} finds it.
   */
  @Test
  void workerSynthesisTaskIsRoutedToTheSharedSynthesisQueue() throws Exception {
    long buildId = insertBuild(jobId, "{}");

    // No synthesis task yet — the controller's coordinator would dispatch one.
    assertTrue(stores.taskQueue().findLatestSynthesisTask(buildId).isEmpty());

    io.adaptiq.titan.store.rows.TaskQueueRow synth = new io.adaptiq.titan.store.rows.TaskQueueRow();
    synth.type = "EXECUTE_COMMAND";
    synth.queueName = "synthesis";
    synth.status = "QUEUED";
    synth.priority = 10;
    synth.payloadJson = "{\"action\":\"SYNTHESIZE\",\"buildId\":" + buildId + "}";
    synth.attempts = 0;
    synth.maxAttempts = 3;
    synth.visibilityTimeoutSeconds = 300;
    synth.buildId = buildId;
    stores.taskQueue().insert(synth);

    var found = stores.taskQueue().findLatestSynthesisTask(buildId);
    assertTrue(found.isPresent(), "the synthesis task is found by build id");
    assertEquals("synthesis", found.get().queueName, "synthesis runs off the shared queue");
    assertEquals("EXECUTE_COMMAND", found.get().type, "synthesis is worker-claimable work");
    assertEquals("QUEUED", found.get().status);
  }

  // ---- helpers --------------------------------------------------------

  private static long insertJob(Connection c, String pipelineYaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "synth/job-" + System.nanoTime());
      ps.setString(2, pipelineYaml);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private long insertBuild(long jobId, String parametersJson) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.builds (job_id, build_number, status, parameters_json) "
                    + "VALUES (?, (SELECT COALESCE(MAX(build_number), 0) + 1 FROM titan.builds "
                    + "WHERE job_id = ?), 'QUEUED', ?)",
                Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, jobId);
      ps.setLong(2, jobId);
      ps.setString(3, parametersJson);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }
}

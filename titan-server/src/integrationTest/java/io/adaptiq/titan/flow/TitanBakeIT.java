package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.flow.parser.PipelineParseException;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link TitanFlowExecution#bake} against real PostgreSQL — Chunk 6C.
 *
 * <p>Proves the bake: a job's Titan YAML pipeline becomes the immutable {@code pipeline_model_json}
 * and the {@code titan.flow_nodes} DAG, in one atomic transaction. Covers the design/31 6C
 * done-when: the DAG materialises with correct statuses, the bake is retry-safe (a re-delivered
 * {@code START_PIPELINE} does not re-bake), {@code when:} is evaluated at bake time, and a broken
 * pipeline fails the bake without leaving a partial DAG.
 */
@Testcontainers
class TitanBakeIT {

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

  /** The reference pipeline bakes into the full flow_nodes DAG with correct statuses. */
  @Test
  void bakesReferencePipelineIntoFlowNodeDag() throws Exception {
    long buildId = insertBuild(jobId, "{\"runSmokeTests\": true}");

    TitanFlowExecution.BakeResult result =
        new TitanFlowExecution(stores, buildId).bake(Fixtures.load("reference-pipeline.yml"));
    assertEquals(TitanFlowExecution.BakeResult.BAKED, result);

    Map<String, FlowNodeRow> nodes = nodesById(buildId);
    // 3 stages + 4 steps + 1 gate.
    assertEquals(8, nodes.size(), "every stage/step/gate is materialised");

    // Root stage is QUEUED; downstream stages PENDING.
    assertEquals("QUEUED", nodes.get("build").status, "the root stage is claimable");
    assertEquals("PENDING", nodes.get("smoke-tests").status);
    assertEquals("PENDING", nodes.get("deploy-production").status);

    // The gate is materialised as a STAGE-typed control node tagged in step_descriptor.
    FlowNodeRow gate = nodes.get("qa-approval");
    assertEquals("STAGE", gate.nodeType);
    assertEquals("gate", gate.stepDescriptor);
    assertEquals("PENDING", gate.status);

    // Steps: descriptor + DAG edge to their stage; root-stage steps are PENDING.
    assertEquals("STEP", nodes.get("build-s0").nodeType);
    assertEquals("sh", nodes.get("build-s0").stepDescriptor);
    assertEquals("containerBuild", nodes.get("build-s1").stepDescriptor);
    assertEquals("build", nodes.get("build-s0").parentIds, "a step's DAG parent is its stage");
    assertEquals("PENDING", nodes.get("build-s0").status);

    // dependsOn becomes a parent-id edge between stage rows.
    assertEquals("build", nodes.get("smoke-tests").parentIds);
    assertEquals("smoke-tests", nodes.get("qa-approval").parentIds);

    // The build is RUNNING and the immutable model is persisted.
    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("RUNNING", build.status);
    assertNotNull(build.startedAt);
    assertNotNull(build.pipelineModelJson, "the baked model is persisted, immutable");
    assertTrue(build.pipelineModelJson.contains("QA Approval"));
  }

  /** A re-delivered START_PIPELINE must not re-bake — the baked DAG is immutable. */
  @Test
  void bakeIsRetrySafe() throws Exception {
    long buildId = insertBuild(jobId, "{\"runSmokeTests\": true}");
    String yaml = Fixtures.load("reference-pipeline.yml");

    assertEquals(
        TitanFlowExecution.BakeResult.BAKED, new TitanFlowExecution(stores, buildId).bake(yaml));
    int afterFirst = stores.flowNodes().listByBuild(buildId).size();

    // A second delivery (reaper re-queued the task) detects the baked model and skips.
    assertEquals(
        TitanFlowExecution.BakeResult.ALREADY_BAKED,
        new TitanFlowExecution(stores, buildId).bake(yaml));
    assertEquals(
        afterFirst,
        stores.flowNodes().listByBuild(buildId).size(),
        "a re-bake must not duplicate flow nodes");
  }

  /** when: false at bake time → the stage and its steps are materialised SKIPPED (WYSIWYG). */
  @Test
  void whenFalseStageIsMaterialisedSkipped() throws Exception {
    long buildId = insertBuild(jobId, "{\"runSmokeTests\": false}");
    new TitanFlowExecution(stores, buildId).bake(Fixtures.load("reference-pipeline.yml"));

    Map<String, FlowNodeRow> nodes = nodesById(buildId);
    assertEquals("SKIPPED", nodes.get("smoke-tests").status, "when: false → stage SKIPPED");
    assertEquals("SKIPPED", nodes.get("smoke-tests-s0").status, "its steps are SKIPPED too");
    // The rest of the DAG is unaffected — the skipped stage still exists (WYSIWYG).
    assertEquals("QUEUED", nodes.get("build").status);
    assertEquals(8, nodes.size(), "a skipped stage is materialised, not omitted");
  }

  /** when: true → the conditional stage is a normal PENDING node. */
  @Test
  void whenTrueStageIsNotSkipped() throws Exception {
    long buildId = insertBuild(jobId, "{\"runSmokeTests\": true}");
    new TitanFlowExecution(stores, buildId).bake(Fixtures.load("reference-pipeline.yml"));
    assertEquals("PENDING", nodesById(buildId).get("smoke-tests").status);
  }

  /**
   * A broken pipeline fails the bake before the transaction — no partial DAG, build stays QUEUED.
   */
  @Test
  void invalidPipelineFailsTheBakeAtomically() throws Exception {
    long buildId = insertBuild(jobId, "{}");
    String broken =
        "titan:\n  stages:\n"
            + "    - stage: A\n      dependsOn: [B]\n      steps: []\n"
            + "    - stage: B\n      dependsOn: [A]\n      steps: []\n"; // a cycle

    assertThrows(
        PipelineParseException.class, () -> new TitanFlowExecution(stores, buildId).bake(broken));

    assertEquals(
        0, stores.flowNodes().listByBuild(buildId).size(), "a failed bake leaves no flow nodes");
    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("QUEUED", build.status, "a failed bake does not move the build off QUEUED");
    assertTrue(build.pipelineModelJson == null, "no model is persisted on a failed bake");
  }

  // ---- helpers --------------------------------------------------------

  private static long insertJob(Connection c, String pipelineYaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "bake/job-" + System.nanoTime());
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

  private Map<String, FlowNodeRow> nodesById(long buildId) {
    return stores.flowNodes().listByBuild(buildId).stream()
        .collect(Collectors.toMap(n -> n.nodeId, n -> n));
  }
}

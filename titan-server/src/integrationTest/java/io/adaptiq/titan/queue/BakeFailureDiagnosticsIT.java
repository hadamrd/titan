package io.adaptiq.titan.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.TitanFlowExecution;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression IT for GitHub issue #36 — legacy golden-path pipelines aborted deterministically at
 * BAKE with an opaque, persisted {@code 'Transaction failed'} and zero {@code flow_nodes}.
 *
 * <p>Root cause: the legacy {@code e2e/pipelines/node-app-with-failing-test} fixture carried a
 * stage-level {@code when: "steps.unit-test.result == 'success'"}. Stage-level {@code when:} is
 * evaluated at bake time against {@code params.*} only (docs/reference/pdl.md), so the evaluator
 * threw {@code ExpressionException("unknown variable 'steps'")} — from <em>inside</em> the bake
 * transaction, where {@code TitanStores.withTransaction} wrapped it as {@code
 * TitanDataException("Transaction failed")}, and {@code BakeHandler} persisted only the wrapper's
 * message.
 *
 * <p>Pinned here, adversarially (message CONTENT, not just status):
 *
 * <ol>
 *   <li>The legacy shape now fails the bake with a PRECISE, persisted diagnostic naming the stage,
 *       the expression and the unknown variable — and 'Transaction failed' appears nowhere.
 *   <li>The repaired live fixture (read from {@code e2e/pipelines/…}, so this test tracks what the
 *       golden-path specs actually submit) bakes green: non-empty {@code flow_nodes}, build
 *       RUNNING, ADVANCE enqueued.
 * </ol>
 */
@Testcontainers
class BakeFailureDiagnosticsIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  /**
   * The exact legacy shape that aborted builds 19/20 and 43/44 on the 2026-07-08 smoke runs —
   * distilled from the pre-fix {@code node-app-with-failing-test} fixture. The step bodies are
   * irrelevant to bake (bake materialises, it does not execute); the stage-level {@code steps.*}
   * {@code when:} is the trigger.
   */
  private static final String LEGACY_GOLDEN_PATH_YAML =
      """
      agent: titan-worker-1

      env:
        NODE_ENV: test
        CI: "true"

      stages:
        - stage: install
          steps:
            - sh: echo install

        - stage: unit-test
          dependsOn: [install]
          steps:
            - sh: echo test

        - stage: build
          dependsOn: [unit-test]
          when: "steps.unit-test.result == 'success'"
          steps:
            - sh: echo build
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

  /**
   * Issue #36 repro + observability contract: the legacy stage-level {@code steps.*} {@code when:}
   * must fail the bake with the ROOT cause persisted — never a bare wrapper message.
   */
  @Test
  void legacyStageLevelStepsWhen_failsBakeWithPreciseDiagnostic() throws Exception {
    long buildId = synthesizedBuild(LEGACY_GOLDEN_PATH_YAML);
    TaskQueueRow task = claimedBakeTask(buildId);

    new BakeHandler(support).handle(stores, task, Map.of("buildId", buildId));

    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("FAILED", build.status, "an unbakeable pipeline must fail-close the build");

    // Adversarial oracle: the persisted MESSAGE, not just the status.
    assertNotNull(build.failureSummary, "the failure reason must be persisted");
    assertNotNull(build.errorMessage, "error_message must be persisted");
    for (String persisted : new String[] {build.failureSummary, build.errorMessage}) {
      assertNotEquals("Transaction failed", persisted);
      assertFalse(
          persisted.contains("Transaction failed"),
          "stage-when evaluation now runs BEFORE the bake transaction — the opaque wrapper "
              + "message must not appear at all, got: "
              + persisted);
      assertTrue(
          persisted.contains("Stage 'build'"),
          "the diagnostic must name the offending stage, got: " + persisted);
      assertTrue(
          persisted.contains("steps.unit-test.result"),
          "the diagnostic must quote the offending expression, got: " + persisted);
      assertTrue(
          persisted.contains("unknown variable 'steps'"),
          "the diagnostic must carry the evaluator's root cause, got: " + persisted);
    }

    // A failed bake leaves no partial DAG, and the task row carries the same actionable reason.
    assertEquals(0, stores.flowNodes().listByBuild(buildId).size());
    TaskQueueRow after = stores.taskQueue().findById(task.id).orElseThrow();
    assertEquals("FAILED", after.status);
    assertTrue(
        after.resultJson.contains("unknown variable 'steps'"),
        "the failed BAKE task must carry the root cause, got: " + after.resultJson);
  }

  /**
   * The repaired golden-path fixture — read LIVE from {@code
   * e2e/pipelines/node-app-with-failing-test/titan-pipeline.yml} so this regression tracks exactly
   * what the two legacy specs submit — must bake green: non-empty {@code flow_nodes} (the
   * adversarial oracle for issue #36's "zero flow_nodes" symptom), build RUNNING, ADVANCE enqueued.
   */
  @Test
  void repairedGoldenPathFixture_bakesGreenWithNonEmptyFlowNodes() throws Exception {
    long buildId = synthesizedBuild(liveGoldenPathFixtureYaml());
    TaskQueueRow task = claimedBakeTask(buildId);

    new BakeHandler(support).handle(stores, task, Map.of("buildId", buildId));

    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("RUNNING", build.status, "the fixed fixture must bake and activate");
    assertTrue(
        stores.flowNodes().listByBuild(buildId).size() >= 6,
        "install/unit-test/build stages + their steps must all materialise");

    TaskQueueRow after = stores.taskQueue().findById(task.id).orElseThrow();
    assertEquals("COMPLETED", after.status);
    long advanceCount =
        stores.taskQueue().listByBuild(buildId).stream()
            .filter(t -> t.payloadJson != null && t.payloadJson.contains("\"ADVANCE\""))
            .count();
    assertTrue(advanceCount >= 1, "a successful bake must enqueue the first ADVANCE");
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  /** Insert a job + QUEUED build and run worker-equivalent synthesis, returning the build id. */
  private long synthesizedBuild(@NonNull String pipelineYaml) throws Exception {
    long jobId;
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) "
                    + "VALUES (?, ?, '{}')",
                Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "bake-diagnostics/job-" + System.nanoTime());
      ps.setString(2, pipelineYaml);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        jobId = keys.getLong(1);
      }
    }
    long buildId;
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.builds (job_id, build_number, status, parameters_json) "
                    + "VALUES (?, 1, 'QUEUED', '{}')",
                Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, jobId);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        buildId = keys.getLong(1);
      }
    }
    // Synthesis parses + persists the model (it must ACCEPT the legacy shape — the live failures
    // passed synthesis and died at BAKE, which is exactly what this IT reproduces).
    assertEquals(
        TitanFlowExecution.SynthesisResult.SYNTHESIZED,
        new TitanFlowExecution(stores, buildId).synthesize(pipelineYaml));
    return buildId;
  }

  /** A claimed ORCHESTRATE/BAKE task row for {@code buildId}, as the QueueProcessor would hold. */
  @NonNull
  private TaskQueueRow claimedBakeTask(long buildId) {
    TaskQueueRow t = new TaskQueueRow();
    t.type = "ORCHESTRATE";
    t.queueName = "default";
    t.status = "CLAIMED";
    t.priority = 5;
    t.payloadJson = "{\"action\":\"BAKE\",\"buildId\":" + buildId + "}";
    t.attempts = 1;
    t.maxAttempts = 3;
    t.visibilityTimeoutSeconds = 300;
    t.buildId = buildId;
    t.availableAt = Instant.now();
    t.claimToken = UUID.randomUUID();
    t.claimedBy = "bake-diagnostics-it";
    t.claimedAt = Instant.now();
    long id = stores.taskQueue().insert(t);
    return stores.taskQueue().findById(id).orElseThrow();
  }

  /**
   * The live golden-path fixture the two legacy specs submit, located by walking up from the test
   * working directory. A missing fixture is a LOUD failure — this test exists precisely so the
   * fixture cannot silently rot (issue #36).
   */
  @NonNull
  private static String liveGoldenPathFixtureYaml() throws IOException {
    Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null) {
      Path candidate = dir.resolve("e2e/pipelines/node-app-with-failing-test/titan-pipeline.yml");
      if (Files.exists(candidate)) {
        return Files.readString(candidate);
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException(
        "e2e/pipelines/node-app-with-failing-test/titan-pipeline.yml not found walking up from "
            + System.getProperty("user.dir")
            + " — the golden-path fixture moved; update BakeFailureDiagnosticsIT");
  }
}

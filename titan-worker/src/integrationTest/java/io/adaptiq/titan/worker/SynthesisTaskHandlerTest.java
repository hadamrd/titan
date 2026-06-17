package io.adaptiq.titan.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for worker-side pipeline synthesis (design/38 Stage 1b) against real PostgreSQL
 * — {@link SynthesisTaskHandler} parses a build's pipeline script into a {@code PipelineModel} and
 * writes {@code titan.builds.pipeline_model_json}.
 *
 * <p>The {@code titan} schema is loaded from the plugin module's real {@code V*.sql} migrations, so
 * this test can never drift from the production schema.
 */
@Testcontainers
class SynthesisTaskHandlerTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  /** A minimal root-form Titan pipeline (design/29 §3 — no legacy titan: wrapper). */
  private static final String ROOT_PIPELINE =
      "agent: linux\n"
          + "stages:\n"
          + "  - stage: Build\n"
          + "    steps:\n"
          + "      - sh: echo build\n"
          + "  - stage: Test\n"
          + "    dependsOn: [Build]\n"
          + "    steps:\n"
          + "      - sh: echo test\n";

  /** The same pipeline under the legacy titan: wrapper — must synthesise identically. */
  private static final String WRAPPED_PIPELINE =
      "titan:\n"
          + "  agent: linux\n"
          + "  stages:\n"
          + "    - stage: Build\n"
          + "      steps:\n"
          + "        - sh: echo build\n";

  private WorkerDb db;

  private SynthesisTaskHandler handler() {
    return new SynthesisTaskHandler(db);
  }

  @BeforeEach
  void setUp() throws Exception {
    Path migrations = Path.of("../titan-db-core/src/main/resources/io/adaptiq/titan/db/migration");
    assertTrue(Files.isDirectory(migrations), "engine migrations not found");
    try (Connection c = conn()) {
      TestMigrations.resetAndApply(c, migrations);
    }
    WorkerConfig cfg =
        new WorkerConfig(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            "test-worker",
            "Test Worker",
            "linux",
            1,
            "/titan",
            "NORMAL",
            "default",
            "synthesis",
            Path.of("."),
            "",
            Path.of("."),
            1000,
            10000,
            "",
            java.util.Map.of());
    db = new WorkerDb(cfg);
  }

  private Connection conn() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /** Insert a job + a QUEUED build, return the build id. */
  private long insertBuild() throws Exception {
    try (Connection c = conn()) {
      long jobId;
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO titan.jobs (full_name, pipeline_script) VALUES (?, 'x') RETURNING id")) {
        ps.setString(1, "synth/job-" + System.nanoTime());
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          jobId = rs.getLong(1);
        }
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO titan.builds (job_id, build_number, status) "
                  + "VALUES (?, 1, 'QUEUED') RETURNING id")) {
        ps.setLong(1, jobId);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          return rs.getLong(1);
        }
      }
    }
  }

  /** Enqueue a SYNTHESIZE task on the synthesis queue and claim it as the worker would. */
  private WorkerDb.ClaimedTask enqueueAndClaim(long buildId, String pipelineScript)
      throws Exception {
    com.fasterxml.jackson.databind.ObjectMapper json =
        new com.fasterxml.jackson.databind.ObjectMapper();
    String payload =
        json.writeValueAsString(
            java.util.Map.of(
                "action", "SYNTHESIZE", "buildId", buildId, "pipelineScript", pipelineScript));
    try (Connection c = conn();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.task_queue (type, queue_name, status, payload_json, build_id) "
                    + "VALUES ('EXECUTE_COMMAND', 'synthesis', 'QUEUED', ?, ?)")) {
      ps.setString(1, payload);
      ps.setLong(2, buildId);
      ps.executeUpdate();
    }
    Optional<WorkerDb.ClaimedTask> claimed = db.claim("test-worker", "synthesis");
    assertTrue(claimed.isPresent(), "the SYNTHESIZE task must be claimable from 'synthesis'");
    return claimed.get();
  }

  private String pipelineModelJson(long buildId) throws Exception {
    try (Connection c = conn();
        PreparedStatement ps =
            c.prepareStatement("SELECT pipeline_model_json FROM titan.builds WHERE id=?")) {
      ps.setLong(1, buildId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  /** The happy path: a root-form pipeline is parsed and its model JSON persisted. */
  @Test
  void synthesisWritesThePipelineModel() throws Exception {
    long buildId = insertBuild();
    WorkerDb.ClaimedTask task = enqueueAndClaim(buildId, ROOT_PIPELINE);

    SynthesisTaskHandler.Result result = handler().run(task);

    assertTrue(result.success(), "synthesis of a valid pipeline succeeds");
    String model = pipelineModelJson(buildId);
    assertNotNull(model, "synthesis writes pipeline_model_json");
    assertTrue(model.contains("Build") && model.contains("Test"), model);
  }

  /** A legacy titan:-wrapped pipeline still synthesises (design/38 Part A back-compat). */
  @Test
  void synthesisAcceptsTheLegacyTitanWrapper() throws Exception {
    long buildId = insertBuild();
    WorkerDb.ClaimedTask task = enqueueAndClaim(buildId, WRAPPED_PIPELINE);

    SynthesisTaskHandler.Result result = handler().run(task);

    assertTrue(result.success());
    assertNotNull(pipelineModelJson(buildId));
  }

  /** A re-delivered SYNTHESIZE task finds the stored model and is a no-op (design/30). */
  @Test
  void synthesisIsRetrySafe() throws Exception {
    long buildId = insertBuild();

    SynthesisTaskHandler handler = handler();
    WorkerDb.ClaimedTask first = enqueueAndClaim(buildId, ROOT_PIPELINE);
    assertTrue(handler.run(first).success());
    String modelAfterFirst = pipelineModelJson(buildId);

    // A second delivery (the reaper re-queued it) detects the stored model and skips.
    WorkerDb.ClaimedTask second = enqueueAndClaim(buildId, ROOT_PIPELINE);
    SynthesisTaskHandler.Result result = handler.run(second);
    assertTrue(result.success(), "a re-delivered synthesis is a successful no-op");
    assertTrue(result.resultJson().contains("ALREADY_SYNTHESIZED"), result.resultJson());
    assertEquals(
        modelAfterFirst,
        pipelineModelJson(buildId),
        "a re-synthesis must not overwrite the stored model");
  }

  /** A broken pipeline (cyclic DAG) fails synthesis — fast, with no model written. */
  @Test
  void brokenPipelineFailsSynthesis() throws Exception {
    long buildId = insertBuild();
    String cyclic =
        "stages:\n"
            + "  - stage: A\n    dependsOn: [B]\n    steps: []\n"
            + "  - stage: B\n    dependsOn: [A]\n    steps: []\n";
    WorkerDb.ClaimedTask task = enqueueAndClaim(buildId, cyclic);

    SynthesisTaskHandler.Result result = handler().run(task);

    assertFalse(result.success(), "a broken DAG fails synthesis");
    assertTrue(result.resultJson().contains("error"), result.resultJson());
    assertNull(pipelineModelJson(buildId), "a failed synthesis persists no model");
  }
}

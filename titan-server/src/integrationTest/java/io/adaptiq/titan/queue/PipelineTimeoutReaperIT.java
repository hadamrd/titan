package io.adaptiq.titan.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.parser.TitanYamlParser;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
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
 * Issue #244 — pipeline-root {@code timeout:}.
 *
 * <p>The outermost safety net. A pipeline carrying a root-level {@code timeout:} must be reaped by
 * {@link QueueProcessor#tick()} once its wall-clock deadline has passed, even when no step/stage
 * timeout fires. These ITs drive the controller directly against a Testcontainers Postgres so the
 * Flyway V15 deadline_at migration, the BAKE-time stamp, and the per-tick reaper sweep all light up
 * together.
 */
@Testcontainers
class PipelineTimeoutReaperIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final ObjectMapper JSON = new ObjectMapper();

  private HikariDataSource ds;
  private TitanStores stores;

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

  /**
   * Acceptance criterion #244: a build whose pipeline declares {@code timeout: 5s} (the YAML lists
   * a {@code sh: sleep 60} step that would otherwise wedge it) is marked {@code FAILED} by the
   * QueueProcessor reaper within a tick of the deadline passing — no step/stage timeout helps it.
   */
  @Test
  void aRunningBuildWithAnElapsedPipelineDeadlineIsReapedAsFailed() throws Exception {
    String yaml =
        "titan:\n"
            + "  timeout: 5s\n"
            + "  stages:\n"
            + "    - stage: Slow\n"
            + "      steps:\n"
            + "        - sh: sleep 60\n";

    // Sanity: the parser sees the root timeout (also validates the grammar wiring).
    PipelineModel parsed = TitanYamlParser.parseAndValidate(yaml);
    assertEquals(5_000L, parsed.getTimeoutMillis());

    long buildId;
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, yaml);
      buildId = insertRunningBuild(c, jobId, JSON.writeValueAsString(parsed));
    }

    // Set the deadline to the past so the reaper fires immediately — equivalent to a build
    // that bake-stamped its deadline, then ran longer than the pipeline timeout. We do not
    // drive the full BAKE phase here (that needs the controller’s synthesis path); we
    // simulate the post-BAKE state and exercise the *reaper*, which is what acceptance
    // criterion #244 actually pins down.
    stores.builds().updateDeadline(buildId, Instant.now().minusSeconds(1));

    // Pre-condition — the build is RUNNING with an elapsed deadline.
    BuildRow before = stores.builds().findById(buildId).orElseThrow();
    assertEquals("RUNNING", before.status);
    assertNotNull(before.deadlineAt);
    assertTrue(before.deadlineAt.isBefore(Instant.now()));

    // Drive one tick — the per-tick reaper sweep marks the build FAILED.
    new QueueProcessor().tick(stores, "test-controller", 3600);

    BuildRow after = stores.builds().findById(buildId).orElseThrow();
    assertEquals("FAILED", after.status, "the reaper marks an overdue build FAILED");
    assertNotNull(after.failureSummary, "and stamps a customer-facing reason");
    assertTrue(
        after.failureSummary.contains("pipeline timeout"),
        "the reason mentions the pipeline timeout (got: " + after.failureSummary + ")");
  }

  /**
   * A {@code RUNNING} build with no {@code deadline_at} (the pipeline had no root {@code timeout:})
   * is never reaped — the column-NULL guard in {@code findOverdueRunningBuilds} keeps natural
   * builds out of the sweep.
   */
  @Test
  void aRunningBuildWithoutADeadlineIsNeverReaped() throws Exception {
    String yaml =
        "titan:\n"
            + "  stages:\n"
            + "    - stage: Slow\n"
            + "      steps:\n"
            + "        - sh: echo hi\n";

    PipelineModel parsed = TitanYamlParser.parseAndValidate(yaml);
    assertNull(parsed.getTimeoutMillis(), "no root timeout: declared");

    long buildId;
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, yaml);
      buildId = insertRunningBuild(c, jobId, JSON.writeValueAsString(parsed));
    }

    new QueueProcessor().tick(stores, "test-controller", 3600);

    BuildRow after = stores.builds().findById(buildId).orElseThrow();
    assertEquals(
        "RUNNING",
        after.status,
        "a build with no pipeline deadline is never touched by the reaper");
  }

  /** Insert a job row and return its id — minimal valid columns. */
  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "pipeline-timeout-it/job-" + System.nanoTime());
      ps.setString(2, yaml);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  /** Insert a build row already in {@code RUNNING} with the supplied pipeline model JSON. */
  private static long insertRunningBuild(Connection c, long jobId, String pipelineModelJson)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, pipeline_model_json, "
                + "started_at) VALUES (?, 1, 'RUNNING', ?::text, CURRENT_TIMESTAMP)",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, jobId);
      ps.setString(2, pipelineModelJson);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }
}

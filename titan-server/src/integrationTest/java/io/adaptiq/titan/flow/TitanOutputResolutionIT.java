package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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
 * Integration test for the output store + {@code ${{ … }}} reference resolution against real
 * PostgreSQL — Chunk 6E (design/29 §6).
 *
 * <p>A {@code Build} stage publishes an output ({@code version}); a downstream {@code Deploy}
 * stage's step references it as {@code ${{ steps['Build'].outputs.version }}}. The test drives the
 * DAG with a stubbed worker that publishes the output, and asserts the orchestrator resolved the
 * reference into {@code Deploy}'s dispatched {@code EXECUTE_COMMAND} payload — at dispatch time,
 * with the real value, not the raw template.
 */
@Testcontainers
class TitanOutputResolutionIT {

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
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, Fixtures.load("output-passing.yml"));
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(Fixtures.load("output-passing.yml"));
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  /** Build publishes version=1.4.2; Deploy's ${{ … }} reference resolves to it at dispatch. */
  @Test
  void downstreamStepReceivesTheResolvedUpstreamOutput() {
    TitanOrchestrator orchestrator = new TitanOrchestrator(stores, buildId);
    String deployPayload = null;

    for (int pass = 0; pass < 30; pass++) {
      TitanOrchestrator.AdvanceResult result = orchestrator.advance();
      if (result.buildFinished()) {
        break;
      }
      Optional<TaskQueueRow> claimed;
      while ((claimed = stores.taskQueue().claim("stub", "default", UUID.randomUUID()))
          .isPresent()) {
        TaskQueueRow t = claimed.get();
        if ("build-s0".equals(t.nodeId)) {
          // The Build step publishes an output in its result_json.
          stores
              .taskQueue()
              .complete(
                  t.id,
                  t.claimToken,
                  "COMPLETED",
                  "{\"exitCode\":0,\"outputs\":{\"version\":\"1.4.2\"}}");
        } else {
          if ("deploy-s0".equals(t.nodeId)) {
            deployPayload = t.payloadJson; // capture the resolved payload
          }
          stores.taskQueue().complete(t.id, t.claimToken, "COMPLETED", "{\"exitCode\":0}");
        }
      }
    }

    assertNotNull(deployPayload, "the Deploy step must have been dispatched");
    assertTrue(
        deployPayload.contains("1.4.2"),
        "the ${{ … }} reference must be resolved to the published value: " + deployPayload);
    assertFalse(
        deployPayload.contains("${{"),
        "no raw template may survive into the dispatched payload: " + deployPayload);
    assertTrue(
        deployPayload.contains("deploy --version 1.4.2"),
        "the resolved command is substituted in place: " + deployPayload);

    assertEquals("SUCCESS", stores.builds().findById(buildId).orElseThrow().status);
  }

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "out/job-" + System.nanoTime());
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

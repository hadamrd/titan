package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.flow.parser.PipelineParseException;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
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
 * Integration tests for declared build parameters against real PostgreSQL — Chunk 6E (parameters).
 *
 * <p>Real, end-to-end on the controller side — submit a build with supplied values, bake it, and
 * assert the effective parameters: defaults applied, validation enforced, {@code when:} driven by
 * the resolved values. No stubs — the bake is the real bake.
 */
@Testcontainers
class TitanParametersIT {

  private static final ObjectMapper JSON = new ObjectMapper();

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
      jobId = insertJob(c, Fixtures.load("parameterised-pipeline.yml"));
    }
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  /** A build supplying only the required param: defaults fill the rest, all persisted. */
  @Test
  void bakeAppliesDefaultsAndPersistsTheEffectiveParameters() throws Exception {
    long buildId = insertBuild("{\"branch\":\"main\"}");
    new TitanFlowExecution(stores, buildId).bake(Fixtures.load("parameterised-pipeline.yml"));

    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    JsonNode params = JSON.readTree(build.parametersJson);
    assertEquals("main", params.get("branch").asText(), "the supplied value is kept");
    assertEquals("dev", params.get("deployEnv").asText(), "the choice default is applied");
    assertTrue(params.get("runSmoke").asBoolean(), "the boolean default is applied");

    // when: params.runSmoke == true → Smoke runs.
    assertEquals("PENDING", nodeStatuses(buildId).get("smoke"));
  }

  /** A supplied parameter drives when: — runSmoke=false materialises Smoke SKIPPED. */
  @Test
  void aSuppliedParameterDrivesAWhenCondition() {
    long buildId = insertBuild("{\"branch\":\"main\",\"runSmoke\":false}");
    new TitanFlowExecution(stores, buildId).bake(Fixtures.load("parameterised-pipeline.yml"));
    assertEquals(
        "SKIPPED",
        nodeStatuses(buildId).get("smoke"),
        "when: params.runSmoke == true is false → stage SKIPPED");
  }

  /** Omitting a required parameter fails the bake — no DAG, build stays QUEUED. */
  @Test
  void aMissingRequiredParameterFailsTheBake() {
    long buildId = insertBuild("{}"); // no branch
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                new TitanFlowExecution(stores, buildId)
                    .bake(Fixtures.load("parameterised-pipeline.yml")));
    assertTrue(e.getMessage().contains("required parameter 'branch'"), e.getMessage());
    assertEquals(0, stores.flowNodes().listByBuild(buildId).size(), "no partial DAG");
    assertEquals("QUEUED", stores.builds().findById(buildId).orElseThrow().status);
  }

  /** A choice value outside the declared set fails the bake. */
  @Test
  void anInvalidChoiceValueFailsTheBake() {
    long buildId = insertBuild("{\"branch\":\"main\",\"deployEnv\":\"qa\"}");
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                new TitanFlowExecution(stores, buildId)
                    .bake(Fixtures.load("parameterised-pipeline.yml")));
    assertTrue(e.getMessage().contains("deployEnv"), e.getMessage());
  }

  private Map<String, String> nodeStatuses(long buildId) {
    return stores.flowNodes().listByBuild(buildId).stream()
        .collect(Collectors.toMap(n -> n.nodeId, n -> n.status));
  }

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "param/job-" + System.nanoTime());
      ps.setString(2, yaml);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private long insertBuild(String parametersJson) {
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
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}

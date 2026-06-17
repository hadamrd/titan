package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.queue.QueueProcessor;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for issue #360 — declarative {@code notify:} hooks fire on a BAKE-time pipeline
 * failure, even though the {@link io.adaptiq.titan.flow.model.PipelineModel} never materialised.
 *
 * <p>Reproduces the build-46 class of failure: a malformed pipeline script crashes the BAKE phase,
 * the build is marked {@code FAILED}, and the user's configured webhook must receive a synthetic
 * {@code BAKE_FAILURE} envelope. The IT drives a real {@link QueueProcessor#tick} pass against a
 * Testcontainers Postgres so the wiring from {@code handleBake} → {@code markBuildFailed} → {@code
 * fireBakeFailureNotifications} is exercised end-to-end.
 */
@Testcontainers
class BakeFailureNotifyIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final ObjectMapper JSON = new ObjectMapper();

  private HikariDataSource ds;
  private TitanStores stores;
  private HttpServer webhookServer;
  private int webhookPort;
  private ConcurrentLinkedQueue<String> deliveries;

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

    // Capture every webhook POST body.
    deliveries = new ConcurrentLinkedQueue<>();
    webhookServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    webhookServer.createContext(
        "/",
        (HttpExchange ex) -> {
          byte[] body = ex.getRequestBody().readAllBytes();
          deliveries.add(new String(body, StandardCharsets.UTF_8));
          ex.sendResponseHeaders(204, -1);
          ex.close();
        });
    webhookServer.start();
    webhookPort = webhookServer.getAddress().getPort();

    // Inject a dispatcher bound to a short-timeout HttpClient so the IT does not block on a
    // misrouted URL.
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    QueueProcessor.setBakeFailureDispatcherForTest(new NotificationDispatcher(client));
  }

  @AfterEach
  void tearDown() {
    QueueProcessor.setBakeFailureDispatcherForTest(null);
    if (webhookServer != null) {
      webhookServer.stop(0);
    }
    if (ds != null) {
      ds.close();
    }
  }

  /**
   * A build whose synthesized {@code pipeline_model_json} cannot be deserialised crashes BAKE; the
   * job declared {@code notify: on: [failure]} so a single {@code BAKE_FAILURE} webhook POST must
   * land. The build itself reaches {@code FAILED} (regression on the existing markBuildFailed
   * path).
   */
  @Test
  void bakeFailure_firesNotifyHook_withBakeFailureKind() throws Exception {
    String hookUrl = "http://127.0.0.1:" + webhookPort + "/hook";
    // The build-46 class of script: a top-level `pipeline:` wrapper that parser rejects. We pair
    // it with a valid `notify:` block at the document root so the tolerant pre-bake parse can find
    // the hook even though the rest of the YAML is malformed for the parser.
    String pipelineScript =
        ""
            + "notify:\n"
            + "  - type: webhook\n"
            + "    on: [failure]\n"
            + "    url: "
            + hookUrl
            + "\n"
            + "pipeline:\n"
            + "  stages:\n"
            + "    - stage: Build\n"
            + "      steps:\n"
            + "        - sh: echo hi\n";
    long jobId;
    long buildId;
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "notify/bake-fail-" + System.nanoTime(), pipelineScript);
      // We bypass the synthesis worker and write a bad pipeline_model_json directly so BAKE
      // crashes on deserialise. The notify hook still lives on the job row.
      buildId = insertBuildWithBadModel(c, jobId);
      enqueueBakeTask(c, buildId);
    }

    // One tick drives handleBake → throw → markBuildFailed → fireBakeFailureNotifications.
    int processed = new QueueProcessor().tick(stores, "test-controller", 3600);
    assertTrue(processed >= 1, "the bake task should have been claimed and dispatched");

    // Build is FAILED.
    String status;
    String failureSummary;
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement("SELECT status, failure_summary FROM titan.builds WHERE id = ?")) {
      ps.setLong(1, buildId);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        status = rs.getString(1);
        failureSummary = rs.getString(2);
      }
    }
    assertEquals("FAILED", status, "build must reach FAILED on bake crash");
    assertNotNull(failureSummary, "failure_summary recorded for operator");

    waitForDelivery(Duration.ofSeconds(5));
    assertEquals(
        1, deliveries.size(), "exactly one BAKE_FAILURE webhook POST captured: " + deliveries);
    JsonNode body = JSON.readTree(deliveries.peek());
    assertEquals(buildId, body.get("buildId").asLong());
    assertEquals("FAILED", body.get("status").asText());
    assertEquals(
        "BAKE_FAILURE",
        body.get("kind").asText(),
        "synthetic kind discriminates from regular terminal-state hooks");
    assertNotNull(body.get("failureReason"), "envelope carries the parser/bake error");
    assertTrue(
        body.get("failureReason").asText().length() <= 1025,
        "failureReason truncated at ~1 KB (1024 + ellipsis)");
    JsonNode jobNameNode = body.get("jobName");
    assertNotNull(jobNameNode, "jobName populated from the job row");
    assertTrue(
        jobNameNode.asText().startsWith("notify/bake-fail-"),
        "jobName carried into envelope: " + jobNameNode.asText());
  }

  /**
   * A bake failure on a job whose only configured notify hook is {@code on: [success]} must NOT
   * fire — a BAKE failure is a FAILED terminal state, not SUCCESS.
   */
  @Test
  void bakeFailure_doesNotFire_whenHookConfiguredForSuccessOnly() throws Exception {
    String hookUrl = "http://127.0.0.1:" + webhookPort + "/never";
    String pipelineScript =
        ""
            + "notify:\n"
            + "  - type: webhook\n"
            + "    on: [success]\n"
            + "    url: "
            + hookUrl
            + "\n"
            + "pipeline:\n"
            + "  stages:\n"
            + "    - stage: Build\n"
            + "      steps:\n"
            + "        - sh: echo hi\n";
    long buildId;
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, "notify/bake-fail-success-" + System.nanoTime(), pipelineScript);
      buildId = insertBuildWithBadModel(c, jobId);
      enqueueBakeTask(c, buildId);
    }

    new QueueProcessor().tick(stores, "test-controller", 3600);

    // Give the dispatcher a beat — even though we expect zero deliveries, we wait a bit so a
    // misroute would be observable instead of racing past the assertion.
    Thread.sleep(500);

    // Confirm the build still moved to FAILED so we're asserting the right scenario (no false
    // negative from the build never reaching the markBuildFailed path).
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement("SELECT status FROM titan.builds WHERE id = ?")) {
      ps.setLong(1, buildId);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals("FAILED", rs.getString(1));
      }
    }

    assertEquals(
        0, deliveries.size(), "on:[success] hook must NOT fire on a BAKE failure: " + deliveries);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void waitForDelivery(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (deliveries.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
  }

  private static long insertJob(Connection c, String fullName, String pipelineScript)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, fullName);
      ps.setString(2, pipelineScript);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  /**
   * Insert a {@code QUEUED} build with a deliberately-malformed {@code pipeline_model_json} —
   * deserialisation throws inside {@code TitanFlowExecution.bake()} and the {@code QueueProcessor}
   * routes the failure into {@code markBuildFailed}. Matches the post-synthesis path the BAKE
   * handler runs against.
   */
  private static long insertBuildWithBadModel(Connection c, long jobId) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, pipeline_model_json) "
                + "VALUES (?, 1, 'QUEUED', ?)",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, jobId);
      // Invalid JSON — Jackson throws on readValue, BAKE fails, markBuildFailed runs.
      ps.setString(2, "{not json at all");
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private void enqueueBakeTask(Connection c, long buildId) throws Exception {
    TaskQueueRow bake = new TaskQueueRow();
    bake.type = "ORCHESTRATE";
    bake.queueName = "default";
    bake.status = "QUEUED";
    bake.priority = 5;
    bake.payloadJson = "{\"action\":\"BAKE\",\"buildId\":" + buildId + "}";
    bake.attempts = 0;
    bake.maxAttempts = 3;
    bake.visibilityTimeoutSeconds = 300;
    bake.buildId = buildId;
    stores.taskQueue().insert(bake);
  }
}

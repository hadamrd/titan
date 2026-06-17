package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.credentials.Credential;
import io.adaptiq.titan.credentials.CredentialUpdate;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.credentials.NewCredentialRequest;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for declarative {@code notify:} lifecycle hooks (#245).
 *
 * <p>Drives a real build to its terminal state and asserts the orchestrator POSTs to the webhook
 * URL declared by {@code notify:}, and only on the matching predicate. Uses the JDK's bundled
 * {@code com.sun.net.httpserver.HttpServer} — no new test dep — to capture deliveries.
 */
@Testcontainers
class TitanNotifyIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final ObjectMapper JSON = new ObjectMapper();

  private HikariDataSource ds;
  private TitanStores stores;
  private long buildId;
  private HttpServer webhookServer;
  private int webhookPort;
  private ConcurrentLinkedQueue<String> deliveries;

  /** Per-path delivery capture for the multi-hook stage-level IT (#359). */
  private ConcurrentMap<String, ConcurrentLinkedQueue<String>> deliveriesByPath;

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

    // Spin up a JDK HttpServer that captures the POST body. The legacy `/hook` context feeds the
    // single-queue `deliveries`. A catch-all root context routes any other path into
    // `deliveriesByPath` keyed by request URI — the stage-level IT (#359) uses distinct paths to
    // prove three independent hooks fire.
    deliveries = new ConcurrentLinkedQueue<>();
    deliveriesByPath = new ConcurrentHashMap<>();
    webhookServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    webhookServer.createContext(
        "/hook",
        (HttpExchange ex) -> {
          byte[] body = ex.getRequestBody().readAllBytes();
          deliveries.add(new String(body, StandardCharsets.UTF_8));
          ex.sendResponseHeaders(204, -1);
          ex.close();
        });
    webhookServer.createContext(
        "/",
        (HttpExchange ex) -> {
          String path = ex.getRequestURI().getPath();
          byte[] body = ex.getRequestBody().readAllBytes();
          if (!"/hook".equals(path)) {
            deliveriesByPath
                .computeIfAbsent(path, k -> new ConcurrentLinkedQueue<>())
                .add(new String(body, StandardCharsets.UTF_8));
          }
          ex.sendResponseHeaders(204, -1);
          ex.close();
        });
    webhookServer.start();
    webhookPort = webhookServer.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    if (webhookServer != null) {
      webhookServer.stop(0);
    }
    if (ds != null) {
      ds.close();
    }
  }

  /** A pipeline that succeeds with `notify on [failure]` must NOT POST anywhere. */
  @Test
  void webhookOnFailure_doesNotFireOnSuccess() throws Exception {
    bootstrap(
        ""
            + "notify:\n"
            + "  - type: webhook\n"
            + "    on: [failure]\n"
            + "    url: "
            + hookUrl()
            + "\n"
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: echo ok\n");
    TitanOrchestrator orch = newOrchestrator();

    orch.advance(); // dispatch
    completeClaimed("COMPLETED", "{\"exitCode\":0}");
    TitanOrchestrator.AdvanceResult last = orch.advance();

    assertTrue(last.buildFinished());
    assertEquals("SUCCESS", last.buildResult());
    assertEquals(
        0, deliveries.size(), "on:[failure] hook must NOT fire on a SUCCESS build: " + deliveries);
  }

  /** A pipeline that fails with `notify on [failure]` POSTs the envelope to the hook URL. */
  @Test
  void webhookOnFailure_firesOnFailureWithEnvelope() throws Exception {
    bootstrap(
        ""
            + "notify:\n"
            + "  - type: webhook\n"
            + "    on: [failure]\n"
            + "    url: "
            + hookUrl()
            + "\n"
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: exit 1\n");
    TitanOrchestrator orch = newOrchestrator();

    orch.advance(); // dispatch
    completeClaimed("FAILED", "{\"exitCode\":1}");
    TitanOrchestrator.AdvanceResult last = orch.advance();

    assertTrue(last.buildFinished());
    assertEquals("FAILED", last.buildResult());

    // Wait briefly for the (synchronous) HTTP POST to land in the capture queue.
    waitForDelivery(Duration.ofSeconds(5));

    assertEquals(1, deliveries.size(), "exactly one webhook POST captured: " + deliveries);
    JsonNode body = JSON.readTree(deliveries.peek());
    assertEquals(buildId, body.get("buildId").asLong());
    assertEquals("FAILED", body.get("status").asText());
    assertNull(body.get("stage"), "build-level hook has no 'stage' field");
  }

  /** A hook with `on: [success]` POSTs on a successful build. */
  @Test
  void webhookOnSuccess_firesOnSuccess() throws Exception {
    bootstrap(
        ""
            + "notify:\n"
            + "  - type: webhook\n"
            + "    on: [success]\n"
            + "    url: "
            + hookUrl()
            + "\n"
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: echo ok\n");
    TitanOrchestrator orch = newOrchestrator();

    orch.advance();
    completeClaimed("COMPLETED", "{\"exitCode\":0}");
    TitanOrchestrator.AdvanceResult last = orch.advance();

    assertEquals("SUCCESS", last.buildResult());
    waitForDelivery(Duration.ofSeconds(5));
    assertEquals(1, deliveries.size());
    assertEquals("SUCCESS", JSON.readTree(deliveries.peek()).get("status").asText());
  }

  /**
   * (#358) End-to-end Slack hook: a {@code credentialsId} resolves to the wiremock URL via an
   * in-memory {@link CredentialsService} stub, the dispatcher POSTs the block-kit payload, and the
   * resolved URL is NEVER inline in {@code config_json}.
   */
  @Test
  void slackOnFailure_resolvesCredentialAndPostsBlockKit() throws Exception {
    String slackUrl = hookUrl(); // wiremock takes the place of hooks.slack.com
    InMemoryCredentialsService creds = new InMemoryCredentialsService();
    creds.put("default", "slack-test", slackUrl);

    bootstrap(
        ""
            + "notify:\n"
            + "  - type: slack\n"
            + "    on: [failure]\n"
            + "    credentialsId: slack-test\n"
            + "    channel: '#deploys'\n"
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: exit 1\n");
    TitanOrchestrator orch = newOrchestratorWithCredentials(creds);

    orch.advance();
    completeClaimed("FAILED", "{\"exitCode\":1}");
    TitanOrchestrator.AdvanceResult last = orch.advance();

    assertTrue(last.buildFinished());
    assertEquals("FAILED", last.buildResult());

    waitForDelivery(Duration.ofSeconds(5));
    assertEquals(1, deliveries.size(), "exactly one slack POST captured: " + deliveries);
    JsonNode body = JSON.readTree(deliveries.peek());

    // Slack block-kit shape (NOT the webhook envelope shape).
    assertTrue(body.has("text"), "block-kit has top-level 'text' fallback");
    assertTrue(body.get("blocks").isArray(), "block-kit has 'blocks' array");
    assertTrue(body.get("blocks").size() >= 2, "section + context blocks");
    assertEquals("#deploys", body.get("channel").asText(), "channel pass-through");

    // The Slack URL must NEVER appear inside the persisted job's config_json — it lives only in
    // the credentials store (CONSTITUTION §6).
    String configJson = readJobConfigJson();
    assertTrue(
        !configJson.contains(slackUrl),
        "resolved slack URL must not leak into config_json: " + configJson);
  }

  /**
   * (#359) Stage-level {@code notify:} hooks fire on each stage's terminal transition, independent
   * of pipeline-level hooks. Two stages, no {@code dependsOn} so they both run; stage 1 fails,
   * stage 2 succeeds; {@code failurePolicy: continueOnFailure} stops the orchestrator from sweeping
   * stage 2 to SKIPPED before its step lands. Asserts three distinct wiremock paths each receive
   * exactly one POST.
   */
  @Test
  void stageLevelHooks_fireOnEachStageTerminalState() throws Exception {
    String stage1Url = pathUrl("/stage1");
    String stage2Url = pathUrl("/stage2");
    String pipelineUrl = pathUrl("/pipeline");

    bootstrap(
        ""
            + "failurePolicy: continueOnFailure\n"
            + "notify:\n"
            + "  - type: webhook\n"
            + "    on: [failure]\n"
            + "    url: "
            + pipelineUrl
            + "\n"
            + "stages:\n"
            + "  - stage: One\n"
            + "    notify:\n"
            + "      - type: webhook\n"
            + "        on: [failure]\n"
            + "        url: "
            + stage1Url
            + "\n"
            + "    steps:\n"
            + "      - sh: exit 1\n"
            + "  - stage: Two\n"
            + "    notify:\n"
            + "      - type: webhook\n"
            + "        on: [success]\n"
            + "        url: "
            + stage2Url
            + "\n"
            + "    steps:\n"
            + "      - sh: echo ok\n");
    TitanOrchestrator orch = newOrchestrator();

    // Pass 1: dispatch the first step of each stage (stages are roots, no dependsOn).
    orch.advance();

    // Complete both dispatched tasks — stage One fails, stage Two succeeds. Order does not
    // matter; the orchestrator reconciles whichever it finds.
    completeOneClaimed("FAILED", "{\"exitCode\":1}");
    completeOneClaimed("COMPLETED", "{\"exitCode\":0}");

    TitanOrchestrator.AdvanceResult last = orch.advance();

    assertTrue(last.buildFinished(), "build finishes after both stages reach terminal state");
    assertEquals("FAILED", last.buildResult(), "any-stage-failed → build FAILED");

    waitForDeliveryOnPath("/stage1", Duration.ofSeconds(5));
    waitForDeliveryOnPath("/stage2", Duration.ofSeconds(5));
    waitForDeliveryOnPath("/pipeline", Duration.ofSeconds(5));

    assertEquals(
        1,
        countOnPath("/stage1"),
        "exactly one POST on /stage1 (stage One failure hook): " + deliveriesByPath);
    assertEquals(
        1,
        countOnPath("/stage2"),
        "exactly one POST on /stage2 (stage Two success hook): " + deliveriesByPath);
    assertEquals(
        1,
        countOnPath("/pipeline"),
        "exactly one POST on /pipeline (build-level failure hook): " + deliveriesByPath);

    // Stage hooks carry the 'stage' field; the pipeline hook does not.
    JsonNode stage1Body = JSON.readTree(deliveriesByPath.get("/stage1").peek());
    assertEquals("FAILED", stage1Body.get("status").asText());
    assertEquals("One", stage1Body.get("stage").asText());

    JsonNode stage2Body = JSON.readTree(deliveriesByPath.get("/stage2").peek());
    assertEquals("SUCCESS", stage2Body.get("status").asText());
    assertEquals("Two", stage2Body.get("stage").asText());

    JsonNode pipelineBody = JSON.readTree(deliveriesByPath.get("/pipeline").peek());
    assertEquals("FAILED", pipelineBody.get("status").asText());
    assertNull(pipelineBody.get("stage"), "build-level hook has no 'stage' field");
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private String hookUrl() {
    return "http://127.0.0.1:" + webhookPort + "/hook";
  }

  private String pathUrl(String path) {
    return "http://127.0.0.1:" + webhookPort + path;
  }

  private void completeOneClaimed(String status, String resultJson) {
    Optional<TaskQueueRow> claimed = stores.taskQueue().claim("stub", "default", UUID.randomUUID());
    assertTrue(claimed.isPresent(), "a dispatched EXECUTE_COMMAND task must be claimable");
    TaskQueueRow t = claimed.get();
    stores.taskQueue().complete(t.id, t.claimToken, status, resultJson);
  }

  private int countOnPath(String path) {
    ConcurrentLinkedQueue<String> q = deliveriesByPath.get(path);
    return q == null ? 0 : q.size();
  }

  private void waitForDeliveryOnPath(String path, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (countOnPath(path) == 0 && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
  }

  /**
   * A {@link TitanOrchestrator} whose {@link NotificationDispatcher} uses an HttpClient with a
   * short timeout so a misrouted URL doesn't drag the test out.
   */
  private TitanOrchestrator newOrchestrator() {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    NotificationDispatcher dispatcher = new NotificationDispatcher(client);
    return new TitanOrchestrator(stores, buildId, CredentialsPort.noOp(), dispatcher);
  }

  private TitanOrchestrator newOrchestratorWithCredentials(CredentialsService creds) {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    NotificationDispatcher dispatcher = new NotificationDispatcher(client, creds);
    return new TitanOrchestrator(stores, buildId, CredentialsPort.noOp(), dispatcher);
  }

  private String readJobConfigJson() throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT j.config_json FROM titan.jobs j "
                    + "JOIN titan.builds b ON b.job_id = j.id WHERE b.id = ?")) {
      ps.setLong(1, buildId);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "job row must exist");
        return rs.getString(1);
      }
    }
  }

  private void bootstrap(String yaml) throws Exception {
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, yaml);
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(yaml);
  }

  private void completeClaimed(String status, String resultJson) {
    Optional<TaskQueueRow> claimed = stores.taskQueue().claim("stub", "default", UUID.randomUUID());
    assertTrue(claimed.isPresent(), "a dispatched EXECUTE_COMMAND task must be claimable");
    TaskQueueRow t = claimed.get();
    stores.taskQueue().complete(t.id, t.claimToken, status, resultJson);
  }

  private void waitForDelivery(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (deliveries.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
  }

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "notify/job-" + System.nanoTime());
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

  /**
   * Minimal in-memory {@link CredentialsService} — the IT doesn't exercise the envelope-cipher
   * stack (that's covered elsewhere); it only needs the {@code resolvePlaintext} contract so the
   * dispatcher's Slack branch resolves to the wiremock URL.
   */
  private static final class InMemoryCredentialsService implements CredentialsService {
    private final java.util.Map<String, String> store = new java.util.HashMap<>();

    void put(String scope, String key, String value) {
      store.put(scope + "/" + key, value);
    }

    @Override
    @NonNull
    public Optional<String> resolvePlaintext(@NonNull String scope, @NonNull String key) {
      return Optional.ofNullable(store.get(scope + "/" + key));
    }

    @Override
    @NonNull
    public Optional<Credential> findById(long id) {
      throw new UnsupportedOperationException();
    }

    @Override
    @NonNull
    public Optional<Credential> findByScopeAndKey(@NonNull String scope, @NonNull String key) {
      throw new UnsupportedOperationException();
    }

    @Override
    @NonNull
    public java.util.List<Credential> listAll() {
      throw new UnsupportedOperationException();
    }

    @Override
    @NonNull
    public java.util.List<Credential> listByScope(@NonNull String scope) {
      throw new UnsupportedOperationException();
    }

    @Override
    @NonNull
    public Credential create(@NonNull NewCredentialRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    @NonNull
    public Credential update(long id, @NonNull CredentialUpdate update) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(long id) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int rotateKek() {
      return 0;
    }

    @Override
    @NonNull
    public String backendName() {
      return "in-memory-test";
    }
  }
}

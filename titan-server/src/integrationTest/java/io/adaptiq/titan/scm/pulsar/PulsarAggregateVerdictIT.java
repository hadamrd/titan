package io.adaptiq.titan.scm.pulsar;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.flow.TitanFlowExecution;
import io.adaptiq.titan.queue.QueueProcessor;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration test for issue #6: a customer-shaped, <em>multi-stage</em> {@code lint →
 * test → build} DAG (real {@code dependsOn} chain, run by a real subprocess) collapses to exactly
 * ONE aggregate {@code build} check posted to a Pulsar node by {@link PulsarCheckReporter} — the
 * check Pulsar's {@code required_checks: ["build"]} blocks the merge on.
 *
 * <p><strong>Why this is more than {@link PulsarRealVerdictIT}.</strong> That IT proves the verdict
 * path for a <em>single-stage</em> pipeline. Issue #6 is specifically about a multi-stage DAG and
 * stage-failure <em>propagation</em>: when the middle {@code test} stage fails, the default {@code
 * failurePolicy: blockOnFailure} must SKIP the downstream {@code build} stage, yet the whole-build
 * verdict must still roll up FAILED, and the reporter must still post the single aggregate {@code
 * build} check as {@code conclusion:failure}. No prior test drove a real multi-stage Pulsar DAG to
 * one aggregate posted check.
 *
 * <p><strong>Why this is a genuine RED test (acceptance criterion 4).</strong> The aggregate {@code
 * build} check conclusion is derived from the DAG <em>rollup</em>, NOT from the {@code build}
 * stage's own status — in the sad leg the {@code build} stage is SKIPPED (never runs) while the
 * overall verdict is FAILED. If stage-failure propagation regressed (a mid-DAG failure no longer
 * tainting the rollup — {@code test} fails but {@code build} still runs green, or the verdict rolls
 * up SUCCESS), the reporter would post {@code conclusion:success}; both the {@code
 * conclusion:failure} verify and the {@code build}-stage {@code SKIPPED} assertion go red. That is
 * exactly the regression this IT exists to catch — and it actually runs {@link
 * PulsarCheckReporter}, so deleting that class fails this test (unlike a pure DAG-propagation
 * guard).
 *
 * <p><strong>Scope / harness.</strong> Mirrors {@link PulsarRealVerdictIT}: the Pulsar provenance
 * (trigger type {@code "pulsar"} + a {@code changeId}) is the exact shape {@code
 * PulsarWebhookApi.enqueueBuild} writes; the worker-side synthesis lives in {@code titan-worker}
 * and is unreachable here, so the DAG is baked directly and each step is run by a real {@code sh}
 * subprocess (exit code EARNED, never stubbed). Offline + deterministic: a fake Pulsar node
 * (WireMock), no live network.
 */
@Testcontainers
class PulsarAggregateVerdictIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String REPO = "acme/web";
  private static final String CHANGE_ID = "42";

  /** The reporter URL-encodes the repo; "acme/web" → "acme%2Fweb". */
  private static final String EVENTS_URL =
      "/_pulsar/ledger/acme%2Fweb/changes/" + CHANGE_ID + "/events";

  // Customer-shaped multi-stage DAG: lint -> test -> build via real `dependsOn` (NOT three
  // independent stages). Default failurePolicy (blockOnFailure) applies — no explicit override.
  private static final String DAG_PASS =
      "titan:\n"
          + "  stages:\n"
          + "    - stage: lint\n"
          + "      steps:\n"
          + "        - sh: echo lint ok\n"
          + "    - stage: test\n"
          + "      dependsOn: [lint]\n"
          + "      steps:\n"
          + "        - sh: echo test ok\n"
          + "    - stage: build\n"
          + "      dependsOn: [test]\n"
          + "      steps:\n"
          + "        - sh: echo build ok\n";

  // Same DAG, but the middle `test` stage fails — `build` must end SKIPPED (propagation).
  private static final String DAG_FAIL =
      "titan:\n"
          + "  stages:\n"
          + "    - stage: lint\n"
          + "      steps:\n"
          + "        - sh: echo lint ok\n"
          + "    - stage: test\n"
          + "      dependsOn: [lint]\n"
          + "      steps:\n"
          + "        - sh: exit 1\n"
          + "    - stage: build\n"
          + "      dependsOn: [test]\n"
          + "      steps:\n"
          + "        - sh: echo build ok\n";

  private HikariDataSource ds;
  private TitanStores stores;
  private WireMockServer wiremock;
  private PulsarCheckReporter reporter;

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

    wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wiremock.start();
    wiremock.stubFor(post(urlMatching("/_pulsar/.*")).willReturn(aResponse().withStatus(201)));

    PulsarClient client = new PulsarClient("http://localhost:" + wiremock.port());
    reporter = new PulsarCheckReporter(stores, client, "https://titan.example.com", true);
  }

  @AfterEach
  void tearDown() {
    if (wiremock != null) {
      wiremock.stop();
    }
    if (ds != null) {
      ds.close();
    }
  }

  // ── happy path: all three stages pass → ONE aggregate `build` check = success ──

  @Test
  void multiStageDag_allStagesPass_postsSingleConclusionSuccess() throws Exception {
    assumeTrue(shAvailable(), "POSIX sh not on PATH — skipping real-exec IT");
    long jobId = insertJob(REPO, DAG_PASS);
    long buildId = insertPulsarBuild(jobId);
    bakeAndDrive(buildId, DAG_PASS);

    assertEquals("SUCCESS", stores.builds().findById(buildId).orElseThrow().status);
    assertEquals("SUCCESS", stageStatus(buildId, "lint"), "lint stage");
    assertEquals("SUCCESS", stageStatus(buildId, "test"), "test stage");
    assertEquals("SUCCESS", stageStatus(buildId, "build"), "build stage");

    fireTerminal(buildId);

    // The whole DAG collapses to EXACTLY ONE check named `build` = success (criterion 5).
    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"check\":\"build\""))
            .withRequestBody(containing("\"conclusion\":\"success\"")));
    assertEquals(1, wiremock.findAll(postRequestedFor(urlMatching("/_pulsar/.*"))).size());
  }

  // ── adversarial: middle `test` stage fails → `build` SKIPPED → ONE check = failure ──

  @Test
  void multiStageDag_testStageFails_buildSkipped_postsSingleConclusionFailure() throws Exception {
    assumeTrue(shAvailable(), "POSIX sh not on PATH — skipping real-exec IT");
    long jobId = insertJob(REPO, DAG_FAIL);
    long buildId = insertPulsarBuild(jobId);
    bakeAndDrive(buildId, DAG_FAIL);

    // Failure propagates under the default blockOnFailure: lint ran, test failed (real `exit 1`),
    // and the downstream build stage is SKIPPED — never run, never green, never pending/hung.
    assertEquals("SUCCESS", stageStatus(buildId, "lint"), "lint runs and passes");
    assertEquals("FAILED", stageStatus(buildId, "test"), "test is the failing stage");
    assertEquals("SKIPPED", stageStatus(buildId, "build"), "downstream build stage is SKIPPED");
    // The DAG rollup is FAILED even though the `build` stage itself never produced a verdict.
    assertEquals("FAILED", stores.builds().findById(buildId).orElseThrow().status);

    fireTerminal(buildId);

    // The single aggregate `build` check is failure — the gate stays refused (criterion 3 & 5).
    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"check\":\"build\""))
            .withRequestBody(containing("\"conclusion\":\"failure\"")));
    // RED-TEST proof: if propagation regressed, the rollup would be SUCCESS and the reporter would
    // post `conclusion:success` instead — this verify(0) would then fail.
    wiremock.verify(
        0,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"conclusion\":\"success\"")));
    assertEquals(1, wiremock.findAll(postRequestedFor(urlMatching("/_pulsar/.*"))).size());
  }

  // ── drive: real bake + real-subprocess execution (mirrors PulsarRealVerdictIT) ──

  /** Bake the DAG, then advance it to terminal running every step as a real subprocess. */
  private void bakeAndDrive(long buildId, String yaml) throws Exception {
    new TitanFlowExecution(stores, buildId).bake(yaml);
    seedAdvance(buildId);

    QueueProcessor proc = new QueueProcessor();
    for (int pass = 0; pass < 60; pass++) {
      proc.tick(stores, "it-controller", 3600);
      runQueuedSteps(buildId);

      try (Connection c = ds.getConnection();
          Statement st = c.createStatement()) {
        st.executeUpdate(
            "UPDATE titan.task_queue SET available_at = CURRENT_TIMESTAMP "
                + "WHERE status = 'QUEUED' AND available_at > CURRENT_TIMESTAMP");
      }

      String status = stores.builds().findById(buildId).orElseThrow().status;
      if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
        return;
      }
    }
    throw new AssertionError(
        "build "
            + buildId
            + " did not finish; status="
            + stores.builds().findById(buildId).orElseThrow().status);
  }

  /**
   * Run every QUEUED EXECUTE_COMMAND step for the build as a real subprocess; complete with exit.
   */
  private void runQueuedSteps(long buildId) throws Exception {
    List<Long> ids = new ArrayList<>();
    List<String> payloads = new ArrayList<>();
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT id, payload_json FROM titan.task_queue WHERE build_id = "
                    + buildId
                    + " AND type = 'EXECUTE_COMMAND' AND status = 'QUEUED'")) {
      while (rs.next()) {
        ids.add(rs.getLong(1));
        payloads.add(rs.getString(2));
      }
    }
    for (int i = 0; i < ids.size(); i++) {
      int exit = runRealSubprocess(payloads.get(i));
      try (Connection c = ds.getConnection();
          Statement st = c.createStatement()) {
        st.executeUpdate(
            "UPDATE titan.task_queue SET status = 'COMPLETED', "
                + "result_json = '{\"exitCode\":"
                + exit
                + "}', completed_at = CURRENT_TIMESTAMP WHERE id = "
                + ids.get(i));
      }
    }
  }

  /** Run an EXECUTE_COMMAND payload's {@code command} array for real; return its exit code. */
  private static int runRealSubprocess(String payloadJson) throws Exception {
    JsonNode cmd = JSON.readTree(payloadJson).path("command");
    List<String> command = new ArrayList<>();
    if (cmd.isArray()) {
      cmd.forEach(n -> command.add(n.asText()));
    }
    if (command.isEmpty()) {
      return 0;
    }
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    if (!p.waitFor(30, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      return 124;
    }
    return p.exitValue();
  }

  /**
   * Fire the terminal {@link BuildStateChangedEvent} into the reporter (the real observer entry).
   */
  private void fireTerminal(long buildId) {
    BuildRow row = stores.builds().findById(buildId).orElseThrow();
    reporter.onBuildStateChanged(
        new BuildStateChangedEvent(
            row.id, row.status, row.triggerType, row.triggerMetaJson, row.jobId, row.buildNumber));
  }

  /** The stage node's status (stage name slugs to its node id: lint/test/build). */
  private String stageStatus(long buildId, String stage) {
    return stores.flowNodes().findByBuildAndNode(buildId, stage).orElseThrow().status;
  }

  // ── fixtures ──────────────────────────────────────────────────────────────────

  private void seedAdvance(long buildId) {
    stores
        .taskQueue()
        .enqueue(
            "ORCHESTRATE",
            "default",
            0,
            "{\"action\":\"ADVANCE\",\"buildId\":" + buildId + "}",
            3,
            3600,
            buildId,
            null);
  }

  private long insertJob(String fullName, String yaml) throws Exception {
    try (Connection c = ds.getConnection();
        java.sql.PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.jobs (full_name, pipeline_script, config_json, enabled) "
                    + "VALUES (?, ?, '{}', TRUE)",
                Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, fullName);
      ps.setString(2, yaml);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  /** A build with the exact Pulsar provenance {@code PulsarWebhookApi} writes. */
  private long insertPulsarBuild(long jobId) throws Exception {
    try (Connection c = ds.getConnection();
        java.sql.PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.builds "
                    + "(job_id, build_number, status, trigger_type, trigger_meta_json) "
                    + "VALUES (?, 1, 'QUEUED', 'pulsar', ?)",
                Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, jobId);
      ps.setString(2, "{\"commitSha\":\"deadbeef\",\"changeId\":\"" + CHANGE_ID + "\"}");
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static boolean shAvailable() {
    try {
      return new ProcessBuilder("sh", "-c", "exit 0").start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }
}

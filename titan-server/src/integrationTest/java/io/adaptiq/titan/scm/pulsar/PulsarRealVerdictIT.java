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
 * End-to-end integration test for the Pulsar verdict path (issue #3): a real one-step {@code
 * ci.yml} is baked, its step is run by a <em>real subprocess</em> (no hand-completed {@code
 * EXECUTE_COMMAND}), the build reaches a real terminal status, and {@link PulsarCheckReporter}
 * observes the terminal {@link BuildStateChangedEvent} and posts the {@code build} check to a fake
 * Pulsar node (WireMock) — exactly the payload that flips the change's merge gate.
 *
 * <p><strong>Why this is more than {@link io.adaptiq.titan.api.PulsarWebhookEnqueueIT}.</strong>
 * That IT proves the real clone + {@code .titan/pipelines} discovery up to the {@code QUEUED}
 * enqueue boundary and stops. This IT drives the <em>execution → verdict → reporter</em> leg the
 * enqueue IT never exercises: the synthesis-baked DAG advances through the real {@link
 * QueueProcessor}, a real {@code sh} subprocess decides the exit code (so the build's verdict is
 * genuinely earned, not stubbed), and the reporter maps that real verdict onto the wire. The
 * adversarial mirror — a step that exits non-zero → {@code FAILED} → {@code conclusion:failure} —
 * is what goes RED if execution is ever left mocked: a hand-completed task would always report exit
 * 0.
 *
 * <p><strong>Scope.</strong> The Pulsar provenance (trigger type {@code "pulsar"} + a {@code
 * changeId} in the trigger meta) is the exact shape {@code PulsarWebhookApi.enqueueBuild} / {@code
 * BuildEnqueuer} write; worker-side synthesis runs in the {@code titan-worker} module and is
 * unreachable here, so the DAG is baked directly (the established {@code TitanOrchestratorIT} /
 * {@code BuildArchiveAndStartedAtIT} pattern) and the step is run by a real subprocess (the
 * established {@code ChaosWorker} pattern — the production {@code TaskExecutor} is package-private
 * in another module). Offline + deterministic: no live Pulsar node, no network.
 */
@Testcontainers
class PulsarRealVerdictIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String REPO = "acme/web";
  private static final String CHANGE_ID = "42";

  /** The reporter URL-encodes the repo; "acme/web" → "acme%2Fweb". */
  private static final String EVENTS_URL =
      "/_pulsar/ledger/acme%2Fweb/changes/" + CHANGE_ID + "/events";

  private static final String ONE_STEP_OK =
      "titan:\n  stages:\n    - stage: Build\n      steps:\n        - sh: echo ok\n";
  private static final String ONE_STEP_FAIL =
      "titan:\n  stages:\n    - stage: Build\n      steps:\n        - sh: exit 1\n";

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

  // ── happy path ───────────────────────────────────────────────────────────────

  @Test
  void realChange_oneStepSucceeds_postsConclusionSuccess() throws Exception {
    assumeTrue(shAvailable(), "POSIX sh not on PATH — skipping real-exec IT");
    long jobId = insertJob(REPO, ONE_STEP_OK);
    long buildId = insertPulsarBuild(jobId);
    bakeAndDrive(buildId, ONE_STEP_OK);

    assertEquals("SUCCESS", stores.builds().findById(buildId).orElseThrow().status);

    fireTerminal(buildId);

    // The real verdict reaches the wire as the gate-clearing build check for THIS change/repo.
    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"check\":\"build\""))
            .withRequestBody(containing("\"conclusion\":\"success\"")));
    assertEquals(1, wiremock.findAll(postRequestedFor(urlMatching("/_pulsar/.*"))).size());
  }

  // ── adversarial / sad path ─────────────────────────────────────────────────────

  @Test
  void realChange_stepExitsNonZero_postsConclusionFailure() throws Exception {
    assumeTrue(shAvailable(), "POSIX sh not on PATH — skipping real-exec IT");
    long jobId = insertJob(REPO, ONE_STEP_FAIL);
    long buildId = insertPulsarBuild(jobId);
    bakeAndDrive(buildId, ONE_STEP_FAIL);

    // The verdict is genuinely earned: a real `sh -c "exit 1"` failed the step.
    assertEquals("FAILED", stores.builds().findById(buildId).orElseThrow().status);

    fireTerminal(buildId);

    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"check\":\"build\""))
            .withRequestBody(containing("\"conclusion\":\"failure\"")));
    // The gate stays refused — no success is ever emitted for that change.
    wiremock.verify(
        0,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"conclusion\":\"success\"")));
  }

  // ── filter guard ────────────────────────────────────────────────────────────────

  @Test
  void nonPulsarBuild_drivenSamePath_postsNoCheck() throws Exception {
    assumeTrue(shAvailable(), "POSIX sh not on PATH — skipping real-exec IT");
    long jobId = insertJob(REPO, ONE_STEP_OK);
    // Same harness, same real execution — but a manual (non-Pulsar) provenance.
    long buildId = insertBuild(jobId, "manual", "{\"actor\":\"alice\"}");
    bakeAndDrive(buildId, ONE_STEP_OK);

    assertEquals("SUCCESS", stores.builds().findById(buildId).orElseThrow().status);

    fireTerminal(buildId);

    // The reporter's triggerType/changeId filter must gate posting: a manual build touches
    // nothing on the Pulsar ledger.
    wiremock.verify(0, postRequestedFor(urlMatching("/_pulsar/.*")));
  }

  // ── drive: real bake + real-subprocess execution ────────────────────────────────

  /** Bake the DAG, then advance it to terminal running every step as a real subprocess. */
  private void bakeAndDrive(long buildId, String yaml) throws Exception {
    new TitanFlowExecution(stores, buildId).bake(yaml);
    seedAdvance(buildId);

    QueueProcessor proc = new QueueProcessor();
    for (int pass = 0; pass < 60; pass++) {
      proc.tick(stores, "it-controller", 3600);

      // Real worker: claim each QUEUED EXECUTE_COMMAND by reading its command array and running it
      // as a real subprocess — the exit code is EARNED, never hand-set. (We complete the row in
      // the same pass, before the next tick's no-worker sweep, mirroring
      // BuildArchiveAndStartedAtIT.)
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
    List<long[]> ids = new ArrayList<>();
    List<String> payloads = new ArrayList<>();
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT id, payload_json FROM titan.task_queue WHERE build_id = "
                    + buildId
                    + " AND type = 'EXECUTE_COMMAND' AND status = 'QUEUED'")) {
      while (rs.next()) {
        ids.add(new long[] {rs.getLong(1)});
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
                + ids.get(i)[0]);
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
   * Fire the terminal {@link BuildStateChangedEvent} into the reporter exactly as {@code
   * BuildCloser.fireStateChangedEvent} does (read the fresh row, carry its provenance) — the real
   * observer entry point.
   */
  private void fireTerminal(long buildId) {
    BuildRow row = stores.builds().findById(buildId).orElseThrow();
    reporter.onBuildStateChanged(
        new BuildStateChangedEvent(
            row.id, row.status, row.triggerType, row.triggerMetaJson, row.jobId, row.buildNumber));
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

  /**
   * A build with the exact Pulsar provenance {@code BuildEnqueuer}/{@code PulsarWebhookApi} write.
   */
  private long insertPulsarBuild(long jobId) throws Exception {
    return insertBuild(
        jobId, "pulsar", "{\"commitSha\":\"deadbeef\",\"changeId\":\"" + CHANGE_ID + "\"}");
  }

  private long insertBuild(long jobId, String triggerType, String triggerMetaJson)
      throws Exception {
    try (Connection c = ds.getConnection();
        java.sql.PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.builds "
                    + "(job_id, build_number, status, trigger_type, trigger_meta_json) "
                    + "VALUES (?, 1, 'QUEUED', ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, jobId);
      ps.setString(2, triggerType);
      ps.setString(3, triggerMetaJson);
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

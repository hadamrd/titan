package io.adaptiq.titan.scm.pulsar;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.build.BuildEnqueuedEvent;
import io.adaptiq.titan.build.BuildEnqueuer;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.flow.TitanFlowExecution;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Adversarial full-loop IT for issue #99 — the {@code queued → in_progress → final} check lifecycle
 * driven by the ENGINE's own transitions against a real Postgres (Testcontainers) and a WireMock
 * Pulsar ledger node.
 *
 * <p>The loop under test, in production order:
 *
 * <ol>
 *   <li><b>Enqueue</b> — {@link BuildEnqueuer#enqueue} (the one canonical seam every Pulsar trigger
 *       path routes through) inserts the {@code QUEUED} row; the enqueue-time {@link
 *       BuildEnqueuedEvent} — the same event {@code PulsarWebhookApi.receive}'s sink fires — drives
 *       the reporter's {@code pending/queued} post.
 *   <li><b>Bake / activate</b> — the real {@link TitanFlowExecution#bake()} synthesises, mater-
 *       ialises the DAG and flips the row QUEUED→RUNNING via the {@code activateIfQueued} CAS; the
 *       post-commit {@link BuildStateChangedEvent} (the issue-#99 fix) drives the reporter's {@code
 *       pending/in_progress} post. Before the fix NOTHING fired here and the ledger jumped straight
 *       from {@code queued} to the terminal verdict.
 *   <li><b>Terminal</b> — a {@code SUCCESS} {@link BuildStateChangedEvent} built from the build's
 *       own row (the shape {@code BuildCloser} emits at close; its programmatic-Arc dispatch needs
 *       a CDI container, so the IT hands the same event to the observer directly) drives the
 *       gate-clearing {@code success} post.
 * </ol>
 *
 * <p>Assertions are adversarial: the three lifecycle events must land <b>in order</b> and
 * <b>exactly once each</b> — a re-delivered BAKE (task retry, design/30) must not double-post
 * {@code in_progress}, and the merge gate must clear ONLY on the terminal event.
 */
@Testcontainers
class PulsarInProgressLifecycleIT {

  private static final String REPO = "sample";
  private static final String CHANGE_ID = "42";
  private static final String EVENTS_URL =
      "/_pulsar/ledger/" + REPO + "/changes/" + CHANGE_ID + "/events";
  private static final String TRIGGER_META =
      "{\"commitSha\":\"oid1abc\",\"changeId\":\"" + CHANGE_ID + "\"}";

  private static final String YAML =
      """
      titan:
        stages:
          - stage: Build
            steps:
              - sh: "echo hi"
      """;

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private WireMockServer node;
  private PulsarCheckReporter reporter;
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

    JobRow job = new JobRow();
    job.fullName = REPO;
    job.pipelineScript = YAML;
    job.configJson = "{}";
    job.enabled = true;
    job.createdAt = Instant.now();
    job.updatedAt = job.createdAt;
    jobId = stores.jobs().insert(job);

    node = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    node.start();
    node.stubFor(post(urlEqualTo(EVENTS_URL)).willReturn(aResponse().withStatus(201)));
    reporter =
        new PulsarCheckReporter(
            stores,
            new PulsarClient("http://localhost:" + node.port()),
            "https://titan.example.com",
            true);
  }

  @AfterEach
  void tearDown() {
    if (node != null) {
      node.stop();
    }
    if (ds != null) {
      ds.close();
    }
  }

  @Test
  void enqueueBakeActivateFinish_ledgerSeesQueuedInProgressFinal_inOrderExactlyOnce() {
    // 1) Enqueue — the canonical production seam; the enqueue-time event posts pending/queued.
    long buildId = BuildEnqueuer.enqueue(stores, jobId, "pulsar", "pulsar", TRIGGER_META, null);
    BuildRow queued = stores.builds().findById(buildId).orElseThrow();
    assertEquals("QUEUED", queued.status, "fixture sanity: enqueue inserts a QUEUED row");
    reporter.onBuildEnqueued(
        new BuildEnqueuedEvent(
            buildId, jobId, queued.triggerType, queued.triggerMetaJson, queued.buildNumber));
    assertEquals("refused_incomplete", gate(), "queued must not clear the gate");

    // 2) Bake — the REAL engine transition. The post-commit RUNNING event (issue-#99 fix) reaches
    //    the reporter through its production observer method.
    TitanFlowExecution execution =
        new TitanFlowExecution(stores, buildId, reporter::onBuildStateChanged);
    assertEquals(TitanFlowExecution.BakeResult.BAKED, execution.bake(YAML));

    BuildRow running = stores.builds().findById(buildId).orElseThrow();
    assertEquals("RUNNING", running.status, "activateIfQueued flipped the row");
    assertNotNull(running.startedAt, "activateIfQueued stamped started_at");
    assertEquals("refused_incomplete", gate(), "in_progress must not clear the gate");

    // Adversarial: a re-delivered BAKE (task retry) is ALREADY_BAKED and posts NOTHING more.
    assertEquals(
        TitanFlowExecution.BakeResult.ALREADY_BAKED,
        new TitanFlowExecution(stores, buildId, reporter::onBuildStateChanged).bake(YAML));
    assertEquals(2, ledger().size(), "a re-delivered bake must not double-post in_progress");

    // 3) Terminal — the SUCCESS event carrying the row's own provenance (BuildCloser's shape).
    reporter.onBuildStateChanged(
        new BuildStateChangedEvent(
            buildId,
            "SUCCESS",
            running.triggerType,
            running.triggerMetaJson,
            running.jobId,
            running.buildNumber));
    assertEquals("allowed", gate(), "the gate clears ONLY on the terminal success event");

    // The ledger holds the three lifecycle events IN ORDER, each EXACTLY ONCE.
    List<JsonNode> ledger = ledger();
    assertEquals(3, ledger.size(), "exactly three lifecycle events — no dupes, no gaps");

    JsonNode first = ledger.get(0);
    assertEquals("ci", first.path("kind").asText());
    assertEquals("pending", first.path("conclusion").asText());
    assertEquals("queued", first.path("phase").asText(), "first event is enqueue-time queued");

    JsonNode second = ledger.get(1);
    assertEquals("pending", second.path("conclusion").asText());
    assertEquals(
        "in_progress",
        second.path("phase").asText(),
        "second event is the engine's own worker-pickup transition");

    JsonNode third = ledger.get(2);
    assertEquals("success", third.path("conclusion").asText());
    assertFalse(third.has("phase"), "terminal success carries no in-flight phase");

    // Exactly-once per phase, adversarially recounted across the whole ledger.
    assertEquals(1, countPhase(ledger, "queued"));
    assertEquals(1, countPhase(ledger, "in_progress"));
    assertEquals(
        1,
        ledger.stream().filter(e -> "success".equals(e.path("conclusion").asText())).count(),
        "exactly one terminal verdict");
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static long countPhase(List<JsonNode> ledger, String phase) {
    return ledger.stream().filter(e -> phase.equals(e.path("phase").asText())).count();
  }

  /** The change ledger, folded from WireMock's request journal in posted order. */
  private List<JsonNode> ledger() {
    List<JsonNode> events = new ArrayList<>();
    for (LoggedRequest req : node.findAll(postRequestedFor(urlEqualTo(EVENTS_URL)))) {
      try {
        events.add(PulsarJson.MAPPER.readTree(req.getBody()));
      } catch (Exception e) {
        throw new IllegalStateException("stub node failed to parse CI event", e);
      }
    }
    return events;
  }

  /** The merge-gate verdict the node folds from the ledger: cleared ONLY by a terminal success. */
  private String gate() {
    for (JsonNode event : ledger()) {
      if ("success".equals(event.path("conclusion").asText())) {
        return "allowed";
      }
    }
    return "refused_incomplete";
  }
}

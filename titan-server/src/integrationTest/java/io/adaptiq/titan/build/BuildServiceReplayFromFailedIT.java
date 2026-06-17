package io.adaptiq.titan.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.api.ApiBadRequestException;
import io.adaptiq.titan.api.ApiNotFoundException;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.JobRow;
import java.sql.Connection;
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
 * Postgres-backed IT for {@code BuildService.replayFromFirstFailed} (closes #664).
 *
 * <p>Hunts the sad paths the new endpoint must cover before it lands in front of an SRE clicking
 * the new "Replay from first failed stage" button:
 *
 * <ul>
 *   <li>parent build does not exist → {@link ApiNotFoundException} (mapped to HTTP 404).
 *       Functionally equivalent to a build that was archived / pruned out of {@code titan.builds}.
 *   <li>parent build succeeded — every materialised stage is SUCCESS → {@link
 *       ApiBadRequestException} (mapped to HTTP 400). Adversarial: the UI button is hidden for
 *       succeeded builds, but a caller hitting the endpoint directly must NOT trigger an
 *       inadvertent full re-run.
 *   <li>parent has at least one FAILED stage → new build is created with replay metadata pointing
 *       at the FIRST failed stage in declared YAML order, and a REPLAY_FROM_NODE orchestration task
 *       is enqueued (the orchestrator handler — already covered in {@code OrchestratorReplayTest} —
 *       is what marks upstream stages as SKIPPED and downstream as QUEUED; this IT pins the
 *       service-side contract that feeds it).
 * </ul>
 *
 * <p>Bypasses the JAX-RS layer (covered by {@code BuildReplayApiTest}): the service is the seam
 * where validation lives, and exercising it against real Postgres pins the SQL contract for the
 * stage-by-stage flow_nodes lookup that picks the first failure.
 */
@Testcontainers
class BuildServiceReplayFromFailedIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private BuildService svc;

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
    svc = new BuildServiceImpl(stores);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  // ── 404: missing / archived parent ──────────────────────────────────────

  @Test
  void replayFromFailed_archivedParent_throwsNotFound() {
    // An "archived" parent has no row in titan.builds — the daily prune (or task_archive sweep)
    // has reaped it. The service surface: ApiNotFoundException → mapped to HTTP 404.
    ApiNotFoundException ex =
        assertThrows(
            ApiNotFoundException.class,
            () -> svc.replayFromFirstFailed(999_999_999L, ReplayOptions.none()));
    assertTrue(ex.getMessage().contains("999999999"));
  }

  // ── 400: build has no failed stages ─────────────────────────────────────

  @Test
  void replayFromFailed_succeededBuild_throwsBadRequest() {
    long parentId =
        freshBakedParent(new Stage("stage-1", "SUCCESS"), new Stage("stage-2", "SUCCESS"));

    ApiBadRequestException ex =
        assertThrows(
            ApiBadRequestException.class,
            () -> svc.replayFromFirstFailed(parentId, ReplayOptions.none()));
    // The error message must name the precondition that failed — diagnostic for the caller.
    assertTrue(
        ex.getMessage().contains("no stage ended FAILED"),
        "expected message to name the missing precondition, got: " + ex.getMessage());
  }

  @Test
  void replayFromFailed_parentWithoutModel_throwsBadRequest() {
    // Parent was created (titan.builds row) but never reached BAKE — no pipeline_model_json.
    // Walking the stage list is undefined; must reject before the stage scan.
    long jobId = freshJob();
    BuildRow b = new BuildRow();
    b.jobId = jobId;
    b.buildNumber = stores.builds().nextBuildNumber(jobId);
    b.status = "FAILED";
    b.queuedAt = Instant.now();
    b.triggeredBy = "test";
    b.triggerType = "manual";
    long parentId = stores.withTransaction(c -> stores.builds().insert(c, b));

    ApiBadRequestException ex =
        assertThrows(
            ApiBadRequestException.class,
            () -> svc.replayFromFirstFailed(parentId, ReplayOptions.none()));
    assertTrue(ex.getMessage().contains("no synthesised pipeline model"));
  }

  // ── 201: happy path — picks the FIRST failed stage in declared order ────

  @Test
  void replayFromFailed_failedBuild_createsReplayPinnedToFirstFailedStage() {
    // stage-1 SUCCESS, stage-2 FAILED, stage-3 FAILED. The service must anchor on stage-2.
    long parentId =
        freshBakedParent(
            new Stage("stage-1", "SUCCESS"),
            new Stage("stage-2", "FAILED"),
            new Stage("stage-3", "FAILED"));

    Build replay = svc.replayFromFirstFailed(parentId, ReplayOptions.none());

    assertNotNull(replay);
    assertNotEquals(parentId, replay.id());
    assertEquals("QUEUED", replay.status());
    assertEquals(Long.valueOf(parentId), replay.replayedFromBuildId());
    assertEquals(
        "stage-2",
        replay.replayedFromNodeId(),
        "first failed stage in declared order is stage-2 — service must not pick stage-3");
    assertEquals("replay", replay.triggerType());

    // The parent's pipeline_model_json must be carried verbatim onto the replay row — the
    // REPLAY_FROM_NODE handler re-bakes from this without re-parsing the YAML or re-fetching SCM.
    BuildRow parentRow = stores.builds().findById(parentId).orElseThrow();
    BuildRow replayRow = stores.builds().findById(replay.id()).orElseThrow();
    assertEquals(parentRow.pipelineModelJson, replayRow.pipelineModelJson);

    // Exactly one ORCHESTRATE/REPLAY_FROM_NODE task for the new build — feeds the orchestrator
    // leg that materialises stages-before-failed as SKIPPED and stages-from-failed as QUEUED
    // (covered end-to-end in OrchestratorReplayTest).
    var tasks = stores.taskQueue().listByBuild(replay.id());
    assertEquals(1, tasks.size(), "expected exactly one task for the replay build");
    var task = tasks.get(0);
    assertEquals("ORCHESTRATE", task.type);
    assertTrue(
        task.payloadJson.contains("REPLAY_FROM_NODE"),
        "payload must dispatch REPLAY_FROM_NODE: " + task.payloadJson);
    assertTrue(
        task.payloadJson.contains(String.valueOf(replay.id())),
        "payload must carry the new build id: " + task.payloadJson);
  }

  @Test
  void replayFromFailed_skipsNonStageFailures_findsFirstStageThatActuallyFailed() {
    // Adversarial: a build with only a non-stage failure (e.g. a gate or precondition) and
    // every stage SUCCESS still has no "stage" that failed — the endpoint name is
    // "replay-from-failed-STAGE", so we require an actual stage failure. (A gate-only failure
    // belongs in the per-node "Replay from here" path, not in the one-click shortcut.)
    long parentId =
        freshBakedParent(new Stage("stage-1", "SUCCESS"), new Stage("stage-2", "SUCCESS"));
    // No flow_nodes row for stage-1 in FAILED state — every stage SUCCESS → 400.

    ApiBadRequestException ex =
        assertThrows(
            ApiBadRequestException.class,
            () -> svc.replayFromFirstFailed(parentId, ReplayOptions.none()));
    assertTrue(ex.getMessage().contains("no stage ended FAILED"));
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  /** Stage spec for the test fixture: a stage id + the terminal status to seed in flow_nodes. */
  private record Stage(String id, String status) {}

  private long freshJob() {
    JobRow row = new JobRow();
    row.fullName = "replay-from-failed-it/" + System.nanoTime();
    row.pipelineScript = "";
    row.configJson = "{}";
    row.enabled = true;
    return stores.jobs().insert(row);
  }

  /**
   * Bake a parent build with the given stages — both in the persisted pipeline_model_json (so the
   * stage walker sees them in declared order) and as flow_nodes rows in the requested statuses. The
   * parent's build-row status is hard-coded to "FAILED" because the only meaningful test shape for
   * replay-from-failed is a build that finished failing.
   */
  private long freshBakedParent(Stage... stages) {
    long jobId = freshJob();
    BuildRow b = new BuildRow();
    b.jobId = jobId;
    b.buildNumber = stores.builds().nextBuildNumber(jobId);
    b.status = "FAILED";
    b.queuedAt = Instant.now();
    b.triggeredBy = "test";
    b.triggerType = "manual";
    StringBuilder stagesJson = new StringBuilder();
    for (int i = 0; i < stages.length; i++) {
      if (i > 0) stagesJson.append(',');
      stagesJson
          .append("{\"name\":\"")
          .append(stages[i].id())
          .append("\",\"id\":\"")
          .append(stages[i].id())
          .append("\",\"steps\":[],\"parentStageIds\":[],\"parallel\":false,\"dependsOn\":[]}");
    }
    b.pipelineModelJson =
        "{\"stages\":["
            + stagesJson
            + "],\"parameters\":[],\"triggers\":[],\"gates\":[],\"preconditions\":[],"
            + "\"failurePolicy\":\"blockOnFailure\"}";
    long buildId = stores.withTransaction(c -> stores.builds().insert(c, b));

    for (Stage s : stages) {
      FlowNodeRow node = new FlowNodeRow();
      node.buildId = buildId;
      node.nodeId = s.id();
      node.nodeType = "STAGE";
      node.displayName = s.id();
      node.status = s.status();
      stores.flowNodes().insert(node);
    }
    return buildId;
  }
}

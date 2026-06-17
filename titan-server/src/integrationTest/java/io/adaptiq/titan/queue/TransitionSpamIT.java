package io.adaptiq.titan.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.flow.TitanOrchestrator;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.AuditLogRow;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Issue #1074 — the per-build transition-spam guard, driven against a real PostgreSQL via
 * Testcontainers + the full Flyway migration set.
 *
 * <p>{@link TransitionCapAuditTest} and {@code HandlerTransitionCapTest} pin the in-memory wiring;
 * this IT proves the load-bearing SQL — the {@code transition_cap_warn} / {@code
 * transition_cap_halt} {@code audit_log} INSERTs and the build fail-close write — parse and execute
 * against postgres-16 (the rig's actual engine).
 *
 * <p>The contrived pipeline re-enters {@code ORCHESTRATE/ADVANCE} via a stub orchestrator whose
 * every pass is non-productive (zero dispatched, zero reconciled, not parked, not finished) —
 * exactly the pathology the guard exists for. The production caps are 200/1000; this IT tightens
 * them to 3/5 via the package-private {@link TransitionCapGuard} test constructor so "re-enter &gt;
 * soft → warn" and "re-enter &gt; hard → halt" run in single-digit ticks instead of a thousand DB
 * round-trips. The cap VALUES are configuration; the BEHAVIOUR under test (cross soft → warn row,
 * cross hard → halt row + FAILED build) is identical at 3/5 and 200/1000.
 */
@Testcontainers
class TransitionSpamIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

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
   * Re-enter ADVANCE past the soft cap (3): the crossing tick must write exactly one {@code
   * TRANSITION_CAP_WARN} audit row for the build — and NOT fail the build (soft cap is advisory).
   */
  @Test
  void reEnteringAdvancePastSoftCap_emitsWarnAuditRow_withoutFailingBuild() throws Exception {
    long buildId = seedRunningBuild();

    QueueHandlerSupport support =
        new QueueHandlerSupport(() -> null, new TransitionCapGuard(3, 100));
    AdvanceHandler handler = nonProductiveAdvanceHandler(support);

    // counts 1,2,3 OK; count 4 crosses the soft cap → one warn row.
    for (int i = 0; i < 4; i++) {
      driveAdvance(handler, buildId);
    }

    List<AuditLogRow> warns =
        stores
            .auditLog()
            .findRecent(null, AuditAction.TRANSITION_CAP_WARN.name(), "BUILD", null, 50, 0)
            .stream()
            .filter(r -> String.valueOf(buildId).equals(r.targetId))
            .toList();
    assertEquals(1, warns.size(), "exactly one transition_cap_warn row for the build");
    assertNotNull(warns.get(0).detailsJson);
    assertTrue(warns.get(0).detailsJson.contains("ADVANCE"), warns.get(0).detailsJson);
    assertTrue(warns.get(0).detailsJson.contains("softCap"), warns.get(0).detailsJson);

    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("RUNNING", build.status, "soft cap is advisory — the build keeps running");
  }

  /**
   * Re-enter ADVANCE past the hard cap (5): the build must fail-close with reason {@code
   * "transition spam guard hit"}, exactly one {@code TRANSITION_CAP_HALT} audit row must be written
   * (not one per spammed tick), and the loop must terminate cleanly — no hang.
   */
  @Test
  void reEnteringAdvancePastHardCap_failsBuildCleanly_emitsOneHaltAuditRow() throws Exception {
    long buildId = seedRunningBuild();

    QueueHandlerSupport support = new QueueHandlerSupport(() -> null, new TransitionCapGuard(3, 5));
    AdvanceHandler handler = nonProductiveAdvanceHandler(support);

    // Re-enter ADVANCE until the guard fail-closes the build. Faithful to production: the
    // fail-close
    // path does NOT enqueue a follow-up ADVANCE, so a real build stops ticking the moment it is
    // FAILED. We stop here on the same signal — and assert it terminates within hardCap+1 ticks
    // (no hang, no runaway). The 20 ceiling is a test-only watchdog, never expected to be reached.
    int ticks = 0;
    for (int i = 0; i < 20; i++) {
      driveAdvance(handler, buildId);
      ticks++;
      if ("FAILED".equals(stores.builds().findById(buildId).orElseThrow().status)) {
        break;
      }
    }
    assertTrue(ticks <= 6, "must fail-close within hardCap+1 ticks (no runaway), took " + ticks);

    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals("FAILED", build.status, "crossing the hard cap must fail-close the build");
    assertNotNull(build.failureSummary);
    assertTrue(
        build.failureSummary.contains("transition spam guard hit"),
        "failure reason must name the spam guard: " + build.failureSummary);
    assertTrue(build.failureSummary.contains("ADVANCE"), build.failureSummary);

    List<AuditLogRow> halts =
        stores
            .auditLog()
            .findRecent(null, AuditAction.TRANSITION_CAP_HALT.name(), "BUILD", null, 50, 0)
            .stream()
            .filter(r -> String.valueOf(buildId).equals(r.targetId))
            .toList();
    assertEquals(
        1, halts.size(), "the halt audit row must be written once, not once per spammed tick");
    assertTrue(halts.get(0).detailsJson.contains("hardCap"), halts.get(0).detailsJson);
    assertEquals("titan-controller", halts.get(0).actor);

    // Sanity: the warn row also landed on the way up (soft cap crossed before hard).
    List<AuditLogRow> warns =
        stores
            .auditLog()
            .findRecent(null, AuditAction.TRANSITION_CAP_WARN.name(), "BUILD", null, 50, 0)
            .stream()
            .filter(r -> String.valueOf(buildId).equals(r.targetId))
            .toList();
    assertFalse(warns.isEmpty(), "the soft-cap warn row must precede the halt");
  }

  /**
   * The production-realistic entry point — issue #1074 review follow-up. Every build task flows
   * through {@link QueueProcessor#dispatch} via {@link QueueProcessor#tick}, which increments the
   * <em>ORCHESTRATE umbrella</em> counter <b>in addition to</b> the per-action handler counter.
   * This test pushes ORCHESTRATE/ADVANCE tasks through the real {@code tick} claim+dispatch loop
   * past the hard cap and asserts:
   *
   * <ul>
   *   <li>the build fail-closes with the spam-guard reason,
   *   <li>exactly <b>one</b> {@code TRANSITION_CAP_HALT} audit row is written — the umbrella halt
   *       fires first (it is checked before the handler) and short-circuits, so the handler's own
   *       ADVANCE cap check never <em>also</em> emits a halt (no umbrella+handler double-emit),
   *   <li>that single halt row carries {@code kind=ORCHESTRATE} (the umbrella), never {@code
   *       kind=ADVANCE}.
   * </ul>
   */
  @Test
  void orchestrateUmbrellaPastHardCap_viaTick_failsBuild_emitsExactlyOneHalt_noDoubleEmit()
      throws Exception {
    long buildId = seedRunningBuild();

    // Low caps (soft 3 / hard 5) wired into the support the QueueProcessor will use; a
    // non-productive ADVANCE fn so the real dispatch loop re-enters ORCHESTRATE without the
    // orchestrator finishing the build (which would evict the counters mid-run).
    QueueHandlerSupport support = new QueueHandlerSupport(() -> null, new TransitionCapGuard(3, 5));
    QueueProcessor processor =
        new QueueProcessor(
            NoWorkerTimeoutSweeper.fromEnv(),
            support,
            (d, b) -> new TitanOrchestrator.AdvanceResult(0, 0, false, null, false));

    // Seed hardCap+1 (6) ORCHESTRATE/ADVANCE tasks, all claimable now. One tick claims the whole
    // batch (BATCH_SIZE=20) and dispatches each: the umbrella counter climbs 1..6 and crosses the
    // hard cap on the 6th, fail-closing the build. The 6th task's handler never runs.
    for (int i = 0; i < 6; i++) {
      enqueueOrchestrateAdvance(buildId);
    }
    int processed = processor.tick(stores, "umbrella-it", 3600);
    assertTrue(processed >= 6, "the tick must dispatch the whole seeded batch, was " + processed);

    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertEquals(
        "FAILED",
        build.status,
        "crossing the umbrella hard cap via tick must fail-close the build");
    assertNotNull(build.failureSummary);
    assertTrue(
        build.failureSummary.contains("transition spam guard hit"),
        "failure reason must name the spam guard: " + build.failureSummary);

    List<AuditLogRow> halts =
        stores
            .auditLog()
            .findRecent(null, AuditAction.TRANSITION_CAP_HALT.name(), "BUILD", null, 50, 0)
            .stream()
            .filter(r -> String.valueOf(buildId).equals(r.targetId))
            .toList();
    assertEquals(
        1,
        halts.size(),
        "exactly one halt row — the umbrella check short-circuits before the handler's own "
            + "ADVANCE check, so there is no umbrella+handler double-emit");
    assertTrue(
        halts.get(0).detailsJson.contains("ORCHESTRATE"),
        "the single halt must be the ORCHESTRATE umbrella, not the ADVANCE handler: "
            + halts.get(0).detailsJson);
    assertTrue(halts.get(0).detailsJson.contains("hardCap"), halts.get(0).detailsJson);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /** Insert one claimable ORCHESTRATE/ADVANCE task for {@code buildId} (available immediately). */
  private void enqueueOrchestrateAdvance(long buildId) {
    TaskQueueRow t = new TaskQueueRow();
    t.type = "ORCHESTRATE";
    t.queueName = "default";
    t.status = "QUEUED";
    t.priority = 0;
    t.payloadJson = "{\"action\":\"ADVANCE\",\"buildId\":" + buildId + "}";
    t.attempts = 0;
    t.maxAttempts = 3;
    t.visibilityTimeoutSeconds = 3600;
    t.buildId = buildId;
    stores.taskQueue().insert(t);
  }

  /** An {@link AdvanceHandler} whose orchestrator pass is always non-productive (re-enter loop). */
  private static AdvanceHandler nonProductiveAdvanceHandler(QueueHandlerSupport support) {
    return new AdvanceHandler(
        support, (d, b) -> new TitanOrchestrator.AdvanceResult(0, 0, false, null, false));
  }

  /** Insert one ORCHESTRATE/ADVANCE task and run it through the handler against the live DB. */
  private void driveAdvance(AdvanceHandler handler, long buildId) {
    TaskQueueRow t = new TaskQueueRow();
    t.type = "ORCHESTRATE";
    t.queueName = "default";
    t.status = "QUEUED";
    t.priority = 0;
    t.payloadJson = "{\"action\":\"ADVANCE\",\"buildId\":" + buildId + "}";
    t.attempts = 0;
    t.maxAttempts = 3;
    t.visibilityTimeoutSeconds = 3600;
    t.buildId = buildId;
    long id = stores.taskQueue().insert(t);
    TaskQueueRow claimed = stores.taskQueue().findById(id).orElseThrow();
    handler.handle(stores, claimed, Map.of("buildId", buildId));
  }

  private long seedRunningBuild() throws Exception {
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, "spam-it/job-" + System.nanoTime());
      return insertRunningBuild(c, jobId);
    }
  }

  private static long insertJob(Connection c, String fullName) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) "
                + "VALUES (?, 'stages: []', '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, fullName);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static long insertRunningBuild(Connection c, long jobId) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, started_at) "
                + "VALUES (?, 1, 'RUNNING', CURRENT_TIMESTAMP)",
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

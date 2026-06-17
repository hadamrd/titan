package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.flow.ApprovalService.Decision;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.ApprovalRow;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Property-based ITs that pin the orchestrator's reconciler-idempotence contract —
 * TitanOrchestrator javadoc: "run it again and it converges further; run it twice and the
 * compare-and-set transitions make the second run a no-op."
 *
 * <p>The property: once a build has been driven to a <em>stable</em> state by one explicit {@code
 * advance()} call (parked at an approval gate, or closed terminal), running {@code advance()} N
 * additional times must leave every row in {@code titan.builds + flow_nodes + approvals +
 * task_queue} unchanged. We snapshot the whole tuple before and after the N replays and assert
 * exact equality.
 *
 * <p>Three properties:
 *
 * <ol>
 *   <li>{@link #afterApproved_advanceIsIdempotent} — approve, drive to terminal, then replay.
 *   <li>{@link #afterRejected_advanceIsIdempotent} — reject, drive to terminal, then replay. This
 *       is the variant that catches the build-22 production bug: on the rig the next advance after
 *       a reject inserted a fresh PENDING approval row for the same {@code (build, flow_node)}
 *       pair, the user rejected again, ad infinitum (6 REJECTED + 1 dangling PENDING for one node).
 *       The fix is in {@code TitanOrchestrator#handleApprovalPending}: never re-park if a terminal
 *       approval row is already on file.
 *   <li>{@link #afterClosedTerminal_advanceIsIdempotent} — simple linear pipeline closed SUCCESS,
 *       then replay; no new rows anywhere.
 * </ol>
 *
 * <p>Backed by a real Postgres Testcontainer (one per test class) — the same wiring {@code
 * ApprovalsApiIT} uses. Per-property the schema is recreated from Flyway so each generated trial
 * starts on a clean slate.
 *
 * <p>This test does NOT use {@code @Testcontainers} or {@code @BeforeEach} — jqwik drives
 * {@code @Property} methods through its own engine which does not honour JUnit-Jupiter lifecycle
 * hooks. The fixture is bootstrapped explicitly per trial.
 */
class OrchestratorIdempotenceProperty {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  private HikariDataSource ds;
  private TitanStores stores;
  private long buildId;

  /** Bring up a fresh schema + stores for one trial. */
  private void freshFixture() throws Exception {
    if (ds != null) {
      ds.close();
    }
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
    cfg.setUsername(POSTGRES.getUsername());
    cfg.setPassword(POSTGRES.getPassword());
    cfg.setDriverClassName("org.postgresql.Driver");
    cfg.setMaximumPoolSize(4);
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

  // ── properties ────────────────────────────────────────────────────────────

  @Property(tries = 25)
  void afterApproved_advanceIsIdempotent(@ForAll @IntRange(min = 1, max = 25) int reTicks)
      throws Exception {
    freshFixture();
    bake("approval-step.yml");
    new TitanOrchestrator(stores, buildId).advance(); // park at approval
    FlowNodeRow parked = findFirstSleepingNode();
    ApprovalRow pending =
        stores.approvals().findLatestForNode(buildId, parked.nodeId).orElseThrow();

    ApprovalService.decide(stores, pending.id, "alice", Set.of("alice"), Decision.APPROVED);
    new TitanOrchestrator(stores, buildId).advance(); // resume node SUCCESS
    completeAllQueuedSteps(); // drive the post-approval `sh` to SUCCESS, build closes
    new TitanOrchestrator(stores, buildId).advance();

    Snapshot before = Snapshot.capture(ds, buildId);
    for (int i = 0; i < reTicks; i++) {
      new TitanOrchestrator(stores, buildId).advance();
    }
    Snapshot after = Snapshot.capture(ds, buildId);
    assertEquals(
        before,
        after,
        "advance() must be idempotent after APPROVED → SUCCESS terminal close (re-ticks="
            + reTicks
            + ")");
  }

  @Property(tries = 25)
  void afterRejected_advanceIsIdempotent(@ForAll @IntRange(min = 1, max = 25) int reTicks)
      throws Exception {
    freshFixture();
    bake("approval-step.yml");
    new TitanOrchestrator(stores, buildId).advance(); // park
    FlowNodeRow parked = findFirstSleepingNode();
    ApprovalRow pending =
        stores.approvals().findLatestForNode(buildId, parked.nodeId).orElseThrow();

    ApprovalService.decide(stores, pending.id, "alice", Set.of("alice"), Decision.REJECTED);
    new TitanOrchestrator(stores, buildId).advance(); // resume node FAILED + close

    // Build-22 reproducer (May 2026 production rig): a replay-from-node resets the flow_node
    // back to PENDING but leaves the historical REJECTED approval row in place. The next
    // advance() must NOT insert a fresh PENDING approval row — instead it must observe the
    // terminal REJECTED row and resume the node FAILED directly. With the buggy code, every
    // replay cycle inserted another PENDING row; on the rig, build 22 ended with 6 REJECTED +
    // 1 dangling PENDING for one flow_node.
    //
    // The property: after the simulated replay, the FIRST advance() must converge directly
    // — leaving exactly one approval row (the original REJECTED) — and N further advances must
    // be exact no-ops on top of that. We capture two invariants:
    //   1. approvals_count_after_reset_advance == 1 (no fresh PENDING was inserted)
    //   2. snapshot(after N more ticks) == snapshot(after first converging tick)
    resetNodeToPending(parked.nodeId);
    new TitanOrchestrator(stores, buildId).advance(); // converge — node back to FAILED

    long approvalsAfterReset =
        stores.approvals().listForBuild(buildId).stream()
            .filter(r -> r.flowNodeId.equals(parked.nodeId))
            .count();
    assertEquals(
        1L,
        approvalsAfterReset,
        "advance() after a replay-from-node MUST NOT insert a fresh PENDING approval row — "
            + "the historical REJECTED row is already the answer (build-22 bug)");

    Snapshot before = Snapshot.capture(ds, buildId);
    for (int i = 0; i < reTicks; i++) {
      new TitanOrchestrator(stores, buildId).advance();
    }
    Snapshot after = Snapshot.capture(ds, buildId);
    assertEquals(
        before,
        after,
        "advance() must be idempotent after REJECTED → FAILED — no fresh PENDING row may "
            + "be inserted on a re-tick (build-22 bug, re-ticks="
            + reTicks
            + ")");
  }

  @Property(tries = 25)
  void afterClosedTerminal_advanceIsIdempotent(@ForAll @IntRange(min = 1, max = 25) int reTicks)
      throws Exception {
    freshFixture();
    bake("linear-success.yml");
    // Drive the linear pipeline to terminal SUCCESS.
    new TitanOrchestrator(stores, buildId).advance();
    completeAllQueuedSteps();
    new TitanOrchestrator(stores, buildId).advance();

    Snapshot before = Snapshot.capture(ds, buildId);
    for (int i = 0; i < reTicks; i++) {
      new TitanOrchestrator(stores, buildId).advance();
    }
    Snapshot after = Snapshot.capture(ds, buildId);
    assertEquals(
        before,
        after,
        "advance() must be idempotent after a terminal SUCCESS close (re-ticks=" + reTicks + ")");
  }

  /**
   * Issue #911 reproducer: N concurrent ADVANCE workers racing the FIRST park-on-approval
   * transition must not produce an exception OR fail-close the build. In production this race
   * landed builds 14 / 29 / 30 on titan.test with a cryptic "compareAndSetStatus failed — internal
   * error" message. The reconciler's contract is that CAS-loss is benign — the loser observes the
   * winner's transition on its next read and converges. This property pins that contract under
   * concurrent execution.
   *
   * <p>The assertion is three-pronged:
   *
   * <ol>
   *   <li>No exception escapes any of the N concurrent {@code advance()} calls.
   *   <li>The build's terminal observation is identical to a single-threaded park outcome — the
   *       approval flow_node is SLEEPING, exactly one PENDING approval row exists, the build row is
   *       non-terminal, and {@code failure_summary} is null (no "internal error" recorded).
   *   <li>A subsequent serial {@code advance()} converges the build to the same shape (idempotent
   *       on top of the racing outcome).
   * </ol>
   */
  @Property(tries = 30)
  void concurrentAdvance_doesNotFailBuild(@ForAll @IntRange(min = 2, max = 6) int workers)
      throws Exception {
    freshFixture();
    bake("approval-step.yml");

    // Run N concurrent advance() passes on the SAME build. Each constructs its own
    // TitanOrchestrator off the shared TitanStores (Hikari pool, postgres). The CAS contract
    // says only one will win the PENDING→SLEEPING transition; the rest must no-op cleanly.
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    try {
      List<CompletableFuture<Void>> futures = new ArrayList<>(workers);
      AtomicReference<Throwable> firstFailure = new AtomicReference<>();
      for (int i = 0; i < workers; i++) {
        futures.add(
            CompletableFuture.runAsync(
                () -> {
                  try {
                    new TitanOrchestrator(stores, buildId).advance();
                  } catch (Throwable t) {
                    firstFailure.compareAndSet(null, t);
                    throw new RuntimeException(t);
                  }
                },
                pool));
      }
      // Collect — any failure surfaces here. We assert via firstFailure so the message is
      // descriptive, but we also drain so the test does not leak a swallowed exception.
      for (CompletableFuture<Void> f : futures) {
        try {
          f.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException expectedIfFailed) {
          // unwrapped via firstFailure below
        }
      }
      Throwable failure = firstFailure.get();
      if (failure != null) {
        throw new AssertionError(
            "concurrent advance() leaked an exception (issue #911 regression): " + failure,
            failure);
      }
    } finally {
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "executor did not terminate");
    }

    // The racing reconcilers must have produced ONE coherent park state.
    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    assertNotEquals(
        "FAILED",
        build.status,
        "build was fail-closed by a concurrent advance race — this is the #911 production bug");
    // Issue #911: the build's failure_summary must be null (no "internal error" recorded).
    if (build.failureSummary != null) {
      throw new AssertionError(
          "build carries a failure_summary after concurrent advance — #911 regression: "
              + build.failureSummary);
    }

    FlowNodeRow parked =
        stores.flowNodes().listByBuildAndStatus(buildId, "SLEEPING").stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "expected exactly one SLEEPING approval node after concurrent advance — "
                            + "found none (build="
                            + buildId
                            + ", status="
                            + build.status
                            + ")"));
    assertNotNull(parked.wakeAt, "SLEEPING node must have wake_at stamped");

    long pendingApprovals =
        stores.approvals().listForBuild(buildId).stream()
            .filter(r -> "PENDING".equals(r.status))
            .filter(r -> r.flowNodeId.equals(parked.nodeId))
            .count();
    assertEquals(
        1L,
        pendingApprovals,
        "concurrent advance produced "
            + pendingApprovals
            + " PENDING approval rows for the same flow node — must be exactly 1 (workers="
            + workers
            + ")");

    // Idempotent re-tick after the race: a serial advance() must not change anything.
    Snapshot before = Snapshot.capture(ds, buildId);
    new TitanOrchestrator(stores, buildId).advance();
    Snapshot after = Snapshot.capture(ds, buildId);
    assertEquals(
        before,
        after,
        "advance() after concurrent race must be idempotent (workers=" + workers + ")");
  }

  // ── fixture helpers (mirror ApprovalsApiIT) ───────────────────────────────

  private void bake(String fixture) throws Exception {
    String yaml = Fixtures.load(fixture);
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, yaml);
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(yaml);
    new TitanOrchestrator(stores, buildId).advance();
    completeAllQueuedSteps();
  }

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "idempotence-prop/job-" + System.nanoTime());
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

  private void completeAllQueuedSteps() throws Exception {
    for (int i = 0; i < 16; i++) {
      boolean any = completeOneRound();
      if (!any) {
        return;
      }
      new TitanOrchestrator(stores, buildId).advance();
    }
  }

  private boolean completeOneRound() throws Exception {
    boolean any = false;
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT id, node_id FROM titan.task_queue "
                    + "WHERE type = 'EXECUTE_COMMAND' AND status = 'QUEUED' AND build_id = "
                    + buildId)) {
      while (rs.next()) {
        long taskId = rs.getLong(1);
        String nodeId = rs.getString(2);
        try (Statement up = c.createStatement()) {
          up.execute(
              "UPDATE titan.task_queue SET status = 'COMPLETED', "
                  + "result_json = '{\"exitCode\":0}' WHERE id = "
                  + taskId);
        }
        Instant now = Instant.now();
        stores
            .flowNodes()
            .compareAndSetStatus(
                buildId, nodeId, "QUEUED", "SUCCESS", null, now, 1L, "{\"exitCode\":0}");
        any = true;
      }
    }
    return any;
  }

  private FlowNodeRow findFirstSleepingNode() {
    return stores.flowNodes().listByBuildAndStatus(buildId, "SLEEPING").stream()
        .findFirst()
        .orElseThrow(() -> new AssertionError("expected one SLEEPING flow node"));
  }

  /**
   * Force a flow_node back to PENDING — the surface a replay-from-node operation creates: the node
   * is reset but historical approval rows stay in place. This is the exact state that triggered the
   * build-22 bug.
   */
  private void resetNodeToPending(String nodeId) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE titan.flow_nodes "
                    + "SET status = 'PENDING', started_at = NULL, completed_at = NULL, "
                    + "wake_at = NULL, failure_category = NULL, failure_reason = NULL, "
                    + "result_json = NULL "
                    + "WHERE build_id = ? AND node_id = ?")) {
      ps.setLong(1, buildId);
      ps.setString(2, nodeId);
      ps.executeUpdate();
    }
    // The build was closed FAILED in the resume pass — reopen it so advance() works.
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE titan.builds SET status = 'RUNNING', finished_at = NULL, "
                    + "duration_ms = NULL WHERE id = ?")) {
      ps.setLong(1, buildId);
      ps.executeUpdate();
    }
    // Reset any SKIPPED downstream sibling nodes the failure-policy cascade marked, so they can
    // re-execute — without this they stay SKIPPED and the reset approval node has no successor.
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE titan.flow_nodes SET status = 'PENDING', started_at = NULL, "
                    + "completed_at = NULL, result_json = NULL "
                    + "WHERE build_id = ? AND status = 'SKIPPED'")) {
      ps.setLong(1, buildId);
      ps.executeUpdate();
    }
  }

  // ── snapshot ──────────────────────────────────────────────────────────────

  /**
   * A whole-build DB snapshot: every row of builds + flow_nodes + approvals + task_queue scoped to
   * this build. Used to assert that N replays of {@code advance()} cause zero state change.
   * Equality is structural over the row tuples — any new INSERT, any UPDATE will show up.
   */
  private record Snapshot(
      List<List<Object>> builds,
      List<List<Object>> flowNodes,
      List<List<Object>> approvals,
      List<List<Object>> tasks) {

    static Snapshot capture(HikariDataSource ds, long buildId) throws Exception {
      return new Snapshot(
          query(
              ds,
              "SELECT id, status, finished_at, duration_ms "
                  + "FROM titan.builds WHERE id = ? ORDER BY id",
              buildId),
          query(
              ds,
              "SELECT node_id, status, started_at, completed_at, wake_at, failure_category, "
                  + "failure_reason, result_json "
                  + "FROM titan.flow_nodes WHERE build_id = ? ORDER BY node_id",
              buildId),
          query(
              ds,
              "SELECT id, flow_node_id, status, decided_by, decided_at, expires_at "
                  + "FROM titan.approvals WHERE build_id = ? ORDER BY id",
              buildId),
          query(
              ds,
              "SELECT id, type, status, node_id, payload_json, result_json "
                  + "FROM titan.task_queue WHERE build_id = ? ORDER BY id",
              buildId));
    }

    private static List<List<Object>> query(HikariDataSource ds, String sql, long buildId)
        throws Exception {
      List<List<Object>> rows = new ArrayList<>();
      try (Connection c = ds.getConnection();
          PreparedStatement ps = c.prepareStatement(sql)) {
        ps.setLong(1, buildId);
        try (ResultSet rs = ps.executeQuery()) {
          int cols = rs.getMetaData().getColumnCount();
          while (rs.next()) {
            List<Object> row = new ArrayList<>(cols);
            for (int i = 1; i <= cols; i++) {
              Object v = rs.getObject(i);
              // Normalise java.sql.Timestamp to Instant for stable equality across JDBC variants.
              if (v instanceof java.sql.Timestamp ts) {
                row.add(ts.toInstant());
              } else {
                row.add(v);
              }
            }
            rows.add(row);
          }
        }
      }
      return rows;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Snapshot other)) return false;
      return Objects.equals(builds, other.builds)
          && Objects.equals(flowNodes, other.flowNodes)
          && Objects.equals(approvals, other.approvals)
          && Objects.equals(tasks, other.tasks);
    }

    @Override
    public int hashCode() {
      return Objects.hash(builds, flowNodes, approvals, tasks);
    }
  }
}

package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * IT for the unschedulable-step guard (#824).
 *
 * <p>Pre-fix: a stage with {@code agent: nonexistent-label} (or a worker that does not poll the
 * label's queue) silently parks the step in {@code QUEUED} forever and the orchestrator tight-loops
 * on every tick. Post-fix: after the grace window, the orchestrator detects that no ONLINE worker
 * subscribes to the queue and fails the step with a clear customer-facing reason.
 */
@Testcontainers
class TitanOrchestratorUnschedulableIT {

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
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  /**
   * Acceptance criterion 3 (#824): a pipeline pinned to {@code agent: nonexistent-label} fails fast
   * with a clear error after the grace window — not silently QUEUED forever.
   */
  @Test
  void aStepPinnedToAnUnservedLabelFailsFastWithAClearReason() throws Exception {
    bootstrap("unschedulable-agent.yml");
    // An ONLINE worker exists, but it serves a DIFFERENT label. The guard must still fire —
    // emptiness of the agent set is NOT the trigger; lack of subscription to the specific
    // queue is.
    registerOnlineAgent("worker-other", "docker");

    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);
    orch.advance(); // dispatch — the step lands QUEUED on queue 'nonexistent-label'

    // Fresh task is NOT failed — the guard's grace window protects against a worker restart blip.
    orch.advance();
    FlowNodeRow stepNode = stepNode();
    assertEquals("QUEUED", stepNode.status, "a fresh QUEUED step is protected by the grace window");

    // Back-date the task's created_at past the grace window — simulates a real 60s wait without
    // sleeping the test for a minute.
    backdateTaskCreatedAt(
        latestExecuteTask().id, TitanOrchestrator.UNSCHEDULABLE_GRACE_SECONDS + 5);

    TitanOrchestrator.AdvanceResult r = orch.advance();

    FlowNodeRow after = stepNode();
    assertEquals("FAILED", after.status, "the unschedulable step is failed");
    assertEquals("DISPATCH", after.failureCategory, "category surfaces the dispatch failure");
    assertNotNull(after.failureReason, "a customer-facing reason is recorded");
    assertTrue(
        after.failureReason.contains("nonexistent-label"),
        "reason names the unserved queue: " + after.failureReason);
    assertTrue(
        after.failureReason.toLowerCase().contains("no worker"),
        "reason explains no worker subscribes: " + after.failureReason);
    // Build closes on this pass — the only step terminated.
    assertTrue(r.buildFinished(), "the build closes once the only step is terminal");
    assertEquals("FAILED", r.buildResult(), "the build's result is FAILED");
  }

  /**
   * Guard rail: a stage targeting a queue that IS served (an existing worker's label) MUST NOT be
   * wrongly failed even after the grace window. This is the false-positive check — without it the
   * guard might fire on any old task.
   */
  @Test
  void aStepRoutedToAServedLabelIsNotWronglyFailed() throws Exception {
    bootstrap("unschedulable-agent.yml");
    // This time the ONLINE worker DOES advertise the label the stage pins to.
    registerOnlineAgent("worker-linux", "nonexistent-label");

    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);
    orch.advance();
    backdateTaskCreatedAt(
        latestExecuteTask().id, TitanOrchestrator.UNSCHEDULABLE_GRACE_SECONDS + 5);
    orch.advance();

    FlowNodeRow after = stepNode();
    assertEquals(
        "QUEUED",
        after.status,
        "the step stays QUEUED — a real worker would claim it on the next tick");
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private void bootstrap(String fixture) throws Exception {
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, Fixtures.load(fixture));
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(Fixtures.load(fixture));
  }

  /** Insert an ONLINE agent with fresh heartbeat and the given labels (CSV). */
  private void registerOnlineAgent(String agentId, String labels) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.agents "
                    + "(agent_id, display_name, labels, num_executors, status, "
                    + " registered_at, last_heartbeat) "
                    + "VALUES (?, ?, ?, 1, 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
      ps.setString(1, agentId);
      ps.setString(2, agentId);
      ps.setString(3, labels);
      ps.executeUpdate();
    }
  }

  private void backdateTaskCreatedAt(long taskId, long secondsInPast) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE titan.task_queue "
                    + "SET created_at = CURRENT_TIMESTAMP - INTERVAL '1 second' * ? "
                    + "WHERE id = ?")) {
      ps.setLong(1, secondsInPast);
      ps.setLong(2, taskId);
      ps.executeUpdate();
    }
  }

  private FlowNodeRow stepNode() {
    // Single-step pipeline — node id is "<stage-slug>-s0". The fixture's stage name is
    // "Unroutable", baked to id "unroutable".
    return stores.flowNodes().findByBuildAndNode(buildId, "unroutable-s0").orElseThrow();
  }

  private TaskQueueRow latestExecuteTask() {
    List<TaskQueueRow> tasks = stores.taskQueue().listByBuild(buildId);
    TaskQueueRow latest = null;
    for (TaskQueueRow t : tasks) {
      if ("EXECUTE_COMMAND".equals(t.type)) {
        latest = t;
      }
    }
    assertNotNull(latest, "an EXECUTE_COMMAND task exists");
    return latest;
  }

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "unschedulable/job-" + System.nanoTime());
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

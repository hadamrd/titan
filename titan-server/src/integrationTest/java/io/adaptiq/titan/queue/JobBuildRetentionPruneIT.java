package io.adaptiq.titan.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Issue #637 — per-job build-retention prune. Caps {@code titan.builds} at the last {@code
 * keepLast} rows per job and drops everything that hangs off the older tail (flow_nodes, artifact,
 * test_result, task_queue, task_archive, logs).
 *
 * <p>Adversarial scenarios:
 *
 * <ul>
 *   <li>Mixed history: 150 builds → keepLast=100 drops exactly the 50 oldest.
 *   <li>Cascade reaches every child table: flow_nodes / artifact / test_result auto-cascade;
 *       task_archive (no FK) and logs (keyed by task_token, no FK) get explicit deletes.
 *   <li>Idempotent: a re-run after the first prune deletes nothing.
 *   <li>{@code keepLast=0} is the operator opt-out — every build survives.
 * </ul>
 */
@Testcontainers
class JobBuildRetentionPruneIT {

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
    jobId = insertJob("acme/widget");
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  @Test
  void pruneCapsHistoryAtKeepLastAndCascadesEveryChildTable() throws Exception {
    // 150 builds — each with one flow_node, one artifact, one test_result, one task_archive row
    // and one logs row (keyed by the archive's task_token).
    List<Long> buildIds = new ArrayList<>(150);
    try (Connection c = ds.getConnection()) {
      for (int i = 1; i <= 150; i++) {
        long buildId = insertBuild(c, jobId, i);
        insertFlowNode(c, buildId, "n" + i);
        insertArtifact(c, buildId, "a-" + i);
        insertTestResult(c, buildId, "t-" + i);
        UUID token = UUID.randomUUID();
        insertArchive(c, buildId, token);
        insertLog(c, token);
        buildIds.add(buildId);
      }
    }
    assertEquals(150, count("builds"));
    assertEquals(150, count("flow_nodes"));
    assertEquals(150, count("artifact"));
    assertEquals(150, count("test_result"));
    assertEquals(150, count("task_archive"));
    assertEquals(150, count("logs"));

    int deleted = JobBuildRetentionPruner.prune(stores, 100);
    assertEquals(50, deleted, "exactly the 50 oldest builds dropped");
    assertEquals(100, count("builds"), "100 newest builds survive");

    // The 50 oldest are buildIds[0..49] (insertion order = ascending id = oldest first).
    for (int i = 0; i < 50; i++) {
      long droppedId = buildIds.get(i);
      assertEquals(0, countWhere("builds", "id = " + droppedId), "build " + droppedId + " gone");
      assertEquals(
          0,
          countWhere("flow_nodes", "build_id = " + droppedId),
          "flow_nodes for " + droppedId + " cascaded");
      assertEquals(
          0,
          countWhere("artifact", "build_id = " + droppedId),
          "artifact for " + droppedId + " cascaded");
      assertEquals(
          0,
          countWhere("test_result", "build_id = " + droppedId),
          "test_result for " + droppedId + " cascaded");
      assertEquals(
          0,
          countWhere("task_archive", "build_id = " + droppedId),
          "task_archive for " + droppedId + " manually deleted");
    }
    // The 100 newest survive intact.
    for (int i = 50; i < 150; i++) {
      long keptId = buildIds.get(i);
      assertTrue(countWhere("builds", "id = " + keptId) == 1, "kept build " + keptId);
      assertEquals(1, countWhere("flow_nodes", "build_id = " + keptId));
      assertEquals(1, countWhere("artifact", "build_id = " + keptId));
      assertEquals(1, countWhere("test_result", "build_id = " + keptId));
      assertEquals(1, countWhere("task_archive", "build_id = " + keptId));
    }
    // logs delete cascade: the 50 oldest tokens are gone (and so 50 log rows remain — one per
    // surviving build's archive token). We seeded one log per build, so 100 total.
    assertEquals(100, count("logs"), "logs for the 50 dropped builds are also gone");

    // Idempotent — re-running finds the job at exactly 100 and does nothing.
    int again = JobBuildRetentionPruner.prune(stores, 100);
    assertEquals(0, again, "re-run is a no-op");
    assertEquals(100, count("builds"));
  }

  @Test
  void perJobBuildRetentionOverrideTrumpsGlobalDefault() throws Exception {
    // #640 — the job's pipeline_script declares `buildRetention.keepLast: 5`. With 50 builds and
    // a global default of 100, the global default would normally keep them all, but the per-job
    // override must win — only 5 builds survive after the prune.
    long overrideJobId =
        insertJobWithScript(
            "acme/override",
            "buildRetention:\n"
                + "  keepLast: 5\n"
                + "stages:\n"
                + "  - stage: build\n"
                + "    steps:\n"
                + "      - sh: echo hi\n");
    try (Connection c = ds.getConnection()) {
      for (int i = 1; i <= 50; i++) {
        insertBuild(c, overrideJobId, i);
      }
    }
    assertEquals(50, countWhere("builds", "job_id = " + overrideJobId));

    int deleted = JobBuildRetentionPruner.prune(stores, 100);
    assertEquals(45, deleted, "per-job override caps at 5 — 45 oldest dropped, not the global 100");
    assertEquals(
        5,
        countWhere("builds", "job_id = " + overrideJobId),
        "per-job buildRetention.keepLast=5 wins over the global default of 100");
  }

  @Test
  void perJobKeepLastZeroOptsOutEvenWhenGlobalIsSet() throws Exception {
    // #640 — `buildRetention.keepLast: 0` is the per-job opt-out: keep everything for this job,
    // even though the global default is 100.
    long optOutJobId =
        insertJobWithScript(
            "acme/optout",
            "buildRetention:\n"
                + "  keepLast: 0\n"
                + "stages:\n"
                + "  - stage: build\n"
                + "    steps:\n"
                + "      - sh: echo hi\n");
    try (Connection c = ds.getConnection()) {
      for (int i = 1; i <= 150; i++) {
        insertBuild(c, optOutJobId, i);
      }
    }
    assertEquals(150, countWhere("builds", "job_id = " + optOutJobId));

    JobBuildRetentionPruner.prune(stores, 100);
    assertEquals(
        150,
        countWhere("builds", "job_id = " + optOutJobId),
        "per-job keepLast=0 keeps full history regardless of the global default");
  }

  @Test
  void retentionZeroIsOperatorOptOutAndDeletesNothing() throws Exception {
    try (Connection c = ds.getConnection()) {
      for (int i = 1; i <= 10; i++) {
        insertBuild(c, jobId, i);
      }
    }
    assertEquals(10, count("builds"));

    int deleted = JobBuildRetentionPruner.prune(stores, 0);
    assertEquals(0, deleted, "keepLast=0 is the opt-out — no deletes");
    assertEquals(10, count("builds"), "all builds preserved when retention is disabled");
  }

  // ----------- DDL helpers -----------

  private long insertJob(String fullName) throws Exception {
    return insertJobWithScript(
        fullName, "stages:\n  - stage: noop\n    steps:\n      - sh: echo hi\n");
  }

  private long insertJobWithScript(String fullName, String pipelineScript) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.jobs (full_name, display_name, pipeline_script, enabled) "
                    + "VALUES (?, ?, ?, TRUE)",
                Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, fullName);
      ps.setString(2, fullName);
      ps.setString(3, pipelineScript);
      ps.executeUpdate();
      try (ResultSet rs = ps.getGeneratedKeys()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static long insertBuild(Connection c, long jobId, int buildNumber) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status) VALUES (?, ?, 'SUCCESS')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, jobId);
      ps.setInt(2, buildNumber);
      ps.executeUpdate();
      try (ResultSet rs = ps.getGeneratedKeys()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static void insertFlowNode(Connection c, long buildId, String nodeId) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.flow_nodes (build_id, node_id, node_type, status) "
                + "VALUES (?, ?, 'STEP', 'SUCCESS')")) {
      ps.setLong(1, buildId);
      ps.setString(2, nodeId);
      ps.executeUpdate();
    }
  }

  private static void insertArtifact(Connection c, long buildId, String name) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.artifact (build_id, kind, name, size_bytes, sha256, "
                + "storage, storage_ref) "
                + "VALUES (?, 'ARTIFACT', ?, 0, ?, 'fs', '/tmp/x')")) {
      ps.setLong(1, buildId);
      ps.setString(2, name);
      // 64-char sha placeholder
      ps.setString(3, String.format("%-64s", name).replace(' ', '0'));
      ps.executeUpdate();
    }
  }

  private static void insertTestResult(Connection c, long buildId, String name) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.test_result (build_id, node_id, suite, class_name, name, status) "
                + "VALUES (?, 'n1', 'suite', 'cls', ?, 'PASSED')")) {
      ps.setLong(1, buildId);
      ps.setString(2, name);
      ps.executeUpdate();
    }
  }

  private static void insertArchive(Connection c, long buildId, UUID token) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.task_archive (type, queue_name, status, priority, "
                + "payload_json, attempts, max_attempts, visibility_timeout_seconds, "
                + "build_id, task_token, completed_at) "
                + "VALUES ('EXECUTE_COMMAND', 'default', 'COMPLETED', 0, '{}', 1, 3, 300, ?, ?, ?)")) {
      ps.setLong(1, buildId);
      ps.setObject(2, token);
      ps.setTimestamp(3, Timestamp.from(Instant.now()));
      ps.executeUpdate();
    }
  }

  private static void insertLog(Connection c, UUID taskToken) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.logs (task_id, chunk_index, stream, data, is_final) "
                + "VALUES (?, 0, 'stdout', 'hello', TRUE)")) {
      ps.setObject(1, taskToken);
      ps.executeUpdate();
    }
  }

  private int count(String table) throws Exception {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM titan." + table)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private int countWhere(String table, String where) throws Exception {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM titan." + table + " WHERE " + where)) {
      rs.next();
      return rs.getInt(1);
    }
  }
}

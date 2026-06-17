package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.api.dto.SystemInfoDto;
import io.adaptiq.titan.api.dto.SystemInfoDto.DbStatus;
import io.adaptiq.titan.api.dto.SystemInfoDto.MigrationStatus;
import io.adaptiq.titan.store.TitanStores;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres-backed integration test for {@link SystemInfoApi} (closes #676).
 *
 * <p>Pins the two load-bearing contracts:
 *
 * <ul>
 *   <li><strong>Happy path:</strong> with a real schema + a seeded queued task + a seeded ONLINE
 *       agent, the endpoint returns {@code dbStatus=UP}, the live counters, and a non-null server
 *       time.
 *   <li><strong>Degraded DB:</strong> when the {@link DataSource} the API was wired against is
 *       closed mid-flight, the endpoint MUST NOT throw — it returns 200-equivalent payload with
 *       {@code dbStatus=DOWN} and best-effort zeros. (The dashboard's primary job is to TELL the
 *       SRE the DB is down — a 500 here defeats the feature.)
 * </ul>
 *
 * <p>Same harness style as {@code StatsApiIT} / {@code AdminQueueApiIT}: Testcontainers-managed
 * Postgres, Flyway migrate, direct construction of the JAX-RS resource (the OIDC filter is unit-
 * test territory).
 */
@Testcontainers
class SystemInfoApiIT {

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
    if (ds != null && !ds.isClosed()) {
      ds.close();
    }
  }

  @Test
  void happyPath_returnsUpStatus_withSeededCounters() throws Exception {
    long jobId;
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "sysinfo-it/job-" + System.nanoTime());
      long buildId = insertBuild(c, jobId, 1);
      enqueueTask(c, buildId);
      enqueueTask(c, buildId);
      // One ONLINE agent + one BUSY agent + one OFFLINE (which must be excluded).
      insertAgent(c, "a-online", "ONLINE");
      insertAgent(c, "a-busy", "BUSY");
      insertAgent(c, "a-offline", "OFFLINE");
    }

    SystemInfoApi api = new SystemInfoApi(stores, ds);
    SystemInfoDto info = api.info();

    assertNotNull(info);
    assertEquals(DbStatus.UP, info.dbStatus(), "ds is healthy → UP");
    assertEquals(2, info.queueDepth(), "two QUEUED tasks seeded");
    assertEquals(2, info.workersOnline(), "ONLINE + BUSY count; OFFLINE excluded");
    assertNotNull(info.version(), "version is never null (fallback 'unknown')");
    assertNotNull(info.buildSha(), "buildSha is never null (fallback 'unknown')");
    assertNotNull(info.serverTime(), "serverTime always populated");
    assertTrue(
        Math.abs(info.serverTime().toEpochMilli() - Instant.now().toEpochMilli()) < 10_000,
        "serverTime is wall-clock-fresh (within 10s)");

    // #790: migration status must surface a known current version and no pending drift
    // (Flyway just ran every migration on disk).
    MigrationStatus ms = info.migrationStatus();
    assertNotNull(ms, "migrationStatus is never null");
    assertNotNull(ms.current(), "current version populated after a fresh Flyway migrate");
    assertEquals(
        List.of(), ms.pending(), "no pending drift — image matches schema after Flyway migrate");
  }

  @Test
  void migrationDrift_pendingListSurfacesUnappliedVersions() throws Exception {
    // Simulate the #790 failure mode: the schema is at an older version than the bundled
    // migrations. We forcibly truncate flyway_schema_history down to V1, then re-probe.
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      // Drop every history row except V1 — including non-integer baseline markers (e.g. "9.1")
      // that Flyway writes for baseline-on-migrate. We want a clean "current=V1, everything
      // else pending" picture.
      st.execute(
          "DELETE FROM titan.flyway_schema_history WHERE version IS NULL " + "OR version <> '1'");
    }

    SystemInfoApi api = new SystemInfoApi(stores, ds);
    SystemInfoDto info = api.info();
    MigrationStatus ms = info.migrationStatus();

    assertNotNull(ms);
    assertEquals("1", ms.current(), "history truncated to V1");
    assertTrue(
        ms.pending().contains("26"),
        "V26 (approvals) is on the classpath but no longer in history → must be pending. "
            + "got pending="
            + ms.pending());
    assertTrue(
        ms.pending().size() >= 2,
        "many bundled migrations are now drift; got pending=" + ms.pending());
  }

  @Test
  void degradedDb_returnsDbStatusDown_withoutCrashing() {
    // Wire the API against a doomed DataSource, then close it. The API MUST degrade
    // gracefully — surfacing the outage in the payload is the whole point of /system.
    SystemInfoApi api = new SystemInfoApi(stores, ds);
    ds.close();

    SystemInfoDto info = api.info();

    assertNotNull(info, "no exception bubbled");
    assertEquals(DbStatus.DOWN, info.dbStatus(), "closed pool → DOWN");
    assertEquals(0, info.queueDepth(), "best-effort zero when DB unreachable");
    assertEquals(0, info.workersOnline(), "best-effort zero when DB unreachable");
    assertNotNull(info.serverTime(), "serverTime still emitted (no DB needed)");
    assertNotNull(info.version(), "build-info is read at construct time and survives DB outage");
    // #790: migration probe must degrade to UNKNOWN, never throw, never lie "in sync".
    MigrationStatus ms = info.migrationStatus();
    assertNotNull(ms, "migrationStatus is never null even when DB is down");
    assertEquals(null, ms.current(), "current=null when history is unreachable");
    assertEquals(List.of(), ms.pending(), "pending=[] when history is unreachable");
  }

  // ── seed helpers ──────────────────────────────────────────────────────────

  private static long insertJob(Connection c, String fullName) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) "
                + "VALUES (?, '', '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, fullName);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static long insertBuild(Connection c, long jobId, int buildNumber) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, queued_at) "
                + "VALUES (?, ?, 'QUEUED', ?)",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, jobId);
      ps.setInt(2, buildNumber);
      ps.setTimestamp(3, Timestamp.from(Instant.now()));
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static void enqueueTask(Connection c, long buildId) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.task_queue "
                + "(build_id, node_id, type, status, priority, queue_name, "
                + " payload_json, created_at) "
                + "VALUES (?, 'n1', 'EXECUTE_COMMAND', 'QUEUED', 5, 'default', '{}', ?)")) {
      ps.setLong(1, buildId);
      ps.setTimestamp(2, Timestamp.from(Instant.now()));
      ps.executeUpdate();
    }
  }

  private static void insertAgent(Connection c, String agentId, String status) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.agents "
                + "(agent_id, display_name, status, labels, current_tasks, max_concurrent, "
                + " registered_at, last_heartbeat) "
                + "VALUES (?, ?, ?, 'linux', 0, 1, ?, ?)")) {
      Timestamp now = Timestamp.from(Instant.now());
      ps.setString(1, agentId);
      ps.setString(2, agentId);
      ps.setString(3, status);
      ps.setTimestamp(4, now);
      ps.setTimestamp(5, now);
      ps.executeUpdate();
    }
  }
}

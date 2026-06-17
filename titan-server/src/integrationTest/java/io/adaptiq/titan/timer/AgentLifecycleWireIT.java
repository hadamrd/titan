package io.adaptiq.titan.timer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Adversarial IT for {@link AgentReaperScheduler} — proves the wire-up of {@link
 * io.adaptiq.titan.store.AgentLifecycle} into the worker-heartbeat path (closes #719).
 *
 * <p>Workers write {@code titan.agents} directly via {@code titan-worker}'s {@code WorkerDb}; this
 * IT simulates that by raw-inserting/updating {@code titan.agents} rows and then driving {@link
 * AgentReaperScheduler#runOnce()} to assert {@code agent_events} converges.
 *
 * <p>Cases:
 *
 * <ol>
 *   <li>brand-new agent row (simulating first heartbeat creating the row) → reaper tick emits
 *       exactly one {@code JOINED}.
 *   <li>same agent's heartbeat ticks 100 more times → still exactly one {@code JOINED} (dedup
 *       window holds — the reaper is idempotent under load).
 *   <li>agent goes stale (last_heartbeat older than threshold) → reaper tick emits {@code LEFT} and
 *       flips status to {@code OFFLINE}.
 *   <li>reaper runs twice over the same dead agent → still exactly one {@code LEFT} (the OFFLINE
 *       flip removes it from the {@code findStale} candidate set).
 * </ol>
 */
@Testcontainers
class AgentLifecycleWireIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  /**
   * Stale threshold for the IT — small enough that the test can drive it deterministically by
   * back-dating {@code last_heartbeat}. Production default is 90s; we use 60s for parity with the
   * production {@code findStale} semantics.
   */
  private static final int STALE_SECONDS = 60;

  private HikariDataSource ds;
  private TitanStores stores;
  private AgentReaperScheduler reaper;

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
    reaper = new AgentReaperScheduler(stores, STALE_SECONDS);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  /** Simulate {@code titan-worker}'s {@code WorkerDb.register()} INSERT branch — direct JDBC. */
  private void simulateWorkerRegisterInsert(String agentId) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.agents "
                    + "(agent_id, display_name, labels, num_executors, status, "
                    + " registered_at, last_heartbeat) "
                    + "VALUES (?, ?, 'linux', 2, 'ONLINE', CURRENT_TIMESTAMP, "
                    + " CURRENT_TIMESTAMP)")) {
      ps.setString(1, agentId);
      ps.setString(2, "Display-" + agentId);
      ps.executeUpdate();
    }
  }

  /** Simulate {@code WorkerDb.heartbeat()} — bumps {@code last_heartbeat} only. */
  private void simulateHeartbeat(String agentId) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE titan.agents SET last_heartbeat = CURRENT_TIMESTAMP "
                    + "WHERE agent_id = ?")) {
      ps.setString(1, agentId);
      ps.executeUpdate();
    }
  }

  /** Force {@code last_heartbeat} into the deep past — simulates a worker that died silently. */
  private void backdateHeartbeat(String agentId, int secondsAgo) throws Exception {
    Timestamp past = new Timestamp(System.currentTimeMillis() - secondsAgo * 1000L);
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement("UPDATE titan.agents SET last_heartbeat = ? WHERE agent_id = ?")) {
      ps.setTimestamp(1, past);
      ps.setString(2, agentId);
      ps.executeUpdate();
    }
  }

  @Test
  void newAgentRow_firstReaperTick_emitsExactlyOneJoined() throws Exception {
    String agentId = "agent-wire-" + System.nanoTime();
    simulateWorkerRegisterInsert(agentId);

    AgentReaperScheduler.ReaperPass pass = reaper.runOnce();

    assertTrue(pass.agentsScanned() >= 1, "saw the freshly-inserted agent");
    assertEquals(1, stores.agentEvents().countByAgentAndType(agentId, "JOINED"));
    assertEquals(0, stores.agentEvents().countByAgentAndType(agentId, "LEFT"));
    // Sanity — display_name surfaces through the join.
    var row =
        stores.agentEvents().listRecent(50).stream()
            .filter(r -> r.agentId().equals(agentId))
            .findFirst()
            .orElseThrow();
    assertEquals("JOINED", row.eventType());
    assertNotNull(row.occurredAt());
  }

  @Test
  void heartbeats100Times_stillOneJoined_dedupHolds() throws Exception {
    String agentId = "agent-wire-" + System.nanoTime();
    simulateWorkerRegisterInsert(agentId);

    // First reaper tick emits JOINED.
    reaper.runOnce();
    assertEquals(1, stores.agentEvents().countByAgentAndType(agentId, "JOINED"));

    // 100 heartbeat ticks + 100 reaper ticks. Dedup window (5min) covers the burst.
    for (int i = 0; i < 100; i++) {
      simulateHeartbeat(agentId);
      reaper.runOnce();
    }
    assertEquals(
        1,
        stores.agentEvents().countByAgentAndType(agentId, "JOINED"),
        "dedup window suppresses all 100 re-emissions");
    assertEquals(0, stores.agentEvents().countByAgentAndType(agentId, "LEFT"));
  }

  @Test
  void staleAgent_reaperEmitsLeftAndFlipsOffline() throws Exception {
    String agentId = "agent-wire-" + System.nanoTime();
    simulateWorkerRegisterInsert(agentId);

    // Establish baseline JOINED.
    reaper.runOnce();
    assertEquals(1, stores.agentEvents().countByAgentAndType(agentId, "JOINED"));

    // Worker dies — last_heartbeat slides past the stale threshold.
    backdateHeartbeat(agentId, STALE_SECONDS * 2);

    AgentReaperScheduler.ReaperPass pass = reaper.runOnce();

    assertTrue(pass.leftEmitted() >= 1, "stale agent was reaped");
    assertEquals(1, stores.agentEvents().countByAgentAndType(agentId, "LEFT"));
    assertEquals("OFFLINE", stores.agents().findById(agentId).orElseThrow().status);
  }

  @Test
  void reaperRunsTwiceOverDeadAgent_noDoubleLeft() throws Exception {
    String agentId = "agent-wire-" + System.nanoTime();
    simulateWorkerRegisterInsert(agentId);

    reaper.runOnce(); // JOINED
    backdateHeartbeat(agentId, STALE_SECONDS * 2);
    reaper.runOnce(); // LEFT + OFFLINE flip
    reaper.runOnce(); // second sweep — must NOT re-emit LEFT

    assertEquals(
        1,
        stores.agentEvents().countByAgentAndType(agentId, "LEFT"),
        "second sweep is a no-op — status='OFFLINE' excludes the row from findStale");
    assertEquals(1, stores.agentEvents().countByAgentAndType(agentId, "JOINED"));
  }
}

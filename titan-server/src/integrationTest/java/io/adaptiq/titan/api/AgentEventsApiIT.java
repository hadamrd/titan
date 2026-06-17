package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.AgentEventsDao;
import io.adaptiq.titan.store.AgentLifecycle;
import io.adaptiq.titan.store.TitanStores;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres-backed integration test for {@link AgentEventsDao} + {@link AgentLifecycle} — closes
 * #714.
 *
 * <p>The four adversarial cases from the brief:
 *
 * <ol>
 *   <li>register agent → exactly 1 {@code JOINED} event.
 *   <li>register same agent twice within 5 minutes → still 1 {@code JOINED} (dedup window).
 *   <li>register then markOffline → {@code JOINED} + {@code LEFT}.
 *   <li>register, simulate 6-minute elapsed window, register again → 2 {@code JOINED} (dedup window
 *       expired).
 * </ol>
 *
 * <p>The "wait 6 minutes" case uses the {@link AgentEventsDao#recordJoinedSince} overload with a
 * caller-supplied cutoff timestamp rather than {@link Thread#sleep} — the production {@link
 * AgentEventsDao#recordJoined} default reads the wall clock, and an IT that paused 6 minutes would
 * be unacceptable. {@code recordJoinedSince} is the inline-testable seam that backs the production
 * helper.
 *
 * <p>Test isolation: each test uses a fresh {@code agent_id} derived from {@code System.nanoTime()}
 * so a leaked row from a previous run cannot perturb dedup counting.
 */
@Testcontainers
class AgentEventsApiIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private AgentLifecycle lifecycle;

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
    lifecycle = AgentLifecycle.of(stores);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  @Test
  void registerOnce_emitsExactlyOneJoinedEvent() {
    String agentId = "agent-it-" + System.nanoTime();

    lifecycle.register(agentId, "Display " + agentId, "linux", 2);

    assertEquals(1, stores.agentEvents().countByAgent(agentId), "exactly one event");
    assertEquals(1, stores.agentEvents().countByAgentAndType(agentId, "JOINED"), "and it's JOINED");

    List<AgentEventsDao.AgentEventRow> recent = stores.agentEvents().listRecent(50);
    AgentEventsDao.AgentEventRow mine =
        recent.stream().filter(r -> r.agentId().equals(agentId)).findFirst().orElseThrow();
    assertEquals("JOINED", mine.eventType());
    assertEquals("Display " + agentId, mine.displayName(), "display name surfaces from join");
    assertNotNull(mine.occurredAt());
  }

  @Test
  void registerTwiceInsideDedupWindow_stillOneJoined() {
    String agentId = "agent-it-" + System.nanoTime();

    lifecycle.register(agentId, "Display", "linux", 2);
    lifecycle.register(agentId, "Display", "linux", 2);

    assertEquals(
        1,
        stores.agentEvents().countByAgentAndType(agentId, "JOINED"),
        "second register inside 5-minute window is deduped");
    assertEquals(1, stores.agentEvents().countByAgent(agentId));
  }

  @Test
  void registerThenMarkOffline_emitsJoinedThenLeft() {
    String agentId = "agent-it-" + System.nanoTime();

    lifecycle.register(agentId, "Display", "linux", 2);
    lifecycle.markOffline(agentId);

    assertEquals(1, stores.agentEvents().countByAgentAndType(agentId, "JOINED"));
    assertEquals(1, stores.agentEvents().countByAgentAndType(agentId, "LEFT"));
    assertEquals(2, stores.agentEvents().countByAgent(agentId));

    List<AgentEventsDao.AgentEventRow> mine =
        stores.agentEvents().listRecent(50).stream()
            .filter(r -> r.agentId().equals(agentId))
            .toList();
    assertEquals(2, mine.size(), "both events present");
    // listRecent is newest-first → LEFT comes first.
    assertEquals("LEFT", mine.get(0).eventType());
    assertEquals("JOINED", mine.get(1).eventType());
    assertTrue(
        !mine.get(0).occurredAt().isBefore(mine.get(1).occurredAt()),
        "LEFT.occurred_at >= JOINED.occurred_at");
  }

  @Test
  void registerAgainAfterDedupWindowExpired_emitsSecondJoined() {
    String agentId = "agent-it-" + System.nanoTime();

    // First register — uses the production helper, which reads the wall clock.
    lifecycle.register(agentId, "Display", "linux", 2);
    assertEquals(1, stores.agentEvents().countByAgentAndType(agentId, "JOINED"));

    // Simulate "wait 6 minutes" by calling the inline-testable overload with a cutoff that lies
    // 6 minutes in the future relative to now. That makes the dedup predicate
    //   occurred_at >= (now + 6min)
    // false for the just-inserted row → the WHERE NOT EXISTS allows the second insert through,
    // exactly as a real 6-minute wait would.
    Timestamp sixMinutesAhead = new Timestamp(System.currentTimeMillis() + 6 * 60 * 1000L);
    int inserted = stores.agentEvents().recordJoinedSince(agentId, sixMinutesAhead);

    assertEquals(1, inserted, "dedup window expired → second JOINED is recorded");
    assertEquals(
        2,
        stores.agentEvents().countByAgentAndType(agentId, "JOINED"),
        "two distinct JOINED rows after dedup window expiry");
  }
}

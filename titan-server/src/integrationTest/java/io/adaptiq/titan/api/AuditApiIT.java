package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.audit.AuditTargetType;
import io.adaptiq.titan.store.AuditLogDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.AuditLogRow;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres-backed integration test for the {@code titan.audit_log} substrate (closes #478) — the
 * V20 migration, the {@link AuditLogDao} insert / find pair, and the index-backed filter shape
 * exercised by {@code GET /api/v1/audit}.
 *
 * <p>Asserts:
 *
 * <ul>
 *   <li>insert round-trips occurredAt + actor + action + target_type/target_id + details_json;
 *   <li>actor filter narrows the result set;
 *   <li>action filter narrows the result set;
 *   <li>since filter narrows the result set;
 *   <li>count and find agree on totals under filter;
 *   <li>ordering is occurred_at DESC.
 * </ul>
 *
 * <p>Mirrors {@link StatsApiIT} — same Testcontainers Postgres + Flyway setup, hits the DAO
 * directly (the OIDC + JAX-RS glue is exercised by {@code AuditApiTest} as a follow-up).
 */
@Testcontainers
class AuditApiIT {

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

  @Test
  void insertAndFilter_actorActionTarget_since_allCompose() {
    AuditLogDao dao = stores.auditLog();

    // Pin the clock once so the since-cutoff math is deterministic regardless of how long the
    // inserts take. Offsets are spaced ≥30s apart and the since boundary (t0-90s) sits cleanly
    // between aliceTrigger (t0-120s, excluded) and bobTrigger (t0-60s, included) — no off-by-one
    // window where wall-clock drift can flip a row across the boundary.
    Instant t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    long oldId = insert(dao, t0.minusSeconds(3600), "alice", AuditAction.JOB_CREATE, "42", null);
    long aliceTrigger =
        insert(dao, t0.minusSeconds(120), "alice", AuditAction.BUILD_TRIGGER, "100", null);
    long bobTrigger =
        insert(dao, t0.minusSeconds(60), "bob", AuditAction.BUILD_TRIGGER, "101", null);
    long aliceRevoke = insert(dao, t0.minusSeconds(30), "alice", AuditAction.PAT_REVOKE, "7", null);

    assertTrue(oldId > 0);
    assertTrue(aliceTrigger > 0);
    assertTrue(bobTrigger > 0);
    assertTrue(aliceRevoke > 0);

    // No filter — newest first.
    List<AuditLogRow> all = dao.findRecent(null, null, null, null, 50, 0);
    assertEquals(4, all.size(), "all four rows visible");
    assertEquals(aliceRevoke, all.get(0).id, "newest row first (occurred_at DESC)");
    assertEquals(oldId, all.get(3).id, "oldest row last");

    // Round-trip the persisted payload.
    AuditLogRow head = all.get(0);
    assertEquals("alice", head.actor);
    assertEquals("PAT_REVOKE", head.action);
    assertEquals("PAT", head.targetType);
    assertEquals("7", head.targetId);
    assertNotNull(head.occurredAt, "occurred_at populated");
    assertNull(head.detailsJson, "details_json round-trips null when not provided");

    // Filter: actor.
    List<AuditLogRow> aliceRows = dao.findRecent("alice", null, null, null, 50, 0);
    assertEquals(3, aliceRows.size(), "alice's three rows");
    assertEquals(3, dao.countRecent("alice", null, null, null), "count agrees with find");

    // Filter: action.
    List<AuditLogRow> triggers = dao.findRecent(null, "BUILD_TRIGGER", null, null, 50, 0);
    assertEquals(2, triggers.size(), "two triggers (one alice, one bob)");

    // Filter: targetType.
    List<AuditLogRow> patRows = dao.findRecent(null, null, "PAT", null, 50, 0);
    assertEquals(1, patRows.size(), "one PAT row");

    // Filter: since — only the two most recent fall after t0-90s.
    List<AuditLogRow> recent = dao.findRecent(null, null, null, t0.minusSeconds(90), 50, 0);
    assertEquals(2, recent.size(), "since narrows to the two recent rows");

    // Combined filter: actor + action.
    List<AuditLogRow> aliceTriggers = dao.findRecent("alice", "BUILD_TRIGGER", null, null, 50, 0);
    assertEquals(1, aliceTriggers.size(), "actor + action composes");
    assertEquals(aliceTrigger, aliceTriggers.get(0).id);

    // All four filters compose (the bug class this test guards): actor + action + targetType
    // + since must AND together. Pick a window that excludes aliceTrigger (older than since) so
    // the actor+action match alone would return 1 row — proving the since predicate is applied.
    List<AuditLogRow> aliceTriggersRecent =
        dao.findRecent("alice", "BUILD_TRIGGER", "BUILD", t0.minusSeconds(90), 50, 0);
    assertEquals(
        0,
        aliceTriggersRecent.size(),
        "actor+action+targetType+since composes — aliceTrigger sits before the since cutoff");
    assertEquals(
        0, dao.countRecent("alice", "BUILD_TRIGGER", "BUILD", t0.minusSeconds(90)), "count agrees");

    // And the symmetric positive: bob's trigger satisfies all four filters.
    List<AuditLogRow> bobTriggersRecent =
        dao.findRecent("bob", "BUILD_TRIGGER", "BUILD", t0.minusSeconds(90), 50, 0);
    assertEquals(1, bobTriggersRecent.size(), "bob's trigger matches all four filters");
    assertEquals(bobTrigger, bobTriggersRecent.get(0).id);

    // Pagination.
    List<AuditLogRow> page1 = dao.findRecent(null, null, null, null, 2, 0);
    List<AuditLogRow> page2 = dao.findRecent(null, null, null, null, 2, 2);
    assertEquals(2, page1.size());
    assertEquals(2, page2.size());
    assertEquals(aliceRevoke, page1.get(0).id);
    assertEquals(oldId, page2.get(1).id);
  }

  @Test
  void insert_withDetailsJson_roundTripsPayload() {
    AuditLogDao dao = stores.auditLog();
    AuditLogRow row = new AuditLogRow();
    row.actor = "alice";
    row.action = AuditAction.JOB_UPDATE.name();
    row.targetType = AuditTargetType.JOB.name();
    row.targetId = "42";
    row.detailsJson = "{\"oldScriptLen\":120,\"newScriptLen\":145}";
    long id = dao.insert(row);

    AuditLogRow fetched = dao.findRecent(null, null, null, null, 1, 0).get(0);
    assertEquals(id, fetched.id);
    assertEquals("{\"oldScriptLen\":120,\"newScriptLen\":145}", fetched.detailsJson);
  }

  private static long insert(
      AuditLogDao dao,
      Instant when,
      String actor,
      AuditAction action,
      String targetId,
      String details) {
    AuditLogRow row = new AuditLogRow();
    row.occurredAt = when;
    row.actor = actor;
    row.action = action.name();
    row.targetType =
        action == AuditAction.JOB_CREATE || action == AuditAction.JOB_UPDATE
            ? AuditTargetType.JOB.name()
            : action == AuditAction.PAT_CREATE || action == AuditAction.PAT_REVOKE
                ? AuditTargetType.PAT.name()
                : AuditTargetType.BUILD.name();
    row.targetId = targetId;
    row.detailsJson = details;
    return dao.insert(row);
  }
}

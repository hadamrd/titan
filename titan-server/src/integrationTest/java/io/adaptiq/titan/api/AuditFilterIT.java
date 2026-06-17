package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.api.dto.AuditEventDto;
import io.adaptiq.titan.api.dto.AuditPage;
import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.audit.AuditTargetType;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.AuditLogRow;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres-backed integration test for the extended {@link AuditApi} filter shape (closes #727).
 * Mirrors {@code BuildsFilterIT} — same Testcontainers Postgres + Flyway substrate, hits the HTTP
 * controller directly so the JDBI parameterised SQL is exercised end-to-end.
 *
 * <p>Adversarial cases per the brief:
 *
 * <ul>
 *   <li>Empty filters → same set as a no-param call (legacy back-compat).
 *   <li>Actor substring (ILIKE) — case-insensitive substring match.
 *   <li>{@code action[]} — repeated param yields an OR-within / AND-across-other-filters subset.
 *   <li>{@code action=} (empty list) → treated as no filter, NOT "match nothing".
 *   <li>SQL injection in {@code actor} → zero rows, never a 500, table still intact.
 *   <li>Combined filters → AND semantics.
 * </ul>
 */
@Testcontainers
class AuditFilterIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private AuditApi api;

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
    api = new AuditApi(stores);

    Instant now = Instant.now();
    seed(now.minusSeconds(3600), "alice@corp", AuditAction.JOB_CREATE, AuditTargetType.JOB, "10");
    seed(
        now.minusSeconds(1800),
        "Alice@corp",
        AuditAction.BUILD_TRIGGER,
        AuditTargetType.BUILD,
        "42");
    seed(now.minusSeconds(900), "bob@corp", AuditAction.BUILD_ABORT, AuditTargetType.BUILD, "42");
    seed(now.minusSeconds(600), "carol@corp", AuditAction.PAT_CREATE, AuditTargetType.PAT, "7");
    seed(now.minusSeconds(60), "carol@corp", AuditAction.PAT_REVOKE, AuditTargetType.PAT, "7");
    seed(now.minusSeconds(30), "system", AuditAction.JOB_UPDATE, AuditTargetType.JOB, "99");
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  @Test
  void emptyFilters_returnsAllRows_sameAsLegacyUnfiltered() {
    AuditPage page = api.list(null, null, null, null, null, 0, 50);
    assertEquals(6, page.items().size(), "every seeded row visible");
    assertEquals(6L, page.total());
    // Newest-first — JOB_UPDATE (30s ago) is the head.
    assertEquals("JOB_UPDATE", page.items().get(0).action());
  }

  @Test
  void actorFilter_isCaseInsensitiveSubstring() {
    // "alice" matches both 'alice@corp' (lowercase seed) and 'Alice@corp' (mixed-case seed).
    AuditPage page = api.list("alice", null, null, null, null, 0, 50);
    assertEquals(2, page.items().size(), "ILIKE matches both casings");
    for (AuditEventDto e : page.items()) {
      assertTrue(
          e.actor().toLowerCase().contains("alice"),
          "every returned row's actor contains 'alice' (any case)");
    }
  }

  @Test
  void actionList_returnsOrWithinAndCorrectSubset() {
    AuditPage page = api.list(null, List.of("PAT_CREATE", "PAT_REVOKE"), null, null, null, 0, 50);
    assertEquals(2, page.items().size(), "two PAT_* rows");
    for (AuditEventDto e : page.items()) {
      assertTrue(
          e.action().equals("PAT_CREATE") || e.action().equals("PAT_REVOKE"),
          "every row is one of the requested actions");
    }
  }

  @Test
  void emptyActionList_treatedAsNoFilter_notMatchNothing() {
    // List.of() — explicit empty multi-param. The contract is "no action filter"; a half-typed
    // ?action= URL must not silently render zero rows (that would look like data loss).
    AuditPage page = api.list(null, List.of(), null, null, null, 0, 50);
    assertEquals(6, page.items().size(), "empty action list must NOT filter to zero rows");

    // Blanks-only also degrades to no filter.
    AuditPage blanks = api.list(null, List.of("", "  "), null, null, null, 0, 50);
    assertEquals(6, blanks.items().size(), "blank action tokens must NOT filter to zero rows");
  }

  @Test
  void sqlInjectionInActor_returnsCleanEmptyResult_not500() {
    String evil = "%'; DROP TABLE titan.audit_log; --";
    AuditPage page = api.list(evil, null, null, null, null, 0, 50);
    assertEquals(0, page.items().size(), "no row matches the literal SQLi payload");
    assertEquals(0L, page.total());

    // Critically: table must still exist after the call (binding worked, no SQL was executed).
    assertTrue(auditLogTableExists(), "the audit_log table is still here — no SQL was executed");
    AuditPage probe = api.list(null, null, null, null, null, 0, 50);
    assertEquals(6, probe.items().size(), "all rows still present post-injection-probe");
  }

  @Test
  void combinedFilters_areAndedTogether() {
    Instant oneHourAgo = Instant.now().minusSeconds(3700);
    // actor=carol AND action=PAT_REVOKE AND since=1h → exactly one row (the 60s-ago revoke).
    AuditPage page =
        api.list("carol", List.of("PAT_REVOKE"), null, null, oneHourAgo.toString(), 0, 50);
    assertEquals(1, page.items().size(), "AND must narrow to the single matching row");
    assertEquals("PAT_REVOKE", page.items().get(0).action());
    assertEquals("carol@corp", page.items().get(0).actor());
  }

  @Test
  void combinedFilters_andSemantics_notOr() {
    // actor=bob AND action=PAT_CREATE → empty (bob never created a PAT). If filters were OR'd
    // we'd see 2 rows (bob's BUILD_ABORT + carol's PAT_CREATE).
    AuditPage page = api.list("bob", List.of("PAT_CREATE"), null, null, null, 0, 50);
    assertEquals(0, page.items().size(), "AND must exclude rows that match only ONE filter");
  }

  @Test
  void resourceFilter_substringAcrossTargetTypeAndId() {
    // resource=42 → the two BUILD/42 rows.
    AuditPage byId = api.list(null, null, null, "42", null, 0, 50);
    assertEquals(2, byId.items().size(), "resource matches target_id substring");
    for (AuditEventDto e : byId.items()) {
      assertEquals("42", e.targetId());
    }

    // resource=PAT → narrows to target_type=PAT rows.
    AuditPage byType = api.list(null, null, null, "PAT", null, 0, 50);
    assertEquals(2, byType.items().size(), "resource matches target_type substring");
    for (AuditEventDto e : byType.items()) {
      assertEquals("PAT", e.targetType());
    }
  }

  @Test
  void sinceFilter_returnsOnlyRowsAtOrAfterCutoff() {
    Instant cutoff = Instant.now().minusSeconds(700);
    AuditPage recent = api.list(null, null, null, null, cutoff.toString(), 0, 50);
    // 600s ago (PAT_CREATE), 60s ago (PAT_REVOKE), 30s ago (JOB_UPDATE) — three rows.
    assertEquals(3, recent.items().size(), "since narrows to the three most recent rows");
    for (AuditEventDto e : recent.items()) {
      assertNotNull(e.occurredAt());
      assertTrue(
          !e.occurredAt().isBefore(cutoff), "every returned row is at or after the since cutoff");
    }
  }

  @Test
  void malformedSince_isHttp400_notSilentlyIgnored() {
    assertThrows(
        ApiBadRequestException.class,
        () -> api.list(null, null, null, null, "not-a-timestamp", 0, 50));
  }

  @Test
  void unknownAction_isHttp400_notSilentEmpty() {
    assertThrows(
        ApiBadRequestException.class,
        () -> api.list(null, List.of("DELETE_ALL"), null, null, null, 0, 50));
  }

  @Test
  void blankActorAndResource_treatedAsAbsent() {
    AuditPage page = api.list("  ", null, null, "  ", null, 0, 50);
    assertEquals(6, page.items().size(), "blank string filters must degrade to unfiltered");
  }

  @Test
  void actionAndResourceTogether_compose() {
    // PAT actions targeting "7" → both the create and revoke on target_id=7.
    AuditPage page = api.list(null, List.of("PAT_CREATE", "PAT_REVOKE"), null, "7", null, 0, 50);
    assertEquals(2, page.items().size(), "action[] AND resource compose correctly");
    for (AuditEventDto e : page.items()) {
      assertEquals("7", e.targetId());
    }
  }

  @Test
  void legacyTargetTypeFilter_stillWorks() {
    // The v0 single-enum targetType filter is preserved for back-compat with existing UI / scripts.
    AuditPage jobs = api.list(null, null, "JOB", null, null, 0, 50);
    assertFalse(jobs.items().isEmpty(), "JOB target rows exist");
    for (AuditEventDto e : jobs.items()) {
      assertEquals("JOB", e.targetType());
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void seed(
      Instant when, String actor, AuditAction action, AuditTargetType targetType, String targetId) {
    AuditLogRow row = new AuditLogRow();
    row.occurredAt = when;
    row.actor = actor;
    row.action = action.name();
    row.targetType = targetType.name();
    row.targetId = targetId;
    row.detailsJson = null;
    stores.auditLog().insert(row);
  }

  private boolean auditLogTableExists() {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT 1 FROM information_schema.tables "
                    + "WHERE table_schema='titan' AND table_name='audit_log'")) {
      return rs.next();
    } catch (Exception e) {
      return false;
    }
  }
}

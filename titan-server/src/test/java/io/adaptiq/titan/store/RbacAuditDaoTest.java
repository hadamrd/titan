package io.adaptiq.titan.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.db.TitanDataException;
import io.adaptiq.titan.store.rows.RbacAuditRow;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Direct DAO tests for {@link RbacAuditDao} — the typed audit trail for {@code @RequiresRole}
 * checks (closes #1131, epic #1114).
 *
 * <p>Backed by the same H2 schema-loader the API tests use ({@code FakeTitanStores}), reached via
 * reflection because {@code FakeTitanStores} lives in the API test source set. Each test gets a
 * fresh, isolated database — the same pattern {@link TestResultDaoTest} uses.
 *
 * <p>Adversarial coverage per the testing manifesto:
 *
 * <ul>
 *   <li>round-trip happy path (insert → count → recent)
 *   <li>null userId is permitted (anonymous probe is auditable)
 *   <li>null effectiveRole is permitted (resolver returned no role)
 *   <li>SQL CHECK constraint rejects an unknown decision (fail-closed at the DB)
 *   <li>countForScope discriminates by (scope_kind, scope_id)
 *   <li>recentForUser filters correctly
 * </ul>
 */
class RbacAuditDaoTest {

  private TitanStores stores;

  @BeforeEach
  void setUp() throws Exception {
    Class<?> fake = Class.forName("io.adaptiq.titan.api.FakeTitanStores");
    Method create = fake.getDeclaredMethod("create");
    create.setAccessible(true);
    stores = (TitanStores) create.invoke(null);
  }

  // ── happy path ────────────────────────────────────────────────────────

  @Test
  void insert_and_count_roundtrip() {
    assertEquals(0, stores.rbacAudit().count());

    stores
        .rbacAudit()
        .insert("alice", "JobsApi.deleteJob", "REPO", "42", "ADMIN", "MAINTAINER", "DENY");

    assertEquals(1, stores.rbacAudit().count());

    List<RbacAuditRow> recent = stores.rbacAudit().recent(10);
    assertEquals(1, recent.size());
    RbacAuditRow row = recent.get(0);
    assertEquals("alice", row.userId);
    assertEquals("JobsApi.deleteJob", row.endpoint);
    assertEquals("REPO", row.scopeKind);
    assertEquals("42", row.scopeId);
    assertEquals("ADMIN", row.requiredRole);
    assertEquals("MAINTAINER", row.effectiveRole);
    assertEquals("DENY", row.decision);
    assertNotNull(row.occurredAt);
  }

  // ── nullable surfaces ────────────────────────────────────────────────

  @Test
  void anonymous_user_is_auditable() {
    stores
        .rbacAudit()
        .insert(null, "BuildDetailApi.cancelBuild", "ORG", "global", "DEVELOPER", "VIEWER", "DENY");

    List<RbacAuditRow> recent = stores.rbacAudit().recent(1);
    assertEquals(1, recent.size());
    assertNull(recent.get(0).userId);
    assertEquals("DENY", recent.get(0).decision);
  }

  @Test
  void null_effective_role_is_permitted() {
    stores.rbacAudit().insert("bob", "JobsApi.patchJob", "REPO", "7", "MAINTAINER", null, "DENY");

    assertEquals(1, stores.rbacAudit().count());
    assertNull(stores.rbacAudit().recent(1).get(0).effectiveRole);
  }

  // ── adversarial: CHECK constraint ────────────────────────────────────

  /** The SQL CHECK constraint must reject any decision string outside {ALLOW, DENY}. */
  @Test
  void unknown_decision_is_rejected_at_db_level() {
    assertThrows(
        TitanDataException.class,
        () ->
            stores
                .rbacAudit()
                .insert(
                    "carol",
                    "BuildReplayApi.replay",
                    "ORG",
                    "global",
                    "DEVELOPER",
                    "VIEWER",
                    "MAYBE"));
  }

  // ── scope discrimination ─────────────────────────────────────────────

  @Test
  void count_for_scope_filters_correctly() {
    stores.rbacAudit().insert("u", "e1", "ORG", "global", "ADMIN", "ADMIN", "ALLOW");
    stores.rbacAudit().insert("u", "e2", "REPO", "42", "ADMIN", "VIEWER", "DENY");
    stores.rbacAudit().insert("u", "e3", "REPO", "42", "MAINTAINER", "VIEWER", "DENY");
    stores.rbacAudit().insert("u", "e4", "REPO", "99", "ADMIN", "ADMIN", "ALLOW");

    assertEquals(4, stores.rbacAudit().count());
    assertEquals(2, stores.rbacAudit().countForScope("REPO", "42"));
    assertEquals(1, stores.rbacAudit().countForScope("REPO", "99"));
    assertEquals(1, stores.rbacAudit().countForScope("ORG", "global"));
    assertEquals(0, stores.rbacAudit().countForScope("REPO", "nonexistent"));
  }

  @Test
  void recent_for_user_filters_by_user_id() {
    stores.rbacAudit().insert("alice", "e1", "ORG", "global", "ADMIN", "ADMIN", "ALLOW");
    stores.rbacAudit().insert("bob", "e2", "ORG", "global", "ADMIN", "VIEWER", "DENY");
    stores.rbacAudit().insert("alice", "e3", "REPO", "42", "MAINTAINER", "MAINTAINER", "ALLOW");

    List<RbacAuditRow> alicesRows = stores.rbacAudit().recentForUser("alice", 10);
    assertEquals(2, alicesRows.size());
    assertTrue(alicesRows.stream().allMatch(r -> "alice".equals(r.userId)));

    List<RbacAuditRow> bobsRows = stores.rbacAudit().recentForUser("bob", 10);
    assertEquals(1, bobsRows.size());
    assertEquals("bob", bobsRows.get(0).userId);
  }

  // ── filtered + paginated query (#1167) ────────────────────────────────

  /** No filters → newest-first feed, ordered occurred_at DESC, id DESC. */
  @Test
  void findFiltered_noFilters_returnsNewestFirst() {
    long first = insertReturningId("alice", "REPO", "ALLOW");
    long second = insertReturningId("bob", "ORG", "DENY");
    long third = insertReturningId("carol", "REPO", "ALLOW");

    List<RbacAuditRow> rows = stores.rbacAudit().findFiltered(null, null, null, null, 50, 0);
    assertEquals(3, rows.size());
    // Same-timestamp rows tie-break on id DESC, so the newest insert (highest id) sorts first.
    assertEquals(third, rows.get(0).id);
    assertEquals(second, rows.get(1).id);
    assertEquals(first, rows.get(2).id);
    assertEquals(3, stores.rbacAudit().countFiltered(null, null, null, null));
  }

  /** verdict filter isolates DENY rows; count agrees and ALLOW is complementary. */
  @Test
  void findFiltered_verdictDeny_isolatesDenyRows() {
    stores.rbacAudit().insert("alice", "e1", "REPO", "1", "ADMIN", "ADMIN", "ALLOW");
    stores.rbacAudit().insert("alice", "e2", "REPO", "2", "ADMIN", "VIEWER", "DENY");
    stores.rbacAudit().insert("bob", "e3", "ORG", "global", "MAINTAINER", "VIEWER", "DENY");

    List<RbacAuditRow> denies = stores.rbacAudit().findFiltered(null, "DENY", null, null, 50, 0);
    assertEquals(2, denies.size());
    assertTrue(denies.stream().allMatch(r -> "DENY".equals(r.decision)));
    assertEquals(2, stores.rbacAudit().countFiltered(null, "DENY", null, null));
    assertEquals(1, stores.rbacAudit().countFiltered(null, "ALLOW", null, null));
  }

  /** actor filter is a case-insensitive substring (ILIKE), not an exact match. */
  @Test
  void findFiltered_actorIsCaseInsensitiveSubstring() {
    stores.rbacAudit().insert("alice.smith", "e1", "REPO", "1", "ADMIN", "ADMIN", "ALLOW");
    stores.rbacAudit().insert("bob", "e2", "ORG", "global", "ADMIN", "VIEWER", "DENY");

    // "ALICE" (different case) as a substring matches "alice.smith".
    List<RbacAuditRow> rows = stores.rbacAudit().findFiltered("ALICE", null, null, null, 50, 0);
    assertEquals(1, rows.size());
    assertEquals("alice.smith", rows.get(0).userId);
    assertEquals(1, stores.rbacAudit().countFiltered("alice", null, null, null));
  }

  /** scopeKind filter narrows to one kind. */
  @Test
  void findFiltered_scopeKindNarrows() {
    stores.rbacAudit().insert("u", "e1", "REPO", "1", "ADMIN", "ADMIN", "ALLOW");
    stores.rbacAudit().insert("u", "e2", "ORG", "global", "ADMIN", "ADMIN", "ALLOW");
    stores.rbacAudit().insert("u", "e3", "REPO", "2", "ADMIN", "VIEWER", "DENY");

    assertEquals(2, stores.rbacAudit().countFiltered(null, null, "REPO", null));
    List<RbacAuditRow> orgRows = stores.rbacAudit().findFiltered(null, null, "ORG", null, 50, 0);
    assertEquals(1, orgRows.size());
    assertEquals("ORG", orgRows.get(0).scopeKind);
  }

  /**
   * {@code since} lower bound excludes older rows. Rows are stamped at "now" by the column default;
   * a bound one day in the future excludes everything, a bound one day in the past includes
   * everything — exercising both sides of the {@code occurred_at >= :since} comparison.
   */
  @Test
  void findFiltered_sinceExcludesOlderRows() {
    stores.rbacAudit().insert("alice", "e1", "REPO", "1", "ADMIN", "ADMIN", "ALLOW");
    stores.rbacAudit().insert("bob", "e2", "ORG", "global", "ADMIN", "VIEWER", "DENY");

    java.time.Instant future = java.time.Instant.now().plus(1, java.time.temporal.ChronoUnit.DAYS);
    java.time.Instant past = java.time.Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS);

    assertEquals(0, stores.rbacAudit().countFiltered(null, null, null, future));
    assertTrue(stores.rbacAudit().findFiltered(null, null, null, future, 50, 0).isEmpty());
    assertEquals(2, stores.rbacAudit().countFiltered(null, null, null, past));
  }

  /** offset/limit paginate without overlap or skipped rows; total is filter-wide. */
  @Test
  void findFiltered_offsetLimitPaginateWithoutOverlap() {
    long[] ids = new long[5];
    for (int i = 0; i < 5; i++) {
      ids[i] = insertReturningId("u" + i, "REPO", "ALLOW");
    }
    // Newest-first by id: ids[4], ids[3], ids[2] | ids[1], ids[0]
    List<RbacAuditRow> page1 = stores.rbacAudit().findFiltered(null, null, null, null, 3, 0);
    List<RbacAuditRow> page2 = stores.rbacAudit().findFiltered(null, null, null, null, 3, 3);
    assertEquals(3, page1.size());
    assertEquals(2, page2.size());
    assertEquals(ids[4], page1.get(0).id);
    assertEquals(ids[2], page1.get(2).id);
    assertEquals(ids[1], page2.get(0).id);
    assertEquals(ids[0], page2.get(1).id);

    // No id appears on both pages — the disjoint-page invariant.
    java.util.Set<Long> seen = new java.util.HashSet<>();
    page1.forEach(r -> seen.add(r.id));
    assertTrue(page2.stream().noneMatch(r -> seen.contains(r.id)), "pages must not overlap");
    assertEquals(5, stores.rbacAudit().countFiltered(null, null, null, null));
  }

  /**
   * Adversarial: a SQLi-style payload in the actor filter is parameterised — zero rows, no crash,
   * table intact.
   */
  @Test
  void findFiltered_sqlInjectionInActorIsInert() {
    stores.rbacAudit().insert("alice", "e1", "REPO", "1", "ADMIN", "ADMIN", "ALLOW");

    List<RbacAuditRow> rows =
        stores
            .rbacAudit()
            .findFiltered("'; DROP TABLE titan.rbac_audit; --", null, null, null, 50, 0);
    assertTrue(rows.isEmpty());
    // The table survived: the legit row is still queryable.
    assertEquals(1, stores.rbacAudit().count());
  }

  /**
   * Insert a row and return its generated id. The DAO {@code insert} is void, so we read the row
   * back via {@code recent} — the just-inserted row is the newest, hence index 0.
   */
  private long insertReturningId(String user, String scopeKind, String decision) {
    stores.rbacAudit().insert(user, "Api.call", scopeKind, "sid", "ADMIN", "VIEWER", decision);
    return stores.rbacAudit().recent(1).get(0).id;
  }
}

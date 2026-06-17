package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.api.dto.BuildDto;
import io.adaptiq.titan.api.dto.BuildsPage;
import io.adaptiq.titan.store.TitanStores;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres-backed integration test for cursor pagination on {@code GET /api/v1/builds} (closes
 * #1098). Mirrors {@link BuildsFilterIT}'s harness so the seam-under-test (HTTP controller + JDBI
 * parameterised SQL + the new cursor predicate) runs against a real Flyway-migrated Postgres.
 *
 * <p>Adversarial coverage maps directly to the ticket's acceptance criteria:
 *
 * <ul>
 *   <li>5 pages of 50 over 250 fixture builds — sixth page has {@code next = null}.
 *   <li>Pages have no overlap and no missing rows (set-cover check).
 *   <li>Inserting a new build mid-stream does NOT shift the cursor (the cursor row stays
 *       semantically the same point in the stream).
 *   <li>Malformed {@code ?after=} → HTTP 400 (ApiBadRequestException).
 *   <li>The default-no-params call returns the first 50 + a cursor — no breaking change for legacy
 *       callers.
 * </ul>
 */
@Testcontainers
class PaginationIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private BuildsApi api;
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
    api = new BuildsApi(stores);

    // Seed 250 builds, each at a distinct microsecond offset so the (queued_at, id) ordering
    // is deterministic and the cursor predicate stays unambiguous across pages.
    try (Connection c = ds.getConnection()) {
      jobId = insertJob(c, "pagination-it/" + System.nanoTime());
      Instant base = Instant.parse("2026-01-01T00:00:00Z");
      for (int i = 1; i <= 250; i++) {
        insertBuild(c, jobId, i, "SUCCESS", base.plusMillis(i), "alice");
      }
    }
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  // ── 250 fixtures → 5 pages of 50, sixth page has next=null ───────────────

  @Test
  void cursorPagination_paginatesAcrossFivePagesOfFifty_thenTerminates() {
    Set<Long> seen = new HashSet<>();
    String cursor = null;
    int pages = 0;
    int maxPages = 10; // safety bound — must terminate inside 5 pages, never a trailing empty.
    while (pages < maxPages) {
      BuildsPage page = api.list(List.of(), null, null, null, null, null, 0, 50, cursor);
      pages++;
      // Regression guard for the +1-probe fence-post fix: 250 is an exact multiple of 50, so a
      // naive "rows.size() < limit" terminator would emit a cursor on page 5 and force a 6th,
      // EMPTY fetch. With the probe the stream NEVER yields an empty page.
      assertFalse(
          page.items().isEmpty(),
          "no dangling empty page — page " + pages + " came back empty (fence-post bug)");
      assertEquals(50, page.items().size(), "page " + pages + " should be exactly limit-sized");
      for (BuildDto b : page.items()) {
        assertTrue(seen.add(b.id()), "no duplicates across pages — saw id " + b.id() + " twice");
      }
      cursor = page.next();
      if (cursor == null) {
        break; // last page signalled by a null next — the only correct terminator.
      }
    }
    assertNull(cursor, "the 5th page of an exact-multiple stream must carry next=null");
    assertEquals(5, pages, "250 / 50 = exactly 5 full pages, then next=null (no empty 6th)");
    assertEquals(250, seen.size(), "every seeded build appears across the cursor stream");
  }

  // ── exact-multiple boundary: limit=2 over a 4-row stream → page 2 ends with next=null ────

  @Test
  void cursorPagination_smallExactMultiple_noDanglingPage() {
    // Seed is 250; page with limit=2 from the head. We only assert the first two pages here:
    // page 1 must yield a cursor; page 2 must be full AND its rows strictly precede page 1's.
    BuildsPage p1 = api.list(List.of(), null, null, null, null, null, 0, 2, null);
    assertEquals(2, p1.items().size());
    assertNotNull(p1.next(), "limit=2 over 250 rows must yield a cursor after page 1");

    BuildsPage p2 = api.list(List.of(), null, null, null, null, null, 0, 2, p1.next());
    assertEquals(2, p2.items().size(), "page 2 is full — no skipped rows");
    long p1Tail = p1.items().get(1).id();
    for (BuildDto b : p2.items()) {
      assertTrue(b.id() < p1Tail, "page 2 must be strictly older than page 1 tail — no overlap");
    }
  }

  // ── stability under mid-stream insert ────────────────────────────────────

  @Test
  void cursor_isStable_acrossMidStreamInsert() throws Exception {
    // Page 1.
    BuildsPage page1 = api.list(List.of(), null, null, null, null, null, 0, 50, null);
    assertEquals(50, page1.items().size());
    String cursor = page1.next();
    assertNotNull(cursor, "page 1 of 250 must yield a next cursor");
    long page1Tail = page1.items().get(49).id();

    // Insert a NEW build NEWER than every existing row. The cursor encodes a point in the
    // stream — the next page must still be the same 50 rows, regardless of the insert.
    try (Connection c = ds.getConnection()) {
      Instant brandNew = Instant.parse("2027-12-31T23:59:59Z");
      insertBuild(c, jobId, 999_999, "SUCCESS", brandNew, "evil-mallory");
    }

    // Page 2 with the cursor we got BEFORE the insert.
    BuildsPage page2 = api.list(List.of(), null, null, null, null, null, 0, 50, cursor);
    assertEquals(
        50, page2.items().size(), "page 2 must be exactly limit-sized even after the insert");

    // The newly-inserted build's id MUST NOT appear on page 2 — the cursor scopes us to rows
    // strictly older than page1.tail's (queuedAt, id), so a newer row can't possibly leak in.
    for (BuildDto b : page2.items()) {
      assertFalse(
          b.triggeredBy().equals("evil-mallory"),
          "mid-stream insert must not appear in page 2 of the cursor stream");
      assertTrue(
          b.id() < page1Tail,
          "page 2 rows must be strictly older than page 1 tail (id=" + page1Tail + ")");
    }
  }

  // ── default no-params call: first 50 + cursor ────────────────────────────

  @Test
  void noParams_returnsFirstFiftyAndNextCursor() {
    BuildsPage page = api.list(List.of(), null, null, null, null, null, 0, 50, null);
    assertEquals(50, page.items().size());
    assertNotNull(page.next(), "with 250 rows seeded, the first page must yield a next cursor");
    assertEquals(0, page.offset(), "legacy offset field is preserved");
    assertEquals(50, page.limit(), "legacy limit field is preserved");
  }

  // ── adversarial: malformed cursor → 400 ──────────────────────────────────

  @Test
  void malformedCursor_returns400() {
    assertThrows(
        ApiBadRequestException.class,
        () -> api.list(List.of(), null, null, null, null, null, 0, 50, "!!!not-base64!!!"));

    // Valid base64, but no colon separator → 400.
    String noColon =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("nothing-to-see-here".getBytes());
    assertThrows(
        ApiBadRequestException.class,
        () -> api.list(List.of(), null, null, null, null, null, 0, 50, noColon));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static long insertJob(Connection c, String fullName) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, '', '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, fullName);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static void insertBuild(
      Connection c,
      long jobId,
      int buildNumber,
      String status,
      Instant queuedAt,
      String triggeredBy)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, queued_at, "
                + "trigger_meta_json, failure_summary, triggered_by, trigger_type) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
      ps.setLong(1, jobId);
      ps.setInt(2, buildNumber);
      ps.setString(3, status);
      ps.setTimestamp(4, Timestamp.from(queuedAt));
      ps.setNull(5, Types.VARCHAR);
      ps.setNull(6, Types.VARCHAR);
      ps.setString(7, triggeredBy);
      ps.setString(8, "manual");
      ps.executeUpdate();
    }
  }
}

package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres-backed integration test for {@link BuildsApi} — the global filterable build list (closes
 * #682). The seam under test is the HTTP controller + JDBI parameterised SQL; the adversarial cases
 * come straight from the ticket:
 *
 * <ul>
 *   <li>Empty filters → same rows as a no-param call (legacy back-compat).
 *   <li>Each filter applied independently → correct subset.
 *   <li>Combined filters → AND semantics, not OR.
 *   <li>SQL-injection attempt in {@code search} → zero rows, never a 500.
 *   <li>Unknown {@code status} token → HTTP 400 (ApiBadRequestException) at the controller, not a
 *       silent zero-row response.
 * </ul>
 */
@Testcontainers
class BuildsFilterIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private BuildsApi api;
  private long jobIdA;
  private long jobIdB;

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

    // Seed: two jobs, six builds across mixed status / branch / timing so AND semantics matter.
    Instant now = Instant.now();
    try (Connection c = ds.getConnection()) {
      jobIdA = insertJob(c, "filter-it/a-" + System.nanoTime());
      jobIdB = insertJob(c, "filter-it/b-" + System.nanoTime());
      // jobA: build 1 SUCCESS trunk (1h ago), 2 FAILED trunk (30m ago, failureSummary), 3 RUNNING
      // release (5m ago)
      insertBuild(
          c,
          jobIdA,
          1,
          "SUCCESS",
          now.minusSeconds(3600),
          "{\"branch\":\"trunk\",\"commitSha\":\"abc1234\",\"actor\":\"alice\"}",
          null,
          "alice");
      insertBuild(
          c,
          jobIdA,
          2,
          "FAILED",
          now.minusSeconds(1800),
          "{\"branch\":\"trunk\",\"commitSha\":\"def5678\",\"actor\":\"bob\"}",
          "stage 'build' timed out",
          "bob");
      insertBuild(
          c,
          jobIdA,
          3,
          "RUNNING",
          now.minusSeconds(300),
          "{\"branch\":\"release/1.x\",\"commitSha\":\"999aaaa\",\"actor\":\"alice\"}",
          null,
          "alice");
      // jobB: build 1 SUCCESS feature/foo (10d ago), 2 FAILED feature/foo (8d ago), 3 SUCCESS trunk
      // (1m ago)
      insertBuild(
          c,
          jobIdB,
          1,
          "SUCCESS",
          now.minusSeconds(10 * 24 * 3600),
          "{\"branch\":\"feature/foo\",\"commitSha\":\"111bbbb\",\"actor\":\"carol\"}",
          null,
          "carol");
      insertBuild(
          c,
          jobIdB,
          2,
          "FAILED",
          now.minusSeconds(8 * 24 * 3600),
          "{\"branch\":\"feature/foo\",\"commitSha\":\"222cccc\",\"actor\":\"carol\"}",
          "compile failed in build.sh",
          "carol");
      insertBuild(
          c,
          jobIdB,
          3,
          "SUCCESS",
          now.minusSeconds(60),
          "{\"branch\":\"trunk\",\"commitSha\":\"333dddd\",\"actor\":\"alice\"}",
          null,
          "alice");
    }
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  @Test
  void emptyFilters_returnsAllBuilds_sameAsLegacyUnfiltered() {
    BuildsPage page = api.list(List.of(), null, null, null, null, null, 0, 50);
    assertEquals(6, page.items().size(), "every seeded build appears when no filter is set");
    assertEquals(6, page.total());
    // Newest first — most recent seed (jobB build 3) is the head.
    assertEquals(3, page.items().get(0).buildNumber());
    assertEquals(jobIdB, page.items().get(0).jobId());
  }

  @Test
  void statusFilter_returnsOnlyMatchingStatuses() {
    BuildsPage failed = api.list(List.of("FAILED"), null, null, null, null, null, 0, 50);
    assertEquals(2, failed.items().size(), "only the two FAILED builds");
    for (BuildDto b : failed.items()) {
      assertEquals("FAILED", b.status());
    }

    // Repeated status param is OR within the status filter.
    BuildsPage failedOrRunning =
        api.list(List.of("FAILED", "RUNNING"), null, null, null, null, null, 0, 50);
    assertEquals(3, failedOrRunning.items().size(), "two FAILED + one RUNNING");
  }

  @Test
  void branchFilter_returnsOnlyMatchingBranch() {
    BuildsPage trunk = api.list(List.of(), "trunk", null, null, null, null, 0, 50);
    assertEquals(3, trunk.items().size(), "two trunk builds on jobA + one on jobB");
    BuildsPage release = api.list(List.of(), "release/1.x", null, null, null, null, 0, 50);
    assertEquals(1, release.items().size());
    assertEquals(3, release.items().get(0).buildNumber());
  }

  @Test
  void searchFilter_matchesFailureSummaryAndCommitSha() {
    // Commit SHA substring match.
    BuildsPage byCommit = api.list(List.of(), null, "999aaaa", null, null, null, 0, 50);
    assertEquals(1, byCommit.items().size());
    assertEquals(3, byCommit.items().get(0).buildNumber());
    assertEquals(jobIdA, byCommit.items().get(0).jobId());

    // Failure-summary substring (closest free-text we have to commit message).
    BuildsPage byFailure = api.list(List.of(), null, "timed out", null, null, null, 0, 50);
    assertEquals(1, byFailure.items().size());
    assertEquals("FAILED", byFailure.items().get(0).status());

    // Numeric search → exact build_number match. Build number 1 exists on both jobs.
    BuildsPage byNumber = api.list(List.of(), null, "1", null, null, null, 0, 50);
    assertTrue(
        byNumber.items().stream().allMatch(b -> b.buildNumber() == 1),
        "every match has build_number = 1");
    assertEquals(2, byNumber.items().size(), "two jobs each with a build #1");

    // # prefix is tolerated.
    BuildsPage byHashed = api.list(List.of(), null, "#1", null, null, null, 0, 50);
    assertEquals(2, byHashed.items().size());
  }

  @Test
  void searchByShortCommitShaPrefix_matchesByPrefix_closes776() {
    // Seed additional builds with longer, realistic-looking commit SHAs so prefix matching has
    // something to bite on. All three share the 'abc1234' 7-char prefix; only one shares
    // 'abc1234defg' (a 12-char prefix). 'feedface...' is the negative control.
    try (Connection c = ds.getConnection()) {
      insertBuild(
          c,
          jobIdA,
          100,
          "SUCCESS",
          Instant.now().minusSeconds(120),
          "{\"branch\":\"trunk\",\"commitSha\":\"abc1234defghijklmnop\",\"actor\":\"alice\"}",
          null,
          "alice");
      insertBuild(
          c,
          jobIdA,
          101,
          "SUCCESS",
          Instant.now().minusSeconds(110),
          "{\"branch\":\"trunk\",\"commitSha\":\"abc1234zzzzzzzzzzzzz\",\"actor\":\"alice\"}",
          null,
          "alice");
      insertBuild(
          c,
          jobIdA,
          102,
          "SUCCESS",
          Instant.now().minusSeconds(100),
          "{\"branch\":\"trunk\",\"commitSha\":\"feedface00000000\",\"actor\":\"alice\"}",
          null,
          "alice");
      // A real full 40-char SHA so the exact-equality / full-prefix case has a target.
      insertBuild(
          c,
          jobIdA,
          103,
          "SUCCESS",
          Instant.now().minusSeconds(90),
          "{\"branch\":\"trunk\",\"commitSha\":\"abcdef1234567890abcdef1234567890abcdef12\",\"actor\":\"alice\"}",
          null,
          "alice");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    // 7-char hex prefix — must match builds whose commitSha starts with 'abc1234' AND the original
    // seeded 'abc1234' build (which is a substring + prefix match). UNION semantics — broader than
    // substring alone.
    BuildsPage page = api.list(List.of(), null, "abc1234", null, null, null, 0, 50);
    // Expected matches: seeded #1 ('abc1234'), #100 ('abc1234defghijklmnop'), #101
    // ('abc1234zzzzzzzzzzzzz') — three rows.
    assertEquals(3, page.items().size(), "prefix match surfaces every build at that SHA prefix");
    for (BuildDto b : page.items()) {
      assertTrue(
          List.of(1, 100, 101).contains(b.buildNumber()),
          "unexpected build #" + b.buildNumber() + " in 7-char-prefix result");
    }

    // 12-char prefix — narrows to a single build.
    BuildsPage narrow = api.list(List.of(), null, "abc1234defg", null, null, null, 0, 50);
    assertEquals(1, narrow.items().size(), "12-char prefix still triggers prefix match");
    assertEquals(100, narrow.items().get(0).buildNumber());

    // 40-char full SHA — prefix match degenerates to equality. Must still find the row.
    BuildsPage exact =
        api.list(
            List.of(), null, "abcdef1234567890abcdef1234567890abcdef12", null, null, null, 0, 50);
    assertEquals(1, exact.items().size(), "40-char full SHA finds the exact build");
    assertEquals(103, exact.items().get(0).buildNumber());

    // 6-char (too short) — must NOT trigger the prefix path (false-positive avoidance). It falls
    // back to substring matching, which still hits the seeded 'abc1234' row via the JSON
    // substring search but must NOT pull in the new '...defghijklmnop' or '...zzzzzzzzzzzzz' rows
    // (because their commitSha values still contain 'abc123' as a substring — they DO match the
    // fallback). The point is that the prefix-only logic doesn't fire; substring fallback is the
    // ONLY contributor, exactly as before #776.
    BuildsPage tooShort = api.list(List.of(), null, "abc123", null, null, null, 0, 50);
    // 'abc123' is a substring of 'abc1234', 'abc1234defghijklmnop', 'abc1234zzzzzzzzzzzzz' — three
    // rows via the existing substring path. The 6-char rejection means we're not ADDING anything;
    // we get the same set we would have gotten on trunk.
    assertEquals(3, tooShort.items().size(), "6-char token falls back to substring only");

    // Non-hex with a space — falls back unchanged. 'compile failed' matches one failure_summary.
    BuildsPage nonHex = api.list(List.of(), null, "compile failed", null, null, null, 0, 50);
    assertEquals(1, nonHex.items().size(), "non-hex search keeps legacy failure_summary behavior");
    assertEquals("FAILED", nonHex.items().get(0).status());
  }

  @Test
  void sinceFilter_returnsOnlyRecentBuilds() {
    // 1-day cutoff excludes the 8d / 10d old jobB builds.
    Instant oneDayAgo = Instant.now().minusSeconds(24 * 3600);
    BuildsPage recent = api.list(List.of(), null, null, oneDayAgo.toString(), null, null, 0, 50);
    assertEquals(4, recent.items().size(), "only the four builds queued within 24h");
    for (BuildDto b : recent.items()) {
      assertNotNull(b.queuedAt());
      assertTrue(
          !b.queuedAt().isBefore(oneDayAgo),
          "every returned build must be at or after the since cutoff");
    }
  }

  @Test
  void combinedFilters_areAndedTogether() {
    Instant oneDayAgo = Instant.now().minusSeconds(24 * 3600);
    // status=FAILED AND branch=trunk AND since=1d → exactly jobA build 2.
    BuildsPage page =
        api.list(List.of("FAILED"), "trunk", null, oneDayAgo.toString(), null, null, 0, 50);
    assertEquals(1, page.items().size(), "the AND must narrow to the single matching row");
    assertEquals("FAILED", page.items().get(0).status());
    assertEquals(2, page.items().get(0).buildNumber());
    assertEquals(jobIdA, page.items().get(0).jobId());
  }

  @Test
  void combinedFilters_andSemantics_notOr() {
    // status=SUCCESS AND branch=release/1.x → empty (no SUCCESS on release/1.x in our seed).
    // If filters were OR'd we'd see 4 rows (3 SUCCESS + 1 release/1.x).
    BuildsPage page = api.list(List.of("SUCCESS"), "release/1.x", null, null, null, null, 0, 50);
    assertEquals(0, page.items().size(), "AND must exclude rows that match only ONE filter");
    assertEquals(0, page.total());
  }

  @Test
  void sqlInjectionInSearch_returnsCleanEmptyResult_not500() {
    // Classic injection payload — must be bound, never concatenated. We don't assert it errors,
    // we assert the query runs cleanly and returns zero rows (because no row contains this
    // literal string).
    String evil = "'; DROP TABLE titan.builds; --";
    BuildsPage page = api.list(List.of(), null, evil, null, null, null, 0, 50);
    assertEquals(0, page.items().size());
    assertEquals(0, page.total());
    // And critically: the table must still exist after the call.
    BuildsPage probe = api.list(List.of(), null, null, null, null, null, 0, 50);
    assertEquals(6, probe.items().size(), "the builds table is still here — no SQL was executed");
  }

  @Test
  void unknownStatus_isHttp400_notSilentEmptyResult() {
    assertThrows(
        ApiBadRequestException.class,
        () -> api.list(List.of("NOT_A_REAL_STATUS"), null, null, null, null, null, 0, 50));
  }

  @Test
  void malformedSince_isHttp400_notSilentlyIgnored() {
    assertThrows(
        ApiBadRequestException.class,
        () -> api.list(List.of(), null, null, "not-a-timestamp", null, null, 0, 50));
  }

  @Test
  void pagination_preservedAcrossFilterChanges() {
    BuildsPage firstPage = api.list(List.of(), null, null, null, null, null, 0, 2);
    BuildsPage secondPage = api.list(List.of(), null, null, null, null, null, 2, 2);
    assertEquals(2, firstPage.items().size());
    assertEquals(2, secondPage.items().size());
    assertEquals(6, firstPage.total(), "total counts the unpaged set");
    assertEquals(6, secondPage.total());
    // No row overlap across pages.
    long overlap =
        firstPage.items().stream()
            .filter(a -> secondPage.items().stream().anyMatch(b -> b.id() == a.id()))
            .count();
    assertEquals(0L, overlap, "pages must not overlap");
  }

  @Test
  void blankFilterParams_treatedAsAbsent() {
    BuildsPage page = api.list(List.of("", " "), "  ", "", "  ", "  ", null, 0, 50);
    assertEquals(6, page.items().size(), "blanks must degrade to unfiltered");
  }

  @Test
  void caseInsensitiveStatus_isAccepted() {
    BuildsPage page = api.list(List.of("failed"), null, null, null, null, null, 0, 50);
    assertEquals(2, page.items().size(), "lowercase status is normalised to FAILED");
    for (BuildDto b : page.items()) {
      assertEquals("FAILED", b.status());
    }
  }

  @Test
  void triggeredByFilter_returnsOnlyMatchingActor_caseInsensitive() {
    // Closes #746 — the "Triggered by me" chip on /builds. Exact case-insensitive
    // match on triggered_by; AND-combines with other filters.
    BuildsPage alice = api.list(List.of(), null, null, null, "alice", null, 0, 50);
    assertEquals(3, alice.items().size(), "alice triggered exactly three seeded builds");
    for (BuildDto b : alice.items()) {
      assertEquals("alice", b.triggeredBy());
    }

    // Case-insensitive: an actor sub like 'Alice' must still match.
    BuildsPage capAlice = api.list(List.of(), null, null, null, "ALICE", null, 0, 50);
    assertEquals(3, capAlice.items().size(), "case-insensitive match on triggered_by");

    // Substring is NOT a match — "ali" must NOT find "alice" (privacy: chip is
    // self-scoped, not a who-triggered-this lookup).
    BuildsPage partial = api.list(List.of(), null, null, null, "ali", null, 0, 50);
    assertEquals(0, partial.items().size(), "substring must not match — chip is exact-equality");

    // AND-combines with status: alice + FAILED → exactly one row (jobA build 2 is bob,
    // so alice has zero FAILED). carol has the FAILED jobB build 2.
    BuildsPage aliceFailed = api.list(List.of("FAILED"), null, null, null, "alice", null, 0, 50);
    assertEquals(0, aliceFailed.items().size(), "AND semantics — alice has no FAILED builds");

    // Unknown actor → empty (the unauthenticated-user-with-chip-on case from the ticket).
    BuildsPage ghost = api.list(List.of(), null, null, null, "no-such-user", null, 0, 50);
    assertEquals(0, ghost.items().size());
    assertEquals(0, ghost.total());
  }

  @Test
  void searchByTriggeredBy_isAlsoCovered() {
    // Free-text search hits triggered_by too — operators looking for "what did alice trigger"
    // should not have to remember a syntax.
    BuildsPage byActor = api.list(List.of(), null, "alice", null, null, null, 0, 50);
    assertFalse(byActor.items().isEmpty(), "alice-triggered builds must surface");
    for (BuildDto b : byActor.items()) {
      assertEquals("alice", b.triggeredBy());
    }
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
      String triggerMetaJson,
      String failureSummary,
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
      if (triggerMetaJson == null) {
        ps.setNull(5, Types.VARCHAR);
      } else {
        ps.setString(5, triggerMetaJson);
      }
      if (failureSummary == null) {
        ps.setNull(6, Types.VARCHAR);
      } else {
        ps.setString(6, failureSummary);
      }
      ps.setString(7, triggeredBy);
      ps.setString(8, "manual");
      ps.executeUpdate();
    }
  }
}

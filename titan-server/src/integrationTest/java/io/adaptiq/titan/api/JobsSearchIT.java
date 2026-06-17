package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.job.Job;
import io.adaptiq.titan.job.JobServiceImpl;
import io.adaptiq.titan.job.JobWithLastBuild;
import io.adaptiq.titan.store.TitanStores;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres-backed integration test for {@code /api/v1/jobs?search=} — the Cmd+K palette server-side
 * filter shipped in PR #696 (closes #697; follow-up to #693). Exercises the load-bearing seam:
 * {@link JobServiceImpl#listAllWithLastBuild(String)} → {@code JobDao.searchAllWithLastBuild} →
 * parameterised ILIKE bind on Postgres. Bypasses the JAX-RS layer (covered by {@code JobsApiTest})
 * and pins the SQL contract:
 *
 * <ul>
 *   <li>Empty/blank {@code search} degrades to the unfiltered call (legacy back-compat).
 *   <li>{@code display_name} substring match is case-insensitive.
 *   <li>{@code full_name} substring match is case-insensitive.
 *   <li>SQL-injection payload returns a clean empty set — never a 500, never the whole table.
 *   <li>Non-ASCII characters round-trip through the bind correctly.
 * </ul>
 *
 * <p>Every assertion pins the <em>exact</em> matched id set — no count-only checks that would pass
 * if the wrong rows came back.
 */
@Testcontainers
class JobsSearchIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private JobServiceImpl jobs;

  // Seeded job ids — populated in @BeforeEach.
  private long fooFullId; // full_name contains "foo"; display_name unrelated
  private long fooDisplayId; // display_name contains "foo"; full_name unrelated
  private long barFullId; // full_name contains "bar"
  private long barDisplayId; // display_name contains "BAR" (case-insensitivity probe)
  private long unicodeId; // display_name contains "café"
  private long unrelatedId; // no overlap with any search term

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
    jobs = new JobServiceImpl(stores);

    // Seed: six jobs with carefully chosen full_name / display_name overlap so each
    // adversarial assertion can pin an EXACT id set.
    try (Connection c = ds.getConnection()) {
      fooFullId = insertJob(c, "team-a/foo-service", "Service A");
      fooDisplayId = insertJob(c, "team-b/checkout", "Foo Display Only");
      barFullId = insertJob(c, "team-c/bar-pipeline", "Pipeline C");
      barDisplayId = insertJob(c, "team-d/migrator", "BAR Migration Worker");
      unicodeId = insertJob(c, "team-e/locale", "café-frontend");
      unrelatedId = insertJob(c, "team-f/totally-unrelated", "Nothing Here");
    }
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  @Test
  void emptySearch_returnsSameSet_asNoParam() {
    List<JobWithLastBuild> unfiltered = jobs.listAllWithLastBuild();
    Set<Long> unfilteredIds = idsOf(unfiltered);

    // Both the null call AND the empty-string call must degrade to the unfiltered set.
    assertEquals(6, unfilteredIds.size(), "all six seeded jobs surface unfiltered");
    assertEquals(unfilteredIds, idsOf(jobs.listAllWithLastBuild(null)));
    assertEquals(unfilteredIds, idsOf(jobs.listAllWithLastBuild("")));
    assertEquals(unfilteredIds, idsOf(jobs.listAllWithLastBuild("   ")));
  }

  @Test
  void search_matchesDisplayNameSubstring_caseInsensitive() {
    // "foo" must match BOTH:
    //   - team-a/foo-service (full_name match)
    //   - team-b/checkout / "Foo Display Only" (display_name match)
    // Mixed-case "FoO" must match the same set — proves ILIKE / case-fold is honoured.
    Set<Long> expected = Set.of(fooFullId, fooDisplayId);

    assertEquals(expected, idsOf(jobs.listAllWithLastBuild("foo")));
    assertEquals(expected, idsOf(jobs.listAllWithLastBuild("FoO")));
    assertEquals(expected, idsOf(jobs.listAllWithLastBuild("FOO")));
  }

  @Test
  void search_matchesFullNameSubstring_caseInsensitive() {
    // "bar" must match BOTH:
    //   - team-c/bar-pipeline (full_name match)
    //   - team-d/migrator / "BAR Migration Worker" (display_name match — case-insensitive)
    Set<Long> expected = Set.of(barFullId, barDisplayId);

    assertEquals(expected, idsOf(jobs.listAllWithLastBuild("bar")));
    assertEquals(expected, idsOf(jobs.listAllWithLastBuild("BAR")));
    assertEquals(expected, idsOf(jobs.listAllWithLastBuild("BaR")));

    // And the substring must actually be a substring — a non-matching token returns nothing,
    // not the whole table (paranoia about a broken WHERE clause silently collapsing).
    assertEquals(Set.of(), idsOf(jobs.listAllWithLastBuild("zzz-no-such-token-zzz")));
  }

  @Test
  void sqlInjectionPayload_returnsCleanEmptyResult_notWholeTable_not500() {
    // Classic injection payload. The WHERE clause becomes
    //   full_name ILIKE '%' OR '1'='1%' OR display_name ILIKE '%' OR '1'='1%'
    // ONLY if the bind is concatenated. With a real JDBI bind it becomes a literal
    // substring search for "%' OR '1'='1" — which matches nothing in our seed.
    String evil = "%' OR '1'='1";

    List<JobWithLastBuild> result = jobs.listAllWithLastBuild(evil);

    assertEquals(
        Set.of(),
        idsOf(result),
        "injection payload must NOT leak unrelated rows — parameterised bind required");

    // And critically: the jobs table must still exist + still hold all seeded rows.
    assertEquals(
        6,
        jobs.listAllWithLastBuild().size(),
        "the jobs table is still here — no SQL was executed by the injection payload");

    // Same shape of test with a destructive payload — still empty, table still alive.
    String evilDrop = "'; DROP TABLE titan.jobs; --";
    assertEquals(Set.of(), idsOf(jobs.listAllWithLastBuild(evilDrop)));
    assertEquals(6, jobs.listAllWithLastBuild().size(), "DROP payload did not execute");
  }

  @Test
  void search_matchesUnicodeCharacters() {
    // Postgres ILIKE on UTF-8 must round-trip non-ASCII through the JDBC bind correctly.
    // "café" is in the display_name of the unicode-seeded job only.
    Set<Long> expected = Set.of(unicodeId);

    assertEquals(expected, idsOf(jobs.listAllWithLastBuild("café")));
    // Partial substring match still works.
    assertEquals(expected, idsOf(jobs.listAllWithLastBuild("afé")));

    // Sanity: a different non-ASCII token that nothing matches returns empty —
    // not the whole table, not a 500.
    assertEquals(Set.of(), idsOf(jobs.listAllWithLastBuild("naïve")));

    // And the unrelated/foo/bar rows must NOT appear in any of the unicode matches.
    List<JobWithLastBuild> cafeMatch = jobs.listAllWithLastBuild("café");
    assertEquals(1, cafeMatch.size());
    Job j = cafeMatch.get(0).job();
    assertNotNull(j.displayName());
    assertTrue(
        j.displayName().toLowerCase(java.util.Locale.ROOT).contains("café"),
        "matched row's displayName must actually contain the needle (got: "
            + j.displayName()
            + ")");
    assertEquals(unicodeId, j.id());
    // unrelatedId never appears.
    assertTrue(
        cafeMatch.stream().noneMatch(jwlb -> jwlb.job().id() == unrelatedId),
        "the unrelated row must never leak into a search result");
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static Set<Long> idsOf(List<JobWithLastBuild> list) {
    return list.stream().map(j -> j.job().id()).collect(Collectors.toUnmodifiableSet());
  }

  private static long insertJob(Connection c, String fullName, String displayName)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, display_name, pipeline_script, config_json) "
                + "VALUES (?, ?, '', '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, fullName);
      ps.setString(2, displayName);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }
}

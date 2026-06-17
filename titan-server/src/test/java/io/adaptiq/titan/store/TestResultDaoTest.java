package io.adaptiq.titan.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.TestResultRow;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Direct DAO tests for {@link TestResultDao} — the four read/write surfaces ({@code insertBatch},
 * {@code countByBuild}, {@code findByBuildPaged}, {@code summaryByBuild}, {@code deleteByBuild}).
 *
 * <p>Backed by the same H2 schema-loader the API tests use ({@code FakeTitanStores}), reached via
 * reflection because {@code FakeTitanStores} lives in the API test source set. Each test gets a
 * fresh, isolated database — the same pattern {@link ArtifactDaoTest} uses.
 */
class TestResultDaoTest {

  private TitanStores stores;
  private long buildId;

  @BeforeEach
  void setUp() throws Exception {
    Class<?> fake = Class.forName("io.adaptiq.titan.api.FakeTitanStores");
    Method create = fake.getDeclaredMethod("create");
    create.setAccessible(true);
    stores = (TitanStores) create.invoke(null);

    JobRow j = new JobRow();
    j.fullName = "tests/dao-" + System.nanoTime();
    j.enabled = true;
    j.pipelineScript = "";
    j.configJson = "{}";
    long jobId = stores.jobs().insert(j);

    BuildRow b = new BuildRow();
    b.jobId = jobId;
    b.buildNumber = stores.builds().nextBuildNumber(jobId);
    b.status = "SUCCESS";
    b.queuedAt = Instant.now();
    b.triggeredBy = "test";
    b.triggerType = "manual";
    buildId = stores.withTransaction(c -> stores.builds().insert(c, b));
  }

  // ── insertBatch + countByBuild ────────────────────────────────────────

  @Test
  void insertBatch_andCountByBuild() {
    List<TestResultRow> rows = rows(5, "PASSED", null);

    int inserted = stores.testResults().insertBatch(buildId, rows);

    assertEquals(5, inserted);
    assertEquals(5, stores.testResults().countByBuild(buildId));
  }

  @Test
  void insertBatch_emptyIsANoop() {
    int inserted = stores.testResults().insertBatch(buildId, List.of());
    assertEquals(0, inserted);
    assertEquals(0, stores.testResults().countByBuild(buildId));
  }

  // ── findByBuildPaged ──────────────────────────────────────────────────

  @Test
  void findByBuildPaged_emptyReturnsEmptyList() {
    List<TestResultRow> page = stores.testResults().findByBuildPaged(buildId, 0, 100);
    assertTrue(page.isEmpty());
  }

  @Test
  void findByBuildPaged_returnsParseOrderUpToLimit() {
    // 250 rows; (offset=0, limit=100) returns 100 oldest-first (ORDER BY id ASC).
    List<TestResultRow> bulk = rows(250, "PASSED", null);
    for (int i = 0; i < bulk.size(); i++) {
      bulk.get(i).name = "t-" + i;
    }
    stores.testResults().insertBatch(buildId, bulk);

    List<TestResultRow> page = stores.testResults().findByBuildPaged(buildId, 0, 100);

    assertEquals(100, page.size());
    // Strictly increasing ids — oldest insert is row 0.
    for (int i = 0; i < page.size() - 1; i++) {
      assertTrue(
          page.get(i).id < page.get(i + 1).id,
          "expected id ASC at idx " + i + ": " + page.get(i).id + " < " + page.get(i + 1).id);
    }
    // The first 100 inserted names line up in order.
    assertEquals("t-0", page.get(0).name);
    assertEquals("t-99", page.get(99).name);
  }

  @Test
  void findByBuildPaged_offsetAndLimitWindow() {
    stores.testResults().insertBatch(buildId, rows(25, "PASSED", null));

    List<TestResultRow> first = stores.testResults().findByBuildPaged(buildId, 0, 10);
    List<TestResultRow> second = stores.testResults().findByBuildPaged(buildId, 10, 10);
    List<TestResultRow> third = stores.testResults().findByBuildPaged(buildId, 20, 10);

    assertEquals(10, first.size());
    assertEquals(10, second.size());
    assertEquals(5, third.size(), "tail page is partial — only 5 rows left after offset 20");

    // No id overlap across the three windows.
    assertTrue(first.get(9).id < second.get(0).id);
    assertTrue(second.get(9).id < third.get(0).id);
  }

  @Test
  void findByBuildPaged_carriesFailureMessageAndNullsForPasses() {
    List<TestResultRow> mixed = new ArrayList<>();
    mixed.addAll(rows(1, "PASSED", null));
    mixed.addAll(rows(1, "FAILED", "kaboom\n\tat foo()"));
    stores.testResults().insertBatch(buildId, mixed);

    List<TestResultRow> page = stores.testResults().findByBuildPaged(buildId, 0, 100);

    assertEquals(2, page.size());
    TestResultRow pass =
        page.stream().filter(r -> "PASSED".equals(r.status)).findFirst().orElseThrow();
    TestResultRow fail =
        page.stream().filter(r -> "FAILED".equals(r.status)).findFirst().orElseThrow();
    assertNull(pass.failureMessage);
    assertEquals("kaboom\n\tat foo()", fail.failureMessage);
  }

  // ── summaryByBuild ────────────────────────────────────────────────────

  @Test
  void summaryByBuild_countsByStatus() {
    List<TestResultRow> all = new ArrayList<>();
    all.addAll(rows(7, "PASSED", null));
    all.addAll(rows(2, "FAILED", "boom"));
    all.addAll(rows(1, "SKIPPED", null));
    stores.testResults().insertBatch(buildId, all);

    TestResultDao.TestSummary summary = stores.testResults().summaryByBuild(buildId);

    assertEquals(new TestResultDao.TestSummary(7, 2, 1), summary);
  }

  @Test
  void summaryByBuild_emptyBuildIsZeroes() {
    TestResultDao.TestSummary summary = stores.testResults().summaryByBuild(buildId);
    assertEquals(new TestResultDao.TestSummary(0, 0, 0), summary);
  }

  // ── deleteByBuild ─────────────────────────────────────────────────────

  @Test
  void deleteByBuild_removesEveryRowOfTheBuild() {
    stores.testResults().insertBatch(buildId, rows(5, "PASSED", null));
    assertEquals(5, stores.testResults().countByBuild(buildId));

    int deleted = stores.testResults().deleteByBuild(buildId);

    assertEquals(5, deleted);
    assertEquals(0, stores.testResults().countByBuild(buildId));
  }

  @Test
  void buildDeletion_cascadesTestResults() {
    // The fk_test_result_build ON DELETE CASCADE — dropping the build row must drop the
    // titan.test_result rows with it. The DAO does not own this; we exercise it so a future
    // migration that accidentally drops the FK or its action is caught.
    stores.testResults().insertBatch(buildId, rows(3, "PASSED", null));
    assertEquals(3, stores.testResults().countByBuild(buildId));

    stores.withTransaction(
        conn -> {
          try (java.sql.PreparedStatement ps =
              conn.prepareStatement("DELETE FROM titan.builds WHERE id = ?")) {
            ps.setLong(1, buildId);
            ps.executeUpdate();
            return null;
          } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
          }
        });

    assertEquals(0, stores.testResults().countByBuild(buildId));
  }

  // ── isolation ─────────────────────────────────────────────────────────

  @Test
  void countByBuild_isolatesByBuildId() {
    stores.testResults().insertBatch(buildId, rows(2, "PASSED", null));

    JobRow j2 = new JobRow();
    j2.fullName = "tests/dao-other-" + System.nanoTime();
    j2.enabled = true;
    j2.pipelineScript = "";
    j2.configJson = "{}";
    long otherJob = stores.jobs().insert(j2);
    BuildRow b2 = new BuildRow();
    b2.jobId = otherJob;
    b2.buildNumber = stores.builds().nextBuildNumber(otherJob);
    b2.status = "SUCCESS";
    b2.queuedAt = Instant.now();
    b2.triggeredBy = "test";
    b2.triggerType = "manual";
    long otherBuild = stores.withTransaction(c -> stores.builds().insert(c, b2));

    stores.testResults().insertBatch(otherBuild, rows(7, "PASSED", null));

    assertEquals(2, stores.testResults().countByBuild(buildId));
    assertEquals(7, stores.testResults().countByBuild(otherBuild));
  }

  // ── helper ────────────────────────────────────────────────────────────

  /** Build {@code n} rows with the given status; bumps the name to keep them distinct. */
  private static List<TestResultRow> rows(int n, String status, String failureMessage) {
    List<TestResultRow> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      TestResultRow r = new TestResultRow();
      r.nodeId = "node-1";
      r.suite = "SuiteA";
      r.className = "com.example.SuiteA";
      r.name = "case-" + status.toLowerCase() + "-" + i;
      r.status = status;
      r.durationMs = 1L + i;
      r.failureMessage = failureMessage;
      out.add(r);
    }
    return out;
  }
}

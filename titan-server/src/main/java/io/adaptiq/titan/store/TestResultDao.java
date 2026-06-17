package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.TestResultRow;
import java.util.List;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindFields;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject read + write access for {@code titan.test_result} (issue #298) — the controller
 * side of the build's parsed JUnit cases. The write side is driven by the worker's {@code
 * DbTestResultSink} (which flows through this DAO via the shared {@link TitanStores} when the
 * server hosts both); the read side serves the upcoming {@code /api/v1/builds/{id}/tests} endpoint
 * and the #296 UI test-results panel.
 *
 * <p>Pattern mirrors {@link ArtifactDao}: a static {@code COLS} constant for the SELECT list,
 * {@code @SqlQuery}/{@code @SqlUpdate}/{@code @SqlBatch} bound to row + parameter binders, and the
 * {@code @RegisterFieldMapper} fills {@link TestResultRow} from lower_snake_case columns by name.
 */
@RegisterFieldMapper(TestResultRow.class)
@RegisterConstructorMapper(TestResultDao.TestSummary.class)
public interface TestResultDao {

  String COLS =
      "id, build_id, node_id, suite, class_name, name, status, "
          + "duration_ms, failure_message, created_at";

  /**
   * Insert a batch of test-case rows for one build. The build-id parameter is taken positionally
   * (every row of a batch belongs to the same build); the row fields supply the rest by name. JDBI
   * returns the per-statement update counts; we sum and return the total row count actually
   * inserted, which is what callers (the worker sink) check against the input list size.
   *
   * @return the total number of rows inserted (sum of per-statement counts)
   */
  @SqlBatch(
      "INSERT INTO titan.test_result "
          + "(build_id, node_id, suite, class_name, name, status, duration_ms, failure_message) "
          + "VALUES (:buildId, :row.nodeId, :row.suite, :row.className, :row.name, "
          + "        :row.status, :row.durationMs, :row.failureMessage)")
  int[] insertBatchRaw(
      @Bind("buildId") long buildId, @BindFields("row") @NonNull Iterable<TestResultRow> rows);

  /**
   * Insert a batch and report the total inserted row count. Convenience over {@link
   * #insertBatchRaw} which exposes the per-statement counts JDBI returns.
   *
   * @return the total number of rows inserted
   */
  default int insertBatch(long buildId, @NonNull List<TestResultRow> rows) {
    if (rows.isEmpty()) {
      return 0;
    }
    int[] counts = insertBatchRaw(buildId, rows);
    int total = 0;
    for (int c : counts) {
      // JDBC may report SUCCESS_NO_INFO (-2) on a batched insert; count those as 1 — the row
      // is in unless the whole batch threw, and JDBI translates a constraint failure into an
      // exception, never a silent -3 (EXECUTE_FAILED) here.
      total += (c < 0) ? 1 : c;
    }
    return total;
  }

  /** Total number of test-case rows recorded for a build. */
  @SqlQuery("SELECT COUNT(*) FROM titan.test_result WHERE build_id = :buildId")
  int countByBuild(@Bind("buildId") long buildId);

  /**
   * One window of test-case rows for a build, oldest-id first ({@code ORDER BY id ASC}) so the
   * paged output reflects parse order, with the standard {@code LIMIT}/{@code OFFSET} cursor.
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.test_result "
          + "WHERE build_id = :buildId "
          + "ORDER BY id ASC LIMIT :limit OFFSET :offset")
  @NonNull
  List<TestResultRow> findByBuildPaged(
      @Bind("buildId") long buildId, @Bind("offset") int offset, @Bind("limit") int limit);

  /**
   * Pass/fail/skip aggregate for a build — one row, three integer counts. The single conditional-
   * sum query is one round trip and one index probe ({@code idx_test_result_build_status}); a
   * three-query alternative would cost three.
   */
  @SqlQuery(
      "SELECT "
          + "COALESCE(SUM(CASE WHEN status = 'PASSED'  THEN 1 ELSE 0 END), 0) AS passed, "
          + "COALESCE(SUM(CASE WHEN status = 'FAILED'  THEN 1 ELSE 0 END), 0) AS failed, "
          + "COALESCE(SUM(CASE WHEN status = 'SKIPPED' THEN 1 ELSE 0 END), 0) AS skipped "
          + "FROM titan.test_result WHERE build_id = :buildId")
  @NonNull
  TestSummary summaryByBuild(@Bind("buildId") long buildId);

  /**
   * Drop every {@code titan.test_result} row of a build. The {@code fk_test_result_build ON DELETE
   * CASCADE} covers whole-build deletion; this entry exists for the worker's re-run path
   * (clear-then-insert so a re-parse converges on the new state instead of accumulating dupes) and
   * for retention reapers.
   *
   * @return the number of rows deleted
   */
  @SqlUpdate("DELETE FROM titan.test_result WHERE build_id = :buildId")
  int deleteByBuild(@Bind("buildId") long buildId);

  /**
   * The (passed, failed, skipped) aggregate for one build — the shape the build-page tests panel
   * and the REST summary endpoint serialise. {@code int} (not {@code long}) — a build with &gt;2
   * billion test cases is not a regime we serve, and the narrower type keeps the JSON compact.
   * {@code SUM(...)} on an empty result set yields {@code NULL}; JDBI's constructor mapper coerces
   * SQL {@code NULL} on a primitive {@code int} to {@code 0}, so an empty build cleanly reads as
   * {@code (0, 0, 0)}.
   */
  record TestSummary(int passed, int failed, int skipped) {}
}

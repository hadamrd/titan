package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/**
 * JDBI SqlObject for per-stage timing percentiles across a job's last N builds (closes #1095).
 *
 * <p>SREs answering <em>"is this build slower than usual?"</em> need a single-glance historical
 * view of each stage's duration distribution. We fetch the most recent {@code n} builds for a job,
 * pull every completed STAGE node out of {@code titan.flow_nodes}, and let PostgreSQL compute the
 * p50/p95/p99 percentiles via {@code percentile_cont} (linear interpolation — matches NumPy's
 * default {@code method='linear'} so unit tests in the UI / DAO are consistent with what an SRE
 * gets from {@code numpy.percentile} on the same array).
 *
 * <p>Two complementary queries — one for percentiles (aggregate), one for per-build samples
 * (histogram). Keeping them separate avoids a {@code WITHIN GROUP} subquery joined to the raw
 * sample rows (which Postgres can't fold cleanly into one statement) and lets the API layer pick
 * the response shape without restructuring SQL.
 *
 * <p><strong>Grouping key.</strong> We group by {@code display_name} — the user-facing stage label
 * the YAML author wrote. If a stage gets renamed in the pipeline, the new name surfaces as a fresh
 * "stage" (with whatever history exists under the new name). That matches what a human reads in the
 * build graph and is the only stable cross-build join the schema offers (node_id is per-build,
 * step_descriptor is for STEP rows not STAGE rows).
 *
 * <p><strong>Window.</strong> We use the last {@code n} <em>finished</em> builds (status in {@code
 * SUCCESS, FAILED, UNSTABLE} — ABORTED stages have partial durations that lie about steady state).
 * RUNNING/QUEUED builds are excluded so an in-flight build can't drag the p99 down with a partial
 * timing.
 *
 * <p><strong>Index strategy.</strong> The inner-most CTE filters {@code builds} by {@code job_id}
 * ordered by {@code queued_at DESC}, served by {@code idx_builds_job_queued} from {@code
 * V1__init.sql}. The outer join to {@code flow_nodes} hits the composite PK {@code (build_id,
 * node_id)} via {@code build_id} alone — Postgres scans the leftmost prefix.
 */
public interface JobTimingsDao {

  /** Hard ceiling on the {@code n} parameter — protects the histogram payload size. */
  int MAX_BUILDS = 100;

  /** Default window size (acceptance criteria: last 30 builds). */
  int DEFAULT_BUILDS = 30;

  /**
   * One row per stage, summarising its duration distribution over the last {@code n} finished
   * builds of {@code jobId}. Stages with zero completed samples in the window are omitted (no
   * percentile to report). Ordered by descending p50 so the slowest stages float to the top — the
   * SRE's eye lands on what matters first.
   */
  @SqlQuery(
      "WITH recent_builds AS ( "
          + "  SELECT id FROM titan.builds "
          + "  WHERE job_id = :job "
          + "    AND status IN ('SUCCESS','FAILED','UNSTABLE') "
          + "  ORDER BY queued_at DESC "
          + "  LIMIT :n "
          + ") "
          + "SELECT fn.display_name AS stage_name, "
          + "       COUNT(*) AS sample_count, "
          + "       CAST(percentile_cont(0.50) WITHIN GROUP (ORDER BY fn.duration_ms) AS BIGINT) "
          + "         AS p50_ms, "
          + "       CAST(percentile_cont(0.95) WITHIN GROUP (ORDER BY fn.duration_ms) AS BIGINT) "
          + "         AS p95_ms, "
          + "       CAST(percentile_cont(0.99) WITHIN GROUP (ORDER BY fn.duration_ms) AS BIGINT) "
          + "         AS p99_ms, "
          + "       MIN(fn.duration_ms) AS min_ms, "
          + "       MAX(fn.duration_ms) AS max_ms "
          + "FROM titan.flow_nodes fn "
          + "JOIN recent_builds rb ON rb.id = fn.build_id "
          + "WHERE fn.node_type = 'STAGE' "
          + "  AND fn.status IN ('SUCCESS','FAILED','UNSTABLE') "
          + "  AND fn.duration_ms IS NOT NULL "
          + "  AND fn.display_name IS NOT NULL "
          + "GROUP BY fn.display_name "
          + "ORDER BY p50_ms DESC NULLS LAST, stage_name ASC")
  @RegisterConstructorMapper(StagePercentileRow.class)
  @NonNull
  List<StagePercentileRow> stagePercentiles(@Bind("job") long jobId, @Bind("n") int n);

  /**
   * One row per (stage, build) sample. The API zips these into the percentile rows so the histogram
   * in the UI can render a bar per build and link each bar to its build-detail page. Ordered by
   * build_number ASC so the histogram reads left-to-right oldest→newest (matches the existing
   * daily-sparkline convention in {@code JobStatsApi}).
   */
  @SqlQuery(
      "WITH recent_builds AS ( "
          + "  SELECT id, build_number FROM titan.builds "
          + "  WHERE job_id = :job "
          + "    AND status IN ('SUCCESS','FAILED','UNSTABLE') "
          + "  ORDER BY queued_at DESC "
          + "  LIMIT :n "
          + ") "
          + "SELECT fn.display_name AS stage_name, "
          + "       rb.id AS build_id, "
          + "       rb.build_number AS build_number, "
          + "       fn.duration_ms AS duration_ms, "
          + "       fn.status AS status "
          + "FROM titan.flow_nodes fn "
          + "JOIN recent_builds rb ON rb.id = fn.build_id "
          + "WHERE fn.node_type = 'STAGE' "
          + "  AND fn.status IN ('SUCCESS','FAILED','UNSTABLE') "
          + "  AND fn.duration_ms IS NOT NULL "
          + "  AND fn.display_name IS NOT NULL "
          + "ORDER BY fn.display_name ASC, rb.build_number ASC")
  @RegisterConstructorMapper(StageSampleRow.class)
  @NonNull
  List<StageSampleRow> stageSamples(@Bind("job") long jobId, @Bind("n") int n);

  /**
   * Count the number of finished builds for the job, capped at {@code n}. Used to populate {@code
   * buildsConsidered} on the response — answers "how much history did the percentile see?" which is
   * critical context for an SRE reading a p99: 4 samples is not the same as 30.
   */
  @SqlQuery(
      "SELECT COUNT(*) FROM ( "
          + "  SELECT 1 FROM titan.builds "
          + "  WHERE job_id = :job "
          + "    AND status IN ('SUCCESS','FAILED','UNSTABLE') "
          + "  ORDER BY queued_at DESC "
          + "  LIMIT :n "
          + ") sub")
  long countFinishedBuildsInWindow(@Bind("job") long jobId, @Bind("n") int n);

  /**
   * Aggregate row for one stage. Percentile columns are {@link Long} (not primitive) so a stage
   * with a single sample still produces a real value — Postgres' {@code percentile_cont} returns
   * the only sample as the answer for any percentile, which is desired.
   */
  record StagePercentileRow(
      @ColumnName("stage_name") @NonNull String stageName,
      @ColumnName("sample_count") long sampleCount,
      @ColumnName("p50_ms") Long p50Ms,
      @ColumnName("p95_ms") Long p95Ms,
      @ColumnName("p99_ms") Long p99Ms,
      @ColumnName("min_ms") Long minMs,
      @ColumnName("max_ms") Long maxMs) {}

  /** Per-sample row used to build the per-stage histogram. */
  record StageSampleRow(
      @ColumnName("stage_name") @NonNull String stageName,
      @ColumnName("build_id") long buildId,
      @ColumnName("build_number") int buildNumber,
      @ColumnName("duration_ms") long durationMs,
      @ColumnName("status") @NonNull String status) {}
}

package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/**
 * JDBI SqlObject for the per-job statistics page (closes #775).
 *
 * <p>Two queries, intentionally separate:
 *
 * <ol>
 *   <li>{@link #totals(long, Instant)} — single-row aggregate (count, failed count, p50/p95) over
 *       all builds in the window. Percentiles use {@code percentile_cont} (PostgreSQL native) so we
 *       never have to ship the whole duration column to Java just to sort it. Percentiles are
 *       computed in a filtered sub-aggregate that excludes RUNNING / PENDING (no {@code
 *       duration_ms} yet), so an idle job in a 7d window returns NULL percentiles rather than 0
 *       (which would lie).
 *   <li>{@link #dailyBuckets(long, Instant)} — one row per UTC calendar day on which the job had
 *       activity. The API layer aligns these to the requested window length, filling zero-count
 *       entries for missing days (so a 7d call always returns 7 entries — DST-safe because we
 *       bucket at the database in pure UTC).
 * </ol>
 *
 * <p><strong>Index strategy.</strong> Both queries filter by {@code job_id = :job AND queued_at
 * &gt;= :since}, which is exactly the access pattern of the existing {@code idx_builds_job_queued
 * (job_id, queued_at DESC)} index from {@code V1__init.sql}. No new migration required.
 */
public interface JobStatsDao {

  /**
   * Single-row aggregate covering everything that doesn't need a per-day grouping: total / failed
   * counts (over ALL builds in the window, including RUNNING) and p50/p95 duration (over completed
   * builds only). Returns a {@code TotalsRow} even when the job has zero builds in the window —
   * counts come back as 0 and percentiles as null.
   */
  @SqlQuery(
      "SELECT "
          + "  COUNT(*) AS total_builds, "
          + "  SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed_builds, "
          + "  CAST(percentile_cont(0.50) WITHIN GROUP ( "
          + "    ORDER BY CASE WHEN status IN ('SUCCESS','FAILED','ABORTED','UNSTABLE') "
          + "                       AND duration_ms IS NOT NULL "
          + "                  THEN duration_ms END "
          + "  ) AS BIGINT) AS p50_duration_ms, "
          + "  CAST(percentile_cont(0.95) WITHIN GROUP ( "
          + "    ORDER BY CASE WHEN status IN ('SUCCESS','FAILED','ABORTED','UNSTABLE') "
          + "                       AND duration_ms IS NOT NULL "
          + "                  THEN duration_ms END "
          + "  ) AS BIGINT) AS p95_duration_ms "
          + "FROM titan.builds "
          + "WHERE job_id = :job AND queued_at >= :since")
  @RegisterConstructorMapper(TotalsRow.class)
  @NonNull
  TotalsRow totals(@Bind("job") long jobId, @Bind("since") @NonNull Instant since);

  /**
   * Per-UTC-day bucket counts. Only days with at least one build appear; the API layer fills the
   * remaining days with zero entries to produce a dense window. We bucket at {@code AT TIME ZONE
   * 'UTC'} so DST shifts in the server's local zone cannot cause two physical days to collapse into
   * one (or split one into two).
   */
  @SqlQuery(
      "SELECT (queued_at AT TIME ZONE 'UTC')::date AS day, "
          + "       COUNT(*) AS total_builds, "
          + "       SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed_builds "
          + "FROM titan.builds "
          + "WHERE job_id = :job AND queued_at >= :since "
          + "GROUP BY (queued_at AT TIME ZONE 'UTC')::date "
          + "ORDER BY day ASC")
  @RegisterConstructorMapper(DailyBucketRow.class)
  @NonNull
  List<DailyBucketRow> dailyBuckets(@Bind("job") long jobId, @Bind("since") @NonNull Instant since);

  /**
   * One-shot totals row. Percentiles are nullable Longs — {@code null} means "no completed build in
   * the window", NOT zero. The API layer surfaces null verbatim to the wire.
   */
  record TotalsRow(
      @ColumnName("total_builds") long totalBuilds,
      @ColumnName("failed_builds") long failedBuilds,
      @ColumnName("p50_duration_ms") @Nullable Long p50DurationMs,
      @ColumnName("p95_duration_ms") @Nullable Long p95DurationMs) {}

  /** Daily bucket as it comes out of the GROUP BY. */
  record DailyBucketRow(
      @ColumnName("day") LocalDate day,
      @ColumnName("total_builds") long totalBuilds,
      @ColumnName("failed_builds") long failedBuilds) {}
}

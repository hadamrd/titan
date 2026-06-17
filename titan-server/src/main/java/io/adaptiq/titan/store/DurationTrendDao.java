package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/**
 * JDBI SqlObject for a job's recent build-duration trend (closes #1096).
 *
 * <p>Powers the inline "Duration trend (30d)" sparkline on the /pipelines (legacy /jobs) index. An
 * operator scanning the fleet for "which pipeline is getting slower" needs the duration shape per
 * row at a glance, not a click-through to each job's stats page.
 *
 * <p>We fetch the most recent {@code n} <em>finished</em> builds (status in {@code SUCCESS, FAILED,
 * UNSTABLE}) with a non-null {@code duration_ms}, then return them <strong>oldest → newest</strong>
 * so the sparkline reads left-to-right newest-on-the-right (the same convention as {@link
 * JobTimingsDao} samples and the status sparkline). RUNNING/QUEUED builds are excluded — an
 * in-flight build has no settled duration and would inject a misleading spike.
 *
 * <p><strong>Window + ordering.</strong> The inner CTE takes the most recent {@code n} finished
 * builds by {@code finished_at DESC LIMIT :n}; the outer query re-sorts {@code finished_at ASC} so
 * the API layer never has to reverse the list. We order on {@code finished_at} (not {@code
 * queued_at}) because the sparkline's x-axis is "when the build completed" — a build that queued
 * earlier but finished later belongs to the right of one that finished before it. This keeps the
 * returned {@code ts} monotonic for a real-world (out-of-order-completion) build history rather
 * than relying on queue order as a proxy.
 */
public interface DurationTrendDao {

  /** Hard ceiling on {@code n} — caps the payload a single row can pull. */
  int MAX_BUILDS = 100;

  /** Default window size (acceptance criteria: last 30 builds). */
  int DEFAULT_BUILDS = 30;

  /**
   * The last {@code n} finished builds of {@code jobId}, ordered oldest→newest. Each row carries
   * the build's {@code finished_at} timestamp, its {@code duration_ms}, and its terminal status so
   * the UI can both plot the duration and tint/annotate per pass/fail. A job with no finished
   * builds yields an empty list (never null) — the UI renders its empty state.
   */
  @SqlQuery(
      "SELECT ts, duration_ms, status FROM ( "
          + "  SELECT finished_at AS ts, duration_ms, status "
          + "  FROM titan.builds "
          + "  WHERE job_id = :job "
          + "    AND status IN ('SUCCESS','FAILED','UNSTABLE') "
          + "    AND duration_ms IS NOT NULL "
          + "    AND finished_at IS NOT NULL "
          + "  ORDER BY finished_at DESC "
          + "  LIMIT :n "
          + ") sub "
          + "ORDER BY sub.ts ASC")
  @RegisterConstructorMapper(DurationTrendRow.class)
  @NonNull
  List<DurationTrendRow> durationTrend(@Bind("job") long jobId, @Bind("n") int n);

  /** One historical build sample: when it finished, how long it took, and its terminal status. */
  record DurationTrendRow(
      @ColumnName("ts") @NonNull Instant ts,
      @ColumnName("duration_ms") long durationMs,
      @ColumnName("status") @NonNull String status) {}
}

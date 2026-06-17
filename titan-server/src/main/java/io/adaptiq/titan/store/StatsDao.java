package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/**
 * JDBI SqlObject for Overview-page KPIs (closes #346 backend half).
 *
 * <p>Backs {@code GET /api/v1/stats}: builds-today + 24h success-rate + 24h median-duration. Kept
 * as a dedicated DAO instead of new {@link BuildDao} methods so the (single, denormalised)
 * aggregate query has a home — and so the unit-test fake can stub three numbers without
 * impersonating the whole {@link BuildDao} surface.
 *
 * <p>Terminal status set is {@code SUCCESS / FAILED / ABORTED / UNSTABLE} — matches the {@code
 * builds_status_check} schema constraint (the engine never inserts {@code CANCELLED}). The "today"
 * boundary is UTC start-of-day; the 24h window is a sliding {@code now() - interval '24 hours'}.
 *
 * <p>The median uses PostgreSQL's {@code PERCENTILE_CONT(0.5)} (also supported by H2 2.x, which
 * backs the {@code @QuarkusTest} unit tests). For windows with no terminal builds {@code
 * medianDurationMs} is {@code 0L} — the row mapper coalesces {@code NULL} via the SQL itself.
 */
public interface StatsDao {

  /**
   * Single-row aggregate over {@code titan.builds}: number of builds queued today (UTC),
   * success-rate over terminal builds in the last 24h, and median duration over terminal builds in
   * the last 24h.
   *
   * <p>Computed in a single SQL pass with three conditional aggregates so we do not multi-trip the
   * DB or hold a snapshot across statements.
   */
  @SqlQuery(
      "SELECT "
          // builds-today = builds whose queued_at >= start-of-UTC-day
          + "  SUM(CASE WHEN queued_at >= :startOfDayUtc THEN 1 ELSE 0 END) AS builds_today, "
          // success-rate = terminal-SUCCESS / terminal-any over the 24h window; 0.0 when no rows
          + "  COALESCE( "
          + "    CAST(SUM(CASE WHEN status = 'SUCCESS' AND finished_at >= :since "
          + "                    THEN 1 ELSE 0 END) AS double precision) "
          + "    / NULLIF(SUM(CASE WHEN status IN ('SUCCESS','FAILED','ABORTED','UNSTABLE') "
          + "                        AND finished_at >= :since THEN 1 ELSE 0 END), 0), "
          + "    0.0) AS success_rate, "
          // median duration (ms) over terminal builds in the 24h window; 0 when none
          + "  COALESCE(CAST( "
          + "    PERCENTILE_CONT(0.5) WITHIN GROUP ( "
          + "      ORDER BY CASE WHEN status IN ('SUCCESS','FAILED','ABORTED','UNSTABLE') "
          + "                      AND finished_at >= :since "
          + "                      AND started_at IS NOT NULL "
          + "                      AND finished_at IS NOT NULL "
          + "                 THEN (EXTRACT(EPOCH FROM finished_at) "
          + "                       - EXTRACT(EPOCH FROM started_at)) * 1000 "
          + "                 ELSE NULL END) "
          + "    AS BIGINT), 0) AS median_duration_ms "
          + "FROM titan.builds")
  @RegisterConstructorMapper(StatsRow.class)
  @NonNull
  StatsRow overview(
      @Bind("startOfDayUtc") @NonNull Instant startOfDayUtc, @Bind("since") @NonNull Instant since);

  /**
   * Result row for {@link #overview}. A plain record with column-mapped constructor params keeps
   * the DAO query and the API DTO decoupled — the API layer rewraps this into {@code StatsDto}.
   */
  record StatsRow(
      @ColumnName("builds_today") int buildsToday,
      @ColumnName("success_rate") double successRate,
      @ColumnName("median_duration_ms") long medianDurationMs) {}
}

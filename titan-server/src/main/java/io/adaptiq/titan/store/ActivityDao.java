package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/**
 * JDBI SqlObject for the Overview-page activity feed (closes #304).
 *
 * <p>v1 derives activity entirely from terminal-state rows in {@code titan.builds} — there is no
 * dedicated events table (see ticket #304). Each terminal build ({@code SUCCESS / FAILED / ABORTED
 * / UNSTABLE}, matching the {@code builds_status_check} constraint in {@code V1__init}) is one feed
 * item, keyed by {@code finished_at DESC}.
 *
 * <p>Pagination is cursor-based on {@code finished_at} epoch-ms: the caller passes the timestamp of
 * the last item it saw and receives the next page strictly older than that. Kept as its own DAO
 * (rather than another method on {@link BuildDao}) so the feed query — which joins jobs and only
 * needs a tight projection — has a single home and a stub-friendly surface for unit tests.
 */
public interface ActivityDao {

  /**
   * The {@code limit} most recent terminal builds with {@code finished_at} strictly less than
   * {@code beforeTs}, or the absolute most-recent page when {@code beforeTs} is {@code null}.
   *
   * <p>Joined to {@code titan.jobs} for {@code full_name}. {@code duration_ms} may be {@code null}
   * for legacy rows; the row record exposes it nullable and the API layer coerces to {@code 0L}.
   */
  @SqlQuery(
      "SELECT b.id AS id, b.build_number AS build_number, b.status AS status, "
          + "       b.finished_at AS finished_at, b.duration_ms AS duration_ms, "
          + "       j.full_name AS job_full_name "
          + "FROM titan.builds b JOIN titan.jobs j ON j.id = b.job_id "
          + "WHERE b.status IN ('SUCCESS','FAILED','ABORTED','UNSTABLE') "
          + "  AND b.finished_at IS NOT NULL "
          // CAST(:beforeTs AS TIMESTAMP) so Postgres can resolve the type when the bind is NULL
          // — JDBI sends NULL as untyped otherwise and the planner refuses (`could not determine
          //   data type of parameter $1`). H2 in PostgreSQL-mode accepts the cast too.
          + "  AND (CAST(:beforeTs AS TIMESTAMP) IS NULL OR b.finished_at < :beforeTs) "
          + "ORDER BY b.finished_at DESC "
          + "LIMIT :limit")
  @RegisterConstructorMapper(ActivityRow.class)
  @NonNull
  List<ActivityRow> recentTerminalBuilds(
      @Bind("limit") int limit, @Bind("beforeTs") @Nullable Instant beforeTs);

  /**
   * Row for {@link #recentTerminalBuilds}. Plain record with column-mapped constructor params so
   * the API layer can rewrap into its DTO without leaking JDBI types upstream.
   */
  record ActivityRow(
      @ColumnName("id") long id,
      @ColumnName("build_number") int buildNumber,
      @ColumnName("status") @NonNull String status,
      @ColumnName("finished_at") @NonNull Instant finishedAt,
      @ColumnName("duration_ms") @Nullable Long durationMs,
      @ColumnName("job_full_name") @NonNull String jobFullName) {}
}

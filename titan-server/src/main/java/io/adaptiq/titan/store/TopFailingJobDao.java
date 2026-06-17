package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/**
 * JDBI SqlObject for the Home "Top failing jobs" widget (closes #769).
 *
 * <p>Aggregates {@code titan.builds} terminal rows over a sliding time window per job, ranks by
 * failure rate descending, and returns the top N. The query uses parameterised binds only — no
 * string concatenation — so {@code since} and {@code limit} are safe to pass through from the HTTP
 * layer.
 *
 * <p><strong>Index strategy.</strong> The predicate filters by {@code queued_at &gt;= :since} and
 * groups by {@code job_id}; the {@code idx_builds_job_queued (job_id, queued_at DESC)} index from
 * {@code V1__init.sql} already covers this access pattern (range scan on the leading column for
 * each group). No new migration is required — adding a redundant index would only slow down inserts
 * in the hot orchestrator path.
 *
 * <p>Only jobs with {@code totalBuilds &gt;= 3} are returned; a single failed build is statistical
 * noise, not a flake signal (issue #769 acceptance criterion).
 */
public interface TopFailingJobDao {

  /**
   * Top-N failing jobs since {@code since}, ranked by failure rate descending. Ties are broken by
   * higher {@code failed} then by {@code job_id} ascending so the order is deterministic across
   * polls (otherwise the UI row order would flicker).
   *
   * <p>The {@code last_failed_build_id} subquery returns NULL when no FAILED row exists in the
   * window — the row can still appear (e.g. ABORTED / UNSTABLE mass) but the UI will not render the
   * "last failure" link.
   */
  @SqlQuery(
      "SELECT j.id AS job_id, "
          + "       COALESCE(NULLIF(j.display_name, ''), j.full_name) AS job_name, "
          + "       COUNT(*) AS total_builds, "
          + "       SUM(CASE WHEN b.status = 'FAILED' THEN 1 ELSE 0 END) AS failed_builds, "
          + "       CAST(SUM(CASE WHEN b.status = 'FAILED' THEN 1 ELSE 0 END) AS double precision) "
          + "         / NULLIF(COUNT(*), 0) AS failure_rate, "
          + "       (SELECT b2.id FROM titan.builds b2 "
          + "         WHERE b2.job_id = j.id AND b2.status = 'FAILED' "
          + "           AND b2.queued_at >= :since "
          + "         ORDER BY b2.queued_at DESC LIMIT 1) AS last_failed_build_id "
          + "FROM titan.builds b "
          + "JOIN titan.jobs j ON j.id = b.job_id "
          + "WHERE b.queued_at >= :since "
          + "  AND b.status IN ('SUCCESS','FAILED','ABORTED','UNSTABLE') "
          + "GROUP BY j.id, j.display_name, j.full_name "
          + "HAVING COUNT(*) >= 3 "
          + "   AND SUM(CASE WHEN b.status = 'FAILED' THEN 1 ELSE 0 END) > 0 "
          + "ORDER BY failure_rate DESC, failed_builds DESC, job_id ASC "
          + "LIMIT :lim")
  @RegisterConstructorMapper(TopFailingJobRow.class)
  @NonNull
  List<TopFailingJobRow> topFailing(@Bind("since") @NonNull Instant since, @Bind("lim") int limit);

  /**
   * DAO row mirroring {@link io.adaptiq.titan.api.dto.TopFailingJobDto} — kept separate so the wire
   * format and the storage shape evolve independently (the API layer rewraps).
   */
  record TopFailingJobRow(
      @ColumnName("job_id") long jobId,
      @ColumnName("job_name") String jobName,
      @ColumnName("total_builds") long totalBuilds,
      @ColumnName("failed_builds") long failedBuilds,
      @ColumnName("failure_rate") double failureRate,
      @ColumnName("last_failed_build_id") Long lastFailedBuildId) {}
}

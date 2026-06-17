package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.JobTriggerRow;
import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.job_triggers} — per-trigger runtime state (design/50).
 *
 * <p>The firing engine ({@code TriggerEngine}) reads and writes this inside a transaction that also
 * holds a {@code FOR UPDATE} lock on the owning {@code titan.jobs} row, so the {@link #recordFired}
 * update-then-insert is race-free: no other writer can touch a given job's trigger rows
 * concurrently. The {@code Connection}-taking overloads re-attach this SqlObject to the caller's
 * transactional connection (see {@code TitanStores.onConnection}).
 */
@RegisterFieldMapper(JobTriggerRow.class)
public interface JobTriggerDao {

  String COLS = "job_id, trigger_id, last_fired_at, last_error";

  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.job_triggers "
          + "WHERE job_id = :jobId AND trigger_id = :triggerId")
  @NonNull
  Optional<JobTriggerRow> find(
      @Bind("jobId") long jobId, @Bind("triggerId") @NonNull String triggerId);

  /** Transaction-safe {@link #find}. */
  @NonNull
  default Optional<JobTriggerRow> find(
      @NonNull Connection conn, long jobId, @NonNull String triggerId) {
    return TitanStores.onConnection(conn, JobTriggerDao.class, dao -> dao.find(jobId, triggerId));
  }

  @SqlUpdate(
      "UPDATE titan.job_triggers SET last_fired_at = :firedAt, last_error = :lastError "
          + "WHERE job_id = :jobId AND trigger_id = :triggerId")
  int updateState(
      @Bind("jobId") long jobId,
      @Bind("triggerId") @NonNull String triggerId,
      @Bind("firedAt") @Nullable Instant firedAt,
      @Bind("lastError") @Nullable String lastError);

  @SqlUpdate(
      "INSERT INTO titan.job_triggers (job_id, trigger_id, last_fired_at, last_error) "
          + "VALUES (:jobId, :triggerId, :firedAt, :lastError)")
  void insertState(
      @Bind("jobId") long jobId,
      @Bind("triggerId") @NonNull String triggerId,
      @Bind("firedAt") @Nullable Instant firedAt,
      @Bind("lastError") @Nullable String lastError);

  /**
   * Persist a trigger's runtime state — update the existing row, or insert it if this is the first
   * time the {@code (job, trigger)} pair is recorded. Race-free because the caller holds the
   * job-row lock (design/50 D4).
   */
  default void recordState(
      @NonNull Connection conn,
      long jobId,
      @NonNull String triggerId,
      @Nullable Instant firedAt,
      @Nullable String lastError) {
    TitanStores.onConnection(
        conn,
        JobTriggerDao.class,
        dao -> {
          if (dao.updateState(jobId, triggerId, firedAt, lastError) == 0) {
            dao.insertState(jobId, triggerId, firedAt, lastError);
          }
          return null;
        });
  }
}

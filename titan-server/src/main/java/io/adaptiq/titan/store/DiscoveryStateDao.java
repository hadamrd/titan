package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.DiscoveryStateRow;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;

/**
 * JDBI SqlObject access for {@code titan.discovery_state} — per-job SCM polling state used by the
 * {@link io.adaptiq.titan.discovery.DiscoveryService}.
 *
 * <p>One row per job that has an {@code scm} block; deleted on cascade when the parent job row is
 * deleted (FK with {@code ON DELETE CASCADE} in V12). Upserts follow the portable "UPDATE then
 * INSERT" pattern used elsewhere in {@code TitanStores} (see {@code AgentDao.upsert}) — race-free
 * under the single-controller assumption.
 */
@RegisterFieldMapper(DiscoveryStateRow.class)
public interface DiscoveryStateDao {

  String COLS = "job_id, last_seen_sha, last_polled_at, last_status, last_error, updated_at";

  @SqlQuery("SELECT " + COLS + " FROM titan.discovery_state WHERE job_id = :jobId")
  @NonNull
  Optional<DiscoveryStateRow> findByJobId(@Bind("jobId") long jobId);

  // ── upsert success ────────────────────────────────────────────────────────

  @SqlUpdate(
      "UPDATE titan.discovery_state SET last_seen_sha = :sha, "
          + "last_polled_at = CURRENT_TIMESTAMP, last_status = 'ok', last_error = NULL, "
          + "updated_at = CURRENT_TIMESTAMP WHERE job_id = :jobId")
  int updateOk(@Bind("jobId") long jobId, @Bind("sha") @NonNull String sha);

  @SqlUpdate(
      "INSERT INTO titan.discovery_state "
          + "(job_id, last_seen_sha, last_polled_at, last_status, last_error) "
          + "VALUES (:jobId, :sha, CURRENT_TIMESTAMP, 'ok', NULL)")
  void insertOk(@Bind("jobId") long jobId, @Bind("sha") @NonNull String sha);

  /** Stamp a successful poll. Upserts the row, recording the just-observed SHA. */
  @Transaction
  default void recordOk(long jobId, @NonNull String sha) {
    if (updateOk(jobId, sha) == 0) {
      insertOk(jobId, sha);
    }
  }

  // ── upsert failure ────────────────────────────────────────────────────────

  @SqlUpdate(
      "UPDATE titan.discovery_state SET last_polled_at = CURRENT_TIMESTAMP, "
          + "last_status = 'failed', last_error = :error, updated_at = CURRENT_TIMESTAMP "
          + "WHERE job_id = :jobId")
  int updateFailed(@Bind("jobId") long jobId, @Bind("error") @Nullable String error);

  @SqlUpdate(
      "INSERT INTO titan.discovery_state "
          + "(job_id, last_seen_sha, last_polled_at, last_status, last_error) "
          + "VALUES (:jobId, NULL, CURRENT_TIMESTAMP, 'failed', :error)")
  void insertFailed(@Bind("jobId") long jobId, @Bind("error") @Nullable String error);

  /**
   * Stamp a failed poll. Does <em>not</em> clear an existing {@code last_seen_sha} — a transient
   * remote failure must not trigger a phantom rebuild on the next success.
   */
  @Transaction
  default void recordFailed(long jobId, @Nullable String error) {
    if (updateFailed(jobId, error) == 0) {
      insertFailed(jobId, error);
    }
  }

  @SqlUpdate("DELETE FROM titan.discovery_state WHERE job_id = :jobId")
  void deleteByJobId(@Bind("jobId") long jobId);
}

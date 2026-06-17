package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.JobRow;
import java.util.List;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.user_starred_jobs} — per-user pinned jobs (#703).
 *
 * <p>Like {@link PersonalAccessTokenDao}, scope is enforced at the DAO boundary: every read or
 * write method takes a {@code userSubject} bind and filters on it. The {@link
 * io.adaptiq.titan.api.StarredJobsApi} layer pulls the subject from {@code SecurityIdentity}.
 *
 * <p>{@link #listForUser(String)} returns the joined {@link JobRow} shape directly so the API can
 * map straight to {@link io.adaptiq.titan.api.dto.JobDto} without a second round-trip to {@link
 * JobDao}. The ORDER BY lines up with the {@code (user_subject, starred_at DESC)} index defined in
 * V24 — a single index scan, no sort buffer.
 */
public interface StarredJobsDao {

  /**
   * List every starred job for {@code userSubject}, newest-pin first. Joins {@code titan.jobs} so
   * the API can return full {@link JobDto}s. Jobs deleted between pin and list are absent thanks to
   * the {@code ON DELETE CASCADE} on the FK.
   */
  @SqlQuery(
      "SELECT j.id, j.full_name, j.display_name, j.folder_path, j.pipeline_script, j.config_json, "
          + "j.created_by, j.created_at, j.updated_at, j.enabled "
          + "FROM titan.user_starred_jobs s "
          + "INNER JOIN titan.jobs j ON j.id = s.job_id "
          + "WHERE s.user_subject = :userSubject "
          + "ORDER BY s.starred_at DESC")
  @RegisterFieldMapper(JobRow.class)
  @NonNull
  List<JobRow> listForUser(@Bind("userSubject") @NonNull String userSubject);

  /** Count the user's stars — backs the 10-cap check in the API layer. */
  @SqlQuery("SELECT COUNT(*) FROM titan.user_starred_jobs WHERE user_subject = :userSubject")
  int countForUser(@Bind("userSubject") @NonNull String userSubject);

  /**
   * True when {@code (userSubject, jobId)} already exists — backs the PUT-idempotency probe in
   * {@link io.adaptiq.titan.api.StarredJobsApi#star}: a re-star on a user at the 10-cap must NOT
   * spuriously trip the 409 gate.
   */
  @SqlQuery(
      "SELECT COUNT(*) FROM titan.user_starred_jobs "
          + "WHERE user_subject = :userSubject AND job_id = :jobId")
  int existsForUser(@Bind("userSubject") @NonNull String userSubject, @Bind("jobId") long jobId);

  /**
   * Idempotent insert — {@code ON CONFLICT DO NOTHING} (PG) / {@code MERGE} (H2) would also work,
   * but a plain insert + caught unique-violation keeps the SQL portable across both backends. The
   * API layer treats a duplicate as a 204 no-op (PUT-idempotent semantics).
   *
   * <p>Returns rows-inserted: 1 on a fresh star, 0 if the row already exists. The API uses the
   * return value to decide whether to bump {@code starred_at} (it does not — pin time is recorded
   * once at first star to give a stable "Recently pinned" order).
   */
  @SqlUpdate(
      "INSERT INTO titan.user_starred_jobs (user_subject, job_id) "
          + "VALUES (:userSubject, :jobId)")
  int insert(@Bind("userSubject") @NonNull String userSubject, @Bind("jobId") long jobId);

  /**
   * Unstar — returns rows-deleted (0 if the user had not starred it, 1 otherwise). Idempotent by
   * construction; the API maps both to 204.
   */
  @SqlUpdate(
      "DELETE FROM titan.user_starred_jobs "
          + "WHERE user_subject = :userSubject AND job_id = :jobId")
  int delete(@Bind("userSubject") @NonNull String userSubject, @Bind("jobId") long jobId);
}

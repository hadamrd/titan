package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.LogRow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindFields;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/** JDBI SqlObject access for {@code titan.logs}. */
@RegisterFieldMapper(LogRow.class)
public interface LogDao {

  String COLS = "id, task_id, chunk_index, stream, data, produced_at, is_final";

  /** Lookup by id. */
  @SqlQuery("SELECT " + COLS + " FROM titan.logs WHERE id = :id")
  @NonNull
  Optional<LogRow> findById(@Bind("id") long id);

  /** All log chunks for a task, ordered by chunk_index. */
  @SqlQuery("SELECT " + COLS + " FROM titan.logs WHERE task_id = :taskId ORDER BY chunk_index")
  @NonNull
  List<LogRow> listByTask(@Bind("taskId") @NonNull UUID taskId);

  /**
   * Insert a log chunk. {@code id} and {@code produced_at} are DB-generated. Returns the generated
   * id.
   */
  @SqlUpdate(
      "INSERT INTO titan.logs (task_id, chunk_index, stream, data, is_final) "
          + "VALUES (:taskId, :chunkIndex, :stream, :data, :isFinal)")
  @GetGeneratedKeys
  long insert(@BindFields LogRow row);

  /** Delete all log chunks for a task. Idempotent. */
  @SqlUpdate("DELETE FROM titan.logs WHERE task_id = :taskId")
  void deleteByTaskId(@Bind("taskId") @NonNull UUID taskId);

  /**
   * Delete every log chunk of a build, across all of its tasks. Used by the build reaper when
   * retention drops a build.
   *
   * <p>{@code titan.logs} is keyed by {@code task_id} (a {@code task_token} UUID) and carries
   * <em>no</em> {@code build_id} column and no foreign key — so the {@code fk_*_build ON DELETE
   * CASCADE} that cleans {@code task_queue} / {@code task_archive} / {@code flow_nodes} / {@code
   * artifact} when a {@code titan.builds} row is dropped does <strong>not</strong> reach the logs.
   * Left alone, every reaped build would leak its entire console in Postgres. So the reaper calls
   * this explicitly, and must call it <em>before</em> dropping the build row — once the cascade
   * removes the task rows, the build's task tokens can no longer be resolved.
   *
   * <p>The tokens are gathered from both the live queue and the archive (a finished build's tasks
   * are usually archived), matching {@code TaskQueueDao.logTokensForBuild}. Idempotent: a second
   * call, or a build with no logs, deletes nothing.
   *
   * @return the number of log chunks deleted
   */
  @SqlUpdate(
      "DELETE FROM titan.logs WHERE task_id IN ("
          + "SELECT task_token FROM titan.task_queue WHERE build_id = :buildId "
          + "UNION ALL "
          + "SELECT task_token FROM titan.task_archive WHERE build_id = :buildId)")
  int deleteByBuild(@Bind("buildId") long buildId);
}

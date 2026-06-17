package io.adaptiq.titan.queue;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.TitanStores;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Daily retention-horizon prune for {@code titan.task_archive} (issue #633).
 *
 * <p>{@code task_archive} otherwise grows unbounded — every terminal task ever processed lives
 * there forever and the table eventually dominates the DB. The {@link QueueProcessorScheduler}
 * invokes {@link #prune(TitanStores, int)} on a coarse daily cadence; a {@code retentionDays} of
 * {@code 0} is the operator opt-out (keeps full history).
 *
 * <p>Cutoff is computed in Java and bound as a {@link java.sql.Timestamp} for H2/PG portability —
 * mirrors {@link io.adaptiq.titan.store.TaskQueueDao#reapStale(int)} (PR #520; in-DB {@code
 * INTERVAL} math is rejected by H2). Extracted from {@link QueueProcessor} per the orchestrator-
 * decomposition convention (design/59) to keep the controller-loop class under the 1000-line cap.
 */
public final class TaskArchivePruner {

  private static final Logger LOGGER = Logger.getLogger(TaskArchivePruner.class.getName());

  /** Milliseconds in one day — the unit retentionDays is denominated in. */
  private static final long MILLIS_PER_DAY = 86_400_000L;

  private TaskArchivePruner() {}

  /**
   * Delete archived task rows whose {@code completed_at} is older than {@code retentionDays} days.
   * Best-effort: a failure is logged and swallowed so the next scheduled run retries.
   *
   * @param daos store façade
   * @param retentionDays retention horizon in days; {@code <= 0} disables the prune (no-op)
   * @return rows deleted on this pass (0 if disabled or nothing past horizon).
   */
  public static int prune(@NonNull TitanStores daos, int retentionDays) {
    if (retentionDays <= 0) {
      return 0;
    }
    try {
      long horizonMillis = System.currentTimeMillis() - retentionDays * MILLIS_PER_DAY;
      java.sql.Timestamp cutoff = new java.sql.Timestamp(horizonMillis);
      int pruned = daos.taskQueue().deleteArchiveOlderThan(cutoff);
      if (pruned > 0) {
        LOGGER.log(
            Level.INFO,
            "[release-flow] pruned {0} archive row(s) older than {1} day(s)",
            new Object[] {pruned, retentionDays});
      }
      return pruned;
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "[release-flow] TaskArchivePruner: prune sweep failed", e);
      return 0;
    }
  }
}

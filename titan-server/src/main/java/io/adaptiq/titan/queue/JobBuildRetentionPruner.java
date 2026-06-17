package io.adaptiq.titan.queue;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.parser.TitanYamlParser;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Daily per-job build-retention prune (issue #637) — caps {@code titan.builds} at the last {@code
 * keepLast} rows per job, dropping the older tail along with everything that hangs off it
 * (flow_nodes, artifact, test_result, task_queue, logs, task_archive).
 *
 * <p>Per-job override (#640): a job's pipeline_script may declare a top-level {@code
 * buildRetention: { keepLast: N }}. When present, that {@code N} is used as the cap for that job;
 * otherwise the server-wide {@code TITAN_JOB_BUILD_RETENTION} default (passed into {@link
 * #prune(TitanStores, int)} as {@code globalKeepLast}) applies. A per-job {@code keepLast: 0} is
 * the per-job opt-out — keeps full history for that job regardless of the global default. A
 * pipeline_script that fails to parse falls back to the global default (we never let a malformed
 * pipeline disable retention).
 *
 * <p>Cascade discipline — what the build-row drop reaches vs. what it does not:
 *
 * <ul>
 *   <li>Auto-cascaded by {@code titan.builds(id) ON DELETE CASCADE}: {@code task_queue}, {@code
 *       flow_nodes}, {@code artifact}, {@code fingerprint_ref}, {@code test_result}. {@code
 *       fingerprint.first_build_id} is {@code SET NULL} — cross-build fingerprints survive.
 *   <li>Manual delete before the build row goes: {@code titan.logs} (no {@code build_id} column —
 *       keyed by task_token; see {@link io.adaptiq.titan.store.LogDao#deleteByBuild}) and {@code
 *       task_archive} (no FK — see {@link
 *       io.adaptiq.titan.store.TaskQueueDao#deleteArchiveByBuild}).
 * </ul>
 *
 * <p>Per-build transactional safety: each build is dropped inside its own {@link
 * TitanStores#withTransaction} block so a partial failure on one build leaves earlier deletes
 * committed and later builds untouched — the next scheduled sweep retries the leftovers.
 * Idempotent: a re-run on a job already at or under {@code keepLast} is a no-op (0 deletes).
 */
public final class JobBuildRetentionPruner {

  private static final Logger LOGGER = Logger.getLogger(JobBuildRetentionPruner.class.getName());

  /**
   * Cap per call on how many over-retention builds we fetch from a single job. Bounded so a job
   * with a sudden retention drop (e.g. operator lowers 1000 → 100) does not have to drop 900 builds
   * in one sweep — the next daily run cleans the residue.
   */
  static final int BATCH_LIMIT = 500;

  private JobBuildRetentionPruner() {}

  /**
   * Prune every job's build history. The effective cap for a job is the job's own {@code
   * buildRetention.keepLast} (#640) when its pipeline_script declares one, else {@code
   * globalKeepLast}. A cap of {@code 0} is the opt-out for that job (full history preserved). A
   * pipeline_script that fails to parse falls back to {@code globalKeepLast} (we never let a
   * malformed pipeline disable retention).
   *
   * @param daos store façade
   * @param globalKeepLast server-wide retention cap; {@code <= 0} disables prune for any job that
   *     does not set its own per-job override
   * @return total builds deleted across all jobs in this pass
   */
  public static int prune(@NonNull TitanStores daos, int globalKeepLast) {
    int totalDeleted = 0;
    try {
      List<JobRow> jobs = daos.jobs().listAll();
      for (JobRow job : jobs) {
        int effectiveKeepLast = effectiveKeepLast(job, globalKeepLast);
        if (effectiveKeepLast <= 0) {
          continue;
        }
        totalDeleted += pruneJob(daos, job.id, effectiveKeepLast);
      }
      if (totalDeleted > 0) {
        LOGGER.log(
            Level.INFO,
            "[titan] pruned {0} build(s) past per-job retention (global default {1})",
            new Object[] {totalDeleted, globalKeepLast});
      }
      return totalDeleted;
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "[titan] JobBuildRetentionPruner: prune sweep failed", e);
      return totalDeleted;
    }
  }

  /**
   * Resolve the effective per-job retention cap: parse the job's pipeline_script, return its {@code
   * buildRetention.keepLast} if declared, else {@code globalKeepLast}. A parse failure logs at FINE
   * and falls back to the global default — design choice: a broken pipeline must not silently
   * disable retention.
   */
  static int effectiveKeepLast(@NonNull JobRow job, int globalKeepLast) {
    Integer override = perJobKeepLast(job);
    return override != null ? override : globalKeepLast;
  }

  @Nullable
  private static Integer perJobKeepLast(@NonNull JobRow job) {
    String script = job.pipelineScript;
    if (script == null || script.isBlank()) {
      return null;
    }
    try {
      PipelineModel model = TitanYamlParser.parse(script);
      return model.getBuildRetentionKeepLast();
    } catch (RuntimeException e) {
      // A malformed pipeline_script must not silently disable retention. Fall back to the
      // global default and record the parse failure so an operator can investigate.
      LOGGER.log(
          Level.FINE,
          "[titan] retention override parse failed for job " + job.id + " — using global default",
          e);
      return null;
    }
  }

  /**
   * Drop builds for a single job whose rank (newest = 0) is at or beyond {@code keepLast}. Each
   * build is deleted inside its own transaction so a poison row cannot block the rest of the job.
   *
   * @return builds deleted for this job
   */
  static int pruneJob(@NonNull TitanStores daos, long jobId, int keepLast) {
    List<Long> overCap = daos.builds().findIdsOverRetentionCap(jobId, keepLast, BATCH_LIMIT);
    int deleted = 0;
    for (long buildId : overCap) {
      try {
        daos.withTransaction(
            conn -> {
              // logs first: titan.logs has no FK to builds; once the build row goes, the task
              // tokens that key the logs are gone via the task_queue/task_archive cascade.
              daos.logs().deleteByBuild(buildId);
              // task_archive has no FK either — explicit delete.
              daos.taskQueue().deleteArchiveByBuild(buildId);
              // builds row goes — cascades clean flow_nodes, artifact, task_queue, test_result,
              // fingerprint_ref. fingerprint.first_build_id is SET NULL (cross-build).
              daos.builds().delete(buildId);
              return null;
            });
        deleted++;
      } catch (RuntimeException e) {
        LOGGER.log(
            Level.WARNING,
            "[titan] retention prune failed for build " + buildId + " of job " + jobId,
            e);
      }
    }
    return deleted;
  }
}

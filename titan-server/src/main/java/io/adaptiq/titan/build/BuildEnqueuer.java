package io.adaptiq.titan.build;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.TaskQueueDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.time.Instant;

/**
 * The one canonical "enqueue a build" seam.
 *
 * <p>Inserts a {@code QUEUED} build row plus its {@code ORCHESTRATE}/{@code SYNTHESIZE} task in a
 * single transaction. Every trigger path — poll discovery ({@code DiscoveryServiceImpl}), the
 * GitHub App webhook ({@code GithubAppWebhookApi}) and the Pulsar webhook ({@code
 * PulsarWebhookApi}) — routes through here so there is exactly one definition of the dispatch
 * transaction (closes the rule-of-three duplication flagged in #1287). {@code triggerType} is
 * passed in by the caller.
 *
 * <p>Closes #821: discovered/triggered builds carry no {@code pipeline_model_json} yet, so the task
 * action is {@code SYNTHESIZE} (not {@code BAKE}) — the controller drives design/38 §3 worker
 * synthesis before bake. Trigger facts travel via {@code trigger_meta_json} (lifted into implicit
 * env by the dispatcher), never {@code parameters_json}, which the bake's ParameterResolver
 * validates against the pipeline's declared {@code parameters:}.
 */
public final class BuildEnqueuer {

  private BuildEnqueuer() {}

  /**
   * Insert a QUEUED build + its SYNTHESIZE task in one transaction.
   *
   * @param stores the store facade providing the transaction + DAOs
   * @param jobId the job to build
   * @param triggeredBy human/actor label recorded on the build
   * @param triggerType the trigger source (e.g. {@code "discovery"}, {@code "github-app"}, {@code
   *     "pulsar"})
   * @param triggerMetaJson trigger facts (branch/sha/actor…) or {@code null}
   * @param parametersJson user-declared parameter values or {@code null}
   * @return the new build id
   */
  public static long enqueue(
      @NonNull TitanStores stores,
      long jobId,
      @NonNull String triggeredBy,
      @NonNull String triggerType,
      @Nullable String triggerMetaJson,
      @Nullable String parametersJson) {
    return stores.withTransaction(
        conn -> {
          int buildNumber = stores.builds().nextBuildNumber(conn, jobId);

          BuildRow build = new BuildRow();
          build.jobId = jobId;
          build.buildNumber = buildNumber;
          build.status = "QUEUED";
          build.triggeredBy = triggeredBy;
          build.triggerType = triggerType;
          build.triggerMetaJson = triggerMetaJson;
          build.parametersJson = parametersJson;
          build.queuedAt = Instant.now();
          long buildId = stores.builds().insert(conn, build);

          enqueueSynthesizeEntryTask(conn, buildId);

          return buildId;
        });
  }

  /**
   * The ONE definition of a new build's entry {@code ORCHESTRATE} task — every path that creates a
   * {@code QUEUED} build (manual API, the three webhook APIs, discovery, the cron trigger engine)
   * must route through here. Issue #106 existed because this payload contract lived in two places:
   * {@code DbTriggerScope.fire()} hand-rolled the row and omitted the {@code "action"} key, so
   * every cron-fired build fail-closed in {@code QueueProcessor.dispatch} with "unknown
   * orchestration action 'null'".
   *
   * <p>Field choices (reconciling the pre-#106 divergences between the manual and cron paths):
   *
   * <ul>
   *   <li>{@code action=SYNTHESIZE} — the design/38 §3 entry action. The controller dispatches a
   *       worker-side synthesis of {@code titan.jobs.pipeline_script} into {@code
   *       pipeline_model_json}, then enqueues BAKE. Enqueuing BAKE directly fails in {@code
   *       QueueProcessor.handleBake} with "no synthesized model" (closes #486, #821).
   *   <li>{@code priority=0} (cron previously used 5) — entry tasks carry the NORMAL tier on {@code
   *       ORDER BY priority DESC}. The job-level priority weight (#1100) is layered on by the
   *       follow-up BAKE/ADVANCE enqueues once a model exists; a flat 5 here would let every
   *       cron-fired build jump ahead of manual/webhook/discovery builds for no design reason.
   *   <li>{@code visibilityTimeoutSeconds=3600} (cron previously used 300) — matches every other
   *       entry path and the reaper's conservative floor ({@code
   *       QueueProcessor.REAP_VISIBILITY_TIMEOUT_SECONDS}=3600). A 300s lease on the task that
   *       coordinates a whole build risks a second controller re-claiming it mid-flight and
   *       double-dispatching worker synthesis — the latent bug flagged in #106.
   *   <li>{@code maxAttempts=3} — identical on both paths pre-#106; kept.
   * </ul>
   *
   * @param conn the caller's transactional connection (the build insert + task insert must commit
   *     atomically)
   * @param buildId the freshly-inserted build the task orchestrates
   */
  public static void enqueueSynthesizeEntryTask(@NonNull Connection conn, long buildId) {
    TaskQueueRow task = synthesizeEntryTask(buildId);
    TitanStores.onConnection(conn, TaskQueueDao.class, dao -> dao.insert(task));
  }

  /**
   * Build (without inserting) the entry {@code ORCHESTRATE/SYNTHESIZE} task row for {@code
   * buildId}. See {@link #enqueueSynthesizeEntryTask} for the field-choice rationale — the payload
   * shape pinned here is asserted by {@code SynthesizeEntryTaskTest}.
   */
  @NonNull
  public static TaskQueueRow synthesizeEntryTask(long buildId) {
    TaskQueueRow task = new TaskQueueRow();
    task.type = "ORCHESTRATE";
    task.queueName = "default";
    task.status = "QUEUED";
    task.priority = 0;
    task.payloadJson = "{\"action\":\"SYNTHESIZE\",\"buildId\":" + buildId + "}";
    task.attempts = 0;
    task.maxAttempts = 3;
    task.visibilityTimeoutSeconds = 3600;
    task.buildId = buildId;
    task.availableAt = Instant.now();
    return task;
  }
}

package io.adaptiq.titan.build;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.TaskQueueDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
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
          TitanStores.onConnection(conn, TaskQueueDao.class, dao -> dao.insert(task));

          return buildId;
        });
  }
}

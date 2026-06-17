package io.adaptiq.titan.queue;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.TitanFlowExecution;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles {@code ORCHESTRATE/REPLAY_FROM_NODE} (issue #307) — bake the replay build from the
 * parent's pipeline model, then mark every node strictly upstream of {@code replayed_from_node_id}
 * as {@code SKIPPED} with {@code reason: REPLAY}. The target node + everything downstream stays in
 * its baked initial status so the existing orchestrator drives it from there.
 *
 * <p>Idempotent: the bake step detects an already-materialised DAG and skips; the SKIP-upstream
 * loop uses {@code compareAndSetStatus} so a re-delivered task whose nodes are already SKIPPED
 * simply wins zero CAS rows.
 */
final class ReplayFromNodeHandler implements QueueMessageHandler {

  private static final Logger LOGGER = Logger.getLogger(ReplayFromNodeHandler.class.getName());

  private final QueueHandlerSupport support;
  private final BiFunction<TitanStores, Long, TitanFlowExecution> executionFactory;

  ReplayFromNodeHandler(@NonNull QueueHandlerSupport support) {
    this(support, TitanFlowExecution::new);
  }

  ReplayFromNodeHandler(
      @NonNull QueueHandlerSupport support,
      @NonNull BiFunction<TitanStores, Long, TitanFlowExecution> executionFactory) {
    this.support = support;
    this.executionFactory = executionFactory;
  }

  @Override
  public void handle(
      @NonNull TitanStores daos, @NonNull TaskQueueRow task, @NonNull Map<String, Object> payload) {
    Object buildIdObj = payload.get("buildId");
    if (buildIdObj == null) {
      support.failTaskSafely(daos, task, "REPLAY_FROM_NODE missing buildId in payload");
      return;
    }
    long buildId = QueueHandlerSupport.toBuildId(buildIdObj);

    BuildRow build = daos.builds().findById(buildId).orElse(null);
    if (build == null) {
      support.failTaskSafely(daos, task, "REPLAY_FROM_NODE: build not found: " + buildId);
      return;
    }
    if (build.replayedFromNodeId == null) {
      support.markBuildFailed(
          daos,
          buildId,
          "the build engine received a REPLAY_FROM_NODE task for a build with no "
              + "replayed_from_node_id — this is an internal error; check the controller log.");
      support.failTaskSafely(daos, task, "REPLAY_FROM_NODE: build has no replayed_from_node_id");
      return;
    }
    if (build.pipelineModelJson == null || build.pipelineModelJson.isBlank()) {
      support.markBuildFailed(
          daos, buildId, "replay build has no pipeline model — this is an internal error.");
      support.failTaskSafely(daos, task, "REPLAY_FROM_NODE: build has no pipeline_model_json");
      return;
    }

    try {
      // 1) Bake — retry-safe; materialises the DAG into flow_nodes (root nodes QUEUED, rest
      // PENDING).
      TitanFlowExecution.BakeResult bakeResult = executionFactory.apply(daos, buildId).bake();
      LOGGER.log(
          Level.INFO,
          "[titan] QueueProcessor: REPLAY_FROM_NODE build {0} bake={1}",
          new Object[] {buildId, bakeResult});

      // 2) Mark every node strictly upstream of replayed_from_node_id as SKIPPED w/ reason=REPLAY.
      PipelineModel model;
      try {
        model = QueueHandlerSupport.JSON.readValue(build.pipelineModelJson, PipelineModel.class);
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        support.markBuildFailed(
            daos, buildId, "replay: cannot deserialise pipeline model: " + e.getMessage());
        support.failTaskSafely(daos, task, "REPLAY_FROM_NODE: bad pipeline model JSON");
        return;
      }

      Set<String> upstream = QueueProcessor.collectUpstream(model, build.replayedFromNodeId);
      if (upstream.isEmpty()) {
        LOGGER.log(
            Level.INFO,
            "[titan] QueueProcessor: REPLAY_FROM_NODE build {0} from ''{1}'' — no upstream"
                + " nodes (replaying from a root); behaves like a fresh build",
            new Object[] {buildId, build.replayedFromNodeId});
      } else {
        int skipped = skipUpstreamNodes(daos, buildId, upstream);
        LOGGER.log(
            Level.INFO,
            "[titan] QueueProcessor: REPLAY_FROM_NODE build {0} from ''{1}'' — {2} upstream"
                + " node(s) skipped",
            new Object[] {buildId, build.replayedFromNodeId, skipped});
      }

      // 3) Kick the regular DAG advancement — the orchestrator picks up the replay frontier.
      support.enqueueAdvance(daos, buildId, 0);
      support.completeTaskSafely(daos, task);
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] QueueProcessor: REPLAY_FROM_NODE failed for build {0}: {1}",
          new Object[] {buildId, e.getMessage()});
      support.markBuildFailed(
          daos, buildId, "replay-from-node failed: " + QueueHandlerSupport.describe(e));
      support.failTaskSafely(daos, task, "REPLAY_FROM_NODE failed: " + e.getMessage());
    }
  }

  /**
   * CAS every node in {@code upstreamNodeIds} to {@code SKIPPED} with {@code result_json} carrying
   * a {@code reason: REPLAY} sentinel. Idempotent: nodes already SKIPPED lose the CAS.
   */
  private int skipUpstreamNodes(
      @NonNull TitanStores daos, long buildId, @NonNull Set<String> upstreamNodeIds) {
    int skipped = 0;
    Instant now = Instant.now();
    String replayResult = "{\"skipped\":true,\"reason\":\"REPLAY\"}";
    for (String nodeId : upstreamNodeIds) {
      FlowNodeRow node = daos.flowNodes().findByBuildAndNode(buildId, nodeId).orElse(null);
      if (node == null) {
        continue;
      }
      if ("SKIPPED".equals(node.status)) {
        continue;
      }
      int won =
          daos.flowNodes()
              .compareAndSetStatus(
                  buildId, nodeId, node.status, "SKIPPED", now, now, 0L, replayResult);
      if (won == 1) {
        skipped++;
      }
    }
    return skipped;
  }
}

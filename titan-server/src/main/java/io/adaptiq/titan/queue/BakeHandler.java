package io.adaptiq.titan.queue;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.TitanFlowExecution;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles {@code ORCHESTRATE/BAKE} (design/38 §3, the BAKE phase) — materialises the build's {@code
 * pipeline_model_json} into a {@code flow_nodes} DAG, stamps the pipeline-root deadline (issue
 * #244), and enqueues the first {@code ADVANCE} with zero delay.
 */
final class BakeHandler implements QueueMessageHandler {

  private static final Logger LOGGER = Logger.getLogger(BakeHandler.class.getName());

  private final QueueHandlerSupport support;
  private final BiFunction<TitanStores, Long, TitanFlowExecution> executionFactory;

  BakeHandler(@NonNull QueueHandlerSupport support) {
    this(support, TitanFlowExecution::new);
  }

  /** Test seam — inject a mock flow-execution factory. */
  BakeHandler(
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
      LOGGER.log(
          Level.WARNING,
          "[release-flow] QueueProcessor: BAKE missing buildId, task id={0}",
          task.id);
      support.failTaskSafely(daos, task, "Missing buildId in payload");
      return;
    }

    long buildId = QueueHandlerSupport.toBuildId(buildIdObj);

    TransitionCapGuard.Outcome capOutcome = support.recordTransition(daos, buildId, "BAKE");
    if (capOutcome == TransitionCapGuard.Outcome.HARD_HALT) {
      long count = support.capGuard().countFor(buildId, "BAKE");
      String reason = support.capGuard().haltReason("BAKE", count);
      LOGGER.log(
          Level.WARNING,
          "[titan] build {0}: BAKE hard-cap halt at count={1} — fail-closing build",
          new Object[] {buildId, count});
      support.markBuildFailed(daos, buildId, reason);
      support.failTaskSafely(daos, task, reason);
      return;
    }

    if (daos.builds().findById(buildId).isEmpty()) {
      support.failTaskSafely(daos, task, "BAKE: build not found: " + buildId);
      return;
    }

    try {
      TitanFlowExecution.BakeResult result = executionFactory.apply(daos, buildId).bake();
      LOGGER.log(
          Level.INFO, "[titan] QueueProcessor: build {0} {1}", new Object[] {buildId, result});
      // issue #244: stamp the pipeline-root deadline now that we have the synthesized model.
      support.stampPipelineDeadline(daos, buildId);
      // Kick off DAG advancement — the first ADVANCE runs immediately.
      support.enqueueAdvance(daos, buildId, 0);
      support.completeTaskSafely(daos, task);
    } catch (RuntimeException e) {
      // Issue #36: never persist a bare wrapper message (e.g. TitanDataException's
      // "Transaction failed") — carry the root cause's class + message into the build's
      // error_message / failure_summary, and log the FULL stack so an SRE can act on it.
      String reason = "bake failed: " + QueueHandlerSupport.describeWithCause(e);
      LOGGER.log(
          Level.WARNING, e, () -> "[titan] QueueProcessor: bake failed for build " + buildId);
      support.markBuildFailed(daos, buildId, reason);
      support.failTaskSafely(daos, task, reason);
    }
  }
}

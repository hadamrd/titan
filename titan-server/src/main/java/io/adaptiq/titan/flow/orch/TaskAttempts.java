package io.adaptiq.titan.flow.orch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Reads the <em>dispatch generation</em> an {@code EXECUTE_COMMAND} task was enqueued for — the
 * step node's {@code flow_nodes.attempt} at dispatch time, stamped into the task payload by {@link
 * StepDispatcher#stepPayload} (issue #125).
 *
 * <p>The stamp is what lets the reconciler ({@code TitanOrchestrator#reconcileFinishedSteps}) tell
 * a <em>superseded</em> terminal task from the current attempt's task. Before #125, an in-place
 * stage retry ({@code BuildServiceImpl#retryStage}) reset the step node but the previous attempt's
 * archived FAILED task still "belonged" to the node, so the very next ADVANCE folded the stale
 * failure straight back onto the freshly-reset node and the step was never re-executed. With the
 * stamp, a task whose {@code attempt} is older than the node's current {@code attempt} is skipped
 * by the reconciler and ignored by the dispatch leg's lost-dispatch self-heal — which then enqueues
 * a fresh task for the current attempt, exactly once.
 *
 * <p>A payload with no {@code attempt} field (every task enqueued before this change) defaults to
 * {@code 1} — the value every node starts at — so re-delivered archives for never-retried nodes
 * keep folding exactly as before (idempotent-reconciler invariant).
 */
public final class TaskAttempts {

  private static final ObjectMapper JSON = new ObjectMapper();

  private TaskAttempts() {}

  /**
   * The {@code attempt} stamped in an {@code EXECUTE_COMMAND} payload, or {@code 1} when absent /
   * unparsable (pre-#125 tasks, non-step payloads). Never below 1.
   */
  public static int attemptOf(@Nullable String payloadJson) {
    if (payloadJson == null || payloadJson.isBlank()) {
      return 1;
    }
    try {
      JsonNode node = JSON.readTree(payloadJson);
      JsonNode attempt = node.get("attempt");
      if (attempt == null || !attempt.canConvertToInt()) {
        return 1;
      }
      return Math.max(1, attempt.asInt(1));
    } catch (Exception e) {
      return 1;
    }
  }
}

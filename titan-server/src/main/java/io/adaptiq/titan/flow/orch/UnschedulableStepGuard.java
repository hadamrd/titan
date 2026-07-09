package io.adaptiq.titan.flow.orch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.FlowNodeDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.AgentRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fails every {@code EXECUTE_COMMAND} task that is structurally unschedulable: still {@code
 * QUEUED}, still unclaimed, older than {@link #GRACE_SECONDS}, and routed to a queue that no ONLINE
 * worker currently subscribes to (#824). Extracted verbatim from {@code TitanOrchestrator} (#357
 * decomposition pattern; moved out in #125 to keep the orchestrator under the size cap).
 *
 * <p>Without this guard the bug from #824 — a worker that does not poll the {@code agent:} label's
 * queue — manifests as a step parked QUEUED forever and the orchestrator tight-looping on every
 * tick. The fix on the worker side (poll all label queues) covers the common case; this guard
 * covers the configuration error (operator typoed {@code agent: linxu} or there is genuinely no
 * worker for that label) and surfaces the failure where the customer can see it: the step's {@code
 * failure_category=DISPATCH} + a sentence naming the missing queue.
 *
 * <p>Idempotent: a step already moved to {@code FAILED} by an earlier pass is skipped on the CAS; a
 * later pass with a worker that came online never sees the task because the previous pass already
 * terminated it. The DAG's normal failure-policy machinery handles the rest.
 */
public final class UnschedulableStepGuard {

  private static final Logger LOGGER = Logger.getLogger(UnschedulableStepGuard.class.getName());
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Grace window before an unclaimed {@code QUEUED} EXECUTE_COMMAND is declared unschedulable
   * (#824). A real worker claim is sub-second on a healthy rig; 60s comfortably covers a worker
   * restart / heartbeat blip without spuriously failing a step.
   */
  public static final long GRACE_SECONDS = 60L;

  /**
   * Liveness window matching {@code AgentDao#listOnline} elsewhere in the orchestrator — a worker
   * is "subscribed" only if its last heartbeat is this fresh.
   */
  private static final int AGENT_LIVENESS_SECONDS = 30;

  private final TitanStores daos;
  private final long buildId;

  public UnschedulableStepGuard(@NonNull TitanStores daos, long buildId) {
    this.daos = daos;
    this.buildId = buildId;
  }

  /** Run the guard over one advance pass's task snapshot. */
  public void failUnschedulableSteps(
      @NonNull FlowNodeDao flowNodes, @NonNull List<TaskQueueRow> tasks) {
    Instant now = Instant.now();
    Instant cutoff = now.minusSeconds(GRACE_SECONDS);
    List<TaskQueueRow> candidates =
        tasks.stream()
            .filter(t -> "EXECUTE_COMMAND".equals(t.type))
            .filter(t -> "QUEUED".equals(t.status))
            .filter(t -> t.claimedAt == null)
            .filter(t -> t.createdAt != null && t.createdAt.isBefore(cutoff))
            .filter(t -> t.nodeId != null && t.queueName != null)
            .toList();
    if (candidates.isEmpty()) {
      return;
    }
    // One agent-list read per tick (only when a candidate exists) — kept off the hot path.
    List<AgentRow> online = daos.agents().listOnline(AGENT_LIVENESS_SECONDS);
    Set<String> served = QueueSubscriptions.servedQueues(online);
    for (TaskQueueRow t : candidates) {
      String nodeId = t.nodeId;
      String queueName = t.queueName;
      if (nodeId == null || queueName == null) {
        continue; // guaranteed non-null by the candidate filter; re-checked for static analysis
      }
      if (served.contains(queueName)) {
        continue;
      }
      String reason =
          "No worker subscribes to queue '"
              + queueName
              + "' (no ONLINE agent advertises a matching label). "
              + "Either add a label '"
              + queueName
              + "' to a running worker (set TITAN_LABELS) or change the stage's `agent:` to a "
              + "served label.";
      flowNodes.updateFailure(buildId, nodeId, "DISPATCH", reason);
      ObjectNode result = JSON.createObjectNode();
      result.put("exitCode", -1);
      result.put("error", reason);
      int won =
          flowNodes.compareAndSetStatus(
              buildId, nodeId, "QUEUED", "FAILED", null, now, null, result.toString());
      if (won == 1) {
        LOGGER.log(
            Level.WARNING,
            "[titan] build {0}: step {1} failed UNSCHEDULABLE — queue ''{2}'' has no subscriber",
            new Object[] {buildId, nodeId, queueName});
        // Cancel the underlying task so the queue feed / depth gauges stop showing a row that
        // can never run. The cancel is independent of the step CAS — if the cancel races a
        // late worker claim and loses, that worker will run + complete the task normally; the
        // step is already FAILED so the result is folded as a no-op on the next reconcile.
        daos.taskQueue().cancel(t.id);
      }
    }
  }
}

package io.adaptiq.titan.flow.orch;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.AgentRow;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Static helper that derives the set of step queues currently served by at least one ONLINE worker
 * — the "is this queue routable?" predicate behind the unschedulable-step guard (#824).
 *
 * <p>The derivation mirrors {@code WorkerConfig#queueNames} byte-for-byte: each agent serves {@code
 * default}, its own {@code agent_id}, and every comma-separated token in its {@code labels} column.
 * If the controller and worker disagree on this rule, a step routed to a label a worker really does
 * serve will be wrongly failed (or worse, silently parked). Keep both sides in lock-step.
 */
public final class QueueSubscriptions {

  private QueueSubscriptions() {}

  /**
   * The union of step queues served by {@code agents} — the set of {@code queue_name} values a
   * {@code QUEUED} EXECUTE_COMMAND task can ever be claimed from. {@code synthesis} is NOT in this
   * set: it is a controller-managed system queue, not an agent-subscribed step queue, and a step
   * authored with {@code agent: synthesis} is a configuration error the operator must see (we want
   * it to fail fast, not silently match the synthesis pool).
   */
  @NonNull
  public static Set<String> servedQueues(@NonNull Collection<AgentRow> agents) {
    if (agents.isEmpty()) {
      return Collections.emptySet();
    }
    LinkedHashSet<String> queues = new LinkedHashSet<>();
    // Universal fallback — any worker drains "default". Adding it once here means a single
    // ONLINE agent is enough to keep `agent`-less stages schedulable.
    queues.add("default");
    for (AgentRow a : agents) {
      if (a.agentId != null && !a.agentId.isBlank()) {
        queues.add(a.agentId);
      }
      if (a.labels == null || a.labels.isBlank()) {
        continue;
      }
      for (String token : a.labels.split(",")) {
        String t = token.trim();
        if (!t.isEmpty()) {
          queues.add(t);
        }
      }
    }
    return queues;
  }
}

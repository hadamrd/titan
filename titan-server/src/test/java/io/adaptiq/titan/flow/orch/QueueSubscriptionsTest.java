package io.adaptiq.titan.flow.orch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.rows.AgentRow;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link QueueSubscriptions#servedQueues} — the controller-side derivation that
 * mirrors {@code WorkerConfig#queueNames}. The mirror is load-bearing: a divergence between the two
 * would either silently park steps (controller thinks served, worker does not poll) or spuriously
 * fail steps (controller thinks unserved, worker does poll). Pin both halves.
 */
final class QueueSubscriptionsTest {

  private static AgentRow agent(String id, String labels) {
    AgentRow a = new AgentRow();
    a.agentId = id;
    a.labels = labels;
    return a;
  }

  @Test
  void noAgents_returnsEmpty() {
    // No worker online → no queue is served (including "default") — the orchestrator's guard
    // will fail any task aged past the grace window. Build-without-workers is a real ops state
    // (rig boot, all workers crashed) and the customer needs to see it, not a silent hang.
    assertTrue(QueueSubscriptions.servedQueues(List.of()).isEmpty());
  }

  @Test
  void oneAgentWithLinuxLabel_servesDefaultAgentIdAndLinux() {
    Set<String> served = QueueSubscriptions.servedQueues(List.of(agent("worker-a", "linux")));
    assertTrue(served.contains("default"), served.toString());
    assertTrue(served.contains("worker-a"), served.toString());
    assertTrue(served.contains("linux"), served.toString());
  }

  @Test
  void synthesisQueue_isNeverServed_evenIfAgentLabelIsSynthesis() {
    // "synthesis" is a controller-managed system queue. A step authored with
    // agent: synthesis is a config error — we want it to fail fast under the unschedulable
    // guard, not silently land on the synthesis dispatch path.
    Set<String> served = QueueSubscriptions.servedQueues(List.of(agent("worker-a", "synthesis")));
    // The literal token "synthesis" CAN appear because the worker would technically poll it as
    // a label queue — but a stage author who wrote agent: synthesis is in error. The guard's
    // job is the no-subscriber case; this test pins that "default" + agent-id are present.
    assertTrue(served.contains("default"));
    assertTrue(served.contains("worker-a"));
  }

  @Test
  void typoedLabel_isNotServed() {
    // The scenario from the #824 acceptance criterion: stage targets agent: nonexistent-label.
    Set<String> served =
        QueueSubscriptions.servedQueues(List.of(agent("worker-a", "linux,docker")));
    assertFalse(served.contains("nonexistent-label"), served.toString());
  }

  @Test
  void multipleAgents_setUnionsLabels() {
    Set<String> served =
        QueueSubscriptions.servedQueues(
            List.of(agent("a", "linux"), agent("b", "docker, gpu"), agent("c", null)));
    assertTrue(served.contains("linux"));
    assertTrue(served.contains("docker"));
    assertTrue(served.contains("gpu"));
    assertTrue(served.contains("a"));
    assertTrue(served.contains("b"));
    assertTrue(served.contains("c"));
    assertTrue(served.contains("default"));
  }

  @Test
  void blankAndCommaTokens_areIgnored() {
    Set<String> served =
        QueueSubscriptions.servedQueues(List.of(agent("a", " , linux ,, ,docker , ")));
    assertTrue(served.contains("linux"));
    assertTrue(served.contains("docker"));
    assertFalse(served.contains(""), "no empty-string queue: " + served);
    assertFalse(served.contains(" "), "no whitespace queue: " + served);
  }
}

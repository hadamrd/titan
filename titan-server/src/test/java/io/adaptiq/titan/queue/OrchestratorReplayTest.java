package io.adaptiq.titan.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link QueueProcessor#collectUpstream} — the topological-closure helper that
 * drives the {@code REPLAY_FROM_NODE} skip-upstream loop (issue #307).
 *
 * <p>These are pure structural tests over hand-built {@link PipelineModel}s — no database, no
 * Quarkus boot. They lock the contract of "upstream of node X" against the four shapes the replay
 * UX has to handle: linear chains, diamond fan-in, replaying from a step mid-stage, and replaying
 * from a root (zero upstream).
 */
class OrchestratorReplayTest {

  // ── Linear chain: a → b → c, replay from "b" → upstream = {a} ────────────

  @Test
  void collectUpstream_linearChain_returnsAllPredecessors() {
    PipelineModel model = new PipelineModel();
    model.setStages(
        List.of(
            stage("a", "a", List.of()),
            stage("b", "b", List.of("a")),
            stage("c", "c", List.of("b"))));

    Set<String> upstream = QueueProcessor.collectUpstream(model, "b");

    assertTrue(upstream.contains("a"), "a must be upstream of b");
    assertFalse(upstream.contains("b"), "the target itself must not be in upstream");
    assertFalse(upstream.contains("c"), "downstream of b must not be in upstream");
    assertEquals(1, upstream.size());
  }

  // ── Diamond fan-in: a → b, a → c, b → d, c → d. Replay from d → {a,b,c} ──

  @Test
  void collectUpstream_diamondFanIn_returnsBothBranches() {
    PipelineModel model = new PipelineModel();
    model.setStages(
        List.of(
            stage("a", "a", List.of()),
            stage("b", "b", List.of("a")),
            stage("c", "c", List.of("a")),
            stage("d", "d", List.of("b", "c"))));

    Set<String> upstream = QueueProcessor.collectUpstream(model, "d");

    assertTrue(upstream.contains("a"));
    assertTrue(upstream.contains("b"));
    assertTrue(upstream.contains("c"));
    assertFalse(upstream.contains("d"));
    assertEquals(3, upstream.size());
  }

  // ── Replaying from a root: zero upstream ────────────────────────────────

  @Test
  void collectUpstream_rootNode_returnsEmptySet() {
    PipelineModel model = new PipelineModel();
    model.setStages(List.of(stage("a", "a", List.of()), stage("b", "b", List.of("a"))));

    Set<String> upstream = QueueProcessor.collectUpstream(model, "a");
    assertTrue(upstream.isEmpty(), "no upstream of a root node");
  }

  // ── Step inside a stage: earlier steps + the owning stage are upstream ──

  @Test
  void collectUpstream_stepInsideStage_includesEarlierStepsAndOwningStage() {
    // stage "x" has three steps in order: x-s0, x-s1, x-s2. Replay from x-s1.
    // Upstream of x-s1 must include x-s0 (earlier step) and "x" (the owning stage),
    // but NOT x-s2 (later step in same stage).
    StageModel x = new StageModel();
    x.setName("x");
    x.setId("x");
    x.setSteps(List.of(step("x-s0"), step("x-s1"), step("x-s2")));

    PipelineModel model = new PipelineModel();
    model.setStages(List.of(x));

    Set<String> upstream = QueueProcessor.collectUpstream(model, "x-s1");

    assertTrue(upstream.contains("x-s0"), "earlier step in the same stage must be upstream");
    assertTrue(upstream.contains("x"), "the owning stage must be upstream");
    assertFalse(upstream.contains("x-s1"), "the target itself must be excluded");
    assertFalse(upstream.contains("x-s2"), "later step in same stage must NOT be upstream");
  }

  // ── Unknown target: defensive empty-set (validation lives upstream) ────

  @Test
  void collectUpstream_unknownTarget_returnsEmptySet() {
    PipelineModel model = new PipelineModel();
    model.setStages(List.of(stage("a", "a", List.of())));
    assertTrue(QueueProcessor.collectUpstream(model, "ghost").isEmpty());
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static StageModel stage(String name, String id, List<String> dependsOn) {
    StageModel s = new StageModel();
    s.setName(name);
    s.setId(id);
    s.setDependsOn(dependsOn);
    return s;
  }

  private static StepModel step(String id) {
    StepModel s = new StepModel();
    s.setId(id);
    s.setDescriptorId("sh");
    return s;
  }
}

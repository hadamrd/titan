package io.adaptiq.titan.flow.orch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure-helper tests for {@link AncestorClosure} — the DAG ancestry rule that fixes issue #945
 * (blockOnFailure tainting independent sibling chains).
 *
 * <p>The hand-built fixtures map directly to the live regression: <em>checkout fans out to two
 * independent chains, one fails, the other MUST keep its closure clean</em>.
 */
class AncestorClosureTest {

  // ── #945 regression fixture: two independent chains off a shared root ───

  @Test
  void siblingChainsHaveDisjointAncestorClosures() {
    // Root -> A1 -> A2
    // Root -> B1 -> B2
    // The whole point: A1's ancestors do NOT contain B1, and vice-versa.
    StageModel root = stage("root", "Root", List.of());
    StageModel a1 = stage("a1", "A1", List.of("Root"));
    StageModel a2 = stage("a2", "A2", List.of("A1"));
    StageModel b1 = stage("b1", "B1", List.of("Root"));
    StageModel b2 = stage("b2", "B2", List.of("B1"));
    PipelineModel model = model(List.of(root, a1, a2, b1, b2));

    AncestorClosure closure = AncestorClosure.of(model);

    assertEquals(Set.of(), closure.ancestorsOf("root"));
    assertEquals(Set.of("root"), closure.ancestorsOf("a1"));
    assertEquals(Set.of("root", "a1"), closure.ancestorsOf("a2"));
    assertEquals(Set.of("root"), closure.ancestorsOf("b1"));
    assertEquals(Set.of("root", "b1"), closure.ancestorsOf("b2"));

    // The contract the fix relies on:
    assertFalse(closure.ancestorsOf("b1").contains("a1"), "B1 must NOT see A1 as an ancestor");
    assertFalse(closure.ancestorsOf("a1").contains("b1"), "A1 must NOT see B1 as an ancestor");
  }

  @Test
  void diamondMergeNodeSeesBothBranches() {
    // root -> a -> merge
    // root -> b -> merge
    StageModel root = stage("root", "Root", List.of());
    StageModel a = stage("a", "A", List.of("Root"));
    StageModel b = stage("b", "B", List.of("Root"));
    StageModel merge = stage("m", "M", List.of("A", "B"));
    PipelineModel model = model(List.of(root, a, b, merge));

    AncestorClosure closure = AncestorClosure.of(model);

    assertEquals(Set.of("root", "a", "b"), closure.ancestorsOf("m"));
  }

  @Test
  void stepAncestorsIncludeOwningStageAndStageAncestors() {
    StageModel root = stage("root", "Root", List.of());
    StepModel s1 = step("a-s1");
    StepModel s2 = step("a-s2", "a-s1"); // step parentIds reference sibling step
    StageModel a = stage("a", "A", List.of("Root"), List.of(s1, s2));
    PipelineModel model = model(List.of(root, a));

    AncestorClosure closure = AncestorClosure.of(model);

    assertTrue(closure.ancestorsOf("a-s1").contains("a"));
    assertTrue(closure.ancestorsOf("a-s1").contains("root"));
    assertTrue(closure.ancestorsOf("a-s2").contains("a-s1"));
    assertTrue(closure.ancestorsOf("a-s2").contains("a"));
    assertTrue(closure.ancestorsOf("a-s2").contains("root"));
  }

  @Test
  void unknownNodeIdYieldsEmptySetNotCrash() {
    PipelineModel model = model(List.of(stage("root", "Root", List.of())));
    AncestorClosure closure = AncestorClosure.of(model);

    assertEquals(Set.of(), closure.ancestorsOf("does-not-exist"));
  }

  // ── builders ────────────────────────────────────────────────────────────

  private static StageModel stage(String id, String name, List<String> dependsOnNames) {
    return stage(id, name, dependsOnNames, List.of());
  }

  private static StageModel stage(
      String id, String name, List<String> dependsOnNames, List<StepModel> steps) {
    StageModel s = new StageModel();
    s.setId(id);
    s.setName(name);
    s.setDependsOn(new ArrayList<>(dependsOnNames));
    s.setSteps(steps);
    return s;
  }

  private static StepModel step(String id, String... parentIds) {
    StepModel s = new StepModel();
    s.setId(id);
    s.setDescriptorId("sh");
    s.setParentIds(new ArrayList<>(List.of(parentIds)));
    return s;
  }

  private static PipelineModel model(List<StageModel> stages) {
    PipelineModel m = new PipelineModel();
    m.setStages(stages);
    return m;
  }
}

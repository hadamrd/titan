package io.adaptiq.titan.flow.orch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure-helper tests for {@link PipelineNodes} — no DAO, no fake stores. These exercise the static
 * model-traversal helpers extracted from {@code TitanOrchestrator} (#357).
 */
class PipelineNodesTest {

  @Test
  void findStepReturnsTheStepWhenItExists() {
    StepModel s1 = step("step-1");
    StepModel s2 = step("step-2");
    StageModel stage = stage("stage-a", List.of(s1, s2));
    PipelineModel model = model(List.of(stage));

    assertSame(s2, PipelineNodes.findStep(model, "step-2"));
  }

  @Test
  void findStepReturnsNullWhenIdIsAStageNotAStep() {
    PipelineModel model = model(List.of(stage("stage-a", List.of(step("step-1")))));
    assertNull(PipelineNodes.findStep(model, "stage-a"));
    assertNull(PipelineNodes.findStep(model, "does-not-exist"));
  }

  @Test
  void findStageReturnsTheParentStageOfAStep() {
    StageModel a = stage("stage-a", List.of(step("step-a1"), step("step-a2")));
    StageModel b = stage("stage-b", List.of(step("step-b1")));
    PipelineModel model = model(List.of(a, b));

    assertSame(a, PipelineNodes.findStage(model, "step-a2"));
    assertSame(b, PipelineNodes.findStage(model, "step-b1"));
    assertNull(PipelineNodes.findStage(model, "stage-a"), "stage ids are not step ids");
  }

  @Test
  void byIdProducesAMapKeyedOnFlowNodeRowId() {
    FlowNodeRow r1 = row("n1", "PENDING");
    FlowNodeRow r2 = row("n2", "RUNNING");

    Map<String, FlowNodeRow> map = PipelineNodes.byId(List.of(r1, r2));

    assertEquals(2, map.size());
    assertSame(r1, map.get("n1"));
    assertSame(r2, map.get("n2"));
  }

  @Test
  void nameToIdMapsEveryPipelineNodeNameToItsId() {
    StageModel a = stage("stage-a", List.of(step("step-a1")));
    a.setName("Build");
    PipelineModel model = model(List.of(a));

    Map<String, String> nameToId = PipelineNodes.nameToId(model);

    assertNotNull(nameToId.get("Build"));
    assertEquals("stage-a", nameToId.get("Build"));
  }

  private static StepModel step(String id) {
    StepModel s = new StepModel();
    s.setId(id);
    s.setDescriptorId("sh");
    return s;
  }

  private static StageModel stage(String id, List<StepModel> steps) {
    StageModel s = new StageModel();
    s.setId(id);
    s.setName(id);
    s.setSteps(steps);
    return s;
  }

  private static PipelineModel model(List<StageModel> stages) {
    PipelineModel m = new PipelineModel();
    m.setStages(stages);
    return m;
  }

  private static FlowNodeRow row(String id, String status) {
    FlowNodeRow r = new FlowNodeRow();
    r.nodeId = id;
    r.status = status;
    return r;
  }
}

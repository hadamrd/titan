package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.GateModel;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PipelineDagValidator} — design/31 6A. Pure: no DB, no engine. */
class PipelineDagValidatorTest {

  private static StageModel stage(String name, String... dependsOn) {
    StageModel s = new StageModel();
    s.setName(name);
    s.setId(name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-'));
    s.setDependsOn(new java.util.ArrayList<>(List.of(dependsOn)));
    return s;
  }

  @Test
  void aWellFormedDagPasses() {
    PipelineModel m = new PipelineModel();
    m.getStages().add(stage("Build"));
    m.getStages().add(stage("Test", "Build"));
    m.getStages().add(stage("Deploy", "Test"));
    assertDoesNotThrow(() -> PipelineDagValidator.validate(m));
  }

  @Test
  void aDiamondDagPasses() {
    // A -> {B, C} -> D, the classic diamond: a node with two parents that
    // share a common ancestor is a valid DAG, not a cycle.
    PipelineModel m = new PipelineModel();
    m.getStages().add(stage("A"));
    m.getStages().add(stage("B", "A"));
    m.getStages().add(stage("C", "A"));
    m.getStages().add(stage("D", "B", "C"));
    assertDoesNotThrow(() -> PipelineDagValidator.validate(m));
  }

  @Test
  void duplicateNodeNameIsRejected() {
    PipelineModel m = new PipelineModel();
    m.getStages().add(stage("Build"));
    m.getStages().add(stage("Build"));
    assertTrue(violation(m).contains("duplicate node name"));
  }

  @Test
  void duplicateNodeIdIsRejected() {
    // Two distinct names colliding on the same id.
    StageModel a = stage("Deploy");
    StageModel b = stage("Deploy 2");
    b.setId("deploy"); // forced collision
    b.setName("Deploy Two");
    PipelineModel m = new PipelineModel();
    m.getStages().add(a);
    m.getStages().add(b);
    assertTrue(violation(m).contains("duplicate node id"));
  }

  @Test
  void missingDependencyTargetIsRejected() {
    PipelineModel m = new PipelineModel();
    m.getStages().add(stage("Deploy", "Build")); // Build does not exist
    assertTrue(violation(m).contains("not a defined"));
  }

  @Test
  void selfDependencyIsRejected() {
    PipelineModel m = new PipelineModel();
    m.getStages().add(stage("Build", "Build"));
    assertTrue(violation(m).contains("depends on itself"));
  }

  @Test
  void aTwoNodeCycleIsRejected() {
    PipelineModel m = new PipelineModel();
    m.getStages().add(stage("A", "B"));
    m.getStages().add(stage("B", "A"));
    assertTrue(violation(m).contains("cycle"));
  }

  @Test
  void aLongerCycleIsRejected() {
    PipelineModel m = new PipelineModel();
    m.getStages().add(stage("A", "C"));
    m.getStages().add(stage("B", "A"));
    m.getStages().add(stage("C", "B"));
    assertTrue(violation(m).contains("cycle"));
  }

  @Test
  void gatesAndPreconditionsParticipateInDagChecks() {
    PipelineModel m = new PipelineModel();
    m.getStages().add(stage("Build"));
    GateModel gate = new GateModel();
    gate.setName("Approve");
    gate.setId("approve");
    gate.setDependsOn(List.of("Nonexistent"));
    m.getGates().add(gate);
    assertTrue(violation(m).contains("not a defined"));
  }

  @Test
  void duplicateStepIdAcrossStagesIsRejected() {
    PipelineModel m = new PipelineModel();
    StageModel s1 = stage("Build");
    StageModel s2 = stage("Test", "Build");
    io.adaptiq.titan.flow.model.StepModel st1 = new io.adaptiq.titan.flow.model.StepModel();
    st1.setId("dup");
    st1.setDescriptorId("sh");
    io.adaptiq.titan.flow.model.StepModel st2 = new io.adaptiq.titan.flow.model.StepModel();
    st2.setId("dup");
    st2.setDescriptorId("sh");
    s1.getSteps().add(st1);
    s2.getSteps().add(st2);
    m.getStages().add(s1);
    m.getStages().add(s2);
    assertTrue(violation(m).contains("duplicate step id"));
  }

  @Test
  void distinctStepIdsAcrossStagesArePermitted() {
    // The duplicate-step-id guard must fire on a *collision*, not on the
    // first step it sees: distinct ids across stages must validate cleanly.
    PipelineModel m = new PipelineModel();
    StageModel s1 = stage("Build");
    StageModel s2 = stage("Test", "Build");
    io.adaptiq.titan.flow.model.StepModel st1 = new io.adaptiq.titan.flow.model.StepModel();
    st1.setId("compile");
    st1.setDescriptorId("sh");
    io.adaptiq.titan.flow.model.StepModel st2 = new io.adaptiq.titan.flow.model.StepModel();
    st2.setId("package");
    st2.setDescriptorId("sh");
    s1.getSteps().add(st1);
    s2.getSteps().add(st2);
    m.getStages().add(s1);
    m.getStages().add(s2);
    assertDoesNotThrow(() -> PipelineDagValidator.validate(m));
  }

  private static String violation(PipelineModel m) {
    return assertThrows(PipelineParseException.class, () -> PipelineDagValidator.validate(m))
        .getMessage();
  }
}

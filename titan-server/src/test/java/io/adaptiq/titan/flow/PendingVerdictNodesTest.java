package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link TitanOrchestrator#pendingVerdictNodes} — the issue #147 conservative
 * guard on the {@code blockOnFailure} SKIPPED sweep.
 *
 * <p>The invariant: a node whose current-generation {@code EXECUTE_COMMAND} task is terminal but
 * not yet folded into {@code flow_nodes} RAN — it has a real verdict pending, and the sweep must
 * hold it (and its owning stage) out of the SKIPPED fold for the pass. {@code SKIPPED} means "never
 * ran"; folding a ran-and-failed step SKIPPED is the wrong-verdict class the #147 smoke failure
 * signature described.
 */
class PendingVerdictNodesTest {

  /** The smoke fixture shape: install → unit-test (fails) → build. */
  private PipelineModel triageModel() {
    StageModel install = stage("install", "install", List.of(), List.of(step("install-s0")));
    StageModel unitTest =
        stage("unit-test", "unit-test", List.of("install"), List.of(step("unit-test-s0")));
    StageModel build = stage("build", "build", List.of("unit-test"), List.of(step("build-s0")));
    PipelineModel m = new PipelineModel();
    m.setStages(List.of(install, unitTest, build));
    return m;
  }

  @Test
  void terminalUnfoldedTaskHoldsItsStepAndOwningStage() {
    PipelineModel model = triageModel();
    // Peer-controller mid-fold state: unit-test-s0's task FAILED but the node is still QUEUED.
    Map<String, FlowNodeRow> nodes =
        nodes(
            node("install", "SUCCESS", 1),
            node("install-s0", "SUCCESS", 1),
            node("unit-test", "RUNNING", 1),
            node("unit-test-s0", "QUEUED", 1),
            node("build", "PENDING", 1),
            node("build-s0", "PENDING", 1));
    List<TaskQueueRow> tasks = List.of(execTask("unit-test-s0", "FAILED", "{\"attempt\":1}"));

    Set<String> pending = TitanOrchestrator.pendingVerdictNodes(model, tasks, nodes);

    assertEquals(
        Set.of("unit-test-s0", "unit-test"),
        pending,
        "an unfolded FAILED verdict must protect the step AND its owning stage from the sweep");
  }

  @Test
  void foldedVerdictsAndRunningTasksArePendingNothing() {
    PipelineModel model = triageModel();
    Map<String, FlowNodeRow> nodes =
        nodes(
            node("install", "SUCCESS", 1),
            node("install-s0", "SUCCESS", 1),
            node("unit-test", "FAILED", 1),
            node("unit-test-s0", "FAILED", 1),
            node("build", "PENDING", 1),
            node("build-s0", "PENDING", 1));
    // install's verdict is already folded (node terminal); unit-test's is folded too; a
    // PROCESSING task carries no verdict at all.
    List<TaskQueueRow> tasks =
        List.of(
            execTask("install-s0", "COMPLETED", "{\"attempt\":1}"),
            execTask("unit-test-s0", "FAILED", "{\"attempt\":1}"),
            execTask("build-s0", "PROCESSING", "{\"attempt\":1}"));

    assertEquals(
        Set.of(),
        TitanOrchestrator.pendingVerdictNodes(model, tasks, nodes),
        "folded verdicts and in-flight tasks must not hold anything out of the sweep");
  }

  /**
   * #125 interplay: a stage retry resets the node (attempt bumped) — the PREVIOUS generation's
   * archived FAILED task is superseded, carries no pending verdict, and must NOT protect the node
   * (the reset node is legitimately sweepable if an ancestor fails before its re-dispatch).
   */
  @Test
  void supersededGenerationTaskIsNotAPendingVerdict() {
    PipelineModel model = triageModel();
    Map<String, FlowNodeRow> nodes =
        nodes(
            node("install", "SUCCESS", 1),
            node("install-s0", "SUCCESS", 1),
            node("unit-test", "QUEUED", 1),
            node("unit-test-s0", "PENDING", 2), // reset by stage retry — attempt bumped
            node("build", "PENDING", 1),
            node("build-s0", "PENDING", 1));
    List<TaskQueueRow> tasks =
        List.of(execTask("unit-test-s0", "FAILED", "{\"attempt\":1}")); // old generation

    assertEquals(
        Set.of(),
        TitanOrchestrator.pendingVerdictNodes(model, tasks, nodes),
        "a superseded generation's terminal task is not a verdict for the current attempt");
  }

  @Test
  void latestTaskPerNodeWinsChronologically() {
    PipelineModel model = triageModel();
    Map<String, FlowNodeRow> nodes =
        nodes(
            node("install", "SUCCESS", 1),
            node("install-s0", "SUCCESS", 1),
            node("unit-test", "RUNNING", 2),
            node("unit-test-s0", "QUEUED", 2),
            node("build", "PENDING", 1),
            node("build-s0", "PENDING", 1));
    // Chronological order (listByBuild contract): the attempt-1 FAILED task is superseded by the
    // attempt-2 task, which is still PROCESSING — no verdict pending.
    List<TaskQueueRow> tasks =
        List.of(
            execTask("unit-test-s0", "FAILED", "{\"attempt\":1}"),
            execTask("unit-test-s0", "PROCESSING", "{\"attempt\":2}"));

    assertEquals(
        Set.of(),
        TitanOrchestrator.pendingVerdictNodes(model, tasks, nodes),
        "only the LATEST task per node carries the current verdict");
  }

  // ── fixture builders (AncestorClosureTest pattern) ─────────────────────

  private static StageModel stage(
      String id, String name, List<String> dependsOnNames, List<StepModel> steps) {
    StageModel s = new StageModel();
    s.setId(id);
    s.setName(name);
    s.setDependsOn(new ArrayList<>(dependsOnNames));
    s.setSteps(steps);
    return s;
  }

  private static StepModel step(String id) {
    StepModel s = new StepModel();
    s.setId(id);
    s.setDescriptorId("sh");
    return s;
  }

  private static FlowNodeRow node(String nodeId, String status, int attempt) {
    FlowNodeRow n = new FlowNodeRow();
    n.buildId = 1L;
    n.nodeId = nodeId;
    n.nodeType = nodeId.contains("-s") ? "STEP" : "STAGE";
    n.status = status;
    n.attempt = attempt;
    return n;
  }

  private static Map<String, FlowNodeRow> nodes(FlowNodeRow... rows) {
    Map<String, FlowNodeRow> byId = new HashMap<>();
    for (FlowNodeRow n : rows) {
      byId.put(n.nodeId, n);
    }
    return byId;
  }

  private static TaskQueueRow execTask(String nodeId, String status, String payloadJson) {
    TaskQueueRow t = new TaskQueueRow();
    t.type = "EXECUTE_COMMAND";
    t.nodeId = nodeId;
    t.status = status;
    t.payloadJson = payloadJson;
    return t;
  }
}

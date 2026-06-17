package io.adaptiq.titan.flow.orch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the matrix {@code maxParallel} scheduler throttle ({@link
 * MatrixCoordinator#matrixThrottled}) — design/54 §2.5, GH #309. Moved verbatim from {@code
 * OrchestratorMatrixMaxParallelTest} as part of the #357 decomposition.
 */
class MatrixCoordinatorTest {

  private static final String GROUP = "build";

  @Test
  void nonMatrixStageIsNeverThrottled() {
    StageModel s = stageWithStep("plain", null, null);
    PipelineModel model = model(s);
    Map<String, FlowNodeRow> nodes = nodes(node("plain", "PENDING"));

    assertFalse(MatrixCoordinator.matrixThrottled(s, model, nodes));
  }

  @Test
  void matrixStageWithoutMaxParallelIsNeverThrottled() {
    PipelineModel model = model(cell("build-a", GROUP, null), cell("build-b", GROUP, null));
    Map<String, FlowNodeRow> nodes = nodes(node("build-a", "RUNNING"), node("build-b", "PENDING"));

    assertFalse(
        MatrixCoordinator.matrixThrottled(model.getStages().get(1), model, nodes),
        "no maxParallel → no scheduler-side throttle");
  }

  @Test
  void firstCellIsAdmittedWhenNoneRunningYet() {
    PipelineModel model = model(cell("build-a", GROUP, 2), cell("build-b", GROUP, 2));
    Map<String, FlowNodeRow> nodes = nodes(node("build-a", "PENDING"), node("build-b", "PENDING"));

    assertFalse(
        MatrixCoordinator.matrixThrottled(model.getStages().get(0), model, nodes),
        "no siblings RUNNING — admit the first cell");
  }

  @Test
  void cellIsAdmittedWhileBelowLimit() {
    PipelineModel model =
        model(cell("a", GROUP, 2), cell("b", GROUP, 2), cell("c", GROUP, 2), cell("d", GROUP, 2));
    Map<String, FlowNodeRow> nodes =
        nodes(
            node("a", "RUNNING"), node("b", "PENDING"), node("c", "PENDING"), node("d", "PENDING"));

    assertFalse(MatrixCoordinator.matrixThrottled(model.getStages().get(1), model, nodes));
  }

  @Test
  void cellIsDeferredAtTheLimit() {
    PipelineModel model =
        model(cell("a", GROUP, 2), cell("b", GROUP, 2), cell("c", GROUP, 2), cell("d", GROUP, 2));
    Map<String, FlowNodeRow> nodes =
        nodes(
            node("a", "RUNNING"), node("b", "RUNNING"), node("c", "PENDING"), node("d", "PENDING"));

    assertTrue(
        MatrixCoordinator.matrixThrottled(model.getStages().get(2), model, nodes),
        "2 RUNNING in group >= maxParallel=2 → defer c");
    assertTrue(
        MatrixCoordinator.matrixThrottled(model.getStages().get(3), model, nodes),
        "2 RUNNING in group >= maxParallel=2 → defer d");
  }

  @Test
  void terminalCellsDoNotCountTowardTheLimit() {
    PipelineModel model = model(cell("a", GROUP, 2), cell("b", GROUP, 2), cell("c", GROUP, 2));
    Map<String, FlowNodeRow> nodes =
        nodes(node("a", "SUCCESS"), node("b", "RUNNING"), node("c", "PENDING"));

    assertFalse(
        MatrixCoordinator.matrixThrottled(model.getStages().get(2), model, nodes),
        "a finished cell frees its slot — c may transition");
  }

  @Test
  void throttleScopesToTheMatrixGroup() {
    PipelineModel model =
        model(
            cell("a1", "groupA", 1),
            cell("a2", "groupA", 1),
            cell("b1", "groupB", 1),
            cell("b2", "groupB", 1));
    Map<String, FlowNodeRow> nodes =
        nodes(
            node("a1", "RUNNING"),
            node("a2", "PENDING"),
            node("b1", "PENDING"),
            node("b2", "PENDING"));

    assertTrue(
        MatrixCoordinator.matrixThrottled(model.getStages().get(1), model, nodes),
        "groupA at limit — a2 deferred");
    assertFalse(
        MatrixCoordinator.matrixThrottled(model.getStages().get(2), model, nodes),
        "groupB unaffected by groupA's saturation — b1 admitted");
  }

  @Test
  void tickLoopNeverExceedsMaxParallelAndEventuallyFinishesEveryCell() {
    int n = 6;
    int max = 2;
    PipelineModel model = new PipelineModel();
    List<StageModel> stages = new ArrayList<>();
    Map<String, FlowNodeRow> nodes = new HashMap<>();
    for (int i = 0; i < n; i++) {
      String id = "cell-" + i;
      stages.add(cell(id, GROUP, max));
      nodes.put(id, node(id, "PENDING"));
    }
    model.setStages(stages);

    int maxObservedConcurrent = 0;
    int finished = 0;
    int safetyBound = n * 4;
    int ticks = 0;
    while (finished < n && ticks < safetyBound) {
      ticks++;
      for (StageModel s : stages) {
        FlowNodeRow node = nodes.get(s.getId());
        if (!"PENDING".equals(node.status)) {
          continue;
        }
        if (MatrixCoordinator.matrixThrottled(s, model, nodes)) {
          continue;
        }
        node.status = "RUNNING";
      }
      int running = 0;
      for (FlowNodeRow node : nodes.values()) {
        if ("RUNNING".equals(node.status)) {
          running++;
        }
      }
      assertTrue(
          running <= max,
          "tick " + ticks + ": " + running + " cells RUNNING — exceeds maxParallel=" + max);
      maxObservedConcurrent = Math.max(maxObservedConcurrent, running);
      for (FlowNodeRow node : nodes.values()) {
        if ("RUNNING".equals(node.status)) {
          node.status = "SUCCESS";
          finished++;
          break;
        }
      }
    }
    assertEquals(n, finished, "every cell must finish within the bounded tick budget");
    assertEquals(
        max,
        maxObservedConcurrent,
        "the scheduler should keep the slot full: peak concurrency == maxParallel");
  }

  // ── #1127 verdict roll-up ────────────────────────────────────────────────

  @Test
  void aggregateVerdict_allSuccessRollsUpAsSuccess() {
    assertEquals(
        "SUCCESS", MatrixCoordinator.aggregateVerdict(List.of("SUCCESS", "SUCCESS"), false));
    assertEquals("SUCCESS", MatrixCoordinator.aggregateVerdict(List.of("SUCCESS"), true));
  }

  @Test
  void aggregateVerdict_inFlightCellsRollUpAsRunning() {
    assertEquals(
        "RUNNING",
        MatrixCoordinator.aggregateVerdict(List.of("SUCCESS", "RUNNING", "PENDING"), false),
        "any non-terminal cell keeps the group RUNNING when not in fail-fast failure");
  }

  @Test
  void aggregateVerdict_failureWithRunningSiblings_failFastFalse_staysRunning() {
    assertEquals(
        "RUNNING",
        MatrixCoordinator.aggregateVerdict(List.of("FAILED", "RUNNING", "PENDING"), false),
        "fail_fast=false: siblings run to completion before the parent flips terminal");
  }

  @Test
  void aggregateVerdict_failureWithRunningSiblings_failFastTrue_isImmediatelyFailed() {
    assertEquals(
        "FAILED",
        MatrixCoordinator.aggregateVerdict(List.of("FAILED", "RUNNING", "PENDING"), true),
        "fail_fast=true: parent flips FAILED the moment any cell fails");
  }

  @Test
  void aggregateVerdict_anyFailedAfterAllTerminal_rollsUpAsFailed() {
    assertEquals(
        "FAILED",
        MatrixCoordinator.aggregateVerdict(List.of("SUCCESS", "FAILED", "SUCCESS"), false));
    assertEquals(
        "FAILED",
        MatrixCoordinator.aggregateVerdict(List.of("ERROR", "SUCCESS"), false),
        "ERROR is treated as a failure flavour");
  }

  @Test
  void aggregateVerdict_allCellsCancelled_rollsUpAsAborted() {
    assertEquals(
        "ABORTED",
        MatrixCoordinator.aggregateVerdict(List.of("ABORTED", "ABORTED"), false),
        "group aborted before any cell could fail — surface ABORTED, not FAILED");
  }

  @Test
  void aggregateVerdict_mixedAbortAndFailure_preservesFailureCause() {
    // Adversarial: a fail-fast cancel storm — cell-0 failed and cancelled the rest.
    // The parent must surface FAILED (the cause), not ABORTED (the symptom).
    assertEquals(
        "FAILED",
        MatrixCoordinator.aggregateVerdict(List.of("FAILED", "ABORTED", "ABORTED"), true));
  }

  @Test
  void aggregateVerdict_mixedAbortAndSuccess_isFailedNotSuccess() {
    // A user-cancelled cell mid-run means the group did not fully succeed.
    assertEquals(
        "FAILED",
        MatrixCoordinator.aggregateVerdict(List.of("SUCCESS", "ABORTED"), false),
        "partial completion must not be reported as SUCCESS");
  }

  @Test
  void aggregateVerdict_emptyListIsRejected() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> MatrixCoordinator.aggregateVerdict(List.of(), false));
  }

  @Test
  void aggregateVerdict_unknownStatusKeepsGroupRunning() {
    // Adversarial: a worker reports an unrecognised status. Don't crash; treat as in-flight
    // so the next tick re-aggregates after the row normalises.
    assertEquals("RUNNING", MatrixCoordinator.aggregateVerdict(List.of("SUCCESS", "WAT"), false));
  }

  // ── #1213 readMatrixMeta failFast parsing ────────────────────────────────

  @Test
  void readMatrixMeta_parsesFailFastTrue() {
    MatrixCoordinator.MatrixMeta meta =
        MatrixCoordinator.readMatrixMeta(failFastCell("a", GROUP, true));
    assertTrue(meta != null && meta.failFast(), "failFast=true must decode true");
  }

  @Test
  void readMatrixMeta_parsesFailFastFalse() {
    MatrixCoordinator.MatrixMeta meta =
        MatrixCoordinator.readMatrixMeta(failFastCell("a", GROUP, false));
    assertTrue(meta != null && !meta.failFast(), "failFast=false must decode false");
  }

  @Test
  void readMatrixMeta_absentFailFastDecodesFalse() {
    // The `cell` helper stamps no failFast key — absent must decode to false (no-cancel default),
    // preserving the legacy behaviour for a hand-built / pre-#1213 cell.
    MatrixCoordinator.MatrixMeta meta = MatrixCoordinator.readMatrixMeta(cell("a", GROUP, null));
    assertTrue(meta != null && !meta.failFast(), "absent failFast must decode false");
  }

  // ── #1213 failFastAbortSet selection ─────────────────────────────────────

  @Test
  void failFastAbortSet_selectsExactlyTheActiveSiblings() {
    PipelineModel model =
        model(
            failFastCell("a", GROUP, true),
            failFastCell("b", GROUP, true),
            failFastCell("c", GROUP, true));
    Map<String, FlowNodeRow> nodes =
        nodes(node("a", "FAILED"), node("b", "RUNNING"), node("c", "PENDING"));

    assertEquals(
        java.util.Set.of("b", "c"),
        new java.util.HashSet<>(MatrixCoordinator.failFastAbortSet(model, nodes)),
        "the abort set is exactly the active siblings — not the FAILED cell");
  }

  @Test
  void failFastAbortSet_failFastFalse_isEmpty() {
    PipelineModel model = model(failFastCell("a", GROUP, false), failFastCell("b", GROUP, false));
    Map<String, FlowNodeRow> nodes = nodes(node("a", "FAILED"), node("b", "RUNNING"));

    assertTrue(
        MatrixCoordinator.failFastAbortSet(model, nodes).isEmpty(),
        "fail_fast=false: no sibling is ever cancelled");
  }

  @Test
  void failFastAbortSet_noFailureIsEmpty() {
    PipelineModel model = model(failFastCell("a", GROUP, true), failFastCell("b", GROUP, true));
    Map<String, FlowNodeRow> nodes = nodes(node("a", "RUNNING"), node("b", "PENDING"));

    assertTrue(
        MatrixCoordinator.failFastAbortSet(model, nodes).isEmpty(),
        "no cell has failed yet — nothing to cancel");
  }

  @Test
  void failFastAbortSet_scopesToTheFailedGroupOnly() {
    PipelineModel model =
        model(
            failFastCell("a1", "groupA", true),
            failFastCell("a2", "groupA", true),
            failFastCell("b1", "groupB", true),
            failFastCell("b2", "groupB", true));
    Map<String, FlowNodeRow> nodes =
        nodes(
            node("a1", "FAILED"),
            node("a2", "RUNNING"),
            node("b1", "RUNNING"),
            node("b2", "PENDING"));

    assertEquals(
        java.util.Set.of("a2"),
        new java.util.HashSet<>(MatrixCoordinator.failFastAbortSet(model, nodes)),
        "only groupA failed — groupB's running siblings are untouched");
  }

  @Test
  void failFastAbortSet_terminalSiblingsAreNotReAborted() {
    // A sibling already SUCCESS/ABORTED/SKIPPED is terminal — never a cancel target. Two cells
    // failing in the same pass produces no double-abort (both terminal, excluded).
    PipelineModel model =
        model(
            failFastCell("a", GROUP, true),
            failFastCell("b", GROUP, true),
            failFastCell("c", GROUP, true),
            failFastCell("d", GROUP, true));
    Map<String, FlowNodeRow> nodes =
        nodes(node("a", "FAILED"), node("b", "FAILED"), node("c", "SUCCESS"), node("d", "RUNNING"));

    assertEquals(
        java.util.Set.of("d"),
        new java.util.HashSet<>(MatrixCoordinator.failFastAbortSet(model, nodes)),
        "only the still-active cell d is aborted; failed/terminal cells are excluded");
  }

  private static StageModel failFastCell(String id, String matrixGroup, boolean failFast) {
    StageModel s = new StageModel();
    s.setName(id);
    s.setId(id);
    StepModel step = new StepModel();
    step.setId(id + "-s0");
    step.setDescriptorId("sh");
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("matrixGroup", matrixGroup);
    meta.put("failFast", failFast);
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("__matrix", meta);
    step.setArguments(args);
    s.setSteps(List.of(step));
    return s;
  }

  private static PipelineModel model(StageModel... s) {
    PipelineModel m = new PipelineModel();
    m.setStages(List.of(s));
    return m;
  }

  private static StageModel stageWithStep(String id, String matrixGroup, Integer maxParallel) {
    StageModel s = new StageModel();
    s.setName(id);
    s.setId(id);
    StepModel step = new StepModel();
    step.setId(id + "-s0");
    step.setDescriptorId("sh");
    Map<String, Object> args = new LinkedHashMap<>();
    if (matrixGroup != null) {
      Map<String, Object> meta = new LinkedHashMap<>();
      meta.put("matrixGroup", matrixGroup);
      if (maxParallel != null) {
        meta.put("maxParallel", maxParallel);
      }
      args.put("__matrix", meta);
    }
    step.setArguments(args);
    s.setSteps(List.of(step));
    return s;
  }

  private static StageModel cell(String id, String matrixGroup, Integer maxParallel) {
    return stageWithStep(id, matrixGroup, maxParallel);
  }

  private static FlowNodeRow node(String id, String status) {
    FlowNodeRow row = new FlowNodeRow();
    row.nodeId = id;
    row.nodeType = "STAGE";
    row.status = status;
    return row;
  }

  private static Map<String, FlowNodeRow> nodes(FlowNodeRow... rows) {
    Map<String, FlowNodeRow> m = new HashMap<>();
    for (FlowNodeRow r : rows) {
      m.put(r.nodeId, r);
    }
    return m;
  }
}

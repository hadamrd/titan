package io.adaptiq.titan.flow.orch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.adaptiq.titan.flow.model.PreviousOutcome;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StructuredWhenGate#previousOutcomeOf} (GH #1093) — the prior-step-outcome
 * derivation a {@code when.previous:} guard is evaluated against.
 *
 * <p>The headline regression (GH #1093 test matrix): a {@code SKIPPED} parent is a terminal, clean
 * no-op and must <strong>not</strong> be observed as a failure by a downstream {@code
 * when.previous: failure} step.
 */
class StructuredWhenGateTest {

  private static FlowNodeRow node(String id, String status) {
    FlowNodeRow r = new FlowNodeRow();
    r.nodeId = id;
    r.status = status;
    return r;
  }

  private static StepModel stepWithParents(String... parentIds) {
    StepModel s = new StepModel();
    s.setId("step-x");
    s.setParentIds(List.of(parentIds));
    return s;
  }

  @Test
  void parentSucceededYieldsSuccess() {
    Map<String, FlowNodeRow> nodes = new LinkedHashMap<>();
    nodes.put("p1", node("p1", "SUCCESS"));
    assertEquals(
        PreviousOutcome.SUCCESS,
        StructuredWhenGate.previousOutcomeOf(stepWithParents("p1"), nodes));
  }

  @Test
  void parentFailedYieldsFailure() {
    Map<String, FlowNodeRow> nodes = new LinkedHashMap<>();
    nodes.put("p1", node("p1", "SUCCESS"));
    nodes.put("p2", node("p2", "FAILED"));
    assertEquals(
        PreviousOutcome.FAILURE,
        StructuredWhenGate.previousOutcomeOf(stepWithParents("p1", "p2"), nodes));
  }

  @Test
  void skippedParentIsNotAFailure() {
    // The regression: a SKIPPED parent must read as SUCCESS, not FAILURE — a downstream
    // when.previous: failure step must NOT treat an upstream skip as a failure.
    Map<String, FlowNodeRow> nodes = new LinkedHashMap<>();
    nodes.put("p1", node("p1", "SKIPPED"));
    assertEquals(
        PreviousOutcome.SUCCESS,
        StructuredWhenGate.previousOutcomeOf(stepWithParents("p1"), nodes));
  }

  @Test
  void unstableParentYieldsFailure() {
    // UNSTABLE is a ran-but-degraded terminal outcome (e.g. test failures that didn't abort the
    // step). A when.previous: failure cleanup/notify step is exactly what users expect to fire
    // here.
    Map<String, FlowNodeRow> nodes = new LinkedHashMap<>();
    nodes.put("p1", node("p1", "UNSTABLE"));
    assertEquals(
        PreviousOutcome.FAILURE,
        StructuredWhenGate.previousOutcomeOf(stepWithParents("p1"), nodes));
  }

  @Test
  void abortedParentIsNotAFailure() {
    // An ABORTED parent means the whole build is being torn down (user/system abort) — the guarded
    // step is itself being abandoned, not reacting to a peer's error. Must NOT read as FAILURE.
    Map<String, FlowNodeRow> nodes = new LinkedHashMap<>();
    nodes.put("p1", node("p1", "ABORTED"));
    assertEquals(
        PreviousOutcome.SUCCESS,
        StructuredWhenGate.previousOutcomeOf(stepWithParents("p1"), nodes));
  }

  @Test
  void nullParentStatusIsIgnored() {
    // Adversarial: a parent node row with a null status (mid-write race) must not NPE or be a
    // failure.
    Map<String, FlowNodeRow> nodes = new LinkedHashMap<>();
    nodes.put("p1", node("p1", null));
    assertEquals(
        PreviousOutcome.SUCCESS,
        StructuredWhenGate.previousOutcomeOf(stepWithParents("p1"), nodes));
  }

  @Test
  void noParentsYieldsSuccess() {
    assertEquals(
        PreviousOutcome.SUCCESS,
        StructuredWhenGate.previousOutcomeOf(stepWithParents(), new LinkedHashMap<>()));
  }

  @Test
  void unknownParentIdIsIgnored() {
    // Adversarial: a parent id with no matching node row (defensive) — not a failure.
    assertEquals(
        PreviousOutcome.SUCCESS,
        StructuredWhenGate.previousOutcomeOf(stepWithParents("ghost"), new LinkedHashMap<>()));
  }
}

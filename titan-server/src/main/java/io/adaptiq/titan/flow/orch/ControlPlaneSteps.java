package io.adaptiq.titan.flow.orch;

import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.ApprovalResolver;
import io.adaptiq.titan.flow.SetBuildNameResolver;
import java.util.Set;

/**
 * Closed registry of <em>control-plane</em> pipeline step descriptor ids — steps the orchestrator
 * resolves itself, on the controller, with NO worker dispatch. The worker has no handler for these
 * keywords; if any of them ever leaks into {@code task_queue} the build sits QUEUED forever (see GH
 * #805).
 *
 * <p>This is the single source of truth for "is this step controller-native?" — the orchestrator
 * consults {@link #isControlPlane} once in its dispatch path; the per-resolver {@code isXxx}
 * helpers (e.g. {@link ApprovalResolver#isApproval}) remain for caller-clarity but resolve to a
 * subset of the same membership test.
 *
 * <p>Adding a new controller-native step (e.g. a future {@code httpProbe:} resolver) means: add the
 * descriptor id to {@link #IDS}, then add the handler branch in {@code
 * TitanOrchestrator#advanceSteps}. The IT {@code ControlPlaneStepDispatchIT} pins the contract that
 * none of these descriptors ever produce a {@code task_queue} row.
 *
 * <p>Note on the {@code WaitResolver.WAIT_STEPS} set: that set is internal to the resolver and
 * carries its own grammar (sleep/waitUntil duration parsing). We list the same two ids here so this
 * registry is exhaustive — they remain operationally identical (no worker dispatch).
 */
public final class ControlPlaneSteps {

  /**
   * Every descriptor id whose step is resolved on the controller and MUST NOT be enqueued onto
   * {@code task_queue}. Closed Set — adding a new controller-native step is an explicit code edit
   * here plus the matching handler in the orchestrator.
   */
  public static final Set<String> IDS =
      Set.of(
          "sleep", "waitUntil", ApprovalResolver.DESCRIPTOR_ID, SetBuildNameResolver.DESCRIPTOR_ID);

  private ControlPlaneSteps() {}

  /**
   * Whether {@code descriptorId} is a controller-native step that MUST NOT be enqueued onto {@code
   * task_queue}. Null-safe — an unknown / parser-produced null returns {@code false}.
   */
  public static boolean isControlPlane(@Nullable String descriptorId) {
    return descriptorId != null && IDS.contains(descriptorId);
  }
}

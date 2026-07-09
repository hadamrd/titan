package io.adaptiq.titan.flow.orch;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.util.Collection;
import java.util.Set;

/**
 * Detects whether a build is "parked at a human" — i.e. has at least one parked flow node whose
 * paired {@code approvals} row is still {@code PENDING}. The signal feeds {@code
 * TitanOrchestrator.AdvanceResult#parked()}, which {@code QueueProcessor#handleAdvance} uses to
 * stop re-ticking the build (resume happens event-driven via {@code ApprovalService#decide}).
 *
 * <p>Two park shapes exist (#953 unified them onto the {@code titan.approvals} surface), and both
 * must count (issue #68):
 *
 * <ul>
 *   <li>an {@code approval:} <em>step</em> parks its node {@code SLEEPING};
 *   <li>a {@code gate:} <em>stage</em> node parks {@code RUNNING} ("awaiting approval" — {@code
 *       GateEvaluator.evaluateGates} never moves it further itself).
 * </ul>
 *
 * <p>Before #68 only the SLEEPING shape was recognised — a gate-parked build was re-armed on the
 * ADVANCE backoff cadence forever (a fresh 300s-delayed task_queue row per cycle, observed piling
 * up on the local rig). Resume for both shapes is event-driven: {@code ApprovalService.decide},
 * {@code GateService.decide} and the timeout sweep each enqueue a fresh ADVANCE.
 *
 * <p>Extracted out of {@code TitanOrchestrator} per design/59 (orchestrator decomposition) — the
 * class was breaching the 1000-line cap. Pure function; no state.
 */
public final class ApprovalParkDetector {

  /** Node statuses a human-approval park can present as (step: SLEEPING; gate stage: RUNNING). */
  private static final Set<String> PARKABLE = Set.of("SLEEPING", "RUNNING");

  private ApprovalParkDetector() {}

  /** True if any SLEEPING/RUNNING node on this build has a {@code PENDING} approval row. */
  public static boolean hasPendingApprovalPark(
      @NonNull TitanStores daos, long buildId, @NonNull Collection<FlowNodeRow> nodes) {
    return nodes.stream()
        .filter(n -> PARKABLE.contains(n.status))
        .anyMatch(
            n ->
                daos.approvals()
                    .findLatestForNode(buildId, n.nodeId)
                    .filter(r -> "PENDING".equals(r.status))
                    .isPresent());
  }
}

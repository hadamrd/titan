package io.adaptiq.titan.flow.orch;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.util.Collection;

/**
 * Detects whether a build is "parked at a human" — i.e. has at least one {@code SLEEPING} flow node
 * whose paired {@code approvals} row is still {@code PENDING}. The signal feeds {@code
 * TitanOrchestrator.AdvanceResult#parked()}, which {@code QueueProcessor#handleAdvance} uses to
 * stop re-ticking the build (resume happens event-driven via {@code ApprovalService#decide}).
 *
 * <p>Extracted out of {@code TitanOrchestrator} per design/59 (orchestrator decomposition) — the
 * class was breaching the 1000-line cap. Pure function; no state.
 */
public final class ApprovalParkDetector {

  private ApprovalParkDetector() {}

  /** True if any {@code SLEEPING} node on this build has a {@code PENDING} approval row. */
  public static boolean hasPendingApprovalPark(
      @NonNull TitanStores daos, long buildId, @NonNull Collection<FlowNodeRow> nodes) {
    return nodes.stream()
        .filter(n -> "SLEEPING".equals(n.status))
        .anyMatch(
            n ->
                daos.approvals()
                    .findLatestForNode(buildId, n.nodeId)
                    .filter(r -> "PENDING".equals(r.status))
                    .isPresent());
  }
}

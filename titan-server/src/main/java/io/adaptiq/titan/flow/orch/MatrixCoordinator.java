package io.adaptiq.titan.flow.orch;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Matrix-cell scheduling decisions extracted from {@code TitanOrchestrator} (#357). Pure
 * model-traversal — no DAO, no state — so all methods are static. The throttle check is per-tick
 * over the in-memory snapshot the orchestrator already loaded (design/54 §2.5, #309).
 */
public final class MatrixCoordinator {

  private MatrixCoordinator() {}

  /** Cell-stage statuses that are terminal — a cell in any of these is never a cancel target. */
  private static final Set<String> CELL_TERMINAL =
      Set.of("SUCCESS", "FAILED", "ABORTED", "SKIPPED");

  /**
   * Whether a matrix-cell stage's PENDING → RUNNING transition must be deferred this tick because
   * its fan-out group already has {@code maxParallel} cells RUNNING (design/54 §2.5, #309).
   *
   * <p>Reads the cell's {@code __matrix} metadata from its first step's arguments (the {@link
   * io.adaptiq.titan.flow.parser.MatrixScope} stamps every cell-step with {@code matrixGroup} +
   * {@code maxParallel?}). Counts sibling cells in the same group whose stage node is RUNNING in
   * the just-loaded {@code nodes} snapshot. Returns {@code false} for any stage that is not a
   * matrix cell, or whose matrix has no {@code maxParallel}.
   *
   * <p>This is an <em>in-memory</em>, per-tick check — no DAO call, no migration. The check is
   * idempotent: a deferred cell stays PENDING and the next tick re-evaluates as a finished cell
   * frees a slot.
   */
  public static boolean matrixThrottled(
      @NonNull StageModel stage,
      @NonNull PipelineModel model,
      @NonNull Map<String, FlowNodeRow> nodes) {
    MatrixMeta meta = readMatrixMeta(stage);
    if (meta == null || meta.maxParallel <= 0) {
      return false;
    }
    int runningInGroup = 0;
    for (StageModel other : model.getStages()) {
      if (other.getId().equals(stage.getId())) {
        continue;
      }
      MatrixMeta otherMeta = readMatrixMeta(other);
      if (otherMeta == null || !meta.matrixGroup.equals(otherMeta.matrixGroup)) {
        continue;
      }
      FlowNodeRow otherNode = nodes.get(other.getId());
      if (otherNode != null && "RUNNING".equals(otherNode.status)) {
        runningInGroup++;
      }
    }
    return runningInGroup >= meta.maxParallel;
  }

  /**
   * Read a matrix cell stage's {@code __matrix} metadata from its first step's arguments, or {@code
   * null} if the stage is not a matrix cell. {@link
   * io.adaptiq.titan.flow.parser.MatrixScope#cloneStep} writes {@code matrixGroup} + {@code
   * failFast} on every cell step ({@link io.adaptiq.titan.flow.parser.EachScope} stamps the same
   * keys); {@code maxParallel} is present iff the fan-out declared one. An absent {@code failFast}
   * key decodes to {@code false} so a hand-built / legacy cell without the flag preserves the
   * no-cancel behaviour.
   */
  @Nullable
  public static MatrixMeta readMatrixMeta(@NonNull StageModel stage) {
    List<StepModel> steps = stage.getSteps();
    if (steps == null || steps.isEmpty()) {
      return null;
    }
    Map<String, Object> args = steps.get(0).getArguments();
    if (args == null) {
      return null;
    }
    Object raw = args.get("__matrix");
    if (!(raw instanceof Map<?, ?> meta)) {
      return null;
    }
    Object group = meta.get("matrixGroup");
    if (!(group instanceof String groupId) || groupId.isBlank()) {
      return null;
    }
    Object mp = meta.get("maxParallel");
    int maxParallel = (mp instanceof Number n) ? n.intValue() : 0;
    Object ff = meta.get("failFast");
    boolean failFast = ff instanceof Boolean b && b;
    return new MatrixMeta(groupId, maxParallel, failFast);
  }

  /**
   * A matrix cell's scheduler-relevant metadata — the fan-out group, its throttle, and whether the
   * group cancels in-flight siblings on the first cell failure (#1213).
   */
  record MatrixMeta(@NonNull String matrixGroup, int maxParallel, boolean failFast) {}

  // ── fail-fast sibling cancellation (#1213) ───────────────────────────────

  /**
   * The set of still-active sibling cell stage ids that a {@code failFast} fan-out group must
   * transition to {@code ABORTED} because one of its cells has reached terminal {@code FAILED}
   * (#1213).
   *
   * <p>Pure selection — no DAO, no CAS, no events: the caller ({@code
   * TitanOrchestrator.finishIfDone}) does the state transition and feeds the returned ids into the
   * ancestor-closure SKIP sweep so each aborted cell's descendants are SKIPPED. The selection is:
   *
   * <ul>
   *   <li>Find every {@code matrixGroup} that declares {@code failFast=true} AND has at least one
   *       cell whose stage node is terminally {@code FAILED}.
   *   <li>Return every <em>non-terminal</em> (PENDING / RUNNING / queued) sibling cell stage in
   *       those groups. The originally-failed cell is terminal, so it is never in the set — its
   *       cause is preserved ({@code FAILED}, not {@code ABORTED}). Cells in groups that did not
   *       fail, and groups with {@code failFast=false}, are never touched.
   * </ul>
   *
   * <p>Idempotent across reconcile passes: once the siblings are ABORTED they are terminal and drop
   * out of the next pass's selection. Two cells failing in the same pass produces no double-abort —
   * both are terminal and excluded; their shared siblings appear once.
   */
  @NonNull
  public static Set<String> failFastAbortSet(
      @NonNull PipelineModel model, @NonNull Map<String, FlowNodeRow> nodes) {
    Set<String> trippedGroups = new HashSet<>();
    for (StageModel stage : model.getStages()) {
      MatrixMeta meta = readMatrixMeta(stage);
      if (meta == null || !meta.failFast()) {
        continue;
      }
      FlowNodeRow node = nodes.get(stage.getId());
      if (node != null && "FAILED".equals(node.status)) {
        trippedGroups.add(meta.matrixGroup());
      }
    }
    if (trippedGroups.isEmpty()) {
      return Set.of();
    }
    Set<String> abort = new LinkedHashSet<>();
    for (StageModel stage : model.getStages()) {
      MatrixMeta meta = readMatrixMeta(stage);
      if (meta == null || !trippedGroups.contains(meta.matrixGroup())) {
        continue;
      }
      FlowNodeRow node = nodes.get(stage.getId());
      if (node != null && !CELL_TERMINAL.contains(node.status)) {
        abort.add(stage.getId());
      }
    }
    return abort;
  }

  // ── verdict roll-up (#1127) ──────────────────────────────────────────────

  /**
   * Aggregate verdict for a matrix fan-out group, given the {@link FlowNodeRow#status} of every
   * cell stage in that group (design/54 §4, #1127).
   *
   * <p>Pure state-machine — no DAO, no events. The UI calls this to render the parent-step verdict;
   * the orchestrator calls it when deciding whether to mark a synthetic {@code matrixGroup}-level
   * node terminal.
   *
   * <p>Semantics:
   *
   * <ul>
   *   <li>{@code RUNNING} if any cell is still {@code PENDING} or {@code RUNNING} and the group is
   *       not in fail-fast cancellation.
   *   <li>{@code FAILED} once any cell is {@code FAILED}/{@code ERROR}/{@code FAILURE} and every
   *       other cell has reached a terminal state (or, with {@code failFast=true}, immediately
   *       after the first failure).
   *   <li>{@code ABORTED} only if every cell is {@code ABORTED} (i.e. the group was cancelled
   *       before any cell could fail). A failed-and-then-cancelled group rolls up as {@code
   *       FAILED}, NOT {@code ABORTED} — preserving the cause.
   *   <li>{@code SUCCESS} iff every cell ended {@code SUCCESS}.
   * </ul>
   *
   * <p>Adversarial cases covered by {@code MatrixCoordinatorTest.aggregateVerdict_*}: empty list
   * (illegal), unknown status (treated as non-terminal), fail-fast short-circuit,
   * cancel-while-failing precedence.
   */
  @NonNull
  public static String aggregateVerdict(@NonNull List<String> cellStatuses, boolean failFast) {
    if (cellStatuses.isEmpty()) {
      throw new IllegalArgumentException("matrix verdict: cell status list must be non-empty");
    }
    boolean anyFailed = false;
    boolean anyRunning = false;
    boolean allAborted = true;
    boolean allSuccess = true;
    for (String s : cellStatuses) {
      String status = s == null ? "" : s;
      switch (status) {
        case "FAILED", "FAILURE", "ERROR" -> {
          anyFailed = true;
          allAborted = false;
          allSuccess = false;
        }
        case "ABORTED" -> {
          allSuccess = false;
        }
        case "SUCCESS" -> {
          allAborted = false;
        }
        default -> {
          // PENDING / RUNNING / unknown — still in flight.
          anyRunning = true;
          allAborted = false;
          allSuccess = false;
        }
      }
    }
    if (anyFailed && failFast) {
      // Fail-fast: failure is sticky the moment we see it, even if siblings are still running.
      return "FAILED";
    }
    if (anyRunning) {
      return "RUNNING";
    }
    if (anyFailed) {
      return "FAILED";
    }
    if (allAborted) {
      return "ABORTED";
    }
    if (allSuccess) {
      return "SUCCESS";
    }
    // Mixed terminal without a failure (e.g. SUCCESS + ABORTED) — surface as FAILED so the
    // operator notices the partial completion.
    return "FAILED";
  }
}

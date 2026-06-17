package io.adaptiq.titan.flow.orch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.ApprovalResolver;
import io.adaptiq.titan.flow.ApprovalService;
import io.adaptiq.titan.flow.TemplateResolver;
import io.adaptiq.titan.flow.expr.ExpressionEvaluator;
import io.adaptiq.titan.flow.model.GateModel;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.PreconditionModel;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.store.FlowNodeDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.ApprovalRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.timer.TimerService;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single-purpose collaborator that evaluates a build's <strong>human-gate</strong> control-plane
 * nodes — manual {@code gate:} stage nodes, {@code precondition:} CEL guards, and {@code approval:}
 * parked steps — extracted from {@link io.adaptiq.titan.flow.TitanOrchestrator} (design 67 step 3).
 *
 * <p>The class owns the three pieces of gate logic that used to live as private methods on the
 * orchestrator: {@link #evaluateGates}, {@link #evaluatePreconditions}, and {@link
 * #evaluateApproval}. The orchestrator now delegates and stays a thin reconciler loop.
 *
 * <h2>The approval-row idempotence fix (post-mortem of build 22, 2026-05-26)</h2>
 *
 * <p>{@link #evaluateApproval} is the single decision point for what to do with a flow node whose
 * step is {@code approval:}. The old code (an "is there a PENDING row?" check inline in {@code
 * advanceSteps}) had a hole: after a user REJECTED an approval and the build subsequently
 * re-encountered the same {@code (build_id, flow_node_id)} pair (e.g. a replay flow, or a race
 * where the SLEEPING→FAILED CAS lost but the row was still walked), the orchestrator's next pass
 * inserted a <em>new</em> PENDING row — and the user saw the approval reappear in {@code
 * /approvals} after rejecting it. Build 22 on titan.test accumulated <strong>6 REJECTED rows + 1
 * dangling PENDING</strong> for the same node before the operator force-aborted.
 *
 * <p>{@link #evaluateApproval} closes that hole by treating {@link
 * io.adaptiq.titan.store.ApprovalsDao#findLatestForNode} as the source of truth and mapping each
 * status into a typed {@link ApprovalDecision}:
 *
 * <ul>
 *   <li>{@code APPROVED} row → {@link ApprovalDecision#ALLOW} (caller folds the node to SUCCESS).
 *   <li>{@code REJECTED} or {@code TIMED_OUT} row → {@link ApprovalDecision#SKIP} (caller fails the
 *       node and lets the surrounding {@code blockOnFailure} cascade drain the build).
 *   <li>{@code PENDING} row → {@link ApprovalDecision#PARK}, no new row inserted (idempotent
 *       re-tick).
 *   <li>No row → insert one PENDING row + arm the timeout timer, then return {@link
 *       ApprovalDecision#PARK}.
 * </ul>
 *
 * <p>The fix is purely additive — the existing SLEEPING-side resume path in {@code
 * TitanOrchestrator} keeps working unchanged; {@code evaluateApproval} is the new authority on the
 * PENDING-side entry into a parked node, and refuses to create a second row when a terminal one
 * already exists.
 */
public final class GateEvaluator {

  private static final Logger LOGGER = Logger.getLogger(GateEvaluator.class.getName());
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Set<String> NODE_TERMINAL =
      Set.of("SUCCESS", "FAILED", "ABORTED", "SKIPPED");
  private static final Set<String> DEP_SATISFIED = Set.of("SUCCESS", "SKIPPED");

  /**
   * Typed outcome of {@link #evaluateApproval}. The caller (the orchestrator's step walk) uses this
   * to decide whether to keep walking the stage's step list (ALLOW), fail the stage (SKIP), or stop
   * and let the build park (PARK).
   */
  public enum ApprovalDecision {
    /** A terminal {@code APPROVED} row exists; the node has been folded to {@code SUCCESS}. */
    ALLOW,
    /**
     * A terminal {@code REJECTED} or {@code TIMED_OUT} row exists; the node has been folded to
     * {@code FAILED} and the stage will fail-cascade per the pipeline's {@code failurePolicy}.
     */
    SKIP,
    /**
     * Either a {@code PENDING} row exists (idempotent re-tick) or a fresh PENDING row was just
     * inserted; the flow node is parked SLEEPING and the build should not be re-ticked until an
     * external event (decide / sweep) un-parks it.
     */
    PARK
  }

  private final TitanStores daos;
  private final long buildId;
  private final TimerService timerService;

  public GateEvaluator(
      @NonNull TitanStores daos, long buildId, @NonNull TimerService timerService) {
    this.daos = daos;
    this.buildId = buildId;
    this.timerService = timerService;
  }

  // ── gates ─────────────────────────────────────────────────────────────────

  /**
   * Advance the build's manual-approval gates. A gate whose {@code dependsOn} are all satisfied is
   * opened — moved to {@code RUNNING} ("awaiting approval"). It stays {@code RUNNING}, holding the
   * build non-terminal, until {@code GateService} records an approver's decision; the orchestrator
   * never resolves it itself. A {@code requiresApproval: false} gate is unattended — it auto-passes
   * to {@code SUCCESS} the moment it is reached.
   */
  public void evaluateGates(
      @NonNull FlowNodeDao flowNodes,
      @NonNull PipelineModel model,
      @NonNull Map<String, String> nameToId,
      @NonNull Map<String, FlowNodeRow> nodes) {
    for (GateModel gate : model.getGates()) {
      FlowNodeRow gn = nodes.get(gate.getId());
      if (gn == null || NODE_TERMINAL.contains(gn.status)) {
        continue;
      }
      if (!depsSatisfied(gate.getDependsOn(), nameToId, flowNodes)) {
        continue; // a dependency is unfinished — the gate is not yet reachable
      }
      if (!"RUNNING".equals(gn.status)) {
        Instant now = Instant.now();
        int won =
            flowNodes.compareAndSetStatus(
                buildId, gate.getId(), gn.status, "RUNNING", now, null, null, null);
        if (won == 1) {
          LOGGER.log(
              Level.INFO,
              "[titan] build {0}: gate ''{1}'' awaiting approval",
              new Object[] {buildId, gate.getName()});
          // Issue #953: unify on the titan.approvals row as the single approval surface — the
          // UI inbox and POST /api/v1/approvals/{id}/{approve|reject} only see rows in that
          // table. ApprovalService.park is idempotent on (build, node) so a re-tick after a
          // controller restart never inserts a duplicate.
          if (gate.isRequiresApproval()) {
            String prompt = gate.getName().isEmpty() ? gate.getId() : gate.getName();
            ApprovalResolver.ParsedApproval parsed =
                new ApprovalResolver.ParsedApproval(
                    prompt, gate.getApprovers(), ApprovalResolver.DEFAULT_TIMEOUT);
            ApprovalService.park(daos, buildId, gate.getId(), parsed, now);
          }
        }
      }
      if (!gate.isRequiresApproval()) {
        // an unattended gate is a pure DAG checkpoint — it passes once reached
        flowNodes.compareAndSetStatus(
            buildId,
            gate.getId(),
            "RUNNING",
            "SUCCESS",
            null,
            Instant.now(),
            null,
            "{\"gate\":\"auto\",\"requiresApproval\":false}");
      }
    }
  }

  // ── preconditions ─────────────────────────────────────────────────────────

  /**
   * Advance the build's precondition nodes. A precondition whose {@code dependsOn} are satisfied
   * has its CEL-subset expression evaluated against the run-time output context built from
   * published step outputs. {@code true} passes the node {@code SUCCESS}; {@code false} — or any
   * evaluation error — fails it {@code FAILED}, which blocks the DAG.
   */
  public void evaluatePreconditions(
      @NonNull FlowNodeDao flowNodes,
      @NonNull PipelineModel model,
      @NonNull Map<String, String> nameToId,
      @NonNull Map<String, FlowNodeRow> nodes,
      @NonNull Map<String, Object> ctx) {
    for (PreconditionModel pre : model.getPreconditions()) {
      FlowNodeRow pn = nodes.get(pre.getId());
      if (pn == null || NODE_TERMINAL.contains(pn.status)) {
        continue;
      }
      if (!depsSatisfied(pre.getDependsOn(), nameToId, flowNodes)) {
        continue;
      }
      boolean pass;
      String detail;
      try {
        pass = ExpressionEvaluator.evaluateBoolean(pre.getExpression(), ctx);
        detail = "precondition evaluated " + pass;
      } catch (RuntimeException e) {
        pass = false;
        detail = "precondition failed to evaluate: " + e.getMessage();
        LOGGER.log(
            Level.WARNING,
            "[titan] build {0}: precondition ''{1}'' — {2}",
            new Object[] {buildId, pre.getName(), detail});
      }
      ObjectNode result = JSON.createObjectNode();
      result.put("precondition", pass);
      result.put("detail", detail);
      if (!pass) {
        flowNodes.updateFailure(
            buildId,
            pre.getId(),
            "PRECONDITION",
            "Precondition '"
                + pre.getName()
                + "' was not met: "
                + detail
                + " — the build cannot proceed past this checkpoint.");
      }
      int won =
          flowNodes.compareAndSetStatus(
              buildId,
              pre.getId(),
              pn.status,
              pass ? "SUCCESS" : "FAILED",
              null,
              Instant.now(),
              null,
              result.toString());
      if (won == 1) {
        LOGGER.log(
            Level.INFO,
            "[titan] build {0}: precondition ''{1}'' -> {2}",
            new Object[] {buildId, pre.getName(), pass ? "SUCCESS" : "FAILED"});
      }
    }
  }

  // ── approval: parked-step gate (#715, build-22 idempotence fix 2026-05-26) ─

  /**
   * Decide the next move for a PENDING flow node whose step is {@code approval:}. This is the
   * idempotence chokepoint that the old "is there a PENDING row?" inline check missed — see the
   * class javadoc for the post-mortem of build 22.
   *
   * <p>Lookup is by {@code findLatestForNode(buildId, nodeId)}; the latest row wins because a
   * replay may re-use the same flow node id and we always want to honour the freshest decision.
   *
   * @param flowNodes the live flow-node DAO (passed by the caller so a single advance() pass can
   *     reuse the handle the rest of the walk uses).
   * @param step the model step (used to resolve arguments + descriptor id when we have to create a
   *     fresh PENDING row).
   * @param node the current flow-node row (PENDING) — we CAS off its current status when we
   *     transition to SLEEPING / SUCCESS / FAILED.
   * @param ctx the {@code ${{ … }}} resolution context for step arguments.
   * @return what the orchestrator should do with the stage walk for this node.
   */
  @NonNull
  public ApprovalDecision evaluateApproval(
      @NonNull FlowNodeDao flowNodes,
      @NonNull StepModel step,
      @NonNull FlowNodeRow node,
      @NonNull Map<String, Object> ctx) {
    ApprovalRow latest = daos.approvals().findLatestForNode(buildId, node.nodeId).orElse(null);
    if (latest != null) {
      switch (latest.status) {
        case "APPROVED":
          foldApprovedNode(flowNodes, node, latest);
          return ApprovalDecision.ALLOW;
        case "REJECTED":
        case "TIMED_OUT":
          // The fix: a terminal-reject row MUST short-circuit the park path. Without this the
          // orchestrator would fall through and insert a fresh PENDING row, recreating the
          // approval the user just rejected (build 22 post-mortem: 6 REJECTED rows + 1 dangling
          // PENDING for the same (build, node) pair).
          foldRejectedNode(flowNodes, node, latest);
          return ApprovalDecision.SKIP;
        case "PENDING":
          // Already parked. Idempotent: do NOT insert a second row. The orchestrator's outer
          // park signal (ApprovalParkDetector) will keep the build off the re-tick cadence.
          return ApprovalDecision.PARK;
        default:
          LOGGER.log(
              Level.WARNING,
              "[titan] approval row {0} has unknown status {1} — treating node as parked",
              new Object[] {latest.id, latest.status});
          return ApprovalDecision.PARK;
      }
    }

    // No row at all — this is the first time we have seen this node as PENDING. Insert a fresh
    // PENDING row, CAS the node SLEEPING, and arm the GATE_RESUME timer.
    return parkFresh(flowNodes, step, node, ctx);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void foldApprovedNode(
      @NonNull FlowNodeDao flowNodes, @NonNull FlowNodeRow node, @NonNull ApprovalRow row) {
    String resultJson = approvalResultJson(row);
    // CAS from PENDING (the entry status) directly to SUCCESS — the SLEEPING leg is for the
    // resume-on-decision path elsewhere in the orchestrator. We accept either entry status to
    // tolerate the rare race where the node has already been parked by a concurrent tick.
    int won =
        flowNodes.compareAndSetStatus(
            buildId, node.nodeId, node.status, "SUCCESS", null, Instant.now(), null, resultJson);
    if (won == 1) {
      timerService.cancel(TimerService.Kind.GATE_RESUME, buildId, node.nodeId);
      LOGGER.log(
          Level.INFO,
          "[titan] build {0}: step {1} -> SUCCESS (approval {2} APPROVED)",
          new Object[] {buildId, node.nodeId, row.id});
    }
  }

  private void foldRejectedNode(
      @NonNull FlowNodeDao flowNodes, @NonNull FlowNodeRow node, @NonNull ApprovalRow row) {
    String reason =
        "TIMED_OUT".equals(row.status)
            ? "auto-rejected: approval timeout"
            : "approval rejected by " + row.decidedBy;
    flowNodes.updateFailure(buildId, node.nodeId, "APPROVAL", reason);
    String resultJson = approvalResultJson(row);
    int won =
        flowNodes.compareAndSetStatus(
            buildId, node.nodeId, node.status, "FAILED", null, Instant.now(), null, resultJson);
    if (won == 1) {
      timerService.cancel(TimerService.Kind.GATE_RESUME, buildId, node.nodeId);
      LOGGER.log(
          Level.INFO,
          "[titan] build {0}: step {1} -> FAILED (approval {2} {3})",
          new Object[] {buildId, node.nodeId, row.id, row.status});
    }
  }

  @NonNull
  private ApprovalDecision parkFresh(
      @NonNull FlowNodeDao flowNodes,
      @NonNull StepModel step,
      @NonNull FlowNodeRow node,
      @NonNull Map<String, Object> ctx) {
    ApprovalResolver.ParsedApproval parsed;
    try {
      parsed = ApprovalResolver.parse(TemplateResolver.resolveArguments(step.getArguments(), ctx));
    } catch (IllegalArgumentException badInput) {
      flowNodes.updateFailure(buildId, node.nodeId, "CONFIG", badInput.getMessage());
      flowNodes.compareAndSetStatus(
          buildId, node.nodeId, "PENDING", "FAILED", null, Instant.now(), null, null);
      return ApprovalDecision.SKIP;
    }
    Instant now = Instant.now();
    long approvalId = ApprovalService.park(daos, buildId, node.nodeId, parsed, now);
    Instant expiresAt = now.plus(parsed.timeout());
    String resultJson = "{\"approval\":{\"id\":" + approvalId + ",\"status\":\"PENDING\"}}";
    if (flowNodes.compareAndSetStatus(
            buildId, node.nodeId, "PENDING", "SLEEPING", now, null, null, resultJson)
        == 1) {
      flowNodes.setWakeAt(buildId, node.nodeId, expiresAt);
      timerService.arm(TimerService.Kind.GATE_RESUME, buildId, node.nodeId, expiresAt, null);
      LOGGER.log(
          Level.INFO,
          "[titan] build {0}: step {1} -> SLEEPING (approval {2}, expires {3})",
          new Object[] {buildId, node.nodeId, approvalId, expiresAt});
    }
    return ApprovalDecision.PARK;
  }

  @NonNull
  private static String approvalResultJson(@NonNull ApprovalRow row) {
    return "{\"approval\":{\"id\":"
        + row.id
        + ",\"status\":\""
        + row.status
        + "\",\"decidedBy\":\""
        + (row.decidedBy == null ? "" : row.decidedBy)
        + "\"}}";
  }

  private boolean depsSatisfied(
      @NonNull java.util.List<String> dependsOnNames,
      @NonNull Map<String, String> nameToId,
      @NonNull FlowNodeDao flowNodes) {
    for (String name : dependsOnNames) {
      FlowNodeRow dep = flowNodes.findByBuildAndNode(buildId, nameToId.get(name)).orElse(null);
      if (dep == null || !DEP_SATISFIED.contains(dep.status)) {
        return false;
      }
    }
    return true;
  }
}

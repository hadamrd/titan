package io.adaptiq.titan.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.flow.ApprovalService.DecisionOutcome;
import io.adaptiq.titan.flow.model.GateModel;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.ApprovalRow;
import io.adaptiq.titan.store.rows.AuditLogRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.time.Instant;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records a human's decision on a manual-approval gate (Chunk 6F, design/29 §3/§7.1).
 *
 * <p>The {@link TitanOrchestrator} opens a gate — moves it to {@code RUNNING} ("awaiting approval")
 * — but never resolves it: a gate is the one node whose next move is <em>not</em> a pure function
 * of the database, it is a person's choice. {@code GateService} is where that choice enters the
 * engine. It is called by the Stapler approval endpoint ({@link GateApprovalAction}) and is the
 * unit the gate ITs drive directly.
 *
 * <p>The decision is a single compare-and-set: {@code RUNNING → SUCCESS} (approve) or {@code
 * RUNNING → FAILED} (reject). The {@code WHERE status = 'RUNNING'} guard makes a double-submit (two
 * approvers clicking at once, a re-posted form) a no-op for the loser — the gate is resolved
 * exactly once. A decision on a gate that is not awaiting approval (already resolved, not yet
 * reached, skipped by an upstream failure) is rejected, not forced.
 *
 * <p>After a winning transition an {@code ORCHESTRATE/ADVANCE} task is enqueued so the DAG
 * continues without waiting for the next periodic poll.
 */
public final class GateService {

  private static final Logger LOGGER = Logger.getLogger(GateService.class.getName());
  private static final ObjectMapper JSON = new ObjectMapper();

  /** A human's decision on a gate. */
  public enum Decision {
    /** The gate passes — its downstream DAG runs. */
    APPROVED,
    /** The gate fails — under {@code blockOnFailure} the downstream DAG is skipped. */
    REJECTED
  }

  /**
   * Outcome of a {@link #decide} call.
   *
   * @param applied whether this call won the {@code RUNNING → terminal} transition
   * @param status the gate node's resulting status ({@code SUCCESS}/{@code FAILED}), or its current
   *     status if the decision was not applied
   * @param message a human-readable explanation
   */
  public record GateOutcome(boolean applied, @NonNull String status, @NonNull String message) {}

  private GateService() {}

  /**
   * Record {@code decision} on the gate {@code gateNodeId} of {@code buildId}, made by {@code
   * actor}.
   *
   * @param daos the Titan stores
   * @param buildId the build whose gate is being decided
   * @param gateNodeId the gate's {@code flow_nodes} node id
   * @param actor the deciding user's id (for the audit record)
   * @param actorIdentities the actor's id plus every group / authority they hold — checked against
   *     the gate's {@code approvers}. An empty {@code approvers} list means any authenticated user
   *     may decide.
   * @param decision approve or reject
   * @return the outcome — {@link GateOutcome#applied()} is {@code false} if the gate was not
   *     awaiting approval
   * @throws IllegalArgumentException if {@code buildId} has no such gate
   * @throws SecurityException if {@code actor} is not an authorised approver of the gate
   */
  @NonNull
  public static GateOutcome decide(
      @NonNull TitanStores daos,
      long buildId,
      @NonNull String gateNodeId,
      @NonNull String actor,
      @NonNull Set<String> actorIdentities,
      @NonNull Decision decision) {

    PipelineModel model = new TitanFlowExecution(daos, buildId).loadModel();
    GateModel gate =
        model.getGates().stream()
            .filter(g -> g.getId().equals(gateNodeId))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "build " + buildId + " has no gate '" + gateNodeId + "'"));

    // ADMIN break-glass: the authz contract (see ApprovalsApi + Roles#ADMIN) says a caller in the
    // gate's approvers OR holding ADMIN may decide; an empty list means any authenticated caller.
    // The sibling decideViaApprovalRow() path already honours this — decide() must match it, else
    // an
    // ADMIN can approve a gate via the approvals inbox but gets 403 on the gate endpoint for the
    // same gate (and a gate whose approvers reference a non-existent identity becomes
    // un-approvable).
    boolean isAdmin = actorIdentities.contains(Roles.ADMIN);
    if (!gate.getApprovers().isEmpty() && !isAdmin) {
      boolean authorised = gate.getApprovers().stream().anyMatch(actorIdentities::contains);
      if (!authorised) {
        throw new SecurityException(
            "'"
                + actor
                + "' is not an authorised approver of gate '"
                + gate.getName()
                + "' (approvers: "
                + gate.getApprovers()
                + ")");
      }
    }

    String to = decision == Decision.APPROVED ? "SUCCESS" : "FAILED";
    int won =
        daos.flowNodes()
            .compareAndSetStatus(
                buildId,
                gateNodeId,
                "RUNNING",
                to,
                null,
                Instant.now(),
                null,
                auditJson(actor, decision));

    if (won == 0) {
      FlowNodeRow current = daos.flowNodes().findByBuildAndNode(buildId, gateNodeId).orElse(null);
      String status = current == null ? "UNKNOWN" : current.status;
      return new GateOutcome(
          false,
          status,
          "gate '" + gate.getName() + "' is not awaiting approval (status=" + status + ")");
    }

    // Mirror the decision onto the unified titan.approvals row (issue #953). The row is the
    // source of truth for the UI inbox; we close it best-effort so legacy callers (the chunk-6F
    // Stapler shim, integration tests calling decide() directly) keep both surfaces consistent.
    closeApprovalRowFor(daos, buildId, gateNodeId, actor, decision, Instant.now());

    enqueueAdvance(daos, buildId);
    LOGGER.log(
        Level.INFO,
        "[titan] build {0}: gate ''{1}'' {2} by {3}",
        new Object[] {buildId, gate.getName(), decision, actor});
    return new GateOutcome(true, to, "gate '" + gate.getName() + "' " + decision + " by " + actor);
  }

  /**
   * Apply an approval decision routed from {@code POST /api/v1/approvals/{id}/{approve|reject}}
   * (issue #953). The unified approvals row is the single winner of any race; the gate's {@code
   * flow_node} status is mirrored from that row.
   *
   * <p>Authorisation: the actor's identities must intersect {@link GateModel#getApprovers} (an
   * empty approvers list means any caller already past the {@code @RolesAllowed} gate may decide;
   * an {@code ADMIN} identity bypasses the per-row list, matching {@link ApprovalService#decide}).
   *
   * @return outcome with the resulting approvals-row status ({@code APPROVED}/{@code REJECTED}) on
   *     applied; on lost race the current row status with {@code applied=false} (caller maps to
   *     HTTP 409).
   * @throws IllegalArgumentException if no gate exists for {@code row.flowNodeId} in the build's
   *     pipeline model (the row was inserted by a non-gate code path — caller maps to 404)
   * @throws SecurityException if the actor is not authorised to decide the gate
   */
  @NonNull
  public static DecisionOutcome decideViaApprovalRow(
      @NonNull TitanStores daos,
      @NonNull ApprovalRow row,
      @NonNull String actor,
      @NonNull Set<String> actorIdentities,
      @NonNull Decision decision) {

    PipelineModel model = new TitanFlowExecution(daos, row.buildId).loadModel();
    GateModel gate =
        model.getGates().stream()
            .filter(g -> g.getId().equals(row.flowNodeId))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "approval " + row.id + " is not a gate-stage approval"));

    boolean isAdmin = actorIdentities.contains(Roles.ADMIN);
    if (!gate.getApprovers().isEmpty() && !isAdmin) {
      boolean authorised = gate.getApprovers().stream().anyMatch(actorIdentities::contains);
      if (!authorised) {
        throw new SecurityException(
            "'"
                + actor
                + "' is not an authorised approver of gate '"
                + gate.getName()
                + "' (approvers: "
                + gate.getApprovers()
                + ")");
      }
    }

    if (!"PENDING".equals(row.status)) {
      return new DecisionOutcome(
          false,
          row.status,
          "approval "
              + row.id
              + " is already "
              + row.status.toLowerCase()
              + " (decided by "
              + row.decidedBy
              + ")");
    }

    Instant now = Instant.now();
    String rowTo = decision == Decision.APPROVED ? "APPROVED" : "REJECTED";
    int won = daos.approvals().decideIfPending(row.id, rowTo, actor, now);
    if (won == 0) {
      ApprovalRow current = daos.approvals().findById(row.id).orElse(row);
      return new DecisionOutcome(
          false,
          current.status,
          "approval "
              + row.id
              + " was already decided concurrently (status="
              + current.status
              + ")");
    }

    // Row CAS won — mirror onto the gate's flow_node. Race-safe: a concurrent legacy
    // GateService.decide() call will lose its own RUNNING→terminal CAS if we win this one.
    String nodeTo = decision == Decision.APPROVED ? "SUCCESS" : "FAILED";
    daos.flowNodes()
        .compareAndSetStatus(
            row.buildId,
            row.flowNodeId,
            "RUNNING",
            nodeTo,
            null,
            now,
            null,
            auditJson(actor, decision));

    auditApprovalDecision(daos, actor, "APPROVAL_" + rowTo, row.id, row.buildId, row.flowNodeId);
    enqueueAdvance(daos, row.buildId);
    LOGGER.log(
        Level.INFO,
        "[titan] build {0}: gate ''{1}'' {2} by {3} (via approval {4})",
        new Object[] {row.buildId, gate.getName(), decision, actor, row.id});
    return new DecisionOutcome(
        true, rowTo, "gate '" + gate.getName() + "' " + decision + " by " + actor);
  }

  /**
   * Best-effort: flip the approvals row for {@code gateNodeId} terminal so the inbox stays in sync
   * with a legacy {@link #decide} caller. CAS guard ensures no double-flip if the row was already
   * resolved through the unified path.
   */
  private static void closeApprovalRowFor(
      @NonNull TitanStores daos,
      long buildId,
      @NonNull String gateNodeId,
      @NonNull String actor,
      @NonNull Decision decision,
      @NonNull Instant now) {
    ApprovalRow row = daos.approvals().findLatestForNode(buildId, gateNodeId).orElse(null);
    if (row == null || !"PENDING".equals(row.status)) {
      return;
    }
    String rowTo = decision == Decision.APPROVED ? "APPROVED" : "REJECTED";
    int won = daos.approvals().decideIfPending(row.id, rowTo, actor, now);
    if (won == 1) {
      auditApprovalDecision(daos, actor, "APPROVAL_" + rowTo, row.id, buildId, gateNodeId);
    }
  }

  private static void auditApprovalDecision(
      @NonNull TitanStores daos,
      @NonNull String actor,
      @NonNull String action,
      long approvalId,
      long buildId,
      @NonNull String flowNodeId) {
    try {
      AuditLogRow row = new AuditLogRow();
      row.actor = actor;
      row.action = action;
      row.targetType = "APPROVAL";
      row.targetId = String.valueOf(approvalId);
      ObjectNode details = JSON.createObjectNode();
      details.put("buildId", buildId);
      details.put("flowNodeId", flowNodeId);
      row.detailsJson = details.toString();
      daos.auditLog().insert(row);
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING, "[titan] failed to record gate-approval audit for " + approvalId, e);
    }
  }

  /** The audit trail of a gate decision — persisted as the gate node's {@code result_json}. */
  @NonNull
  private static String auditJson(@NonNull String actor, @NonNull Decision decision) {
    ObjectNode audit = JSON.createObjectNode();
    audit.put("gate", decision == Decision.APPROVED ? "approved" : "rejected");
    audit.put("decidedBy", actor);
    audit.put("decidedAt", Instant.now().toString());
    return audit.toString();
  }

  /** Enqueue an immediate {@code ORCHESTRATE/ADVANCE} so the DAG continues past the gate now. */
  private static void enqueueAdvance(@NonNull TitanStores daos, long buildId) {
    daos.taskQueue()
        .enqueue(
            "ORCHESTRATE",
            "default",
            0,
            "{\"action\":\"ADVANCE\",\"buildId\":" + buildId + "}",
            3,
            3600,
            buildId,
            null);
  }
}

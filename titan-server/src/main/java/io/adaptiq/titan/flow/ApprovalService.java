package io.adaptiq.titan.flow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.ApprovalRow;
import io.adaptiq.titan.store.rows.AuditLogRow;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records a human's decision on a parked {@code approval:} step (#715) — the durable counterpart of
 * {@link GateService} for the {@code gate:} stage node.
 *
 * <p>Lifecycle:
 *
 * <ol>
 *   <li>{@link io.adaptiq.titan.flow.TitanOrchestrator} parks an {@code approval:} step ({@code
 *       PENDING → SLEEPING} on the flow node) and inserts a PENDING row here via {@link #park}.
 *   <li>Either a {@link #decide} call (APPROVE / REJECT, gated by RBAC + approver-list) or a {@link
 *       #sweepTimedOut} pass (PENDING rows past {@code expires_at} → TIMED_OUT) flips the row
 *       terminal.
 *   <li>The transition enqueues an ORCHESTRATE/ADVANCE so the orchestrator can wake the parked flow
 *       node SUCCESS (APPROVED) or FAILED (REJECTED / TIMED_OUT) on the next pass.
 * </ol>
 *
 * <p>Every winning decision (APPROVED, REJECTED, TIMED_OUT) is recorded in {@code titan.audit_log}
 * with the actor's OIDC subject — security-critical actions never go un-audited.
 */
public final class ApprovalService {

  private static final Logger LOGGER = Logger.getLogger(ApprovalService.class.getName());
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<List<String>> STRING_LIST =
      new TypeReference<List<String>>() {};

  /** A human's decision on an approval gate. */
  public enum Decision {
    APPROVED,
    REJECTED
  }

  /** Outcome of a {@link #decide} call — mirror of {@code GateService.GateOutcome}. */
  public record DecisionOutcome(boolean applied, @NonNull String status, @NonNull String message) {}

  private ApprovalService() {}

  // ── park ──────────────────────────────────────────────────────────────────

  /**
   * Insert a PENDING row for an approval the orchestrator has just parked. Idempotent on (build,
   * node): if a PENDING row already exists for the pair we return its id; only when none exists is
   * a fresh row inserted. This makes a re-tick of {@code advance()} after a controller crash safe.
   */
  public static long park(
      @NonNull TitanStores daos,
      long buildId,
      @NonNull String flowNodeId,
      @NonNull ApprovalResolver.ParsedApproval parsed,
      @NonNull Instant now) {
    ApprovalRow existing = daos.approvals().findLatestForNode(buildId, flowNodeId).orElse(null);
    if (existing != null && "PENDING".equals(existing.status)) {
      return existing.id;
    }
    ApprovalRow row = new ApprovalRow();
    row.buildId = buildId;
    row.flowNodeId = flowNodeId;
    row.prompt = parsed.prompt();
    row.approversJson = toApproversJson(parsed.approvers());
    row.expiresAt = now.plus(parsed.timeout());
    long id = daos.approvals().insertPending(row);
    LOGGER.log(
        Level.INFO,
        "[titan] approval parked: build={0} node={1} approvers={2} timeout={3}",
        new Object[] {buildId, flowNodeId, parsed.approvers(), parsed.timeout()});
    return id;
  }

  // ── decide ────────────────────────────────────────────────────────────────

  /**
   * Record {@code decision} on approval {@code approvalId} by {@code actor}. RBAC enforcement is
   * the caller's job ({@code @RolesAllowed(APPROVE_BUILD, ADMIN)}); this method enforces the
   * <em>row-level</em> rule: the actor's subject (or one of their roles) must appear in the
   * approval's approvers list, unless the actor holds {@link Roles#ADMIN} OR the approvers list is
   * empty (any APPROVE_BUILD holder may decide).
   *
   * @return {@link DecisionOutcome#applied()} is {@code false} when the row was no longer PENDING
   *     (caller turns that into a 409)
   * @throws IllegalArgumentException if no such row
   * @throws SecurityException if the actor is not an authorised approver
   */
  @NonNull
  public static DecisionOutcome decide(
      @NonNull TitanStores daos,
      long approvalId,
      @NonNull String actor,
      @NonNull Set<String> actorIdentities,
      @NonNull Decision decision) {
    ApprovalRow row =
        daos.approvals()
            .findById(approvalId)
            .orElseThrow(
                () -> new IllegalArgumentException("approval " + approvalId + " not found"));

    if (!"PENDING".equals(row.status)) {
      return new DecisionOutcome(
          false,
          row.status,
          "approval "
              + approvalId
              + " is already "
              + row.status.toLowerCase()
              + " (decided by "
              + row.decidedBy
              + ")");
    }

    List<String> approvers = parseApprovers(row.approversJson);
    boolean isAdmin = actorIdentities.contains(Roles.ADMIN);
    if (!approvers.isEmpty() && !isAdmin) {
      boolean authorised = approvers.stream().anyMatch(actorIdentities::contains);
      if (!authorised) {
        throw new SecurityException(
            "'"
                + actor
                + "' is not an authorised approver of approval "
                + approvalId
                + " (approvers: "
                + approvers
                + ")");
      }
    }

    Instant now = Instant.now();
    String to = decision == Decision.APPROVED ? "APPROVED" : "REJECTED";
    int won = daos.approvals().decideIfPending(approvalId, to, actor, now);
    if (won == 0) {
      // Lost a race to a concurrent decide / sweep. Re-fetch for the accurate terminal state.
      ApprovalRow current = daos.approvals().findById(approvalId).orElse(row);
      return new DecisionOutcome(
          false,
          current.status,
          "approval "
              + approvalId
              + " was already decided concurrently (status="
              + current.status
              + ")");
    }

    audit(daos, actor, "APPROVAL_" + to, approvalId, row.buildId, row.flowNodeId);
    enqueueAdvance(daos, row.buildId);
    LOGGER.log(
        Level.INFO,
        "[titan] approval {0} {1} by {2} (build={3} node={4})",
        new Object[] {approvalId, decision, actor, row.buildId, row.flowNodeId});
    return new DecisionOutcome(
        true, to, "approval " + approvalId + " " + decision + " by " + actor);
  }

  // ── sweep ─────────────────────────────────────────────────────────────────

  /**
   * Bulk-flip every PENDING row past its {@code expires_at} to TIMED_OUT, enqueuing one
   * ORCHESTRATE/ADVANCE per affected build. Called by the scheduled timer sweep (alongside {@code
   * TimerSweepWorker.sweep}). Returns the count of rows that timed out.
   */
  public static int sweepTimedOut(@NonNull TitanStores daos, @NonNull Instant now) {
    List<ApprovalRow> expired = daos.approvals().listExpired(now);
    if (expired.isEmpty()) {
      return 0;
    }
    int flipped = daos.approvals().sweepTimedOut(now);
    Set<Long> buildsToAdvance = new HashSet<>();
    for (ApprovalRow row : expired) {
      buildsToAdvance.add(row.buildId);
      audit(daos, "<timeout>", "APPROVAL_TIMED_OUT", row.id, row.buildId, row.flowNodeId);
    }
    for (Long b : buildsToAdvance) {
      enqueueAdvance(daos, b);
    }
    LOGGER.log(
        Level.INFO,
        "[titan] approval sweep: {0} row(s) timed out across {1} build(s)",
        new Object[] {flipped, buildsToAdvance.size()});
    return flipped;
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  @NonNull
  static String toApproversJson(@NonNull List<String> approvers) {
    try {
      return JSON.writeValueAsString(approvers);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // approvers is List<String> — Jackson cannot fail to serialise it. Fold to a defensible
      // default rather than propagate a checked exception out of the park path.
      LOGGER.log(Level.WARNING, "[titan] approvers JSON serialisation failed — using []", e);
      return "[]";
    }
  }

  @NonNull
  public static List<String> parseApprovers(@NonNull String approversJson) {
    try {
      return JSON.readValue(approversJson, STRING_LIST);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] approval row carries malformed approvers_json: {0}",
          approversJson);
      return List.of();
    }
  }

  private static void audit(
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
      // Audit failure must never fail the decision — log and move on (mirror of the AuditApi
      // pattern: best-effort from the perspective of the caller).
      LOGGER.log(Level.WARNING, "[titan] failed to record approval audit for " + approvalId, e);
    }
  }

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

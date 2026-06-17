package io.adaptiq.titan.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.api.dto.ApprovalDto;
import io.adaptiq.titan.auth.AuthContext;
import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.flow.ApprovalService;
import io.adaptiq.titan.flow.ApprovalService.Decision;
import io.adaptiq.titan.flow.ApprovalService.DecisionOutcome;
import io.adaptiq.titan.flow.GateService;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.ApprovalRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Jakarta REST resource: the human-approval inbox + decide endpoints (#715 backend).
 *
 * <ul>
 *   <li>{@code GET /api/v1/approvals?status=PENDING} — list approval rows filtered by status
 *       (default PENDING).
 *   <li>{@code POST /api/v1/approvals/{id}/approve} — record APPROVE.
 *   <li>{@code POST /api/v1/approvals/{id}/reject} — record REJECT.
 * </ul>
 *
 * <p>RBAC: the decide endpoints require {@link Roles#APPROVE_BUILD} or {@link Roles#ADMIN}. On top
 * of {@code @RolesAllowed}, {@link ApprovalService#decide} enforces row-level authorisation: the
 * caller's OIDC subject (or one of their granted roles) must appear in the approval's approvers
 * list, unless the caller holds {@code ADMIN} or the list is empty.
 *
 * <p>Already-decided rows return HTTP 409 (idempotent — a double-click is a no-op for the loser).
 * Missing rows are 404; non-authorised callers are 403.
 *
 * <p>Audit: every winning decision is recorded in {@code titan.audit_log} (the service handles it —
 * this resource is a thin gateway).
 */
@Path("/api/v1/approvals")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class ApprovalsApi {

  private static final Set<String> ALLOWED_STATUSES =
      Set.of("PENDING", "APPROVED", "REJECTED", "TIMED_OUT");

  private final TitanStores stores;
  private final AuthContext authContext;

  ApprovalsApi(TitanStores stores, AuthContext authContext) {
    this.stores = stores;
    this.authContext = authContext;
  }

  // ── GET /api/v1/approvals ─────────────────────────────────────────────────

  /**
   * Paginated list of approval rows, filtered by status. Default status is PENDING — the common
   * "what needs my attention?" call. Read access is intentionally broad ({@code READ_JOB} or any of
   * the build-action roles) so the inbox is visible to anyone who can read the build.
   */
  @GET
  @RolesAllowed({Roles.READ_JOB, Roles.TRIGGER_BUILD, Roles.APPROVE_BUILD, Roles.ADMIN})
  public ApprovalPage list(
      @QueryParam("status") @DefaultValue("PENDING") String status,
      @QueryParam("buildId") @Nullable Long buildId,
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("limit") @DefaultValue("50") int limit) {
    String normalised = status == null ? "PENDING" : status.trim().toUpperCase();
    if (!ALLOWED_STATUSES.contains(normalised)) {
      throw new ApiBadRequestException(
          "query parameter 'status' must be one of " + ALLOWED_STATUSES);
    }
    int cappedLimit = Math.min(Math.max(limit, 1), 200);
    int safeOffset = Math.max(offset, 0);

    if (buildId != null) {
      // Issue #953: per-build inbox call — return every row for the build matching the status,
      // covering both gate-stage rows and approval-step rows with no schema divergence.
      List<ApprovalDto> all =
          stores.approvals().listForBuild(buildId).stream()
              .filter(r -> normalised.equals(r.status))
              .map(ApprovalDto::from)
              .toList();
      long totalForBuild = all.size();
      int end = Math.min(safeOffset + cappedLimit, all.size());
      List<ApprovalDto> page = safeOffset >= all.size() ? List.of() : all.subList(safeOffset, end);
      return new ApprovalPage(page, totalForBuild, safeOffset, cappedLimit);
    }

    List<ApprovalDto> rows =
        stores.approvals().listByStatus(normalised, cappedLimit, safeOffset).stream()
            .map(ApprovalDto::from)
            .toList();
    long total = stores.approvals().countByStatus(normalised);
    return new ApprovalPage(rows, total, safeOffset, cappedLimit);
  }

  // ── POST /api/v1/approvals/{id}/approve ───────────────────────────────────

  @POST
  @Path("/{id}/approve")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.APPROVE_BUILD, Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.DEVELOPER, kind = ScopeKind.ORG, scopeId = "global")
  public Response approve(@PathParam("id") long id, @Nullable DecisionBody body) {
    return decide(id, body, Decision.APPROVED);
  }

  // ── POST /api/v1/approvals/{id}/reject ────────────────────────────────────

  @POST
  @Path("/{id}/reject")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.APPROVE_BUILD, Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.DEVELOPER, kind = ScopeKind.ORG, scopeId = "global")
  public Response reject(@PathParam("id") long id, @Nullable DecisionBody body) {
    return decide(id, body, Decision.REJECTED);
  }

  // ── POST /api/v1/approvals/bulk/{approve|reject} (#734) ───────────────────

  /**
   * Bulk-decide N approvals in one round-trip — release-train workflows where an SRE has 5+ pending
   * rows queue up. Per-id outcome (mirror of the single endpoint's semantics):
   *
   * <ul>
   *   <li>{@code applied=true} + terminal status when the row was PENDING and the caller decided
   *       it.
   *   <li>{@code applied=false} + {@code reason="already_decided"} when the row was no longer
   *       PENDING (HTTP 409 equivalent for the single endpoint).
   *   <li>{@code applied=false} + {@code reason="forbidden"} when the caller is not an authorised
   *       approver of that specific row (HTTP 403 equivalent).
   *   <li>{@code applied=false} + {@code reason="not_found"} when no such row (HTTP 404
   *       equivalent).
   * </ul>
   *
   * <p>The overall response is always 200 (partial success is the norm — release trains routinely
   * mix pending + already-decided ids when two SREs race). Empty {@code ids} → 400. Duplicate ids
   * are de-duped before processing (first-occurrence order preserved).
   *
   * <p>RBAC: {@code APPROVE_BUILD} or {@code ADMIN} required for the endpoint as a whole; per-row
   * approver-list enforcement happens in {@link ApprovalService#decide} exactly as the single
   * endpoint does.
   */
  @POST
  @Path("/bulk/approve")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.APPROVE_BUILD, Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.DEVELOPER, kind = ScopeKind.ORG, scopeId = "global")
  public BulkApprovalResponse bulkApprove(BulkApprovalRequest body) {
    return bulkDecide(body, Decision.APPROVED);
  }

  @POST
  @Path("/bulk/reject")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.APPROVE_BUILD, Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.DEVELOPER, kind = ScopeKind.ORG, scopeId = "global")
  public BulkApprovalResponse bulkReject(BulkApprovalRequest body) {
    return bulkDecide(body, Decision.REJECTED);
  }

  // ── internals ─────────────────────────────────────────────────────────────

  private BulkApprovalResponse bulkDecide(@Nullable BulkApprovalRequest body, Decision decision) {
    if (body == null || body.ids() == null || body.ids().isEmpty()) {
      throw new ApiBadRequestException("field 'ids' must be a non-empty array of approval ids");
    }
    // De-dupe while preserving first-occurrence order so the outcomes list mirrors caller intent.
    Set<Long> unique = new LinkedHashSet<>(body.ids());

    String actor = authContext.currentUser();
    Set<String> identities = new HashSet<>(authContext.currentRoles());
    identities.add(actor);

    List<BulkOutcome> outcomes = new ArrayList<>(unique.size());
    for (Long id : unique) {
      outcomes.add(decideOne(id, actor, identities, decision));
    }
    return new BulkApprovalResponse(outcomes);
  }

  private BulkOutcome decideOne(long id, String actor, Set<String> identities, Decision decision) {
    try {
      DecisionOutcome outcome = decideRouting(id, actor, identities, decision);
      if (outcome.applied()) {
        return new BulkOutcome(id, true, outcome.status(), null);
      }
      // Service returned applied=false: row was no longer PENDING (HTTP 409 in the single path).
      return new BulkOutcome(id, false, outcome.status(), "already_decided");
    } catch (IllegalArgumentException missing) {
      return new BulkOutcome(id, false, null, "not_found");
    } catch (SecurityException forbidden) {
      return new BulkOutcome(id, false, null, "forbidden");
    }
  }

  /**
   * Dispatch an approval-row decision to the right service. Issue #953: a row whose flow node is a
   * {@code gate:} stage (materialised with {@code step_descriptor='gate'}) goes through {@link
   * GateService#decideViaApprovalRow} so the gate's {@code flow_node} CAS happens in lock-step with
   * the approvals-row CAS. Step-form {@code approval:} rows keep the existing path.
   */
  private DecisionOutcome decideRouting(
      long id, String actor, Set<String> identities, Decision decision) {
    ApprovalRow row =
        stores
            .approvals()
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("approval " + id + " not found"));
    FlowNodeRow node =
        stores.flowNodes().findByBuildAndNode(row.buildId, row.flowNodeId).orElse(null);
    boolean isGate = node != null && "gate".equals(node.stepDescriptor);
    if (isGate) {
      GateService.Decision gateDecision =
          decision == Decision.APPROVED
              ? GateService.Decision.APPROVED
              : GateService.Decision.REJECTED;
      return GateService.decideViaApprovalRow(stores, row, actor, identities, gateDecision);
    }
    return ApprovalService.decide(stores, id, actor, identities, decision);
  }

  private Response decide(long id, @Nullable DecisionBody body, Decision decision) {
    if (body != null && body.reason() != null && body.reason().length() > 500) {
      throw new ApiBadRequestException("field 'reason' must be at most 500 characters");
    }
    String actor = authContext.currentUser();
    Set<String> identities = new HashSet<>(authContext.currentRoles());
    identities.add(actor);

    DecisionOutcome outcome;
    try {
      outcome = decideRouting(id, actor, identities, decision);
    } catch (IllegalArgumentException missing) {
      throw new ApiNotFoundException(missing.getMessage());
    } catch (SecurityException forbidden) {
      throw new WebApplicationException(forbidden.getMessage(), Response.Status.FORBIDDEN);
    }
    DecisionResponse payload =
        new DecisionResponse(outcome.applied(), outcome.status(), outcome.message());
    int status = outcome.applied() ? 200 : 409;
    return Response.status(status).entity(payload).build();
  }

  // ── wire types ────────────────────────────────────────────────────────────

  /** Paged list shape — mirror of {@code AuditPage}. */
  public record ApprovalPage(List<ApprovalDto> items, long total, int offset, int limit) {}

  /** Request body for approve / reject. {@code reason} is optional. */
  @JsonInclude(Include.NON_NULL)
  public record DecisionBody(@Nullable String reason) {}

  /** Response body for approve / reject. */
  public record DecisionResponse(boolean applied, String status, String message) {}

  /** Request body for bulk approve / reject (#734). {@code ids} is required + non-empty. */
  public record BulkApprovalRequest(List<Long> ids) {}

  /** Response body for bulk approve / reject (#734) — per-id outcome list. */
  public record BulkApprovalResponse(List<BulkOutcome> outcomes) {}

  /**
   * Per-id outcome of a bulk decide. {@code status} is the row's terminal status when known; {@code
   * reason} is a stable string code ({@code already_decided | forbidden | not_found}) when {@code
   * applied=false}, null on success.
   */
  @JsonInclude(Include.NON_NULL)
  public record BulkOutcome(
      long id, boolean applied, @Nullable String status, @Nullable String reason) {}
}

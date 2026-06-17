package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.ApprovalRow;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * REST-layer adversarial tests for the bulk approve/reject endpoints (closes #734).
 *
 * <p>H2-backed {@link TitanStores} via {@code H2StoresProducer} — no Docker, no Postgres. Mirrors
 * the {@link GatesApiTest} pattern: seed jobs + builds + approval rows directly via the DAO, then
 * exercise the resource through {@code restassured} so the {@code @RolesAllowed} gate, the
 * {@code @TestSecurity}-projected {@link io.quarkus.security.identity.SecurityIdentity}, the
 * approver-list enforcement in {@link io.adaptiq.titan.flow.ApprovalService#decide}, and the per-id
 * outcome mapping are all in the loop.
 *
 * <p>Coverage:
 *
 * <ul>
 *   <li>3 pending → 3 applied=true, all APPROVED;
 *   <li>mixed (2 pending + 1 already-approved) → 2 applied=true + 1 applied=false reason= {@code
 *       already_decided};
 *   <li>non-approver bulk-approve 2 → 2 applied=false reason={@code forbidden}, rows stay PENDING
 *       (no DB mutation);
 *   <li>unknown id → applied=false reason={@code not_found};
 *   <li>empty ids → 400 problem+json;
 *   <li>duplicate ids → de-duped, one outcome per unique id.
 * </ul>
 */
@QuarkusTest
class BulkApprovalsApiTest {

  @Inject TitanStores stores;

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  @TestSecurity(user = "alice", roles = "APPROVE_BUILD")
  void bulkApprove_threePending_allApplied() {
    long buildId = seedBuild();
    long a = seedPending(buildId, "n-a", List.of("alice"));
    long b = seedPending(buildId, "n-b", List.of("alice"));
    long c = seedPending(buildId, "n-c", List.of("alice"));

    given()
        .contentType("application/json")
        .body("{\"ids\":[" + a + "," + b + "," + c + "]}")
        .when()
        .post("/api/v1/approvals/bulk/approve")
        .then()
        .statusCode(200)
        .body("outcomes.size()", equalTo(3))
        .body("outcomes.applied", equalTo(List.of(true, true, true)))
        .body("outcomes.status", equalTo(List.of("APPROVED", "APPROVED", "APPROVED")));

    // DB state: all three flipped APPROVED.
    for (long id : List.of(a, b, c)) {
      ApprovalRow r = stores.approvals().findById(id).orElseThrow();
      org.junit.jupiter.api.Assertions.assertEquals("APPROVED", r.status, "id=" + id);
      org.junit.jupiter.api.Assertions.assertEquals("alice", r.decidedBy);
    }
  }

  // ── mixed outcome ─────────────────────────────────────────────────────────

  @Test
  @TestSecurity(user = "alice", roles = "APPROVE_BUILD")
  void bulkApprove_mixedPendingAndAlreadyDecided_partialSuccess() {
    long buildId = seedBuild();
    long a = seedPending(buildId, "m-a", List.of("alice"));
    long b = seedPending(buildId, "m-b", List.of("alice"));
    long alreadyDone = seedPending(buildId, "m-done", List.of("alice"));
    // Pre-decide one row so it is no longer PENDING.
    stores.approvals().decideIfPending(alreadyDone, "APPROVED", "alice", Instant.now());

    given()
        .contentType("application/json")
        .body("{\"ids\":[" + a + "," + b + "," + alreadyDone + "]}")
        .when()
        .post("/api/v1/approvals/bulk/approve")
        .then()
        .statusCode(200)
        .body("outcomes.size()", equalTo(3))
        .body("outcomes.find { it.id == " + a + " }.applied", equalTo(true))
        .body("outcomes.find { it.id == " + b + " }.applied", equalTo(true))
        .body("outcomes.find { it.id == " + alreadyDone + " }.applied", equalTo(false))
        .body("outcomes.find { it.id == " + alreadyDone + " }.reason", equalTo("already_decided"))
        .body("outcomes.find { it.id == " + alreadyDone + " }.status", equalTo("APPROVED"));
  }

  // ── 403 per-id without DB mutation ────────────────────────────────────────

  @Test
  @TestSecurity(user = "mallory", roles = "APPROVE_BUILD")
  void bulkApprove_nonApprover_allForbidden_noRowsMutated() {
    long buildId = seedBuild();
    long a = seedPending(buildId, "f-a", List.of("alice"));
    long b = seedPending(buildId, "f-b", List.of("alice"));

    given()
        .contentType("application/json")
        .body("{\"ids\":[" + a + "," + b + "]}")
        .when()
        .post("/api/v1/approvals/bulk/approve")
        .then()
        .statusCode(200)
        .body("outcomes.size()", equalTo(2))
        .body("outcomes.applied", equalTo(List.of(false, false)))
        .body("outcomes.reason", equalTo(List.of("forbidden", "forbidden")));

    // No rows were mutated — both remain PENDING with no decider.
    for (long id : List.of(a, b)) {
      ApprovalRow r = stores.approvals().findById(id).orElseThrow();
      org.junit.jupiter.api.Assertions.assertEquals("PENDING", r.status, "id=" + id);
      org.junit.jupiter.api.Assertions.assertNull(r.decidedBy, "id=" + id);
    }
  }

  // ── 404 per-id ────────────────────────────────────────────────────────────

  @Test
  @TestSecurity(user = "alice", roles = "APPROVE_BUILD")
  void bulkApprove_unknownId_returnsNotFoundReason() {
    given()
        .contentType("application/json")
        .body("{\"ids\":[999999991, 999999992]}")
        .when()
        .post("/api/v1/approvals/bulk/approve")
        .then()
        .statusCode(200)
        .body("outcomes.size()", equalTo(2))
        .body("outcomes.applied", equalTo(List.of(false, false)))
        .body("outcomes.reason", equalTo(List.of("not_found", "not_found")));
  }

  // ── 400: empty + missing ids ──────────────────────────────────────────────

  @Test
  @TestSecurity(user = "alice", roles = "APPROVE_BUILD")
  void bulkApprove_emptyIds_returns400() {
    given()
        .contentType("application/json")
        .body("{\"ids\":[]}")
        .when()
        .post("/api/v1/approvals/bulk/approve")
        .then()
        .statusCode(400);
  }

  @Test
  @TestSecurity(user = "alice", roles = "APPROVE_BUILD")
  void bulkApprove_missingIds_returns400() {
    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/approvals/bulk/approve")
        .then()
        .statusCode(400);
  }

  // ── de-dup ────────────────────────────────────────────────────────────────

  @Test
  @TestSecurity(user = "alice", roles = "APPROVE_BUILD")
  void bulkApprove_duplicateIdsAreDeDuped_singleOutcomePerUniqueId() {
    long buildId = seedBuild();
    long a = seedPending(buildId, "d-a", List.of("alice"));

    given()
        .contentType("application/json")
        .body("{\"ids\":[" + a + "," + a + "," + a + "]}")
        .when()
        .post("/api/v1/approvals/bulk/approve")
        .then()
        .statusCode(200)
        .body("outcomes.size()", equalTo(1))
        .body("outcomes[0].id", equalTo((int) a))
        .body("outcomes[0].applied", equalTo(true))
        .body("outcomes[0].status", equalTo("APPROVED"));
  }

  // ── reject mirror ─────────────────────────────────────────────────────────

  @Test
  @TestSecurity(user = "alice", roles = "APPROVE_BUILD")
  void bulkReject_twoPending_allRejected() {
    long buildId = seedBuild();
    long a = seedPending(buildId, "r-a", List.of("alice"));
    long b = seedPending(buildId, "r-b", List.of("alice"));

    given()
        .contentType("application/json")
        .body("{\"ids\":[" + a + "," + b + "]}")
        .when()
        .post("/api/v1/approvals/bulk/reject")
        .then()
        .statusCode(200)
        .body("outcomes.size()", equalTo(2))
        .body("outcomes.status", hasItem("REJECTED"));
  }

  // ── RBAC ──────────────────────────────────────────────────────────────────

  @Test
  void bulkApprove_unauthenticated_returns401() {
    given()
        .contentType("application/json")
        .body("{\"ids\":[1]}")
        .when()
        .post("/api/v1/approvals/bulk/approve")
        .then()
        .statusCode(401);
  }

  @Test
  @TestSecurity(user = "reader", roles = "READ_JOB")
  void bulkApprove_readJobOnly_returns403() {
    given()
        .contentType("application/json")
        .body("{\"ids\":[1]}")
        .when()
        .post("/api/v1/approvals/bulk/approve")
        .then()
        .statusCode(403);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private long seedBuild() {
    JobRow j = new JobRow();
    j.fullName = "org/bulk-approvals-" + System.nanoTime();
    j.enabled = true;
    j.pipelineScript = "";
    j.configJson = "{}";
    long jobId = stores.jobs().insert(j);
    BuildRow b = new BuildRow();
    b.jobId = jobId;
    b.buildNumber = stores.builds().nextBuildNumber(jobId);
    b.status = "RUNNING";
    b.queuedAt = Instant.now();
    b.triggeredBy = "test";
    b.triggerType = "manual";
    return stores.withTransaction(conn -> stores.builds().insert(conn, b));
  }

  private long seedPending(long buildId, String nodeId, List<String> approvers) {
    ApprovalRow row = new ApprovalRow();
    row.buildId = buildId;
    row.flowNodeId = nodeId;
    row.prompt = "Approve?";
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < approvers.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append('"').append(approvers.get(i)).append('"');
    }
    sb.append(']');
    row.approversJson = sb.toString();
    row.expiresAt = Instant.now().plusSeconds(3600);
    return stores.approvals().insertPending(row);
  }
}

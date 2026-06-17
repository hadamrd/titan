package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.flow.model.GateModel;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * REST-layer tests for {@link GatesApi} (closes #295 backend half). H2-backed {@link TitanStores}
 * via {@link H2StoresProducer} — no Docker, no Postgres.
 *
 * <p>Authn/Authz coverage mirrors {@link io.adaptiq.titan.auth.RbacTest}. Happy-path coverage
 * inserts a build with a pipeline_model_json containing a gate, plus the corresponding flow_node
 * row at RUNNING, and exercises the full list/approve/reject flow against the real {@link
 * io.adaptiq.titan.flow.GateService}.
 */
@QuarkusTest
class GatesApiTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject TitanStores stores;

  // ── RBAC: GET ───────────────────────────────────────────────────────────────

  @Test
  void list_unauthenticated_returns401() {
    given().when().get("/api/v1/builds/1/gates").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "alice", roles = "EDIT_PIPELINE")
  void list_wrongRole_returns403() {
    given().when().get("/api/v1/builds/1/gates").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "alice", roles = "READ_JOB")
  void list_readJobRoleOnMissingBuild_returns404() {
    given().when().get("/api/v1/builds/999999999/gates").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "alice", roles = "TRIGGER_BUILD")
  void list_triggerBuildRoleOnMissingBuild_returns404() {
    given().when().get("/api/v1/builds/999999999/gates").then().statusCode(404);
  }

  // ── RBAC: POST approve ──────────────────────────────────────────────────────

  @Test
  void approve_unauthenticated_returns401() {
    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/builds/1/gates/g1/approve")
        .then()
        .statusCode(401);
  }

  @Test
  @TestSecurity(user = "alice", roles = "READ_JOB")
  void approve_readOnlyRole_returns403() {
    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/builds/1/gates/g1/approve")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "alice", roles = "TRIGGER_BUILD")
  void approve_triggerBuildOnMissingBuild_returns404() {
    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/builds/999999999/gates/g1/approve")
        .then()
        .statusCode(404);
  }

  @Test
  @TestSecurity(user = "admin", roles = "ADMIN")
  void approve_adminOnMissingBuild_returns404() {
    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/builds/999999999/gates/g1/approve")
        .then()
        .statusCode(404);
  }

  // ── RBAC: POST reject ───────────────────────────────────────────────────────

  @Test
  void reject_unauthenticated_returns401() {
    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/builds/1/gates/g1/reject")
        .then()
        .statusCode(401);
  }

  @Test
  @TestSecurity(user = "alice", roles = "READ_JOB")
  void reject_readOnlyRole_returns403() {
    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/builds/1/gates/g1/reject")
        .then()
        .statusCode(403);
  }

  // ── Happy path: list / approve / re-approve / list-empty ────────────────────

  @Test
  @TestSecurity(user = "alice", roles = "TRIGGER_BUILD")
  void list_pendingGate_returnsIt() {
    long buildId = seedBuildWithGate("g-list", "Manual QA", List.of(), "RUNNING");

    given()
        .when()
        .get("/api/v1/builds/" + buildId + "/gates")
        .then()
        .statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].nodeId", equalTo("g-list"))
        .body("[0].name", equalTo("Manual QA"))
        .body("[0].state", equalTo("RUNNING"))
        .body("[0].awaitingSince", is(not(equalTo(null))));
  }

  @Test
  @TestSecurity(user = "alice", roles = "TRIGGER_BUILD")
  void list_skipsTerminalAndPendingNodes() {
    long buildId = seedBuildWithGate("g-done", "Resolved", List.of(), "SUCCESS");

    given()
        .when()
        .get("/api/v1/builds/" + buildId + "/gates")
        .then()
        .statusCode(200)
        .body("size()", equalTo(0));
  }

  @Test
  @TestSecurity(user = "alice", roles = "TRIGGER_BUILD")
  void approve_appliesAndSecondCallReturns409() {
    long buildId = seedBuildWithGate("g-approve", "Deploy?", List.of(), "RUNNING");

    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/builds/" + buildId + "/gates/g-approve/approve")
        .then()
        .statusCode(200)
        .body("applied", equalTo(true))
        .body("status", equalTo("SUCCESS"));

    // Second call: gate is already resolved → 409 + applied:false.
    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/builds/" + buildId + "/gates/g-approve/approve")
        .then()
        .statusCode(409)
        .body("applied", equalTo(false));

    // List no longer shows it.
    given()
        .when()
        .get("/api/v1/builds/" + buildId + "/gates")
        .then()
        .statusCode(200)
        .body("size()", equalTo(0));
  }

  @Test
  @TestSecurity(user = "alice", roles = "TRIGGER_BUILD")
  void reject_withReason_returns200() {
    long buildId = seedBuildWithGate("g-reject", "Block?", List.of(), "RUNNING");

    given()
        .contentType("application/json")
        .body("{\"reason\":\"smoke tests red\"}")
        .when()
        .post("/api/v1/builds/" + buildId + "/gates/g-reject/reject")
        .then()
        .statusCode(200)
        .body("applied", equalTo(true))
        .body("status", equalTo("FAILED"));
  }

  // ── 404: approve on a non-existent gate inside an existing build ────────────

  @Test
  @TestSecurity(user = "alice", roles = "TRIGGER_BUILD")
  void approve_unknownGateNodeId_returns404() {
    long buildId = seedBuildWithGate("g-real", "Real Gate", List.of(), "RUNNING");

    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/builds/" + buildId + "/gates/g-does-not-exist/approve")
        .then()
        .statusCode(404);
  }

  // ── 403: SecurityException — caller is not an approver ──────────────────────

  @Test
  @TestSecurity(user = "alice", roles = "TRIGGER_BUILD")
  void approve_notInApproversList_returns403() {
    long buildId = seedBuildWithGate("g-bob-only", "Bob's Gate", List.of("bob"), "RUNNING");

    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/builds/" + buildId + "/gates/g-bob-only/approve")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "bob", roles = "TRIGGER_BUILD")
  void approve_inApproversList_returns200() {
    long buildId = seedBuildWithGate("g-bob-ok", "Bob's Gate", List.of("bob"), "RUNNING");

    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/builds/" + buildId + "/gates/g-bob-ok/approve")
        .then()
        .statusCode(200)
        .body("applied", equalTo(true));
  }

  // ── 400: reason too long ────────────────────────────────────────────────────

  @Test
  @TestSecurity(user = "alice", roles = "TRIGGER_BUILD")
  void reject_reasonTooLong_returns400() {
    long buildId = seedBuildWithGate("g-long", "Long Reason", List.of(), "RUNNING");

    String huge = "x".repeat(501);
    String body = "{\"reason\":\"" + huge + "\"}";

    given()
        .contentType("application/json")
        .body(body)
        .when()
        .post("/api/v1/builds/" + buildId + "/gates/g-long/reject")
        .then()
        .statusCode(400);
  }

  // ── 400: non-numeric buildId ────────────────────────────────────────────────

  @Test
  @TestSecurity(user = "alice", roles = "TRIGGER_BUILD")
  void list_nonNumericBuildId_returns400() {
    given().when().get("/api/v1/builds/not-a-number/gates").then().statusCode(400);
  }

  // ── existing-build-but-not-baked: list returns [] (no model yet) ────────────

  @Test
  @TestSecurity(user = "alice", roles = "READ_JOB")
  void list_buildExistsButNotBaked_returnsEmpty() {
    long jobId = stores.jobs().insert(job("org/unbaked-" + System.nanoTime()));
    long buildId = insertQueuedBuild(stores, jobId);

    given()
        .when()
        .get("/api/v1/builds/" + buildId + "/gates")
        .then()
        .statusCode(200)
        .body("size()", equalTo(0));
  }

  // ── READ_JOB can see pending gates (list) ───────────────────────────────────

  @Test
  @TestSecurity(user = "alice", roles = "READ_JOB")
  void list_readJobCanSeePendingGate() {
    long buildId = seedBuildWithGate("g-read", "Read", List.of(), "RUNNING");

    given()
        .when()
        .get("/api/v1/builds/" + buildId + "/gates")
        .then()
        .statusCode(200)
        .body("size()", greaterThanOrEqualTo(1))
        .body("nodeId", hasItem("g-read"));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /**
   * Insert a job, a build, a serialized {@link PipelineModel} containing a single gate, and the
   * gate's {@code flow_nodes} row at {@code initialStatus} (typically {@code RUNNING}).
   *
   * @return the new build id.
   */
  private long seedBuildWithGate(
      String gateId, String gateName, List<String> approvers, String initialStatus) {
    long jobId = stores.jobs().insert(job("org/gates-" + System.nanoTime()));
    long buildId = insertQueuedBuild(stores, jobId);

    GateModel gate = new GateModel();
    gate.setId(gateId);
    gate.setName(gateName);
    gate.setApprovers(new java.util.ArrayList<>(approvers));
    PipelineModel model = new PipelineModel();
    model.getGates().add(gate);

    String modelJson;
    try {
      modelJson = JSON.writeValueAsString(model);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    stores.builds().updatePipelineModelJson(buildId, modelJson);

    FlowNodeRow node = new FlowNodeRow();
    node.buildId = buildId;
    node.nodeId = gateId;
    node.nodeType = "STAGE";
    node.displayName = gateName;
    node.stepDescriptor = "gate";
    node.status = initialStatus;
    node.startedAt = Instant.now();
    stores.flowNodes().insert(node);

    return buildId;
  }

  private static JobRow job(String fullName) {
    JobRow r = new JobRow();
    r.fullName = fullName;
    r.enabled = true;
    r.pipelineScript = "";
    r.configJson = "{}";
    return r;
  }

  private static long insertQueuedBuild(TitanStores stores, long jobId) {
    BuildRow r = new BuildRow();
    r.jobId = jobId;
    r.buildNumber = stores.builds().nextBuildNumber(jobId);
    r.status = "RUNNING";
    r.queuedAt = Instant.now();
    r.triggeredBy = "test";
    r.triggerType = "manual";
    return stores.withTransaction(conn -> stores.builds().insert(conn, r));
  }
}

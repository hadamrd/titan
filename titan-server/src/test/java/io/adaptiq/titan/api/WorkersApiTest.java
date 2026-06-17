package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.AgentRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * REST-layer tests for {@link WorkersApi}. H2-backed {@link TitanStores}; agents seeded directly
 * via {@link io.adaptiq.titan.store.AgentDao#upsert}. Covers drain happy path, undrain happy path,
 * 404, idempotency on already-DRAINING, and the ADMIN-only RBAC.
 */
@QuarkusTest
@TestSecurity(user = "admin", roles = "ADMIN")
class WorkersApiTest {

  @Inject TitanStores stores;

  // ── GET /api/v1/workers ────────────────────────────────────────────────────

  @Test
  void list_returnsSeededAgentInItems() {
    String id = "worker-list-" + System.nanoTime();
    seedAgent(id, "ONLINE", 3);

    given()
        .when()
        .get("/api/v1/workers")
        .then()
        .statusCode(200)
        .body("total", greaterThanOrEqualTo(1))
        .body("items.id", hasItem(id))
        .body("items.find { it.id == '" + id + "' }.state", equalTo("ONLINE"))
        .body("items.find { it.id == '" + id + "' }.currentTasks", equalTo(3))
        .body("items.find { it.id == '" + id + "' }.labels", hasItem("default"));
  }

  /**
   * Adversarial: the wire envelope must NOT leak internal {@link AgentRow} column names — only the
   * enumerated {@link io.adaptiq.titan.api.dto.WorkerDto} fields. A regression that swaps the DTO
   * for the raw row (e.g. someone returning {@code stores.agents().listAll()} directly) would leak
   * {@code capabilitiesJson}, {@code endpointUrl}, {@code lastSeenBy}, {@code agentId}.
   */
  @Test
  void list_doesNotLeakInternalRowColumns() {
    String id = "worker-leak-" + System.nanoTime();
    AgentRow a = new AgentRow();
    a.agentId = id;
    a.displayName = id;
    a.labels = "linux";
    a.status = "ONLINE";
    a.numExecutors = 1;
    a.maxConcurrent = 4;
    a.currentTasks = 0;
    a.capabilitiesJson = "{\"docker\":true}";
    a.endpointUrl = "http://internal:5050/agent";
    a.lastSeenBy = "controller-1";
    a.remoteFs = "/var/titan";
    a.usageMode = "EXCLUSIVE";
    a.osInfo = "Linux 6.6";
    a.javaVersion = "21";
    a.lastHeartbeat = Instant.now();
    a.registeredAt = Instant.now();
    stores.agents().upsert(a);

    String body = given().when().get("/api/v1/workers").then().statusCode(200).extract().asString();

    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("capabilitiesJson"), "raw row column 'capabilitiesJson' leaked: " + body);
    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("endpointUrl"), "raw row column 'endpointUrl' leaked: " + body);
    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("lastSeenBy"), "raw row column 'lastSeenBy' leaked: " + body);
    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("remoteFs"), "raw row column 'remoteFs' leaked: " + body);
    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("usageMode"), "raw row column 'usageMode' leaked: " + body);
    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("\"agentId\""),
        "raw row column 'agentId' leaked (DTO field is 'id'): " + body);
  }

  @Test
  void list_honorsPaginationCap() {
    given()
        .when()
        .get("/api/v1/workers?limit=999999")
        .then()
        .statusCode(200)
        .body("limit", equalTo(WorkersApi.MAX_LIMIT));
  }

  @Test
  void list_negativeOffsetIsClamped() {
    given()
        .when()
        .get("/api/v1/workers?offset=-50")
        .then()
        .statusCode(200)
        .body("offset", equalTo(0));
  }

  @Test
  @TestSecurity(user = "reader", roles = "READ_JOB")
  void list_readJobIsAllowed() {
    given().when().get("/api/v1/workers").then().statusCode(200);
  }

  @Test
  @TestSecurity(
      user = "anon",
      roles = {})
  void list_unauthorizedRoleIsForbidden() {
    given().when().get("/api/v1/workers").then().statusCode(403);
  }

  @Test
  void list_labelsAreSplitAndTrimmed() {
    String id = "worker-labels-" + System.nanoTime();
    AgentRow a = new AgentRow();
    a.agentId = id;
    a.displayName = id;
    a.labels = "linux, docker , gpu";
    a.status = "ONLINE";
    a.numExecutors = 1;
    a.maxConcurrent = 4;
    a.currentTasks = 0;
    a.lastHeartbeat = Instant.now();
    a.registeredAt = Instant.now();
    stores.agents().upsert(a);

    given()
        .when()
        .get("/api/v1/workers?limit=500")
        .then()
        .statusCode(200)
        .body("items.find { it.id == '" + id + "' }.labels", hasItem("linux"))
        .body("items.find { it.id == '" + id + "' }.labels", hasItem("docker"))
        .body("items.find { it.id == '" + id + "' }.labels", hasItem("gpu"))
        // No empty/whitespace labels survived the split.
        .body("items.find { it.id == '" + id + "' }.labels", not(hasItem("")));
  }

  // ── POST /api/v1/workers/{id}/drain ────────────────────────────────────────

  @Test
  void drain_unknownWorkerReturns404() {
    given().when().post("/api/v1/workers/does-not-exist/drain").then().statusCode(404);
  }

  @Test
  void drain_onlineWorkerTransitionsToDraining() {
    String id = "worker-drain-" + System.nanoTime();
    seedAgent(id, "ONLINE", 2);

    given()
        .when()
        .post("/api/v1/workers/" + id + "/drain")
        .then()
        .statusCode(200)
        .body("workerId", equalTo(id))
        .body("state", equalTo("DRAINING"))
        .body("inflightBuilds", equalTo(2))
        .body("estimatedSecondsRemaining", equalTo(120));

    AgentRow after = stores.agents().findById(id).orElseThrow();
    assertEquals("DRAINING", after.status);
  }

  @Test
  void drain_alreadyDrainingIsIdempotent() {
    String id = "worker-redrain-" + System.nanoTime();
    seedAgent(id, "DRAINING", 0);

    given()
        .when()
        .post("/api/v1/workers/" + id + "/drain")
        .then()
        .statusCode(200)
        .body("state", equalTo("DRAINING"));
  }

  @Test
  void drain_offlineWorkerStaysOffline() {
    String id = "worker-offline-" + System.nanoTime();
    seedAgent(id, "OFFLINE", 0);

    // markDraining is gated to ONLINE/BUSY, so OFFLINE stays as-is. The endpoint
    // returns the post-state without lying about it.
    given()
        .when()
        .post("/api/v1/workers/" + id + "/drain")
        .then()
        .statusCode(200)
        .body("state", equalTo("OFFLINE"));
  }

  // ── POST /api/v1/workers/{id}/undrain ──────────────────────────────────────

  @Test
  void undrain_unknownWorkerReturns404() {
    given().when().post("/api/v1/workers/missing/undrain").then().statusCode(404);
  }

  @Test
  void undrain_drainingWorkerReturnsOnline() {
    String id = "worker-undrain-" + System.nanoTime();
    seedAgent(id, "DRAINING", 1);

    given()
        .when()
        .post("/api/v1/workers/" + id + "/undrain")
        .then()
        .statusCode(200)
        .body("state", equalTo("ONLINE"))
        .body("inflightBuilds", greaterThanOrEqualTo(0));

    AgentRow after = stores.agents().findById(id).orElseThrow();
    assertEquals("ONLINE", after.status);
  }

  // ── RBAC matrix ────────────────────────────────────────────────────────────

  @Test
  @TestSecurity(user = "reader", roles = "READ_JOB")
  void drain_readJobIsForbidden() {
    String id = "worker-rbac-" + System.nanoTime();
    seedAgent(id, "ONLINE", 0);
    given().when().post("/api/v1/workers/" + id + "/drain").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "trigger", roles = "TRIGGER_BUILD")
  void drain_triggerBuildIsForbidden() {
    String id = "worker-rbac2-" + System.nanoTime();
    seedAgent(id, "ONLINE", 0);
    given().when().post("/api/v1/workers/" + id + "/drain").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "reader", roles = "READ_JOB")
  void undrain_readJobIsForbidden() {
    String id = "worker-rbac3-" + System.nanoTime();
    seedAgent(id, "DRAINING", 0);
    given().when().post("/api/v1/workers/" + id + "/undrain").then().statusCode(403);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void seedAgent(String id, String status, int currentTasks) {
    AgentRow a = new AgentRow();
    a.agentId = id;
    a.displayName = id;
    a.labels = "default";
    a.status = status;
    a.numExecutors = 1;
    a.maxConcurrent = 4;
    a.currentTasks = currentTasks;
    a.lastHeartbeat = Instant.now();
    a.registeredAt = Instant.now();
    stores.agents().upsert(a);
  }
}

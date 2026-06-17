package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * REST-layer tests for {@link AdminQueueApi}. H2-backed {@link TitanStores} via {@link
 * H2StoresProducer}. Covers happy path + RBAC + request validation for both endpoints.
 */
@QuarkusTest
@TestSecurity(user = "admin", roles = "ADMIN")
class AdminQueueApiTest {

  @Inject TitanStores stores;

  // ── drain ───────────────────────────────────────────────────────────────

  @Test
  void drain_returnsCountAndCancelsQueuedTasks() {
    long id1 = enqueue("drain-q-" + System.nanoTime(), 0);
    long id2 = enqueue("drain-q-" + System.nanoTime(), 0);

    given()
        .when()
        .post("/api/v1/queue/drain")
        .then()
        .statusCode(200)
        .body("drained", greaterThanOrEqualTo(2));

    assertEquals("CANCELLED", stores.taskQueue().findById(id1).orElseThrow().status);
    assertEquals("CANCELLED", stores.taskQueue().findById(id2).orElseThrow().status);
  }

  @Test
  void drain_isIdempotent_emptyQueueReturnsZero() {
    // Drain once to clear anything other tests left.
    given().when().post("/api/v1/queue/drain").then().statusCode(200);
    // Second call must succeed with 0.
    given().when().post("/api/v1/queue/drain").then().statusCode(200).body("drained", equalTo(0));
  }

  // ── reorder ──────────────────────────────────────────────────────────────

  @Test
  void reorder_setsDescendingPrioritiesHeadFirst() {
    long a = enqueue("reorder-a-" + System.nanoTime(), 1);
    long b = enqueue("reorder-b-" + System.nanoTime(), 1);
    long c = enqueue("reorder-c-" + System.nanoTime(), 1);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("taskIds", List.of(c, a, b)))
        .when()
        .post("/api/v1/queue/reorder")
        .then()
        .statusCode(204);

    int pc = stores.taskQueue().findById(c).orElseThrow().priority;
    int pa = stores.taskQueue().findById(a).orElseThrow().priority;
    int pb = stores.taskQueue().findById(b).orElseThrow().priority;

    // Head id (c) got the highest priority; descending head-first.
    assertEquals(true, pc > pa, "head id has highest priority");
    assertEquals(true, pa > pb, "middle id higher than tail");
  }

  @Test
  void reorder_emptyListIsNoOp() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("taskIds", List.of()))
        .when()
        .post("/api/v1/queue/reorder")
        .then()
        .statusCode(204);
  }

  @Test
  void reorder_unknownIdIsBadRequest() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("taskIds", List.of(999_999_999L)))
        .when()
        .post("/api/v1/queue/reorder")
        .then()
        .statusCode(400);
  }

  @Test
  void reorder_duplicateIdsAreBadRequest() {
    long a = enqueue("reorder-dup-" + System.nanoTime(), 0);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("taskIds", List.of(a, a)))
        .when()
        .post("/api/v1/queue/reorder")
        .then()
        .statusCode(400);
  }

  @Test
  void reorder_nonQueuedTaskIsBadRequest() {
    long a = enqueue("reorder-nonq-" + System.nanoTime(), 0);
    stores.taskQueue().cancel(a); // now CANCELLED
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("taskIds", List.of(a)))
        .when()
        .post("/api/v1/queue/reorder")
        .then()
        .statusCode(400);
  }

  @Test
  void reorder_missingBodyIsBadRequest() {
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/v1/queue/reorder")
        .then()
        .statusCode(400);
  }

  // ── RBAC matrix ──────────────────────────────────────────────────────────

  @Test
  @TestSecurity(user = "reader", roles = "READ_JOB")
  void drain_readJobIsForbidden() {
    given().when().post("/api/v1/queue/drain").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "trigger", roles = "TRIGGER_BUILD")
  void drain_triggerBuildIsForbidden() {
    given().when().post("/api/v1/queue/drain").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "reader", roles = "READ_JOB")
  void reorder_readJobIsForbidden() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("taskIds", List.of()))
        .when()
        .post("/api/v1/queue/reorder")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "trigger", roles = "TRIGGER_BUILD")
  void reorder_triggerBuildIsForbidden() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("taskIds", List.of()))
        .when()
        .post("/api/v1/queue/reorder")
        .then()
        .statusCode(403);
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private long enqueue(String queueName, int priority) {
    TaskQueueRow t = new TaskQueueRow();
    t.type = "ORCHESTRATE";
    t.queueName = queueName;
    t.status = "QUEUED";
    t.priority = priority;
    t.payloadJson = "{}";
    t.attempts = 0;
    t.maxAttempts = 3;
    t.visibilityTimeoutSeconds = 3600;
    t.availableAt = Instant.now();
    return stores.taskQueue().insert(t);
  }
}

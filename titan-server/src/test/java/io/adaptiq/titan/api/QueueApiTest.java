package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * REST-layer tests for {@link QueueApi}. Mirrors {@link BuildsApiTest} — H2-backed {@link
 * TitanStores} via {@link H2StoresProducer}. Covers the happy path, the empty-queue base case,
 * pagination, the join to job names, the QUEUED-only filter, and the RBAC matrix.
 */
@QuarkusTest
@TestSecurity(user = "testuser", roles = "ADMIN")
class QueueApiTest {

  @Inject TitanStores stores;

  @Test
  void list_returnsPagedShape() {
    given()
        .when()
        .get("/api/v1/queue")
        .then()
        .statusCode(200)
        .body("items", notNullValue())
        .body("total", greaterThanOrEqualTo(0))
        .body("offset", equalTo(0))
        .body("limit", equalTo(100));
  }

  @Test
  void list_returnsEnqueuedTaskWithJobName() {
    String jobName = "org/queue-test-" + System.nanoTime();
    long jobId = stores.jobs().insert(job(jobName));
    long buildId = insertBuild(jobId);
    long taskId = enqueueOrchestrate(buildId, 5, "build-jvm");

    given()
        .when()
        .get("/api/v1/queue?limit=500")
        .then()
        .statusCode(200)
        .body("items.find { it.taskId == " + taskId + " }.jobName", equalTo(jobName))
        .body("items.find { it.taskId == " + taskId + " }.buildId", equalTo((int) buildId))
        .body("items.find { it.taskId == " + taskId + " }.priority", equalTo(5))
        .body("items.find { it.taskId == " + taskId + " }.requestedLabels", equalTo("build-jvm"))
        .body("items.find { it.taskId == " + taskId + " }.waitingMs", greaterThanOrEqualTo(0));
  }

  @Test
  void list_excludesNonQueuedTask() {
    long jobId = stores.jobs().insert(job("org/excl-" + System.nanoTime()));
    long buildId = insertBuild(jobId);
    long terminalTaskId = enqueueOrchestrate(buildId, 0, "drained");
    // Move it to a terminal status — must not appear in the feed.
    stores.taskQueue().cancel(terminalTaskId);

    given()
        .when()
        .get("/api/v1/queue?limit=500")
        .then()
        .statusCode(200)
        .body(
            "items.find { it.taskId == " + terminalTaskId + " }",
            org.hamcrest.Matchers.nullValue());
  }

  @Test
  void list_capsLimitAt500() {
    given()
        .when()
        .get("/api/v1/queue?limit=10000")
        .then()
        .statusCode(200)
        .body("limit", equalTo(500));
  }

  @Test
  void list_negativeOffsetIsClampedToZero() {
    given().when().get("/api/v1/queue?offset=-5").then().statusCode(200).body("offset", equalTo(0));
  }

  // ── RBAC matrix ────────────────────────────────────────────────────────────

  @Test
  @TestSecurity(user = "reader", roles = "READ_JOB")
  void list_readJobIsAllowed() {
    given().when().get("/api/v1/queue").then().statusCode(200);
  }

  @Test
  @TestSecurity(user = "trigger", roles = "TRIGGER_BUILD")
  void list_triggerBuildIsAllowed() {
    given().when().get("/api/v1/queue").then().statusCode(200);
  }

  @Test
  @TestSecurity(user = "outsider", roles = "APPROVE_GATE")
  void list_unrelatedRoleIsForbidden() {
    given().when().get("/api/v1/queue").then().statusCode(403);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static JobRow job(String fullName) {
    JobRow r = new JobRow();
    r.fullName = fullName;
    r.enabled = true;
    r.pipelineScript = "";
    r.configJson = "{}";
    return r;
  }

  private long insertBuild(long jobId) {
    BuildRow r = new BuildRow();
    r.jobId = jobId;
    r.buildNumber = stores.builds().nextBuildNumber(jobId);
    r.status = "QUEUED";
    r.queuedAt = Instant.now();
    r.triggeredBy = "test";
    r.triggerType = "manual";
    return stores.withTransaction(conn -> stores.builds().insert(conn, r));
  }

  private long enqueueOrchestrate(long buildId, int priority, String queueName) {
    TaskQueueRow t = new TaskQueueRow();
    t.type = "ORCHESTRATE";
    t.queueName = queueName;
    t.status = "QUEUED";
    t.priority = priority;
    t.payloadJson = "{\"action\":\"BAKE\",\"buildId\":" + buildId + "}";
    t.attempts = 0;
    t.maxAttempts = 3;
    t.visibilityTimeoutSeconds = 3600;
    t.buildId = buildId;
    t.availableAt = Instant.now();
    return stores.taskQueue().insert(t);
  }
}

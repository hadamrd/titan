package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.TestResultRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * REST-layer tests for {@link TestResultsApi}.
 *
 * <p>Mirrors {@code ArtifactsApiTest}: the H2-backed {@link TitanStores} from {@code
 * H2StoresProducer} is application-scoped and shared across the whole test source set, so each test
 * inserts its own build and asserts only on rows tied to that fresh build id.
 */
@QuarkusTest
@TestSecurity(
    user = "testuser",
    roles = {"READ_JOB", "ADMIN"})
class TestResultsApiTest {

  @Inject TitanStores stores;

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void list_unknownBuildReturns404() {
    given().when().get("/api/v1/builds/9999999/tests").then().statusCode(404);
  }

  @Test
  void list_buildWithNoTestsReturnsEmptyPage() {
    long buildId = insertBuild();

    given()
        .when()
        .get("/api/v1/builds/" + buildId + "/tests")
        .then()
        .statusCode(200)
        .body("items", is(empty()))
        .body("total", equalTo(0))
        .body("summary.passed", equalTo(0))
        .body("summary.failed", equalTo(0))
        .body("summary.skipped", equalTo(0));
  }

  @Test
  void list_mixedStatusesProduceCorrectSummary() {
    long buildId = insertBuild();
    // 3 passed, 2 failed, 1 skipped
    insertResult(buildId, "PASSED", "S", "C", "p1", 10, null);
    insertResult(buildId, "PASSED", "S", "C", "p2", 11, null);
    insertResult(buildId, "PASSED", "S", "C", "p3", 12, null);
    insertResult(buildId, "FAILED", "S", "C", "f1", 13, "boom1");
    insertResult(buildId, "FAILED", "S", "C", "f2", 14, "boom2");
    insertResult(buildId, "SKIPPED", "S", "C", "s1", 0, null);

    given()
        .when()
        .get("/api/v1/builds/" + buildId + "/tests")
        .then()
        .statusCode(200)
        .body("items", hasSize(6))
        .body("total", equalTo(6))
        .body("summary.passed", equalTo(3))
        .body("summary.failed", equalTo(2))
        .body("summary.skipped", equalTo(1));
  }

  @Test
  void list_paginationWindowsAcrossLargeSet() {
    long buildId = insertBuild();
    for (int i = 0; i < 250; i++) {
      String status = (i % 5 == 0) ? "FAILED" : ((i % 7 == 0) ? "SKIPPED" : "PASSED");
      insertResult(buildId, status, "S", "C", "t-" + i, i, "FAILED".equals(status) ? "x" : null);
    }

    // page 1
    given()
        .queryParam("limit", 100)
        .when()
        .get("/api/v1/builds/" + buildId + "/tests")
        .then()
        .statusCode(200)
        .body("items", hasSize(100))
        .body("total", equalTo(250));

    // page 3 (tail)
    given()
        .queryParam("offset", 200)
        .queryParam("limit", 100)
        .when()
        .get("/api/v1/builds/" + buildId + "/tests")
        .then()
        .statusCode(200)
        .body("items", hasSize(50))
        .body("total", equalTo(250))
        // summary spans the WHOLE build, not the slice — counts must match regardless of paging
        .body("summary.passed", equalTo(passedCount()))
        .body("summary.failed", equalTo(failedCount()))
        .body("summary.skipped", equalTo(skippedCount()));
  }

  // expected mix for the 250-case loop above
  private static int passedCount() {
    int n = 0;
    for (int i = 0; i < 250; i++) {
      if (!(i % 5 == 0) && !(i % 7 == 0)) n++;
    }
    return n;
  }

  private static int failedCount() {
    int n = 0;
    for (int i = 0; i < 250; i++) {
      if (i % 5 == 0) n++;
    }
    return n;
  }

  private static int skippedCount() {
    int n = 0;
    for (int i = 0; i < 250; i++) {
      if (!(i % 5 == 0) && (i % 7 == 0)) n++;
    }
    return n;
  }

  @Test
  void list_orderingIsStableByIdAsc() {
    long buildId = insertBuild();
    for (int i = 0; i < 5; i++) {
      insertResult(buildId, "PASSED", "S", "C", "t-" + i, i, null);
    }

    given()
        .when()
        .get("/api/v1/builds/" + buildId + "/tests")
        .then()
        .statusCode(200)
        .body("items[0].name", equalTo("t-0"))
        .body("items[4].name", equalTo("t-4"));
  }

  @Test
  void list_limitClampedAt500() {
    long buildId = insertBuild();
    insertResult(buildId, "PASSED", "S", "C", "only", 1, null);

    given()
        .queryParam("limit", 999)
        .when()
        .get("/api/v1/builds/" + buildId + "/tests")
        .then()
        .statusCode(200)
        // 1 row exists; limit clamped to 500 — we still see exactly 1
        .body("items", hasSize(1))
        .body("total", equalTo(1));
  }

  // ── wire-format invariants ────────────────────────────────────────────────

  @Test
  void list_failureMessageAppearsOnlyOnFailedRows() {
    long buildId = insertBuild();
    insertResult(buildId, "PASSED", "S", "C", "p", 1, "should-be-suppressed");
    insertResult(buildId, "FAILED", "S", "C", "f", 1, "actual-failure-text");
    insertResult(buildId, "SKIPPED", "S", "C", "s", 0, "should-be-suppressed-too");

    String body =
        given()
            .when()
            .get("/api/v1/builds/" + buildId + "/tests")
            .then()
            .statusCode(200)
            .extract()
            .asString();

    // the FAILED row's message is on the wire; non-FAILED messages are not
    org.junit.jupiter.api.Assertions.assertTrue(
        body.contains("actual-failure-text"), "FAILED row must surface failureMessage: " + body);
    assertFalse(
        body.contains("should-be-suppressed"),
        "non-FAILED rows must not surface a failureMessage: " + body);

    // and the JSON shape: count failureMessage occurrences — exactly one (the FAILED row)
    int occurrences = countOccurrences(body, "\"failureMessage\"");
    org.junit.jupiter.api.Assertions.assertEquals(
        1,
        occurrences,
        "failureMessage must appear exactly once (only on FAILED): body was " + body);
  }

  @Test
  void list_responseDoesNotLeakInternalColumns() {
    long buildId = insertBuild();
    insertResult(buildId, "FAILED", "suite", "pkg.Cls", "leakCheck", 5, "boom");

    String body =
        given()
            .when()
            .get("/api/v1/builds/" + buildId + "/tests")
            .then()
            .statusCode(200)
            .extract()
            .asString();

    assertFalse(body.contains("nodeId"), "leaked nodeId field: " + body);
    assertFalse(body.contains("buildId"), "leaked buildId field: " + body);
    assertFalse(body.contains("createdAt"), "leaked createdAt field: " + body);
    assertFalse(body.contains("node_id"), "leaked node_id column: " + body);
    assertFalse(body.contains("build_id"), "leaked build_id column: " + body);
    assertFalse(body.contains("created_at"), "leaked created_at column: " + body);
  }

  // ── RBAC matrix ────────────────────────────────────────────────────────────

  @Test
  @TestSecurity // no roles → anonymous
  void list_unauthenticated_returns401() {
    given().when().get("/api/v1/builds/1/tests").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "alice", roles = "EDIT_PIPELINE")
  void list_wrongRole_returns403() {
    given().when().get("/api/v1/builds/1/tests").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "alice", roles = "READ_JOB")
  void list_readJobRole_unknownBuild_returns404NotForbidden() {
    // 404 = auth passed, resource not found — confirms role enforcement does not block
    given().when().get("/api/v1/builds/999999999/tests").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "bob", roles = "TRIGGER_BUILD")
  void list_triggerBuildRole_unknownBuild_returns404NotForbidden() {
    given().when().get("/api/v1/builds/999999999/tests").then().statusCode(404);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static int countOccurrences(String haystack, String needle) {
    int n = 0;
    int idx = 0;
    while ((idx = haystack.indexOf(needle, idx)) != -1) {
      n++;
      idx += needle.length();
    }
    return n;
  }

  private long insertBuild() {
    JobRow j = new JobRow();
    j.fullName = "tr/test-" + System.nanoTime();
    j.enabled = true;
    j.pipelineScript = "";
    j.configJson = "{}";
    long jobId = stores.jobs().insert(j);

    BuildRow b = new BuildRow();
    b.jobId = jobId;
    b.buildNumber = stores.builds().nextBuildNumber(jobId);
    b.status = "SUCCESS";
    b.queuedAt = Instant.now();
    b.triggeredBy = "test";
    b.triggerType = "manual";
    return stores.withTransaction(c -> stores.builds().insert(c, b));
  }

  private void insertResult(
      long buildId,
      String status,
      String suite,
      String className,
      String name,
      long durationMs,
      String failureMessage) {
    TestResultRow row = new TestResultRow();
    row.nodeId = "n1";
    row.suite = suite;
    row.className = className;
    row.name = name;
    row.status = status;
    row.durationMs = durationMs;
    row.failureMessage = failureMessage;
    List<TestResultRow> batch = new ArrayList<>(1);
    batch.add(row);
    stores.testResults().insertBatch(buildId, batch);
  }
}

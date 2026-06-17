package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for builds API ({@link JobBuildsApi} + {@link BuildDetailApi}). H2-backed {@link
 * TitanStores} via {@link H2StoresProducer} — no Docker. Quarkus test server on ephemeral port;
 * REST-assured pre-configured by {@code @QuarkusTest}.
 */
@QuarkusTest
@TestSecurity(user = "testuser", roles = "ADMIN")
class BuildsApiTest {

  @Inject TitanStores stores;

  // ── GET /api/v1/jobs/{jobId}/builds ────────────────────────────────────────

  @Test
  void listBuilds_unknownJobReturns404() {
    given().when().get("/api/v1/jobs/99999/builds").then().statusCode(404);
  }

  @Test
  void listBuilds_knownJobNoBuildsReturnsEmptyPage() {
    long jobId = stores.jobs().insert(job("org/empty-" + System.nanoTime()));

    given()
        .when()
        .get("/api/v1/jobs/" + jobId + "/builds")
        .then()
        .statusCode(200)
        .body("total", equalTo(0))
        .body("items", is(empty()));
  }

  @Test
  void listBuilds_returnsBuildsForCorrectJobOnly() {
    long jobId1 = stores.jobs().insert(job("org/a-" + System.nanoTime()));
    long jobId2 = stores.jobs().insert(job("org/b-" + System.nanoTime()));
    insertBuild(stores, jobId1, "RUNNING");
    insertBuild(stores, jobId1, "SUCCESS");
    insertBuild(stores, jobId2, "QUEUED");

    given()
        .when()
        .get("/api/v1/jobs/" + jobId1 + "/builds")
        .then()
        .statusCode(200)
        .body("total", equalTo(2));
  }

  // ── POST /api/v1/jobs/{jobId}/builds ──────────────────────────────────────

  @Test
  void triggerBuild_unknownJobReturns404() {
    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/jobs/99999/builds")
        .then()
        .statusCode(404);
  }

  @Test
  void triggerBuild_returns201WithBuildIdAndQueued() {
    long jobId = stores.jobs().insert(job("org/trigger-" + System.nanoTime()));

    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/jobs/" + jobId + "/builds")
        .then()
        .statusCode(201)
        .body("buildId", greaterThan(0))
        .body("buildNumber", equalTo(1))
        .body("status", equalTo("QUEUED"));
  }

  @Test
  void triggerBuild_secondTriggerGetsBuildNumber2() {
    long jobId = stores.jobs().insert(job("org/numbering-" + System.nanoTime()));
    insertBuild(stores, jobId, "SUCCESS");

    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/jobs/" + jobId + "/builds")
        .then()
        .statusCode(201)
        .body("buildNumber", equalTo(2));
  }

  @Test
  void triggerBuild_insertsInitialBakeTask() {
    long jobId = stores.jobs().insert(job("org/task-" + System.nanoTime()));

    // REST-assured returns Integer for small JSON numbers; use Number to cover both int and long.
    long buildId =
        ((Number)
                given()
                    .contentType("application/json")
                    .body("{}")
                    .when()
                    .post("/api/v1/jobs/" + jobId + "/builds")
                    .then()
                    .statusCode(201)
                    .extract()
                    .path("buildId"))
            .longValue();

    var tasks = stores.taskQueue().listByBuild(buildId);
    assertFalse(tasks.isEmpty(), "at least one task must be enqueued after trigger");
    assertTrue(
        tasks.stream().anyMatch(t -> "ORCHESTRATE".equals(t.type)),
        "must have an ORCHESTRATE task; tasks=" + tasks);
  }

  // ── GET /api/v1/builds/{buildId} ──────────────────────────────────────────

  @Test
  void getBuild_unknownIdReturns404() {
    given()
        .when()
        .get("/api/v1/builds/999999")
        .then()
        .statusCode(404)
        .contentType(containsString("problem+json"))
        .body("status", equalTo(404));
  }

  @Test
  void getBuild_knownIdReturnsCorrectDto() {
    long jobId = stores.jobs().insert(job("org/svc-" + System.nanoTime()));
    long buildId = insertBuild(stores, jobId, "RUNNING");

    given()
        .when()
        .get("/api/v1/builds/" + buildId)
        .then()
        .statusCode(200)
        .body("id", equalTo((int) buildId))
        .body("status", equalTo("RUNNING"));
  }

  @Test
  void getBuild_nonNumericIdReturns400() {
    given().when().get("/api/v1/builds/bad-id").then().statusCode(400);
  }

  // ── ETag / If-None-Match (#1099) ──────────────────────────────────────────

  @Test
  void getBuild_emitsEtagHeader() {
    long jobId = stores.jobs().insert(job("org/etag-" + System.nanoTime()));
    long buildId = insertBuild(stores, jobId, "RUNNING");

    String etag =
        given()
            .when()
            .get("/api/v1/builds/" + buildId)
            .then()
            .statusCode(200)
            .extract()
            .header("ETag");

    assertTrue(etag != null && !etag.isBlank(), "ETag header must be present");
    assertTrue(
        etag.startsWith("W/\"") && etag.endsWith("\""), "ETag must be quoted weak validator");
  }

  @Test
  void getBuild_matchingIfNoneMatchReturns304WithEmptyBody() {
    long jobId = stores.jobs().insert(job("org/etag-304-" + System.nanoTime()));
    long buildId = insertBuild(stores, jobId, "RUNNING");

    String etag =
        given()
            .when()
            .get("/api/v1/builds/" + buildId)
            .then()
            .statusCode(200)
            .extract()
            .header("ETag");

    String body =
        given()
            .header("If-None-Match", etag)
            .when()
            .get("/api/v1/builds/" + buildId)
            .then()
            .statusCode(304)
            .header("ETag", equalTo(etag))
            .extract()
            .body()
            .asString();

    assertTrue(body == null || body.isEmpty(), "304 must have empty body, got: " + body);
  }

  @Test
  void getBuild_staleIfNoneMatchReturns200WithFreshEtag() {
    long jobId = stores.jobs().insert(job("org/etag-stale-" + System.nanoTime()));
    long buildId = insertBuild(stores, jobId, "RUNNING");

    given()
        .header("If-None-Match", "W/\"completely-bogus-hash\"")
        .when()
        .get("/api/v1/builds/" + buildId)
        .then()
        .statusCode(200)
        .header("ETag", containsString("W/\""))
        .body("id", equalTo((int) buildId));
  }

  // ── GET /api/v1/builds/{buildId}/nodes ────────────────────────────────────

  @Test
  void listNodes_unknownBuildReturns404() {
    given().when().get("/api/v1/builds/999999/nodes").then().statusCode(404);
  }

  @Test
  void listNodes_returnsAllNodesForBuildInOrder() {
    long jobId = stores.jobs().insert(job("org/nodes-" + System.nanoTime()));
    long buildId = insertBuild(stores, jobId, "RUNNING");
    insertFlowNode(stores, buildId, "n1", "STAGE", "RUNNING");
    insertFlowNode(stores, buildId, "n2", "STEP", "QUEUED");

    given()
        .when()
        .get("/api/v1/builds/" + buildId + "/nodes")
        .then()
        .statusCode(200)
        .body("size()", equalTo(2))
        .body("[0].nodeId", equalTo("n1"))
        .body("[1].nodeId", equalTo("n2"));
  }

  @Test
  void listNodes_emptyWhenBuildHasNoNodes() {
    long jobId = stores.jobs().insert(job("org/no-nodes-" + System.nanoTime()));
    long buildId = insertBuild(stores, jobId, "QUEUED");

    given()
        .when()
        .get("/api/v1/builds/" + buildId + "/nodes")
        .then()
        .statusCode(200)
        .body("size()", equalTo(0));
  }

  // ── POST /api/v1/builds/{buildId}/cancel ──────────────────────────────────

  @Test
  void cancelBuild_unknownIdReturns404() {
    given().when().post("/api/v1/builds/999999/cancel").then().statusCode(404);
  }

  @Test
  void cancel_terminalSuccess_returns409WithCurrentStatus() {
    long jobId = stores.jobs().insert(job("org/done-success-" + System.nanoTime()));
    long buildId = insertBuild(stores, jobId, "SUCCESS");

    given()
        .when()
        .post("/api/v1/builds/" + buildId + "/cancel")
        .then()
        .statusCode(409)
        .contentType(containsString("problem+json"))
        .body("status", equalTo(409))
        .body("title", equalTo("Conflict"))
        .body("detail", containsString("SUCCESS"))
        .body("build_id", equalTo((int) buildId))
        .body("current_status", equalTo("SUCCESS"));

    // Status must not have been rewritten by the rejected call.
    assertEquals(
        "SUCCESS",
        stores.builds().findById(buildId).orElseThrow().status,
        "rejected cancel must not mutate the build's status");
  }

  @Test
  void cancel_terminalFailed_returns409() {
    long jobId = stores.jobs().insert(job("org/done-failed-" + System.nanoTime()));
    long buildId = insertBuild(stores, jobId, "FAILED");

    given()
        .when()
        .post("/api/v1/builds/" + buildId + "/cancel")
        .then()
        .statusCode(409)
        .contentType(containsString("problem+json"))
        .body("current_status", equalTo("FAILED"))
        .body("detail", containsString("FAILED"));
  }

  @Test
  void cancel_terminalAborted_returns409() {
    long jobId = stores.jobs().insert(job("org/done-aborted-" + System.nanoTime()));
    long buildId = insertBuild(stores, jobId, "ABORTED");

    given()
        .when()
        .post("/api/v1/builds/" + buildId + "/cancel")
        .then()
        .statusCode(409)
        .contentType(containsString("problem+json"))
        .body("current_status", equalTo("ABORTED"))
        .body("detail", containsString("ABORTED"));
  }

  @Test
  void cancel_terminalUnstable_returns409() {
    long jobId = stores.jobs().insert(job("org/done-unstable-" + System.nanoTime()));
    long buildId = insertBuild(stores, jobId, "UNSTABLE");

    given()
        .when()
        .post("/api/v1/builds/" + buildId + "/cancel")
        .then()
        .statusCode(409)
        .contentType(containsString("problem+json"))
        .body("current_status", equalTo("UNSTABLE"))
        .body("detail", containsString("UNSTABLE"));
  }

  @Test
  void cancel_queued_returns202() {
    long jobId = stores.jobs().insert(job("org/live-queued-" + System.nanoTime()));
    long buildId = insertBuild(stores, jobId, "QUEUED");

    given()
        .when()
        .post("/api/v1/builds/" + buildId + "/cancel")
        .then()
        .statusCode(202)
        .body("aborted", equalTo(true));

    BuildRow after = stores.builds().findById(buildId).orElseThrow();
    assertEquals("ABORTED", after.status, "build must be ABORTED in DB after cancel");
  }

  @Test
  void cancel_running_returns202() {
    long jobId = stores.jobs().insert(job("org/live-running-" + System.nanoTime()));
    long buildId = insertBuild(stores, jobId, "RUNNING");

    given()
        .when()
        .post("/api/v1/builds/" + buildId + "/cancel")
        .then()
        .statusCode(202)
        .body("aborted", equalTo(true));

    BuildRow after = stores.builds().findById(buildId).orElseThrow();
    assertEquals("ABORTED", after.status, "build must be ABORTED in DB after cancel");
  }

  // ── GET /api/v1/builds?headSha=<sha> (closes #967) ────────────────────────

  @Test
  void listBuilds_byHeadSha_returnsMatchingBuilds() {
    long jobId = stores.jobs().insert(job("org/headsha-match-" + System.nanoTime()));
    String sha = "abcdef0123456789abcdef0123456789abcdef01";
    long matchingId = insertBuildWithTriggerMeta(stores, jobId, "SUCCESS", sha);
    // Sibling build with a different SHA — must NOT match.
    insertBuildWithTriggerMeta(
        stores, jobId, "SUCCESS", "0000000000000000000000000000000000000000");

    given()
        .when()
        .get("/api/v1/builds?headSha=" + sha)
        .then()
        .statusCode(200)
        .body("total", equalTo(1))
        .body("items.size()", equalTo(1))
        .body("items[0].id", equalTo((int) matchingId))
        .body("items[0].triggerMeta.commitSha", equalTo(sha));
  }

  @Test
  void listBuilds_byHeadSha_malformedSha_returns400() {
    // Short SHA (7-char) — not the full 40-char form. Must reject loudly.
    given()
        .when()
        .get("/api/v1/builds?headSha=abc1234")
        .then()
        .statusCode(400)
        .contentType(containsString("problem+json"))
        .body("detail", containsString("40-character"));

    // Right length, non-hex character.
    given()
        .when()
        .get("/api/v1/builds?headSha=zzzzef0123456789abcdef0123456789abcdef01")
        .then()
        .statusCode(400)
        .contentType(containsString("problem+json"))
        .body("detail", containsString("hex"));
  }

  @Test
  void listBuilds_byHeadSha_unknownSha_returnsEmpty() {
    long jobId = stores.jobs().insert(job("org/headsha-unknown-" + System.nanoTime()));
    insertBuildWithTriggerMeta(
        stores, jobId, "SUCCESS", "abcdef0123456789abcdef0123456789abcdef01");

    given()
        .when()
        .get("/api/v1/builds?headSha=ffffffffffffffffffffffffffffffffffffffff")
        .then()
        .statusCode(200)
        .body("total", equalTo(0))
        .body("items", is(empty()));
  }

  @Test
  void listBuilds_byHeadSha_caseInsensitive() {
    // Persisted lowercase; query upper-case — must still match (SHA hex is case-insensitive).
    long jobId = stores.jobs().insert(job("org/headsha-case-" + System.nanoTime()));
    String sha = "deadbeef0123456789deadbeef0123456789dead";
    insertBuildWithTriggerMeta(stores, jobId, "SUCCESS", sha);

    given()
        .when()
        .get("/api/v1/builds?headSha=" + sha.toUpperCase(java.util.Locale.ROOT))
        .then()
        .statusCode(200)
        .body("total", equalTo(1));
  }

  // ── BuildDto.triggerMeta.commitSha returns full SHA (regression #971) ─────

  @Test
  void triggerMetaCommitSha_returnsFullSha() {
    long jobId = stores.jobs().insert(job("org/full-sha-dto-" + System.nanoTime()));
    String fullSha = "1234567890abcdef1234567890abcdef12345678";
    long buildId = insertBuildWithTriggerMeta(stores, jobId, "SUCCESS", fullSha);

    given()
        .when()
        .get("/api/v1/builds/" + buildId)
        .then()
        .statusCode(200)
        .body("triggerMeta.commitSha", equalTo(fullSha))
        .body("triggerMeta.commitSha.length()", equalTo(40));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  static JobRow job(String fullName) {
    JobRow r = new JobRow();
    r.fullName = fullName;
    r.enabled = true;
    r.pipelineScript = "";
    r.configJson = "{}";
    return r;
  }

  static long insertBuild(TitanStores stores, long jobId, String status) {
    BuildRow r = new BuildRow();
    r.jobId = jobId;
    r.buildNumber = stores.builds().nextBuildNumber(jobId);
    r.status = status;
    r.queuedAt = Instant.now();
    r.triggeredBy = "test";
    r.triggerType = "manual";
    return stores.withTransaction(conn -> stores.builds().insert(conn, r));
  }

  /**
   * Insert a build whose {@code trigger_meta_json} carries the supplied full-40-char commit SHA —
   * the post-#971 wire shape webhook-born builds persist. Used by the {@code ?headSha=} filter
   * tests and the {@code BuildDto.triggerMeta.commitSha} full-SHA regression test.
   */
  static long insertBuildWithTriggerMeta(
      TitanStores stores, long jobId, String status, String commitSha) {
    BuildRow r = new BuildRow();
    r.jobId = jobId;
    r.buildNumber = stores.builds().nextBuildNumber(jobId);
    r.status = status;
    r.queuedAt = Instant.now();
    r.triggeredBy = "test";
    r.triggerType = "github";
    r.triggerMetaJson = "{\"branch\":\"trunk\",\"commitSha\":\"" + commitSha + "\"}";
    return stores.withTransaction(conn -> stores.builds().insert(conn, r));
  }

  static void insertFlowNode(
      TitanStores stores, long buildId, String nodeId, String nodeType, String status) {
    FlowNodeRow r = new FlowNodeRow();
    r.buildId = buildId;
    r.nodeId = nodeId;
    r.nodeType = nodeType;
    r.status = status;
    stores.flowNodes().insert(r);
  }
}

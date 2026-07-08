package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the full HTTP API surface — real PostgreSQL via Testcontainers ({@link
 * PostgresTestResource}), real {@link TitanStores}, real Quarkus server.
 *
 * <p>Uses {@link PostgresItProfile} to force Quarkus to re-augment with {@code db-kind=postgresql}
 * (a build-time property) so the PostgreSQL driver is active when {@link PostgresTestResource}
 * injects the Testcontainers JDBC URL at runtime.
 *
 * <p>Covers: healthz, list jobs, get job, list builds, trigger build, get build, list nodes, cancel
 * build.
 */
@QuarkusTest
@TestProfile(PostgresItProfile.class)
@QuarkusTestResource(value = PostgresTestResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = "testuser", roles = "ADMIN")
class ApiSurfaceIT {

  @Inject TitanStores stores;

  // ── /healthz (backward-compat smoke) ──────────────────────────────────────

  @Test
  void healthz_returns200WithStatusOk() {
    given().when().get("/healthz").then().statusCode(200).body("status", equalTo("ok"));
  }

  // ── /q/health ─────────────────────────────────────────────────────────────

  @Test
  void quarkusHealth_returns200() {
    given().when().get("/q/health").then().statusCode(200).body("status", equalTo("UP"));
  }

  @Test
  void quarkusHealthLive_returns200() {
    given().when().get("/q/health/live").then().statusCode(200).body("status", equalTo("UP"));
  }

  @Test
  void quarkusHealthReady_returns200() {
    given().when().get("/q/health/ready").then().statusCode(200).body("status", equalTo("UP"));
  }

  // ── /q/openapi ────────────────────────────────────────────────────────────

  @Test
  void openapi_returns200WithOpenApiSpec() {
    given().when().get("/q/openapi").then().statusCode(200).body(containsString("openapi"));
  }

  // ── GET /api/v1/jobs ──────────────────────────────────────────────────────

  @Test
  void listJobs_returnsSeededJob() {
    long jobId = stores.jobs().insert(seedJob("it/list-jobs-" + System.nanoTime()));

    given()
        .when()
        .get("/api/v1/jobs")
        .then()
        .statusCode(200)
        .body("total", greaterThanOrEqualTo(1));
  }

  // ── GET /api/v1/jobs/{jobId} ──────────────────────────────────────────────

  @Test
  void getJob_knownIdReturns200() {
    String name = "it/get-job-" + System.nanoTime();
    long jobId = stores.jobs().insert(seedJob(name));

    given()
        .when()
        .get("/api/v1/jobs/" + jobId)
        .then()
        .statusCode(200)
        .body("id", equalTo((int) jobId))
        .body("fullName", equalTo(name));
  }

  @Test
  void getJob_missingIdReturns404() {
    given()
        .when()
        .get("/api/v1/jobs/999999999")
        .then()
        .statusCode(404)
        .contentType(containsString("problem+json"))
        .body("status", equalTo(404));
  }

  // ── POST /api/v1/jobs/{jobId}/builds → GET /api/v1/builds/{buildId} ───────

  @Test
  void triggerBuild_thenGetBuild_converges() {
    long jobId = stores.jobs().insert(seedJob("it/trigger-" + System.nanoTime()));

    // Trigger
    // REST-assured returns Integer for small JSON longs; use Number to avoid ClassCastException.
    long buildId =
        ((Number)
                given()
                    .contentType("application/json")
                    .body("{\"triggeredBy\":\"it-test\"}")
                    .when()
                    .post("/api/v1/jobs/" + jobId + "/builds")
                    .then()
                    .statusCode(201)
                    .body("buildId", greaterThan(0))
                    .body("buildNumber", equalTo(1))
                    .body("status", equalTo("QUEUED"))
                    .extract()
                    .path("buildId"))
            .longValue();

    // Get build
    given()
        .when()
        .get("/api/v1/builds/" + buildId)
        .then()
        .statusCode(200)
        .body("id", equalTo((int) buildId))
        .body("status", equalTo("QUEUED"));

    // List builds for job
    given()
        .when()
        .get("/api/v1/jobs/" + jobId + "/builds")
        .then()
        .statusCode(200)
        .body("total", equalTo(1));
  }

  // ── GET /api/v1/builds/{buildId}/nodes ────────────────────────────────────

  @Test
  void listNodes_emptyForFreshBuild() {
    long jobId = stores.jobs().insert(seedJob("it/nodes-" + System.nanoTime()));

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

    given()
        .when()
        .get("/api/v1/builds/" + buildId + "/nodes")
        .then()
        .statusCode(200)
        .body("size()", equalTo(0));
  }

  // ── POST /api/v1/builds/{buildId}/cancel ──────────────────────────────────

  @Test
  void cancelBuild_queuedBuildBecomesAborted() {
    long jobId = stores.jobs().insert(seedJob("it/cancel-" + System.nanoTime()));

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

    // Cancel
    given()
        .when()
        .post("/api/v1/builds/" + buildId + "/cancel")
        .then()
        .statusCode(202)
        .body("aborted", equalTo(true));

    // Status via GET must now show ABORTED
    given()
        .when()
        .get("/api/v1/builds/" + buildId)
        .then()
        .statusCode(200)
        .body("status", equalTo("ABORTED"));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static JobRow seedJob(String fullName) {
    JobRow r = new JobRow();
    r.fullName = fullName;
    r.displayName = fullName;
    r.enabled = true;
    r.pipelineScript = "stages:\n  - stage: hello\n    steps:\n      - sh: echo hi\n";
    r.configJson = "{}";
    return r;
  }
}

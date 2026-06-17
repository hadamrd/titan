package io.adaptiq.titan.auth;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * RBAC enforcement tests — verifies that every protected endpoint:
 *
 * <ol>
 *   <li>Returns HTTP 401 for unauthenticated requests.
 *   <li>Returns HTTP 403 when the caller lacks the required role.
 *   <li>Returns a non-401/403 code when the caller has the required role.
 * </ol>
 *
 * <p>No Docker or Postgres needed: OIDC is disabled in the test profile ({@code
 * quarkus.oidc.enabled=false}) and the H2 CDI alternative wires in-memory stores. Security is
 * exercised via {@code @TestSecurity} — Quarkus injects a synthetic {@link
 * io.quarkus.security.identity.SecurityIdentity} with the declared user + roles.
 *
 * <p>Unauthenticated cases rely on the absence of {@code @TestSecurity}, which causes Quarkus to
 * present an anonymous identity; with {@code @RolesAllowed} on the resource method Quarkus returns
 * 401.
 */
@QuarkusTest
class RbacTest {

  // ── GET /api/v1/jobs ──────────────────────────────────────────────────────

  @Test
  void listJobs_unauthenticated_returns401() {
    given().when().get("/api/v1/jobs").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "alice", roles = "TRIGGER_BUILD")
  void listJobs_wrongRole_returns403() {
    given().when().get("/api/v1/jobs").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "alice", roles = "READ_JOB")
  void listJobs_readJobRole_returns200() {
    given().when().get("/api/v1/jobs").then().statusCode(200);
  }

  @Test
  @TestSecurity(user = "admin", roles = "ADMIN")
  void listJobs_adminRole_returns200() {
    given().when().get("/api/v1/jobs").then().statusCode(200);
  }

  // ── GET /api/v1/jobs/{jobId} ──────────────────────────────────────────────

  @Test
  void getJob_unauthenticated_returns401() {
    given().when().get("/api/v1/jobs/1").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "alice", roles = "TRIGGER_BUILD")
  void getJob_wrongRole_returns403() {
    given().when().get("/api/v1/jobs/1").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "alice", roles = "READ_JOB")
  void getJob_readJobRole_returns404NotForbidden() {
    // 404 = auth passed, resource not found — confirms role enforcement does not block
    given().when().get("/api/v1/jobs/999999999").then().statusCode(404);
  }

  // ── GET /api/v1/jobs/{jobId}/builds ──────────────────────────────────────

  @Test
  void listBuilds_unauthenticated_returns401() {
    given().when().get("/api/v1/jobs/1/builds").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "alice", roles = "TRIGGER_BUILD")
  void listBuilds_wrongRole_returns403() {
    given().when().get("/api/v1/jobs/1/builds").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "alice", roles = "READ_JOB")
  void listBuilds_readJobRole_returns404NotForbidden() {
    given().when().get("/api/v1/jobs/999999999/builds").then().statusCode(404);
  }

  // ── POST /api/v1/jobs/{jobId}/builds ─────────────────────────────────────

  @Test
  void triggerBuild_unauthenticated_returns401() {
    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/jobs/1/builds")
        .then()
        .statusCode(401);
  }

  @Test
  @TestSecurity(user = "alice", roles = "READ_JOB")
  void triggerBuild_readOnlyRole_returns403() {
    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/jobs/1/builds")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "alice", roles = "TRIGGER_BUILD")
  void triggerBuild_triggerRole_returns404NotForbidden() {
    // 404 = auth passed; job 999999999 does not exist
    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/jobs/999999999/builds")
        .then()
        .statusCode(404);
  }

  @Test
  @TestSecurity(user = "admin", roles = "ADMIN")
  void triggerBuild_adminRole_returns404NotForbidden() {
    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/jobs/999999999/builds")
        .then()
        .statusCode(404);
  }

  // ── GET /api/v1/builds/{buildId} ─────────────────────────────────────────

  @Test
  void getBuild_unauthenticated_returns401() {
    given().when().get("/api/v1/builds/1").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "alice", roles = "TRIGGER_BUILD")
  void getBuild_wrongRole_returns403() {
    given().when().get("/api/v1/builds/1").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "alice", roles = "READ_JOB")
  void getBuild_readJobRole_returns404NotForbidden() {
    given().when().get("/api/v1/builds/999999999").then().statusCode(404);
  }

  // ── GET /api/v1/builds/{buildId}/nodes ───────────────────────────────────

  @Test
  void listNodes_unauthenticated_returns401() {
    given().when().get("/api/v1/builds/1/nodes").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "alice", roles = "TRIGGER_BUILD")
  void listNodes_wrongRole_returns403() {
    given().when().get("/api/v1/builds/1/nodes").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "alice", roles = "READ_JOB")
  void listNodes_readJobRole_returns404NotForbidden() {
    given().when().get("/api/v1/builds/999999999/nodes").then().statusCode(404);
  }

  // ── POST /api/v1/builds/{buildId}/cancel ─────────────────────────────────

  @Test
  void cancelBuild_unauthenticated_returns401() {
    given().when().post("/api/v1/builds/1/cancel").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "alice", roles = "READ_JOB")
  void cancelBuild_readOnlyRole_returns403() {
    given().when().post("/api/v1/builds/1/cancel").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "alice", roles = "TRIGGER_BUILD")
  void cancelBuild_triggerRole_returns404NotForbidden() {
    given().when().post("/api/v1/builds/999999999/cancel").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "admin", roles = "ADMIN")
  void cancelBuild_adminRole_returns404NotForbidden() {
    given().when().post("/api/v1/builds/999999999/cancel").then().statusCode(404);
  }
}

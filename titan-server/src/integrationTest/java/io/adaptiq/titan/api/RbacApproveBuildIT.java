package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * Adversarial RBAC test for the new {@code APPROVE_BUILD} role (#715 backend).
 *
 * <p>Mirrors {@code RbacReplayBuildIT}: {@code @QuarkusTest} + {@code @TestSecurity} projects the
 * requested role set into Quarkus's {@code SecurityIdentity}, which is what {@code @RolesAllowed}
 * consults at request time. Keycloak is NOT started — the assertions here load-bear because the
 * only glue under test is {@code @RolesAllowed} ↔ {@code SecurityIdentity}.
 *
 * <p>Asserts the contract corners for #715:
 *
 * <ul>
 *   <li>a user holding ONLY {@code APPROVE_BUILD} can POST {@code /api/v1/approvals/{id}/approve}
 *       (handler reached → not 403; 404 from the missing row is post-auth);
 *   <li>the same user can POST {@code /api/v1/approvals/{id}/reject} (handler reached → not 403);
 *   <li>{@code APPROVE_BUILD} CANNOT POST {@code /api/v1/jobs} (403) — distinct from {@code
 *       EDIT_PIPELINE};
 *   <li>a user holding only {@code READ_JOB} CANNOT decide (403);
 *   <li>{@code ADMIN} continues to satisfy the decide gate (regression).
 * </ul>
 */
@QuarkusTest
class RbacApproveBuildIT {

  private static final String EMPTY_BODY = "{}";

  @Test
  @TestSecurity(
      user = "approver",
      roles = {"APPROVE_BUILD"})
  void approveBuildOnly_canApprove() {
    int status =
        given()
            .contentType("application/json")
            .body(EMPTY_BODY)
            .when()
            .post("/api/v1/approvals/999999999/approve")
            .then()
            .extract()
            .statusCode();
    org.junit.jupiter.api.Assertions.assertNotEquals(
        403,
        status,
        "APPROVE_BUILD must satisfy the @RolesAllowed gate on /approvals/{id}/approve");
  }

  @Test
  @TestSecurity(
      user = "approver",
      roles = {"APPROVE_BUILD"})
  void approveBuildOnly_canReject() {
    int status =
        given()
            .contentType("application/json")
            .body(EMPTY_BODY)
            .when()
            .post("/api/v1/approvals/999999999/reject")
            .then()
            .extract()
            .statusCode();
    org.junit.jupiter.api.Assertions.assertNotEquals(
        403, status, "APPROVE_BUILD must satisfy the @RolesAllowed gate on /approvals/{id}/reject");
  }

  @Test
  @TestSecurity(
      user = "approver",
      roles = {"APPROVE_BUILD"})
  void approveBuildOnly_cannotCreateJob() {
    String pdl = "stages:\\n  - stage: s1\\n    steps:\\n      - sh: echo hi\\n";
    given()
        .contentType("application/json")
        .body(
            "{\"fullName\":\"org/rbac-approve-deny-"
                + System.nanoTime()
                + "\",\"pipelineScript\":\""
                + pdl
                + "\"}")
        .when()
        .post("/api/v1/jobs")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(
      user = "reader",
      roles = {"READ_JOB"})
  void readJobOnly_cannotDecide() {
    given()
        .contentType("application/json")
        .body(EMPTY_BODY)
        .when()
        .post("/api/v1/approvals/999999999/approve")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(
      user = "admin",
      roles = {"ADMIN"})
  void admin_canStillDecide() {
    int status =
        given()
            .contentType("application/json")
            .body(EMPTY_BODY)
            .when()
            .post("/api/v1/approvals/999999999/approve")
            .then()
            .extract()
            .statusCode();
    org.junit.jupiter.api.Assertions.assertNotEquals(
        403, status, "ADMIN must continue to satisfy the @RolesAllowed gate on approve");
  }
}

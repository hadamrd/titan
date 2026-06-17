package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * Adversarial RBAC test for the new {@code ABORT_BUILD} role (partial #479).
 *
 * <p>Mirrors {@link RbacReadAuditIT}: {@code @QuarkusTest} + {@code @TestSecurity} projects the
 * requested role set into Quarkus's {@code SecurityIdentity}, which is what {@code @RolesAllowed}
 * consults at request time. Keycloak is NOT started in the test JVM — the assertions are still
 * load-bearing for the production OIDC path because the only thing being exercised is the
 * {@code @RolesAllowed} ↔ {@code SecurityIdentity} glue.
 *
 * <p>Following the established pattern in {@link
 * io.adaptiq.titan.auth.RbacTest#cancelBuild_triggerRole_returns404NotForbidden()}, we probe with a
 * non-existent buildId — a {@code 404} response proves the {@code @RolesAllowed} gate was satisfied
 * (a forbidden caller would short-circuit to {@code 403} before the handler runs and never reach
 * the "build not found" branch).
 *
 * <p>Asserts the three contract corners that matter for #479:
 *
 * <ul>
 *   <li>a user holding ONLY {@code ABORT_BUILD} can {@code POST /api/v1/builds/{id}/cancel}
 *       (reaches the handler → 404 for an unknown id);
 *   <li>the same user CANNOT {@code POST /api/v1/jobs} (403) — that route requires {@code
 *       EDIT_PIPELINE} or {@code ADMIN}, neither of which {@code ABORT_BUILD} implies;
 *   <li>an {@code ADMIN} caller can still cancel (regression check: the additive change did not
 *       break the existing role gates on the cancel endpoint).
 * </ul>
 */
@QuarkusTest
class RbacAbortBuildIT {

  @Test
  @TestSecurity(
      user = "aborter",
      roles = {"ABORT_BUILD"})
  void abortBuildOnly_canCancelBuild() {
    // 404 (not 403) proves the @RolesAllowed gate accepted ABORT_BUILD and the handler ran.
    given().when().post("/api/v1/builds/999999999/cancel").then().statusCode(404);
  }

  @Test
  @TestSecurity(
      user = "aborter",
      roles = {"ABORT_BUILD"})
  void abortBuildOnly_cannotCreateJob() {
    // JobsApi.create requires EDIT_PIPELINE or ADMIN — ABORT_BUILD must not bypass that.
    String pdl = "stages:\\n  - stage: s1\\n    steps:\\n      - sh: echo hi\\n";
    given()
        .contentType("application/json")
        .body(
            "{\"fullName\":\"org/rbac-abort-deny-"
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
      user = "admin",
      roles = {"ADMIN"})
  void admin_canStillCancelBuild() {
    // Regression: adding ABORT_BUILD to the @RolesAllowed list must not displace ADMIN.
    given().when().post("/api/v1/builds/999999999/cancel").then().statusCode(404);
  }
}

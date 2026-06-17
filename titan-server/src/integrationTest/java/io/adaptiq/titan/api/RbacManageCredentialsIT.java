package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * Adversarial RBAC test for the new {@code MANAGE_CREDENTIALS} role (closes #479).
 *
 * <p>Mirrors {@link RbacReplayBuildIT}: {@code @QuarkusTest} + {@code @TestSecurity} projects the
 * requested role set into Quarkus's {@code SecurityIdentity}, which is what {@code @RolesAllowed}
 * consults at request time. Keycloak is NOT started in the test JVM — the assertions are still
 * load-bearing for the production OIDC path because the only thing being exercised is the
 * {@code @RolesAllowed} ↔ {@code SecurityIdentity} glue.
 *
 * <p>The probe shape follows the established pattern: hit a real route with a well-formed body and
 * assert the response is NOT 403. A 2xx (created) or 4xx-other (e.g. 409/422) both prove the
 * {@code @RolesAllowed} gate was satisfied — a forbidden caller would short-circuit to 403 before
 * the handler runs and never reach the service layer.
 *
 * <p>Asserts the three contract corners that matter for the #479 close-out:
 *
 * <ul>
 *   <li>a user holding ONLY {@code MANAGE_CREDENTIALS} can {@code POST /api/v1/credentials}
 *       (reaches the handler → not 403);
 *   <li>the same user CANNOT {@code POST /api/v1/jobs} (403) — that route requires {@code
 *       EDIT_PIPELINE} or {@code ADMIN}, neither of which {@code MANAGE_CREDENTIALS} implies;
 *   <li>an {@code ADMIN} caller can still create credentials (regression check: the additive change
 *       did not displace ADMIN on the create endpoint).
 * </ul>
 */
@QuarkusTest
class RbacManageCredentialsIT {

  private static String createBody() {
    // Unique key per call so successive runs don't collide if the handler accepts the row.
    return "{\"kind\":\"secret-text\",\"scope\":\"global\",\"key\":\"rbac-mc-"
        + System.nanoTime()
        + "\",\"plaintext\":\"hunter2\"}";
  }

  @Test
  @TestSecurity(
      user = "credsop",
      roles = {"MANAGE_CREDENTIALS"})
  void manageCredentialsOnly_canCreateCredential() {
    // Anything other than 403 proves the @RolesAllowed gate accepted MANAGE_CREDENTIALS and the
    // handler ran (2xx = created, 4xx-other = post-auth validation/service response — both prove
    // the auth gate was satisfied).
    int status =
        given()
            .contentType("application/json")
            .body(createBody())
            .when()
            .post("/api/v1/credentials")
            .then()
            .extract()
            .statusCode();
    org.junit.jupiter.api.Assertions.assertNotEquals(
        403, status, "MANAGE_CREDENTIALS must satisfy the @RolesAllowed gate on POST /credentials");
  }

  @Test
  @TestSecurity(
      user = "credsop",
      roles = {"MANAGE_CREDENTIALS"})
  void manageCredentialsOnly_cannotCreateJob() {
    // JobsApi.create requires EDIT_PIPELINE or ADMIN — MANAGE_CREDENTIALS must not bypass that.
    String pdl = "stages:\\n  - stage: s1\\n    steps:\\n      - sh: echo hi\\n";
    given()
        .contentType("application/json")
        .body(
            "{\"fullName\":\"org/rbac-mc-deny-"
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
  void admin_canStillCreateCredential() {
    // Regression: adding MANAGE_CREDENTIALS to the @RolesAllowed list must not displace ADMIN.
    int status =
        given()
            .contentType("application/json")
            .body(createBody())
            .when()
            .post("/api/v1/credentials")
            .then()
            .extract()
            .statusCode();
    org.junit.jupiter.api.Assertions.assertNotEquals(
        403, status, "ADMIN must continue to satisfy the @RolesAllowed gate on POST /credentials");
  }
}

package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * Adversarial RBAC test for the new {@code READ_AUDIT} role (partial #479).
 *
 * <p>The brief asks for a "Keycloak user with ONLY READ_AUDIT". The existing IT harness in this
 * module (see {@link AuditApiIT}, {@link StatsApiIT}) is DAO-backed Testcontainers and does NOT
 * stand up Keycloak — and the documented pattern for exercising the {@code @RolesAllowed} glue
 * end-to-end is {@code @QuarkusTest} + {@code @TestSecurity} (see {@link AuditApiTest}). We mirror
 * that pattern here: {@code @TestSecurity} projects exactly the requested role set into Quarkus's
 * {@code SecurityIdentity}, which is what {@code @RolesAllowed} consults at request time, so the
 * assertions below are load-bearing for the production OIDC path even though Keycloak itself does
 * not run in the test JVM.
 *
 * <p>Asserts the two contract corners that matter for #479:
 *
 * <ul>
 *   <li>a user holding ONLY {@code READ_AUDIT} can {@code GET /api/v1/audit} (200);
 *   <li>the same user CANNOT {@code POST /api/v1/jobs} (403) — that route requires {@code
 *       EDIT_PIPELINE} or {@code ADMIN}, neither of which {@code READ_AUDIT} implies.
 * </ul>
 *
 * <p>Together these prove the new role is additive on the audit surface and DOES NOT leak into
 * unrelated write surfaces — the core failure mode the brief calls out.
 */
@QuarkusTest
class RbacReadAuditIT {

  @Test
  @TestSecurity(
      user = "auditor",
      roles = {"READ_AUDIT"})
  void readAuditOnly_canGetAuditLog() {
    given()
        .when()
        .get("/api/v1/audit")
        .then()
        .statusCode(200)
        // shape sanity — AuditPage has these fields per AuditApi.list()
        .body("items", notNullValue())
        .body("total", notNullValue());
  }

  @Test
  @TestSecurity(
      user = "auditor",
      roles = {"READ_AUDIT"})
  void readAuditOnly_cannotCreateJob() {
    // JobsApi.create requires EDIT_PIPELINE or ADMIN — READ_AUDIT must not bypass that.
    String pdl = "stages:\\n  - stage: s1\\n    steps:\\n      - sh: echo hi\\n";
    given()
        .contentType("application/json")
        .body(
            "{\"fullName\":\"org/rbac-deny-"
                + System.nanoTime()
                + "\",\"pipelineScript\":\""
                + pdl
                + "\"}")
        .when()
        .post("/api/v1/jobs")
        .then()
        .statusCode(403);
  }
}

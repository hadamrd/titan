package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * Adversarial RBAC test for {@code GET /api/v1/admin/users} (backend half of #603).
 *
 * <p>Mirrors the {@link RbacReadAuditIT} pattern: {@code @QuarkusTest} + {@code @TestSecurity}
 * projects an explicit role set into the {@code SecurityIdentity}, which is what
 * {@code @RolesAllowed} consults — so the assertions below are load-bearing for the production OIDC
 * path even though Keycloak itself does not run in the test JVM.
 *
 * <p>In the test profile no {@code titan.kc.admin.client-id} / {@code …client-secret} are set, so
 * {@link io.adaptiq.titan.admin.KeycloakAdminClient} serves its STUB list (currently the single
 * dev/ADMIN user). The contract corners covered:
 *
 * <ul>
 *   <li>ADMIN user → 200, returns ≥1 user including the dev user with {@code ADMIN} in {@code
 *       realmRoles};
 *   <li>non-ADMIN user (only {@code READ_AUDIT}) → 403 — listing realm users must NOT leak to
 *       lesser roles.
 * </ul>
 */
@QuarkusTest
class AdminUsersApiIT {

  @Test
  @TestSecurity(
      user = "root",
      roles = {"ADMIN"})
  void admin_canListUsers() {
    given()
        .when()
        .get("/api/v1/admin/users")
        .then()
        .statusCode(200)
        .body("$", notNullValue())
        .body("size()", greaterThanOrEqualTo(1))
        .body("username", hasItem("dev"))
        .body("find { it.username == 'dev' }.realmRoles", hasItem("ADMIN"));
  }

  @Test
  @TestSecurity(
      user = "auditor",
      roles = {"READ_AUDIT"})
  void readAuditOnly_cannotListUsers() {
    given().when().get("/api/v1/admin/users").then().statusCode(403);
  }

  @Test
  void noAuth_isUnauthorized() {
    given().when().get("/api/v1/admin/users").then().statusCode(401);
  }
}

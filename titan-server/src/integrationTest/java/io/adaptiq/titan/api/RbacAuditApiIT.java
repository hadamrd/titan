package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.TitanStores;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@code GET /api/v1/rbac-audit} — the operator read surface over {@code
 * titan.rbac_audit} (closes #1167).
 *
 * <p>{@code @QuarkusTest} (H2-backed via {@code H2StoresProducer}, same wiring as {@link
 * AuditApiTest} / {@code JobParametersApiIT}) so the full {@code @RolesAllowed} ↔ {@code
 * SecurityIdentity} ↔ JAX-RS ↔ DAO path is exercised; {@code @TestSecurity} projects exactly the
 * role set under test into the identity {@code @RolesAllowed} consults. Keycloak is not started —
 * the only glue being asserted is role-gating + filter handling, which {@code @TestSecurity}
 * reproduces faithfully for the production OIDC path.
 *
 * <p>Each test seeds rows tagged with a unique {@code actor} marker and filters by it, so the
 * shared H2 instance (rows persist across methods in one JVM) cannot cross-contaminate assertions.
 *
 * <p>Covers the #1167 IT matrix:
 *
 * <ul>
 *   <li>ADMIN gets 200 + correct page shape;
 *   <li>READ_AUDIT-only gets 200;
 *   <li>a non-audit role (READ_JOB) gets 403 — the gate does not leak rows;
 *   <li>{@code verdict=DENY} returns only deny rows;
 *   <li>{@code offset}/{@code limit} paginate without overlap;
 *   <li>adversarial: {@code verdict=BOGUS} and an unknown {@code scopeKind} are 400, never 500.
 * </ul>
 */
@QuarkusTest
class RbacAuditApiIT {

  @Inject TitanStores stores;

  private String seed(int allow, int deny, String scopeKind) {
    String marker = "it-" + System.nanoTime();
    for (int i = 0; i < allow; i++) {
      stores
          .rbacAudit()
          .insert(marker, "JobsApi.list", scopeKind, "s" + i, "READ_JOB", "READ_JOB", "ALLOW");
    }
    for (int i = 0; i < deny; i++) {
      stores
          .rbacAudit()
          .insert(marker, "JobsApi.create", scopeKind, "s" + i, "ADMIN", "READ_JOB", "DENY");
    }
    return marker;
  }

  @Test
  @TestSecurity(
      user = "admin",
      roles = {"ADMIN"})
  void admin_gets200_withPageShape() {
    String marker = seed(2, 1, "ORG");
    given()
        .queryParam("actor", marker)
        .when()
        .get("/api/v1/rbac-audit")
        .then()
        .statusCode(200)
        .body("items", notNullValue())
        .body("total", equalTo(3))
        .body("offset", equalTo(0))
        .body("items.size()", equalTo(3))
        // first-class verdict column is present and closed-union
        .body("items.verdict", everyItem(notNullValue()))
        .body("items[0].scopeKind", equalTo("ORG"));
  }

  @Test
  @TestSecurity(
      user = "auditor",
      roles = {"READ_AUDIT"})
  void readAuditOnly_gets200() {
    String marker = seed(1, 0, "REPO");
    given()
        .queryParam("actor", marker)
        .when()
        .get("/api/v1/rbac-audit")
        .then()
        .statusCode(200)
        .body("total", equalTo(1));
  }

  @Test
  @TestSecurity(
      user = "dev",
      roles = {"READ_JOB"})
  void nonAuditRole_gets403_andNoRowsLeak() {
    seed(1, 1, "ORG");
    // 403 fires in the @RolesAllowed interceptor before the handler runs, so no audit rows are
    // ever serialised to a caller lacking READ_AUDIT/ADMIN.
    given().when().get("/api/v1/rbac-audit").then().statusCode(403);
  }

  @Test
  @TestSecurity(
      user = "admin",
      roles = {"ADMIN"})
  void verdictDeny_returnsOnlyDenyRows() {
    String marker = seed(3, 2, "REPO");
    given()
        .queryParam("actor", marker)
        .queryParam("verdict", "DENY")
        .when()
        .get("/api/v1/rbac-audit")
        .then()
        .statusCode(200)
        .body("total", equalTo(2))
        .body("items.verdict", everyItem(equalTo("DENY")));
  }

  @Test
  @TestSecurity(
      user = "admin",
      roles = {"ADMIN"})
  void offsetLimit_paginateWithoutOverlap() {
    String marker = seed(5, 0, "ORG");

    List<Integer> page1 =
        given()
            .queryParam("actor", marker)
            .queryParam("limit", 2)
            .queryParam("offset", 0)
            .when()
            .get("/api/v1/rbac-audit")
            .then()
            .statusCode(200)
            .body("items.size()", equalTo(2))
            .extract()
            .jsonPath()
            .getList("items.id");

    List<Integer> page2 =
        given()
            .queryParam("actor", marker)
            .queryParam("limit", 2)
            .queryParam("offset", 2)
            .when()
            .get("/api/v1/rbac-audit")
            .then()
            .statusCode(200)
            .body("items.size()", equalTo(2))
            .extract()
            .jsonPath()
            .getList("items.id");

    assertTrue(page2.stream().noneMatch(page1::contains), "pages must not overlap");
  }

  @Test
  @TestSecurity(
      user = "admin",
      roles = {"ADMIN"})
  void bogusVerdict_is400_never500() {
    given().queryParam("verdict", "BOGUS").when().get("/api/v1/rbac-audit").then().statusCode(400);
  }

  @Test
  @TestSecurity(
      user = "admin",
      roles = {"ADMIN"})
  void unknownScopeKind_is400_never500() {
    given()
        .queryParam("scopeKind", "GALAXY")
        .when()
        .get("/api/v1/rbac-audit")
        .then()
        .statusCode(400);
  }

  @Test
  @TestSecurity(
      user = "admin",
      roles = {"ADMIN"})
  void blankActor_degradesToUnfilteredFeed() {
    String marker = seed(2, 0, "ORG");
    // A blank actor filter must NOT mean "match nothing" — the whole feed is returned, so our
    // 2 seeded rows are visible among the total.
    int total =
        given()
            .queryParam("actor", "  ")
            .when()
            .get("/api/v1/rbac-audit")
            .then()
            .statusCode(200)
            .extract()
            .path("total");
    assertTrue(total >= 2, "blank actor must degrade to the unfiltered feed, got total=" + total);
    // And the marker rows are reachable when we DO filter.
    given()
        .queryParam("actor", marker)
        .when()
        .get("/api/v1/rbac-audit")
        .then()
        .statusCode(200)
        .body("total", equalTo(2));
  }

  @Test
  @TestSecurity(
      user = "admin",
      roles = {"ADMIN"})
  void anonymousDenyRow_rendersWithNullActorAndEffectiveRole() {
    // Anonymous deny: null user_id + null effective_role. The DTO must serialise without NPE and
    // the UI test asserts it renders; here we assert the wire shape carries the nulls.
    String marker = "anon-" + System.nanoTime();
    stores.rbacAudit().insert(null, "JobsApi.create", "ORG", marker, "MAINTAINER", null, "DENY");
    given()
        .queryParam("scopeKind", "ORG")
        .queryParam("verdict", "DENY")
        .when()
        .get("/api/v1/rbac-audit")
        .then()
        .statusCode(200)
        // at least our anonymous row is present with a null actor
        .body(
            "items.find { it.scopeId == '" + marker + "' }.actor",
            org.hamcrest.Matchers.nullValue())
        .body(
            "items.find { it.scopeId == '" + marker + "' }.effectiveRole",
            org.hamcrest.Matchers.nullValue());
    assertEquals(1, stores.rbacAudit().countForScope("ORG", marker));
  }
}

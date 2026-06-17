package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.RbacUserRoleRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * REST-layer tests for {@link AdminUsersApi}'s role-assignment write surface (epic #1114 item 6,
 * closes #1236). Same H2-backed {@link H2StoresProducer} wiring as {@link CredentialsApiTest}, so
 * the full {@code @RolesAllowed} → {@code @RequiresRole} → {@code ScopedAuthz} → DAO path runs and
 * the injected {@link TitanStores} can assert the persisted {@code rbac_user_role} + {@code
 * audit_log} rows.
 *
 * <p>Covers the #1236 test matrix: an ADMIN grants a role (happy path), a non-ADMIN is forbidden
 * (adversarial), and a revoke of an absent assignment is an idempotent 404 (never 500).
 */
@QuarkusTest
class AdminUsersApiTest {

  @Inject TitanStores stores;

  private static String grantBody(String role, String scopeKind, String scopeId) {
    return "{\"role\":\""
        + role
        + "\",\"scopeKind\":\""
        + scopeKind
        + "\",\"scopeId\":\""
        + scopeId
        + "\"}";
  }

  // ── PUT {userId}/roles — happy path ────────────────────────────────────────────

  @Test
  @TestSecurity(
      user = "grant-admin",
      roles = {"ADMIN"})
  void grantRole_adminCanAssignMaintainer() {
    String target = "assignee-maintainer";

    given()
        .contentType("application/json")
        .body(grantBody("MAINTAINER", "ORG", "global"))
        .when()
        .put("/api/v1/admin/users/" + target + "/roles")
        .then()
        .statusCode(200)
        // resulting role set echoes the new assignment
        .body("role", hasItem("MAINTAINER"))
        .body("find { it.role == 'MAINTAINER' }.scopeKind", equalTo("ORG"))
        .body("find { it.role == 'MAINTAINER' }.scopeId", equalTo("global"));

    // rbac_user_role row landed
    List<RbacUserRoleRow> rows = stores.rbacUserRoles().findByUserAndScope(target, "ORG", "global");
    assertTrue(
        rows.stream().anyMatch(r -> "MAINTAINER".equals(r.role)),
        "expected a MAINTAINER rbac_user_role row for " + target);

    // exactly the grant emitted a ROLE_GRANT audit row under the acting admin
    assertFalse(
        stores.auditLog().findRecent("grant-admin", "ROLE_GRANT", "USER", null, 10, 0).isEmpty(),
        "grant must write a ROLE_GRANT/USER audit row under the acting admin");
  }

  // ── PUT {userId}/roles — adversarial: non-admin forbidden ──────────────────────

  @Test
  @TestSecurity(
      user = "grant-editpipeline",
      roles = {"EDIT_PIPELINE"})
  void grantRole_nonAdminForbidden() {
    // EDIT_PIPELINE resolves to MAINTAINER on ORG:global (< ADMIN), so the @RequiresRole
    // interceptor
    // — the sole gate on the write verb — denies it: 403, no rbac_user_role row, and (AC5) a DENY
    // rbac_audit row recording the rejected attempt. Deny-by-default holds: a non-admin can never
    // assign a Titan role.
    given()
        .contentType("application/json")
        .body(grantBody("MAINTAINER", "ORG", "global"))
        .when()
        .put("/api/v1/admin/users/victim/roles")
        .then()
        .statusCode(403);

    assertTrue(
        stores.rbacUserRoles().findByUserAndScope("victim", "ORG", "global").isEmpty(),
        "a forbidden grant must not persist any rbac_user_role row");

    // AC5: the denial routes through ScopedAuthz and leaves a DENY rbac_audit row for the actor.
    assertTrue(
        stores.rbacAudit().recentForUser("grant-editpipeline", 10).stream()
            .anyMatch(r -> "DENY".equals(r.decision)),
        "AC5: a forbidden grant must write a DENY rbac_audit row for the actor");
  }

  // ── DELETE {userId}/roles/{scopeKind}/{scopeId} — idempotent 404 ────────────────

  @Test
  @TestSecurity(
      user = "revoke-admin",
      roles = {"ADMIN"})
  void revokeRole_idempotent404() {
    // No assignment exists → 404 (never 500); repeating the same DELETE is still 404, never 500.
    given().when().delete("/api/v1/admin/users/ghost-user/roles/ORG/global").then().statusCode(404);

    given().when().delete("/api/v1/admin/users/ghost-user/roles/ORG/global").then().statusCode(404);
  }

  // ── PUT {userId}/roles — adversarial: unknown role is a 400, not a 500 ──────────

  @Test
  @TestSecurity(
      user = "grant-admin-badrole",
      roles = {"ADMIN"})
  void grantRole_unknownRoleIs400() {
    given()
        .contentType("application/json")
        .body(grantBody("WIZARD", "ORG", "global"))
        .when()
        .put("/api/v1/admin/users/some-user/roles")
        .then()
        .statusCode(400);
  }

  // ── DELETE happy path: revoke a present assignment is 204, then 404 ─────────────

  @Test
  @TestSecurity(
      user = "revoke-admin-present",
      roles = {"ADMIN"})
  void revokeRole_presentAssignmentReturns204ThenIdempotent404() {
    String target = "assignee-to-revoke";
    stores.rbacUserRoles().grant(target, "ORG", "global", "DEVELOPER");

    given()
        .when()
        .delete("/api/v1/admin/users/" + target + "/roles/ORG/global")
        .then()
        .statusCode(204);

    assertEquals(
        0,
        stores.rbacUserRoles().findByUserAndScope(target, "ORG", "global").size(),
        "revoke must clear every role on the scope");

    // repeat → 404 (idempotent, never 500)
    given()
        .when()
        .delete("/api/v1/admin/users/" + target + "/roles/ORG/global")
        .then()
        .statusCode(404);
  }

  // ── batched enrichment: one IN(...) SELECT covers a whole page (no N+1) ──────────

  @Test
  void findByUsers_batchesAcrossUsersAndIsEmptySafe() {
    // The admin list path enriches a page via one findByUsers() SELECT instead of N findByUser()
    // calls. Prove the batched query returns each user's rows grouped correctly, and that an empty
    // page binds IN (NULL) → no rows (never throws), so listing an empty page is safe.
    stores.rbacUserRoles().grant("batch-alice", "ORG", "global", "MAINTAINER");
    stores.rbacUserRoles().grant("batch-bob", "REPO", "team/app", "DEVELOPER");
    stores.rbacUserRoles().grant("batch-bob", "ORG", "global", "VIEWER");

    List<RbacUserRoleRow> rows =
        stores.rbacUserRoles().findByUsers(List.of("batch-alice", "batch-bob"));

    assertEquals(
        1, rows.stream().filter(r -> "batch-alice".equals(r.userId)).count(), "alice has one role");
    assertEquals(
        2, rows.stream().filter(r -> "batch-bob".equals(r.userId)).count(), "bob has two roles");
    assertTrue(
        rows.stream().noneMatch(r -> "batch-carol".equals(r.userId)),
        "un-requested users are not returned");

    assertTrue(
        stores.rbacUserRoles().findByUsers(List.of()).isEmpty(),
        "empty page binds IN (NULL) → empty result, never a SQL error");
  }
}

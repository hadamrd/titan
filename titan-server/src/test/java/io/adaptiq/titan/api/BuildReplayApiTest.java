package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * REST-layer tests for {@link BuildReplayApi} — issue #307.
 *
 * <p>RBAC matrix lives in {@code RbacTest} (the shared cross-API security check); these tests focus
 * on the request-validation surface and the happy-path 201 contract.
 *
 * <p>Beyond the {@code @RolesAllowed} realm-role gate, the replay endpoints run the DB-backed
 * {@link io.adaptiq.titan.auth.Authz#requires} check (#1121). Since #126 that check resolves roles
 * through the CANONICAL chain — {@code titan.rbac_user_role} (the table AdminUsersApi writes) →
 * legacy {@code titan.user_roles} → realm floor. Each test therefore seeds a scoped MAINTAINER
 * grant for {@code testuser} in {@code @BeforeEach} and revokes it in {@code @AfterEach} so no role
 * assignment leaks into other test classes sharing the H2 database. The class-level realm role is
 * deliberately ONLY {@code TRIGGER_BUILD} (floors to DEVELOPER, below the MAINTAINER+ BUILD_RERUN
 * policy) so the scoped grant — not the realm floor — is what authorizes the happy paths, pinning
 * the canonical-table read.
 */
@QuarkusTest
@TestSecurity(
    user = "testuser",
    roles = {"TRIGGER_BUILD"})
class BuildReplayApiTest {

  @Inject TitanStores stores;

  @BeforeEach
  void grantMaintainerViaCanonicalTable() {
    // Authz.requires(BUILD_RERUN) resolves via rbac_user_role (ORG:global) — the same table the
    // AdminUsersApi grant surface writes (#126). Default-deny without this row.
    stores.rbacUserRoles().grant("testuser", "ORG", "global", "MAINTAINER");
  }

  @AfterEach
  void revokeMaintainerGrant() {
    stores.rbacUserRoles().revoke("testuser", "ORG", "global", "MAINTAINER");
  }

  @Test
  void replay_unknownParent_returns404() {
    given()
        .contentType("application/json")
        .body("{\"nodeId\":\"stage-1\"}")
        .when()
        .post("/api/v1/builds/9999999/replay")
        .then()
        .statusCode(404);
  }

  @Test
  void replay_missingNodeId_returns400() {
    long parentId = freshBakedParent("stage-1", "SUCCESS");
    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/builds/" + parentId + "/replay")
        .then()
        .statusCode(400);
  }

  @Test
  void replay_blankNodeId_returns400() {
    long parentId = freshBakedParent("stage-1", "SUCCESS");
    given()
        .contentType("application/json")
        .body("{\"nodeId\":\"\"}")
        .when()
        .post("/api/v1/builds/" + parentId + "/replay")
        .then()
        .statusCode(400);
  }

  @Test
  void replay_unknownNode_returns400() {
    long parentId = freshBakedParent("stage-1", "SUCCESS");
    given()
        .contentType("application/json")
        .body("{\"nodeId\":\"ghost-node\"}")
        .when()
        .post("/api/v1/builds/" + parentId + "/replay")
        .then()
        .statusCode(400);
  }

  @Test
  void replay_nonTerminalNode_returns400() {
    long parentId = freshBakedParent("stage-1", "RUNNING");
    given()
        .contentType("application/json")
        .body("{\"nodeId\":\"stage-1\"}")
        .when()
        .post("/api/v1/builds/" + parentId + "/replay")
        .then()
        .statusCode(400);
  }

  @Test
  void replay_happyPath_returns201WithNewBuildId() {
    long parentId = freshBakedParent("stage-1", "SUCCESS");
    given()
        .contentType("application/json")
        .body("{\"nodeId\":\"stage-1\"}")
        .when()
        .post("/api/v1/builds/" + parentId + "/replay")
        .then()
        .statusCode(201)
        .body("newBuildId", notNullValue())
        .body("newBuildId", greaterThan(0));
  }

  @Test
  void replay_withParamOverrides_accepted() {
    long parentId = freshBakedParent("stage-1", "SUCCESS");
    given()
        .contentType("application/json")
        .body("{\"nodeId\":\"stage-1\",\"withChanges\":{\"params\":{\"VERSION\":\"2.0\"}}}")
        .when()
        .post("/api/v1/builds/" + parentId + "/replay")
        .then()
        .statusCode(201)
        .body("newBuildId", greaterThan(0));
  }

  // ── RBAC — caller without TRIGGER_BUILD/ADMIN is denied ────────────────

  @Test
  @TestSecurity(
      user = "reader",
      roles = {"READ_JOB"})
  void replay_withoutTriggerRole_returns403() {
    long parentId = freshBakedParent("stage-1", "SUCCESS");
    given()
        .contentType("application/json")
        .body("{\"nodeId\":\"stage-1\"}")
        .when()
        .post("/api/v1/builds/" + parentId + "/replay")
        .then()
        .statusCode(403);
  }

  // ── RBAC #126 — canonical-store resolution ──────────────────────────────

  /**
   * Adversarial (#126 default-deny): TRIGGER_BUILD clears the coarse gate and floors to DEVELOPER,
   * but with NO rbac_user_role grant the MAINTAINER+ BUILD_RERUN policy must still deny. Distinct
   * user — the class-level {@code @BeforeEach} seed applies to {@code testuser} only.
   */
  @Test
  @TestSecurity(
      user = "dev-ungranted",
      roles = {"TRIGGER_BUILD"})
  void replay_ungrantedDeveloper_returns403() {
    long parentId = freshBakedParent("stage-1", "SUCCESS");
    given()
        .contentType("application/json")
        .body("{\"nodeId\":\"stage-1\"}")
        .when()
        .post("/api/v1/builds/" + parentId + "/replay")
        .then()
        .statusCode(403);
  }

  /**
   * The exact #126 rig scenario: a caller whose JWT carries the ADMIN realm role and who has NO row
   * in either RBAC table must clear BUILD_RERUN via the realm floor — this was the caller the old
   * flat-table-only read 403'd on every fresh rig.
   */
  @Test
  @TestSecurity(
      user = "admin-realm-only",
      roles = {"ADMIN"})
  void replay_adminRealmRole_noDbSeed_returns201() {
    long parentId = freshBakedParent("stage-1", "SUCCESS");
    given()
        .contentType("application/json")
        .body("{\"nodeId\":\"stage-1\"}")
        .when()
        .post("/api/v1/builds/" + parentId + "/replay")
        .then()
        .statusCode(201)
        .body("newBuildId", greaterThan(0));
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private long freshBakedParent(String nodeId, String nodeStatus) {
    JobRow j = new JobRow();
    j.fullName = "replay-api-test/" + System.nanoTime();
    j.pipelineScript = "";
    j.configJson = "{}";
    j.enabled = true;
    long jobId = stores.jobs().insert(j);

    BuildRow b = new BuildRow();
    b.jobId = jobId;
    b.buildNumber = stores.builds().nextBuildNumber(jobId);
    b.status = "SUCCESS";
    b.queuedAt = Instant.now();
    b.triggeredBy = "test";
    b.triggerType = "manual";
    b.pipelineModelJson =
        "{\"stages\":[{\"name\":\"stage-1\",\"id\":\""
            + nodeId
            + "\",\"steps\":[],\"parentStageIds\":[],\"parallel\":false,\"dependsOn\":[]}],"
            + "\"parameters\":[],\"triggers\":[],\"gates\":[],\"preconditions\":[],"
            + "\"failurePolicy\":\"blockOnFailure\"}";
    long buildId = stores.withTransaction(c -> stores.builds().insert(c, b));

    FlowNodeRow node = new FlowNodeRow();
    node.buildId = buildId;
    node.nodeId = nodeId;
    node.nodeType = "STAGE";
    node.displayName = nodeId;
    node.status = nodeStatus;
    stores.flowNodes().insert(node);

    return buildId;
  }

  /** Keep imports referenced. */
  @SuppressWarnings("unused")
  private static Map<String, String> keepImport() {
    return Map.of();
  }
}

package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * Adversarial RBAC test for the new {@code OPERATE_WORKER} role (partial #479).
 *
 * <p>Mirrors {@link RbacAbortBuildIT}: {@code @QuarkusTest} + {@code @TestSecurity} projects the
 * requested role set into Quarkus's {@code SecurityIdentity}, which is what {@code @RolesAllowed}
 * consults at request time. Keycloak is NOT started in the test JVM — the assertions are still
 * load-bearing for the production OIDC path because the only thing being exercised is the
 * {@code @RolesAllowed} ↔ {@code SecurityIdentity} glue.
 *
 * <p>We probe drain with a non-existent workerId — a {@code 404} response proves the
 * {@code @RolesAllowed} gate was satisfied (a forbidden caller would short-circuit to {@code 403}
 * before the handler runs and never reach the "worker not found" branch).
 *
 * <p>Asserts the three contract corners that matter for #479:
 *
 * <ul>
 *   <li>a user holding ONLY {@code OPERATE_WORKER} can {@code POST /api/v1/workers/{id}/drain}
 *       (reaches the handler → 404 for an unknown id);
 *   <li>the same user CANNOT {@code POST /api/v1/jobs} (403) — that route requires {@code
 *       EDIT_PIPELINE} or {@code ADMIN}, neither of which {@code OPERATE_WORKER} implies;
 *   <li>an {@code ADMIN} caller can still drain (regression check: the additive change did not
 *       break the existing role gates on the drain endpoint).
 * </ul>
 */
@QuarkusTest
class RbacOperateWorkerIT {

  @Test
  @TestSecurity(
      user = "operator",
      roles = {"OPERATE_WORKER"})
  void operateWorkerOnly_canDrainWorker() {
    // 404 (not 403) proves the @RolesAllowed gate accepted OPERATE_WORKER and the handler ran.
    given().when().post("/api/v1/workers/nonexistent-worker-id/drain").then().statusCode(404);
  }

  @Test
  @TestSecurity(
      user = "operator",
      roles = {"OPERATE_WORKER"})
  void operateWorkerOnly_cannotCreateJob() {
    // JobsApi.create requires EDIT_PIPELINE or ADMIN — OPERATE_WORKER must not bypass that.
    String pdl = "stages:\\n  - stage: s1\\n    steps:\\n      - sh: echo hi\\n";
    given()
        .contentType("application/json")
        .body(
            "{\"fullName\":\"org/rbac-operate-worker-deny-"
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
  void admin_canStillDrainWorker() {
    // Regression: adding OPERATE_WORKER to the @RolesAllowed list must not displace ADMIN.
    given().when().post("/api/v1/workers/nonexistent-worker-id/drain").then().statusCode(404);
  }
}

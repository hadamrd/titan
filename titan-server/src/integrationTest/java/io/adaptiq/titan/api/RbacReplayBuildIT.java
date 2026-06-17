package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * Adversarial RBAC test for the new {@code REPLAY_BUILD} role (partial #479).
 *
 * <p>Mirrors {@link RbacAbortBuildIT}: {@code @QuarkusTest} + {@code @TestSecurity} projects the
 * requested role set into Quarkus's {@code SecurityIdentity}, which is what {@code @RolesAllowed}
 * consults at request time. Keycloak is NOT started in the test JVM — the assertions are still
 * load-bearing for the production OIDC path because the only thing being exercised is the
 * {@code @RolesAllowed} ↔ {@code SecurityIdentity} glue.
 *
 * <p>The probe shape follows the established pattern: hit a real route with a non-existent buildId
 * and assert the response is NOT 403. A 404 (build not found) or 400 (missing/invalid body) both
 * prove the {@code @RolesAllowed} gate was satisfied — a forbidden caller would short-circuit to
 * 403 before the handler runs and never reach validation.
 *
 * <p>Asserts the three contract corners that matter for #479:
 *
 * <ul>
 *   <li>a user holding ONLY {@code REPLAY_BUILD} can {@code POST /api/v1/builds/{id}/replay}
 *       (reaches the handler → not 403);
 *   <li>the same user CANNOT {@code POST /api/v1/jobs} (403) — that route requires {@code
 *       EDIT_PIPELINE} or {@code ADMIN}, neither of which {@code REPLAY_BUILD} implies;
 *   <li>an {@code ADMIN} caller can still replay (regression check: the additive change did not
 *       break the existing role gates on the replay endpoint).
 * </ul>
 */
@QuarkusTest
class RbacReplayBuildIT {

  /** A syntactically-valid replay body — the handler reaches build-lookup, which 404s. */
  private static final String REPLAY_BODY = "{\"nodeId\":\"some-node\"}";

  @Test
  @TestSecurity(
      user = "replayer",
      roles = {"REPLAY_BUILD"})
  void replayBuildOnly_canReplayBuild() {
    // Anything other than 403 proves the @RolesAllowed gate accepted REPLAY_BUILD and the handler
    // ran (404 = build-not-found, 400 = validation failure on the synthetic nodeId — both are
    // post-auth responses).
    int status =
        given()
            .contentType("application/json")
            .body(REPLAY_BODY)
            .when()
            .post("/api/v1/builds/999999999/replay")
            .then()
            .extract()
            .statusCode();
    org.junit.jupiter.api.Assertions.assertNotEquals(
        403, status, "REPLAY_BUILD must satisfy the @RolesAllowed gate on /builds/{id}/replay");
  }

  @Test
  @TestSecurity(
      user = "replayer",
      roles = {"REPLAY_BUILD"})
  void replayBuildOnly_cannotCreateJob() {
    // JobsApi.create requires EDIT_PIPELINE or ADMIN — REPLAY_BUILD must not bypass that.
    String pdl = "stages:\\n  - stage: s1\\n    steps:\\n      - sh: echo hi\\n";
    given()
        .contentType("application/json")
        .body(
            "{\"fullName\":\"org/rbac-replay-deny-"
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
  void admin_canStillReplayBuild() {
    // Regression: adding REPLAY_BUILD to the @RolesAllowed list must not displace ADMIN.
    int status =
        given()
            .contentType("application/json")
            .body(REPLAY_BODY)
            .when()
            .post("/api/v1/builds/999999999/replay")
            .then()
            .extract()
            .statusCode();
    org.junit.jupiter.api.Assertions.assertNotEquals(
        403, status, "ADMIN must continue to satisfy the @RolesAllowed gate on replay");
  }
}

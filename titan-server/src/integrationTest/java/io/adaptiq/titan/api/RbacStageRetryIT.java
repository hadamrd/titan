package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * Adversarial RBAC test for the new stage-retry endpoint (#744 backend).
 *
 * <p>Mirrors {@link RbacReplayBuildIT}: {@code @QuarkusTest} + {@code @TestSecurity} projects an
 * explicit role set into Quarkus's {@code SecurityIdentity}, which is what {@code @RolesAllowed}
 * consults at request time. Keycloak is NOT started in the test JVM — the assertions are still
 * load-bearing for production OIDC because the only thing being exercised here is the
 * {@code @RolesAllowed} ↔ {@code SecurityIdentity} glue.
 *
 * <p>Pinned corners:
 *
 * <ul>
 *   <li>a user with only {@code REPLAY_BUILD} reaches the handler (status != 403);
 *   <li>a user with only {@code READ_JOB} is forbidden (403) — read-only roles must NOT retry;
 *   <li>{@code ADMIN} can retry (regression check on the allow list).
 * </ul>
 */
@QuarkusTest
class RbacStageRetryIT {

  private static final String PATH = "/api/v1/builds/999999999/stages/stage-1/retry";

  @Test
  @TestSecurity(
      user = "replayer",
      roles = {"REPLAY_BUILD"})
  void replayBuildOnly_canRetryStage() {
    // 404 (unknown build) or 409 (precondition) are both post-auth responses — anything other
    // than 403 proves @RolesAllowed accepted REPLAY_BUILD.
    int status =
        given().contentType("application/json").when().post(PATH).then().extract().statusCode();
    org.junit.jupiter.api.Assertions.assertNotEquals(
        403, status, "REPLAY_BUILD must satisfy @RolesAllowed on /stages/{id}/retry");
  }

  @Test
  @TestSecurity(
      user = "reader",
      roles = {"READ_JOB"})
  void readJobOnly_cannotRetryStage_is403() {
    given().contentType("application/json").when().post(PATH).then().statusCode(403);
  }

  @Test
  @TestSecurity(
      user = "admin",
      roles = {"ADMIN"})
  void admin_canRetryStage() {
    int status =
        given().contentType("application/json").when().post(PATH).then().extract().statusCode();
    org.junit.jupiter.api.Assertions.assertNotEquals(
        403, status, "ADMIN must satisfy @RolesAllowed on /stages/{id}/retry");
  }
}

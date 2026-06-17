package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * REST-layer tests for {@link InfoApi}: JSON shape + public (no-OIDC) access (Forge-loop tick #48).
 *
 * <p>The endpoint is {@code @PermitAll}; no {@code @TestSecurity} annotation is used so the request
 * carries no bearer token, mirroring the way the Settings page hits it before the login flow has
 * established a session.
 *
 * <p>Build-info content varies across invocations: when the Gradle {@code writeTitanBuildInfo} task
 * has run, {@code version} matches the project version; in plain IDE runs the value degrades to
 * {@code "unknown"}. The test accepts both so it passes regardless of the runner.
 */
@QuarkusTest
class InfoApiTest {

  @Test
  void info_returnsAllFourFieldsAndIsPublic() {
    given()
        .when()
        .get("/api/v1/info")
        .then()
        .statusCode(200)
        .body("version", notNullValue())
        // Accept any semver-ish project version OR the "unknown" IDE-fallback. Hard-coding the
        // exact version here couples the test to gradle.properties and breaks on every bump
        // (T73 #815: 0.1.0 → 1.0.0-rc1 silently nuked the rig build until this was relaxed).
        .body("version", matchesPattern("(unknown|\\d+\\.\\d+\\.\\d+(-[\\w.]+)?)"))
        .body("commit", notNullValue())
        .body("builtAt", notNullValue())
        .body("uptimeSeconds", notNullValue())
        .body("uptimeSeconds", greaterThanOrEqualTo(0));
  }

  @Test
  void info_doesNotRequireBearer() {
    // No @TestSecurity, no Authorization header — must still return 200.
    given().when().get("/api/v1/info").then().statusCode(200);
  }
}

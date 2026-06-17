package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * REST-layer tests for {@link SystemUiConfigApi} — locks the contract negotiated with the frontend
 * agent against issue #898 (happy-path shape + trailing-slash normalisation + no-auth
 * reachability).
 *
 * <p>{@code titan.ui.oidc-authority} is set explicitly so this test does not depend on {@code
 * quarkus.oidc.auth-server-url}, which is intentionally unset in the default test profile ({@code
 * quarkus.oidc.enabled=false}).
 */
@QuarkusTest
@TestProfile(SystemUiConfigApiTest.HappyPath.class)
class SystemUiConfigApiTest {

  @Test
  void uiConfig_returnsExpectedShape_withTrailingSlashNormalised() {
    given()
        .when()
        .get("/api/v1/system/ui-config")
        .then()
        .statusCode(200)
        .contentType("application/json")
        .body("publicUrl", equalTo("https://titan.test.example.com"))
        .body("oidc.authority", equalTo("https://titan.test.example.com/realms/titan-dev"))
        .body("oidc.clientId", equalTo("titan-ui"))
        .body("oidc.redirectUri", equalTo("https://titan.test.example.com/login/callback"))
        .body("oidc.postLogoutRedirectUri", equalTo("https://titan.test.example.com/login"));
  }

  @Test
  void uiConfig_doesNotRequireBearer() {
    // No @TestSecurity, no Authorization header — must still return 200. The SPA hits this BEFORE
    // the OIDC redirect flow completes; requiring auth would be a chicken-and-egg block.
    given().when().get("/api/v1/system/ui-config").then().statusCode(200);
  }

  public static class HappyPath implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          // trailing slash on purpose — endpoint must normalise so we don't emit //login/callback
          "titan.public-url", "https://titan.test.example.com/",
          "titan.ui.oidc-client-id", "titan-ui",
          "titan.ui.oidc-authority", "https://titan.test.example.com/realms/titan-dev/");
    }
  }
}

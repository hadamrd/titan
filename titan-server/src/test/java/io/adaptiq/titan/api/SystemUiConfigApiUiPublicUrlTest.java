package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Split-origin test for {@link SystemUiConfigApi}: when {@code titan.ui.public-url} is set to the
 * SPA origin, the derived {@code oidc.redirectUri} / {@code oidc.postLogoutRedirectUri} MUST use
 * it, while {@code publicUrl} MUST keep mirroring {@code titan.public-url} (issue #38).
 *
 * <p>Why the split matters: {@code titan.public-url} is consumed by server-origin features (SCM
 * status links, GitHub App webhook URLs, notification deep-links) and cannot be repointed at the
 * SPA origin on rigs where the two differ — e.g. the local rig serves the SPA on {@code
 * localhost:5180} and titan-server on {@code localhost:18080}, and the Keycloak realm seed
 * allowlists exactly {@code http://localhost:5180/*} for the {@code titan-ui} client. Serving a
 * server-origin redirectUri there makes browser login impossible.
 *
 * <p>The fallback (key unset → redirect URIs derive from {@code titan.public-url}) is covered by
 * the happy-path test ({@code SystemUiConfigApiTest}), which does not set the override.
 */
@QuarkusTest
@TestProfile(SystemUiConfigApiUiPublicUrlTest.SplitOrigin.class)
class SystemUiConfigApiUiPublicUrlTest {

  @Test
  void uiConfig_redirectUris_useUiPublicUrl_whilePublicUrlStaysServerOrigin() {
    given()
        .when()
        .get("/api/v1/system/ui-config")
        .then()
        .statusCode(200)
        .contentType("application/json")
        // publicUrl stays the SERVER origin — webhook/SCM consumers depend on it.
        .body("publicUrl", equalTo("http://localhost:18080"))
        // redirect URIs move to the SPA origin — the one Keycloak allowlists.
        .body("oidc.redirectUri", equalTo("http://localhost:5180/login/callback"))
        .body("oidc.postLogoutRedirectUri", equalTo("http://localhost:5180/login"))
        .body("oidc.authority", equalTo("http://localhost:8081/realms/titan-dev"));
  }

  public static class SplitOrigin implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          // Mirrors the local rig shape: server on :18080, SPA on :5180, Keycloak on :8081.
          "titan.public-url", "http://localhost:18080",
          // trailing slash on purpose — the override must be normalised like titan.public-url.
          "titan.ui.public-url", "http://localhost:5180/",
          "titan.ui.oidc-authority", "http://localhost:8081/realms/titan-dev");
    }
  }
}

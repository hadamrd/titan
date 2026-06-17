package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Authority-fallback test for {@link SystemUiConfigApi}: when {@code titan.ui.oidc-authority} is
 * unset, the endpoint MUST fall back to {@code quarkus.oidc.auth-server-url}. This matches the
 * common deployment shape where the same issuer URL is reachable from both the server (token
 * validation) and the browser (login redirect).
 *
 * <p>The override only matters in k8s rigs where in-cluster service DNS differs from the public
 * ingress; that scenario is covered by the happy-path test ({@code SystemUiConfigApiTest}).
 */
@QuarkusTest
@TestProfile(SystemUiConfigApiAuthorityFallbackTest.AuthorityFallback.class)
class SystemUiConfigApiAuthorityFallbackTest {

  @Test
  void uiConfig_authority_fallsBackToOidcAuthServerUrl_whenUiOverrideUnset() {
    given()
        .when()
        .get("/api/v1/system/ui-config")
        .then()
        .statusCode(200)
        .body("oidc.authority", equalTo("https://kc.example.com/realms/titan"))
        .body("publicUrl", equalTo("https://app.example.com"));
  }

  public static class AuthorityFallback implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // Enable OIDC + set auth-server-url, but disable discovery so we don't need a live Keycloak
      // at test time. titan.ui.oidc-authority intentionally NOT set — the resolver must fall back.
      return Map.of(
          "titan.public-url", "https://app.example.com",
          "quarkus.oidc.enabled", "true",
          "quarkus.oidc.discovery-enabled", "false",
          // jwks-path is required by Quarkus OIDC when discovery is disabled — value is unused at
          // request time for our @PermitAll endpoint, but the tenant context refuses to start
          // without it.
          "quarkus.oidc.jwks-path", "/protocol/openid-connect/certs",
          "quarkus.oidc.auth-server-url", "https://kc.example.com/realms/titan");
    }
  }
}

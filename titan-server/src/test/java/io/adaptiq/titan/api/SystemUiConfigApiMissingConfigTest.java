package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Sad-path test for {@link SystemUiConfigApi}: when NEITHER {@code titan.ui.oidc-authority} NOR
 * {@code quarkus.oidc.auth-server-url} is set, the endpoint must return a 500 {@code
 * application/problem+json} with a clear pointer to the missing keys — NOT a 200 with an
 * empty-string {@code authority} (the #898 silent-config failure mode we're fixing on the server
 * side).
 *
 * <p>This is the realistic server-side misconfig we can simulate without bringing down unrelated
 * beans: {@code titan.public-url} is required by other components (e.g. {@link
 * io.adaptiq.titan.scm.github.GithubStatusReporter}) and is given a sensible localhost default in
 * {@code application.properties}, so a "blank public URL" deployment doesn't happen in practice on
 * the server. Empty-string OIDC authority, however, IS a real production hazard (any deploy that
 * forgets to set {@code TITAN_OIDC_ISSUER}).
 *
 * <p>Kept in its own top-level class because {@link QuarkusTestProfile} is applied per-test-class.
 */
@QuarkusTest
@TestProfile(SystemUiConfigApiMissingConfigTest.MissingAuthority.class)
class SystemUiConfigApiMissingConfigTest {

  @Test
  void uiConfig_missingOidcAuthority_returns500ProblemJson_withClearDetail() {
    given()
        .when()
        .get("/api/v1/system/ui-config")
        .then()
        .statusCode(500)
        .contentType("application/problem+json")
        .body("status", is(500))
        .body("title", equalTo("Internal Server Error"))
        // Detail must name the config keys an SRE would grep for — no opaque stack-trace prose.
        .body("detail", containsString("titan.ui.oidc-authority"))
        .body("detail", containsString("quarkus.oidc.auth-server-url"));
  }

  public static class MissingAuthority implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // Default test profile has quarkus.oidc.enabled=false (so auth-server-url is unset) AND we
      // deliberately leave titan.ui.oidc-authority unset. publicUrl gets a sane test value so the
      // failure isolates to the authority resolver.
      return Map.of("titan.public-url", "https://example.com");
    }
  }
}

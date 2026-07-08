package io.adaptiq.titan.auth;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Minimal test profile for {@code KeycloakAuthIT} (src/integrationTest).
 *
 * <p>Re-enables OIDC so that Quarkus Dev Services for Keycloak starts a real Keycloak container and
 * auto-injects {@code quarkus.oidc.auth-server-url}. No URL is set here — Dev Services resolves it
 * at runtime after the container starts.
 *
 * <p>The default test profile disables OIDC ({@code quarkus.oidc.enabled=false}) so that the
 * {@code @TestSecurity}-based {@link RbacTest} does not require a live OIDC server. This profile
 * overrides that single property to re-enable Dev Services for the Keycloak IT.
 */
public class KeycloakDevServicesProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    // Dev Services injects quarkus.oidc.auth-server-url at runtime — do NOT set it here
    // (setting it, even to empty, suppresses Dev Services injection).
    return Map.of(
        // Re-enable OIDC so Dev Services starts the Keycloak container.
        "quarkus.oidc.enabled", "true",
        // service mode: validate bearer tokens, no browser redirect.
        "quarkus.oidc.application-type", "service",
        // Match the titan-server client seeded in test-realm.json. The realm's
        // oidc-audience-mapper adds aud=titan-server to access tokens, so the
        // default-scope audience check (titan-server) passes unchanged.
        "quarkus.oidc.client-id", "titan-server",
        "quarkus.oidc.token.audience", "titan-server",
        // Bind the embedded HTTP server to an ephemeral port. Quarkus' default
        // test-port is 8081, which collides with the local rig's Keycloak
        // (`local-keycloak-1` -> 127.0.0.1:8081). Using port 0 lets the OS
        // pick a free port — RestAssured picks it up via Quarkus' test
        // infrastructure, so no further wiring is needed.
        "quarkus.http.test-port", "0");
  }
}

package io.adaptiq.titan.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Integration test scaffold for the OIDC <em>dual-URL</em> scenario that bit production in PR #343.
 *
 * <p>In the deployed rig the SPA reaches Keycloak through one URL ({@code
 * http://localhost:8081/...}) while titan-server (running in a Docker network) reaches Keycloak
 * through another ({@code http://keycloak:8080/...}). Tokens issued by Keycloak carry the SPA-side
 * URL in their {@code iss} claim, so titan-server's OIDC validator must use {@code
 * quarkus.oidc.token.issuer=<external-url>} to override the auto-derived one, otherwise every
 * bearer token is rejected with 401.
 *
 * <p><strong>Status: DISABLED — needs hand-rolled Keycloak Testcontainer harness.</strong> The
 * existing {@code KeycloakAuthIT} (src/integrationTest) uses Quarkus Dev Services for Keycloak,
 * which auto-injects {@code quarkus.oidc.auth-server-url} at runtime and exposes a single URL to
 * both sides. To reproduce the dual-URL bug we'd need:
 *
 * <ol>
 *   <li>A {@code @QuarkusTestResource(KeycloakDualUrlResource.class)} that boots a Keycloak
 *       Testcontainer, then derives {@code quarkus.oidc.auth-server-url} from the container's
 *       internal network alias and {@code quarkus.oidc.token.issuer} from the host-mapped port.
 *   <li>A helper that mints a JWT with {@code iss=<external-url>} (Keycloak's host-mapped issuer
 *       URL) so the validator must use the override to accept it.
 *   <li>The negative variant (same harness, NO {@code token.issuer} override) that asserts 401.
 * </ol>
 *
 * <p>Follow-up tracked separately — see PR body for the issue link. Until that infra lands, this
 * file documents the intended assertions and the bug class it guards.
 */
@QuarkusTest
@TestProfile(OidcIssuerOverrideIT.Profile.class)
@Disabled(
    "Needs a hand-rolled Keycloak Testcontainer + dual-URL resource. Dev Services exposes only one"
        + " URL so the issuer override path cannot be exercised here. Follow-up filed.")
class OidcIssuerOverrideIT {

  /**
   * Placeholder profile. Real implementation will set {@code quarkus.oidc.token.issuer} to the
   * host-mapped URL while {@code quarkus.oidc.auth-server-url} points to the container-internal
   * URL.
   */
  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.oidc.enabled", "true", "quarkus.oidc.application-type", "service");
    }
  }

  /**
   * Positive: server is configured with the issuer override; a token whose {@code iss} matches the
   * external URL is accepted; {@code /api/v1/jobs} returns 200.
   */
  @Test
  void validToken_withIssuerOverride_returns200() {
    // TODO: implement after KeycloakDualUrlResource lands. See class-level Javadoc.
  }

  /**
   * Negative: same wire setup, but no {@code quarkus.oidc.token.issuer} override — the validator
   * rejects the token because the derived issuer ({@code auth-server-url}) does not match the
   * token's {@code iss} claim. Expect 401.
   */
  @Test
  void validToken_withoutIssuerOverride_returns401() {
    // TODO: implement after KeycloakDualUrlResource lands. See class-level Javadoc.
  }
}

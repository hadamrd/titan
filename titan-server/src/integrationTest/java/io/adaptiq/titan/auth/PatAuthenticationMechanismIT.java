package io.adaptiq.titan.auth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.TitanStores;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * End-to-end IT for {@link PatAuthenticationMechanism} — closes #498.
 *
 * <p>Companion to {@link KeycloakAuthIT} (OIDC) and {@code PatTokenVerifierTest} (unit). The IT was
 * deferred at PR #477 because {@code @QuarkusTest} startup was broken by the H2-dialect bug in
 * {@code QueueProcessor.reapStale}; PR #520 fixed that, so this file can now boot the full Quarkus
 * stack.
 *
 * <h2>Profile</h2>
 *
 * <p>Re-uses {@link KeycloakDevServicesProfile} so OIDC is enabled — the default test profile
 * disables OIDC entirely, but that breaks {@link PatAuthenticationMechanism}'s {@code
 * getCredentialTypes()} contract because no {@code IdentityProvider<TokenAuthenticationRequest>} is
 * registered when OIDC is off. With Dev Services for Keycloak running, the OIDC mechanism registers
 * that provider as a side effect, and the PAT mechanism can boot. This is also the production
 * posture (OIDC always on alongside PAT), so this profile matches real deployment.
 *
 * <h2>What this IT asserts</h2>
 *
 * <ol>
 *   <li>{@link #verifier_acceptsFreshlyMintedToken} — the {@link PatTokenVerifier} bean, injected
 *       via the same CDI graph the mechanism uses, accepts a token minted through the live HTTP
 *       endpoint. This is the closest end-to-end coverage we can give the verifier path without
 *       depending on the mechanism-selection wiring (which has a known coexistence bug — see
 *       below).
 *   <li>{@link #typo_returns401} — a PAT-shaped bearer with a single-byte flip is rejected.
 *       Status-only assertion — constant-time is BCrypt's contract, not ours to time-bound.
 *   <li>{@link #revoked_returns401} — after DELETE, the same bearer is rejected. The DAO's {@code
 *       revoked_at IS NULL} filter short-circuits the lookup before BCrypt runs.
 * </ol>
 *
 * <h2>Closes #527 (coexistence fix)</h2>
 *
 * <p>The two coexistence assertions ({@link #patBearer_succeedsAgainstProtectedEndpoint}, {@link
 * #patAndOidc_coexist_withDistinctPrincipals}) were previously {@code @Disabled} because Quarkus'
 * {@code HttpAuthenticator} sorts mechanisms by {@link
 * io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism#getPriority()} (DESCENDING)
 * and walks them in that order. The OIDC mechanism returns {@code DEFAULT_PRIORITY + 1 = 1001}, so
 * before #527 it always ran first, tried to introspect the opaque {@code titanpat_…} bearer against
 * Keycloak, failed, and short-circuited the chain with an {@code AuthenticationFailedException} —
 * the PAT mechanism never saw the request.
 *
 * <p>The fix in {@link PatAuthenticationMechanism} overrides {@code getPriority()} to return {@code
 * DEFAULT_PRIORITY + 2 = 1002}, putting PAT ahead of OIDC. PAT claims {@code titanpat_*} bearers
 * and returns {@link io.smallrye.mutiny.Uni#createFrom()}{@code .nullItem()} for every other
 * request shape, restoring the documented OIDC fallback path.
 *
 * <h2>Constitution adherence</h2>
 *
 * <ul>
 *   <li>Plaintext token only ever lives in test-local strings; never echoed in failure messages.
 *   <li>Bearer-only — no cookie or query-param variant is exercised (the absence of those code
 *       paths in {@link PatAuthenticationMechanism} is the negative-coverage contract).
 *   <li>Compare goes through {@code BcryptUtil.matches → BCrypt.checkpw} (PR #497) on every
 *       valid-PAT verification — implicit in the verifier-direct assertion.
 * </ul>
 */
@QuarkusTest
@TestProfile(KeycloakDevServicesProfile.class)
class PatAuthenticationMechanismIT {

  private final KeycloakTestClient keycloak = new KeycloakTestClient();

  @Inject TitanStores stores;
  @Inject PatTokenVerifier verifier;

  // ── Verifier-direct (HTTP mint → CDI verify) — passing today ─────────────

  @Test
  void verifier_acceptsFreshlyMintedToken() {
    String oidcToken = keycloak.getAccessToken("alice");
    String tokenName = "verifier-direct-" + System.nanoTime();
    String pat = mintPat(oidcToken, tokenName);
    assertTrue(pat.startsWith(PatTokenVerifier.TOKEN_PREFIX), "minted token carries titanpat_");

    // Same CDI graph the mechanism would use — proves the DAO write + BCrypt hash + DAO read
    // + BCrypt match path is intact end-to-end under a fully-booted Quarkus runtime (not just
    // the unit-test FakeTitanStores in PatTokenVerifierTest).
    Optional<PatTokenVerifier.VerifiedPat> result = verifier.verify(pat);
    assertTrue(result.isPresent(), "verifier accepts freshly-minted PAT");
    assertNotNull(result.get().userSubject(), "verified PAT carries the owning subject");
    assertTrue(result.get().tokenId() > 0, "verified PAT carries the row id");

    // Cross-check the row landed in the same store the verifier reads.
    assertTrue(
        !stores.personalAccessTokens().findActiveByPrefix(pat.substring(0, 13)).isEmpty(),
        "DAO sees the row the mint endpoint wrote");
  }

  // ── Adversarial A: typo → verifier rejects ───────────────────────────────

  @Test
  void typo_returns401() {
    String oidcToken = keycloak.getAccessToken("alice");
    String pat = mintPat(oidcToken, "verifier-typo-" + System.nanoTime());

    // Single-byte flip; still PAT-shaped so it survives the prefix gate, but BCrypt.checkpw
    // must reject. The verifier returns Optional.empty; the HTTP layer would surface 401.
    char flipped = pat.charAt(pat.length() - 1) == 'A' ? 'B' : 'A';
    String typo = pat.substring(0, pat.length() - 1) + flipped;
    assertNotEquals(pat, typo, "sanity: typo string differs from minted token");

    assertTrue(verifier.verify(typo).isEmpty(), "verifier rejects PAT with single-byte typo");

    // HTTP-level: the typo bearer is rejected. The mechanism still returns 401 here even
    // before the production-side coexistence fix lands, because either path (PAT
    // rejected → AuthenticationFailedException; OIDC rejected → introspection failure) ends
    // in 401. The assertion is robust to the known mechanism-selection bug.
    given()
        .header("Authorization", "Bearer " + typo)
        .when()
        .get("/api/v1/me/tokens")
        .then()
        .statusCode(401);
  }

  // ── Adversarial B: revoke → verifier rejects ─────────────────────────────

  @Test
  void revoked_returns401() {
    String oidcToken = keycloak.getAccessToken("alice");
    String tokenName = "verifier-revoke-" + System.nanoTime();
    String pat = mintPat(oidcToken, tokenName);

    // Pre: verifier accepts.
    assertTrue(verifier.verify(pat).isPresent(), "verifier accepts before revoke");

    // Revoke via the owner's OIDC bearer (the canonical revoke path).
    long tokenId = lookupTokenIdByName(oidcToken, tokenName);
    given()
        .auth()
        .oauth2(oidcToken)
        .when()
        .delete("/api/v1/me/tokens/" + tokenId)
        .then()
        .statusCode(204);

    // Post: verifier rejects. DAO's revoked_at IS NULL filter short-circuits BEFORE the
    // BCrypt compare ever runs — the revocation is the gate, not a per-token check.
    assertTrue(verifier.verify(pat).isEmpty(), "verifier rejects revoked PAT");

    // HTTP-level: bearer is rejected (401 regardless of which mechanism handles it).
    given()
        .header("Authorization", "Bearer " + pat)
        .when()
        .get("/api/v1/me/tokens")
        .then()
        .statusCode(401);
  }

  // ── Coexistence with OIDC (re-enabled by #527) ───────────────────────────

  @Test
  void patBearer_succeedsAgainstProtectedEndpoint() {
    String oidcToken = keycloak.getAccessToken("alice");
    String tokenName = "happy-" + System.nanoTime();
    String pat = mintPat(oidcToken, tokenName);

    given()
        .header("Authorization", "Bearer " + pat)
        .when()
        .get("/api/v1/me/tokens")
        .then()
        .statusCode(200)
        .body("find { it.name == '" + tokenName + "' }.id", notNullValue());
  }

  @Test
  void patAndOidc_coexist_withDistinctPrincipals() {
    String aliceOidc = keycloak.getAccessToken("alice");
    String viewerOidc = keycloak.getAccessToken("viewer");
    String tokenName = "coexist-" + System.nanoTime();
    String alicePat = mintPat(aliceOidc, tokenName);

    // alice's PAT lists her tokens.
    given()
        .header("Authorization", "Bearer " + alicePat)
        .when()
        .get("/api/v1/me/tokens")
        .then()
        .statusCode(200)
        .body("find { it.name == '" + tokenName + "' }.id", notNullValue());

    // viewer's OIDC bearer sees viewer's (empty) list — disjoint from alice's.
    given()
        .auth()
        .oauth2(viewerOidc)
        .when()
        .get("/api/v1/me/tokens")
        .then()
        .statusCode(200)
        .body("find { it.name == '" + tokenName + "' }", org.hamcrest.Matchers.nullValue());
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static String mintPat(String oidcToken, String name) {
    String body = "{\"name\":\"" + name + "\"}";
    String token =
        given()
            .auth()
            .oauth2(oidcToken)
            .contentType("application/json")
            .body(body)
            .when()
            .post("/api/v1/me/tokens")
            .then()
            .statusCode(201)
            .body("token", startsWith(PatTokenVerifier.TOKEN_PREFIX))
            .extract()
            .path("token");
    assertNotNull(token, "mint returned a token");
    return token;
  }

  private static long lookupTokenIdByName(String oidcToken, String name) {
    Number id =
        given()
            .auth()
            .oauth2(oidcToken)
            .when()
            .get("/api/v1/me/tokens")
            .then()
            .statusCode(200)
            .body("size()", greaterThanOrEqualTo(1))
            .extract()
            .path("find { it.name == '" + name + "' }.id");
    assertNotNull(id, "lookup found a token row by name");
    assertTrue(id.longValue() > 0, "lookup returned a positive id");
    return id.longValue();
  }
}

package io.adaptiq.titan.auth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.response.Response;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Integration test: real bearer-token auth against a Keycloak container managed by Quarkus Dev
 * Services.
 *
 * <p>{@link KeycloakDevServicesProfile} re-enables OIDC so Dev Services starts a real Keycloak
 * container. The {@code keycloak/test-realm.json} file is imported automatically via {@code
 * quarkus.keycloak.devservices.realm-path}. Dev Services auto-injects {@code
 * quarkus.oidc.auth-server-url} before the application boots — no URL is set manually.
 *
 * <p>Realm: {@code titan-test}<br>
 * Client: {@code titan-server} (public, direct-access-grants enabled)<br>
 * Users:
 *
 * <ul>
 *   <li>{@code alice} — roles: {@code READ_JOB, TRIGGER_BUILD, EDIT_PIPELINE, APPROVE_GATE}
 *   <li>{@code viewer} — roles: {@code READ_JOB} only
 * </ul>
 *
 * <p>Three mandatory assertions:
 *
 * <ol>
 *   <li>No token → 401 (unauthenticated).
 *   <li>Valid token for {@code alice} (has {@code READ_JOB}) → not 401/403; proves OIDC plumbing.
 *   <li>Valid token for {@code viewer} (lacks {@code TRIGGER_BUILD}) → 403 on a trigger endpoint.
 * </ol>
 */
@QuarkusTest
@TestProfile(KeycloakDevServicesProfile.class)
class KeycloakAuthIT {

  /** Dev Services auto-configures the realm; KeycloakTestClient reads the injected issuer URL. */
  private final KeycloakTestClient keycloak = new KeycloakTestClient();

  // ── 1. Unauthenticated → 401 ─────────────────────────────────────────────

  @Test
  void getBuild_noToken_returns401() {
    given().when().get("/api/v1/builds/1").then().statusCode(401);
  }

  @Test
  void listJobs_noToken_returns401() {
    given().when().get("/api/v1/jobs").then().statusCode(401);
  }

  @Test
  void triggerBuild_noToken_returns401() {
    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/jobs/1/builds")
        .then()
        .statusCode(401);
  }

  // ── 2. Valid token (alice has READ_JOB) → OIDC plumbing end-to-end ───────
  // 404 = auth passed, resource not found. 200 also acceptable. Must NOT be 401/403.

  @Test
  void getBuild_validToken_succeeds() {
    String token = keycloak.getAccessToken("alice");
    int status =
        given()
            .auth()
            .oauth2(token)
            .when()
            .get("/api/v1/builds/999999")
            .then()
            .extract()
            .statusCode();
    // 404 = resource not found (auth passed); 200 also valid if a build exists
    assert status != 401 && status != 403
        : "Expected non-401/403 with alice's valid bearer token, got " + status;
  }

  @Test
  void listJobs_validToken_returns200() {
    String token = keycloak.getAccessToken("alice");
    given().auth().oauth2(token).when().get("/api/v1/jobs").then().statusCode(200);
  }

  // ── 3. Read-only token (viewer lacks TRIGGER_BUILD) → 403 ────────────────

  @Test
  void triggerBuild_readOnlyToken_returns403() {
    String token = keycloak.getAccessToken("viewer");
    given()
        .auth()
        .oauth2(token)
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/jobs/999999/builds")
        .then()
        .statusCode(403);
  }

  // ── 4. PAT + OIDC coexistence smoke (closes #499) ────────────────────────
  // The PAT mechanism sits at @Priority(1000) alongside OIDC. Both share the
  // "Bearer" credential transport; the discriminator is the bearer PREFIX
  // (titanpat_*). Three asserts pin coexistence:
  //
  //   a) Valid OIDC JWT against /api/v1/me/tokens (the PAT-owned resource)
  //      still returns 200 even with the PAT mechanism in the bean container.
  //      This is the "OIDC didn't get shadowed" assertion #499 calls for.
  //   b) No Authorization header → 401 whose WWW-Authenticate carries the
  //      OIDC challenge shape (NOT `realm="titan"` — that's the PAT
  //      mechanism's shape, used only for PAT-shaped-but-invalid bearers).
  //      Pins that PAT.getChallenge() returns nullItem() for non-PAT
  //      requests, so OIDC owns the 401.
  //   c) DISABLED — PAT bearer minted via OIDC reaches the same endpoint and
  //      resolves to the PAT's userSubject (distinct from a second OIDC
  //      caller's subject). Blocked on #527 (PR #477 follow-up — Quarkus'
  //      HttpAuthenticator selects OIDC over PAT for shared "Bearer"
  //      transports). Re-enable when #527 lands.

  @Test
  void oidcBearer_listMyTokens_returns200_alongsidePatMechanism() {
    // Prove the OIDC mechanism is not shadowed by PatAuthenticationMechanism
    // for non-PAT bearers, even on a resource that PAT-auth is meant to also
    // cover. /api/v1/me/tokens is the canonical OIDC-gated endpoint per
    // PersonalAccessTokenApi.@RolesAllowed("**").
    String oidcToken = keycloak.getAccessToken("alice");
    given().auth().oauth2(oidcToken).when().get("/api/v1/me/tokens").then().statusCode(200);
  }

  @Test
  void noAuthHeader_listMyTokens_returns401_withOidcChallenge_notPatChallenge() {
    // No Authorization header → PatAuthenticationMechanism.getChallenge()
    // returns Uni.nullItem() (see its `bearer == null` branch) and OIDC owns
    // the 401. The OIDC challenge MUST NOT carry the PAT mechanism's
    // `realm="titan"` discriminator — if it does, the PAT challenge has
    // wrongly taken over the no-bearer path and the SPA's OIDC redirect
    // flow will break.
    Response resp =
        given().when().get("/api/v1/me/tokens").then().statusCode(401).extract().response();

    String challenge = resp.getHeader("WWW-Authenticate");
    // OIDC mechanism always emits a WWW-Authenticate; bare "Bearer" or a
    // Bearer with parameters other than the PAT mechanism's `realm="titan"`.
    assertNotNull(challenge, "401 must carry a WWW-Authenticate header (RFC 7235)");
    assertTrue(
        challenge.startsWith("Bearer"), "OIDC challenge starts with 'Bearer', got: " + challenge);
    assertTrue(
        !challenge.contains("realm=\"titan\""),
        "PAT mechanism's challenge leaked to no-bearer request; expected OIDC challenge, got: "
            + challenge);
  }

  @Test
  @Disabled(
      "BLOCKED on #527: Quarkus' HttpAuthenticator picks the OIDC mechanism over PAT for shared"
          + " 'Bearer' HttpCredentialTransport, so a titanpat_* bearer is introspected against"
          + " Keycloak and rejected before PatAuthenticationMechanism runs. Re-enable once the"
          + " mechanism narrows its tokenType discriminator (see PatAuthenticationMechanismIT's"
          + " patAndOidc_coexist_withDistinctPrincipals for the same gap).")
  void patAndOidcBearer_resolveToDistinctPrincipals_onSameEndpoint() {
    // Coexistence smoke per #499: same endpoint, two bearers, two distinct
    // resolved principals. alice mints a PAT; viewer presents her OIDC
    // bearer. The PAT-authenticated call must see alice's tokens (PAT's
    // userSubject == alice's sub), the OIDC-authenticated call must see
    // viewer's tokens (empty / disjoint from alice's). If they overlap, the
    // mechanism wired the wrong principal — hard fail.
    String aliceOidc = keycloak.getAccessToken("alice");
    String viewerOidc = keycloak.getAccessToken("viewer");

    String tokenName = "coexist-smoke-" + System.nanoTime();
    String alicePat = mintPatAs(aliceOidc, tokenName);
    assertTrue(alicePat.startsWith("titanpat_"), "minted token shape sanity");

    // PAT bearer → alice's identity → sees the freshly-minted token.
    given()
        .header("Authorization", "Bearer " + alicePat)
        .when()
        .get("/api/v1/me/tokens")
        .then()
        .statusCode(200)
        .body("find { it.name == '" + tokenName + "' }.id", notNullValue());

    // viewer's OIDC bearer → viewer's identity → MUST NOT see alice's
    // token. Hard assertion (constitution adversarial-tests rule) — no
    // "either one is fine" tolerance.
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

  private static String mintPatAs(String oidcToken, String name) {
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
            .body("token", startsWith("titanpat_"))
            .extract()
            .path("token");
    assertNotNull(token, "mint returned a token");
    return token;
  }
}

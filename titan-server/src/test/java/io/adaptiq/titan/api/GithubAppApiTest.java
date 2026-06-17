package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.adaptiq.titan.scm.github.GithubClientFactory;
import io.adaptiq.titan.scm.github.TestRsaKeys;
import io.quarkus.test.Mock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.security.KeyPair;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * REST-layer tests for {@link GithubAppApi}.
 *
 * <p>Production wiring is replaced by a CDI alternative that injects a {@link GithubApiHttpClient}
 * pointed at a class-scoped {@link WireMockServer}. The fake credential-key provider from {@link
 * FixedCredentialKeyProviderProducer} and the in-memory H2 stores from {@link H2StoresProducer} are
 * reused untouched.
 *
 * <p><strong>Hard invariants under test:</strong>
 *
 * <ul>
 *   <li>{@code POST /api/v1/github-app/manifest-callback?code=X} returns 201 + metadata-only DTO
 *   <li>Response NEVER contains the PEM, the webhook secret, or any sealed-blob field name
 *   <li>Replayed callback with a fresh code → row updated in place, still 201, still one row
 *   <li>Invalid code (GitHub 422) → HTTP 400 from the API
 *   <li>{@code GET /api/v1/github-app} → 204 when no row, 200 + metadata DTO when registered
 * </ul>
 */
@QuarkusTest
@TestSecurity(
    user = "admin",
    roles = {"ADMIN"})
class GithubAppApiTest {

  private static WireMockServer WIREMOCK;
  private static String TEST_PEM;
  private static final String PLAINTEXT_WEBHOOK_SECRET = "super-secret-webhook-99";

  @BeforeAll
  static void startWireMock() throws Exception {
    WIREMOCK = new WireMockServer(WireMockConfiguration.options().port(38765));
    WIREMOCK.start();
    KeyPair kp = TestRsaKeys.newRsaKeyPair();
    TEST_PEM = TestRsaKeys.toPkcs8Pem(kp);
  }

  @AfterAll
  static void stopWireMock() {
    if (WIREMOCK != null) WIREMOCK.stop();
  }

  /**
   * Test-scope alternative that wins over the production {@link GithubClientFactory} producer in
   * {@link io.adaptiq.titan.scm.github.GithubAppProducer}. Points the kohsuke {@code GitHub} client
   * at the local WireMock instance instead of {@code api.github.com}.
   */
  @Mock
  @ApplicationScoped
  public static class WireMockGithubApiClientProducer {
    @Produces
    @ApplicationScoped
    public GithubClientFactory factory() {
      return new GithubClientFactory("http://localhost:38765");
    }
  }

  @Test
  void getApp_whenUnregistered_returns204() {
    given().when().get("/api/v1/github-app").then().statusCode(204);
  }

  @Test
  void manifestCallback_persistsRow_andResponseNeverLeaksSecret() {
    String code = "callback-code-" + System.nanoTime();
    stubManifest(code, 4242L, "Titan First", "titan-first");

    String resp =
        given()
            .when()
            .post("/api/v1/github-app/manifest-callback?code=" + code)
            .then()
            .statusCode(201)
            .body("appId", equalTo(4242))
            .body("slug", equalTo("titan-first"))
            .extract()
            .asString();

    // Hard invariants — none of these strings may appear in the JSON.
    assertFalse(resp.contains(PLAINTEXT_WEBHOOK_SECRET), "response leaked webhook secret: " + resp);
    assertFalse(resp.contains("BEGIN PRIVATE KEY"), "response leaked PEM: " + resp);
    assertFalse(resp.contains("BEGIN RSA PRIVATE KEY"), "response leaked PEM: " + resp);
    assertFalse(resp.contains("pem"), "response leaked the 'pem' field name: " + resp);
    assertFalse(
        resp.contains("webhook_secret") || resp.contains("webhookSecret"),
        "response leaked webhook_secret field: " + resp);
    assertFalse(resp.contains("pemSealedValue"), "response leaked sealed-pem field: " + resp);
    assertFalse(resp.contains("pemWrappedDek"), "response leaked wrapped-dek field: " + resp);
  }

  @Test
  void manifestCallback_missingCode_returns400() {
    given().when().post("/api/v1/github-app/manifest-callback").then().statusCode(400);
  }

  @Test
  void manifestCallback_invalidCode_returns400() {
    String code = "bad-code-" + System.nanoTime();
    WIREMOCK.stubFor(
        WireMock.post(WireMock.urlEqualTo("/app-manifests/" + code + "/conversions"))
            .willReturn(
                WireMock.aResponse().withStatus(422).withBody("{\"message\":\"Bad code\"}")));

    given().when().post("/api/v1/github-app/manifest-callback?code=" + code).then().statusCode(400);
  }

  @Test
  void manifestCallback_replayedWithFreshCode_keepsSingleRow() {
    String code1 = "first-" + System.nanoTime();
    stubManifest(code1, 1000L, "First", "first");
    given()
        .when()
        .post("/api/v1/github-app/manifest-callback?code=" + code1)
        .then()
        .statusCode(201);

    String code2 = "second-" + System.nanoTime();
    stubManifest(code2, 2000L, "Second", "second");
    given()
        .when()
        .post("/api/v1/github-app/manifest-callback?code=" + code2)
        .then()
        .statusCode(201)
        .body("appId", equalTo(2000))
        .body("slug", equalTo("second"));

    // The GET endpoint reflects the most recent registration — still exactly one row.
    given().when().get("/api/v1/github-app").then().statusCode(200).body("slug", equalTo("second"));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static void stubManifest(String code, long appId, String name, String slug) {
    String escapedPem = TEST_PEM.replace("\n", "\\n");
    String body =
        "{\"id\":"
            + appId
            + ",\"name\":\""
            + name
            + "\",\"slug\":\""
            + slug
            + "\",\"html_url\":\"https://github.com/apps/"
            + slug
            + "\",\"pem\":\""
            + escapedPem
            + "\",\"webhook_secret\":\""
            + PLAINTEXT_WEBHOOK_SECRET
            + "\"}";
    WIREMOCK.stubFor(
        WireMock.post(WireMock.urlEqualTo("/app-manifests/" + code + "/conversions"))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }
}

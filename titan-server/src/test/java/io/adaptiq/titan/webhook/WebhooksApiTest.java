package io.adaptiq.titan.webhook;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * @QuarkusTest covering {@link WebhooksApi} — the legacy {@code /api/v1/webhooks/github} endpoint
 * is now a pure 308 redirect shim to the canonical {@code /api/v1/triggers/github} (issue #970,
 * design/52).
 *
 * <p>Adversarial intent: the body of these tests is what catches accidental regression to the old
 * dual-write behavior. If anybody puts dispatch logic back into {@code WebhooksApi}, the "no body /
 * 308 / Location header" trio fails immediately. We deliberately do NOT follow the redirect here —
 * that's the integration test's job; the unit test verifies the shim itself.
 */
@QuarkusTest
@TestSecurity(user = "testuser", roles = "ADMIN")
class WebhooksApiTest {

  // ── happy path: POST → 308 with the right headers ──────────────────────────

  @Test
  void githubPost_returns308WithLocationAndDeprecationHeaders() {
    given()
        .header("X-GitHub-Event", "push")
        .header("X-Hub-Signature-256", "sha256=" + "0".repeat(64))
        .contentType("application/json")
        .body("{\"ref\":\"refs/heads/main\"}")
        .redirects()
        .follow(false)
        .when()
        .post("/api/v1/webhooks/github")
        .then()
        .statusCode(308)
        .header("Location", equalTo("/api/v1/triggers/github"))
        .header("Deprecation", equalTo("true"))
        .header("Sunset", notNullValue());
  }

  // ── redirect is unconditional: malformed JSON still gets 308, no parse-leak ─

  @Test
  void githubPost_malformedJson_stillRedirects() {
    given()
        .header("X-GitHub-Event", "push")
        .contentType("application/json")
        .body("{ not json")
        .redirects()
        .follow(false)
        .when()
        .post("/api/v1/webhooks/github")
        .then()
        .statusCode(308)
        .header("Location", equalTo("/api/v1/triggers/github"));
  }

  // ── redirect is unconditional: bad signature still gets 308 — verification
  //    must happen at the canonical endpoint, never on the legacy shim ────────

  @Test
  void githubPost_spoofedSignature_stillRedirects() {
    String body = "{\"ref\":\"refs/heads/main\",\"repository\":{\"full_name\":\"x/y\"}}";
    given()
        .header("X-GitHub-Event", "push")
        .header("X-Hub-Signature-256", "sha256=" + "f".repeat(64))
        .contentType("application/json")
        .body(body)
        .redirects()
        .follow(false)
        .when()
        .post("/api/v1/webhooks/github")
        .then()
        .statusCode(308)
        .header("Location", equalTo("/api/v1/triggers/github"));
  }

  // ── shim has no body — empty response is the contract ──────────────────────

  @Test
  void githubPost_responseBodyIsEmpty() {
    String responseBody =
        given()
            .header("X-GitHub-Event", "push")
            .contentType("application/json")
            .body("{}")
            .redirects()
            .follow(false)
            .when()
            .post("/api/v1/webhooks/github")
            .then()
            .statusCode(308)
            .extract()
            .asString();
    org.junit.jupiter.api.Assertions.assertTrue(
        responseBody == null || responseBody.isEmpty(),
        "308 shim must not emit a body — got: " + responseBody);
  }

  // ── successor-version Link header is part of the deprecation contract ──────

  @Test
  void githubPost_advertisesSuccessorVersionLink() {
    given()
        .header("X-GitHub-Event", "push")
        .contentType("application/json")
        .body("{}")
        .redirects()
        .follow(false)
        .when()
        .post("/api/v1/webhooks/github")
        .then()
        .statusCode(308)
        .header("Link", is("</api/v1/triggers/github>; rel=\"successor-version\""));
  }
}

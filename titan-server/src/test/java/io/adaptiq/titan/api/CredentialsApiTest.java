package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * REST-layer tests for {@link CredentialsApi}. Mirrors {@link JobsApiTest} — same {@link
 * H2StoresProducer} + {@link FixedCredentialKeyProviderProducer} provide an isolated store and a
 * deterministic sealing key.
 *
 * <p>The non-negotiable invariant under test: <strong>no response, on any verb, ever contains the
 * plaintext or the sealed blob.</strong>
 */
@QuarkusTest
@TestSecurity(
    user = "testuser",
    roles = {"EDIT_PIPELINE", "ADMIN"})
class CredentialsApiTest {

  private static final String PLAINTEXT = "SUPER-SECRET-TOKEN-9001";

  // ── POST /api/v1/credentials ───────────────────────────────────────────────

  @Test
  void create_returnsMetadataOnly_neverPlaintextOrSealed() {
    String body =
        "{\"kind\":\"STRING\",\"scope\":\"global\",\"key\":\"create-"
            + System.nanoTime()
            + "\",\"plaintext\":\""
            + PLAINTEXT
            + "\"}";

    String response =
        given()
            .contentType("application/json")
            .body(body)
            .when()
            .post("/api/v1/credentials")
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("kind", equalTo("STRING"))
            .body("scope", equalTo("global"))
            .extract()
            .asString();

    // Hard invariant — the wire format must never contain plaintext, "sealedValue", or "aad".
    assertFalse(response.contains(PLAINTEXT), "response contained plaintext: " + response);
    assertFalse(response.contains("sealedValue"), "response leaked sealedValue: " + response);
    assertFalse(response.contains("aad"), "response leaked aad: " + response);
  }

  @Test
  void create_rejectsMissingPlaintext() {
    String body = "{\"kind\":\"STRING\",\"scope\":\"global\",\"key\":\"empty\"}";
    given()
        .contentType("application/json")
        .body(body)
        .when()
        .post("/api/v1/credentials")
        .then()
        .statusCode(400);
  }

  @Test
  void create_rejectsUnknownKind() {
    String body =
        "{\"kind\":\"MAGIC\",\"scope\":\"global\",\"key\":\"k-"
            + System.nanoTime()
            + "\",\"plaintext\":\"x\"}";
    given()
        .contentType("application/json")
        .body(body)
        .when()
        .post("/api/v1/credentials")
        .then()
        .statusCode(400);
  }

  // ── GET /api/v1/credentials ────────────────────────────────────────────────

  @Test
  void list_neverLeaksPlaintextOrSealed() {
    String key = "list-" + System.nanoTime();
    String body =
        "{\"kind\":\"STRING\",\"scope\":\"global\",\"key\":\""
            + key
            + "\",\"plaintext\":\""
            + PLAINTEXT
            + "\"}";
    given()
        .contentType("application/json")
        .body(body)
        .when()
        .post("/api/v1/credentials")
        .then()
        .statusCode(201);

    String resp =
        given()
            .when()
            .get("/api/v1/credentials")
            .then()
            .statusCode(200)
            .body("total", greaterThanOrEqualTo(1))
            .extract()
            .asString();

    assertFalse(resp.contains(PLAINTEXT), "list leaked plaintext: " + resp);
    assertFalse(resp.contains("sealedValue"), "list leaked sealedValue: " + resp);
  }

  // ── GET /api/v1/credentials/{id} ───────────────────────────────────────────

  @Test
  void getById_neverLeaksPlaintextOrSealed() {
    String key = "get-" + System.nanoTime();
    String body =
        "{\"kind\":\"STRING\",\"scope\":\"global\",\"key\":\""
            + key
            + "\",\"plaintext\":\""
            + PLAINTEXT
            + "\"}";
    int id =
        given()
            .contentType("application/json")
            .body(body)
            .when()
            .post("/api/v1/credentials")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    String resp =
        given()
            .when()
            .get("/api/v1/credentials/" + id)
            .then()
            .statusCode(200)
            .body("id", equalTo(id))
            .body("kind", equalTo("STRING"))
            .extract()
            .asString();

    assertFalse(resp.contains(PLAINTEXT));
    assertFalse(resp.contains("sealedValue"));
    assertFalse(resp.contains("aad"));
  }

  @Test
  void getById_unknownReturns404() {
    given().when().get("/api/v1/credentials/9999999").then().statusCode(404);
  }

  @Test
  void getById_nonNumericReturns400() {
    given().when().get("/api/v1/credentials/not-a-number").then().statusCode(400);
  }

  // ── PUT /api/v1/credentials/{id} ───────────────────────────────────────────

  @Test
  void update_rotatesSecret_andResponseDoesNotLeak() {
    String key = "rot-" + System.nanoTime();
    String createBody =
        "{\"kind\":\"STRING\",\"scope\":\"global\",\"key\":\""
            + key
            + "\",\"plaintext\":\"old-val\"}";
    int id =
        given()
            .contentType("application/json")
            .body(createBody)
            .when()
            .post("/api/v1/credentials")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    String putBody = "{\"kind\":\"STRING\",\"plaintext\":\"NEW-ROTATED-VAL-77\"}";
    String resp =
        given()
            .contentType("application/json")
            .body(putBody)
            .when()
            .put("/api/v1/credentials/" + id)
            .then()
            .statusCode(200)
            .body("id", equalTo(id))
            .extract()
            .asString();

    assertFalse(resp.contains("NEW-ROTATED-VAL-77"));
    assertFalse(resp.contains("sealedValue"));
  }

  @Test
  void update_unknownReturns404() {
    String body = "{\"kind\":\"STRING\",\"plaintext\":\"x\"}";
    given()
        .contentType("application/json")
        .body(body)
        .when()
        .put("/api/v1/credentials/9999999")
        .then()
        .statusCode(404);
  }

  // ── DELETE /api/v1/credentials/{id} ────────────────────────────────────────

  @Test
  void delete_returns204_andSubsequentGetIs404() {
    String key = "del-" + System.nanoTime();
    String body =
        "{\"kind\":\"STRING\",\"scope\":\"global\",\"key\":\"" + key + "\",\"plaintext\":\"x\"}";
    int id =
        given()
            .contentType("application/json")
            .body(body)
            .when()
            .post("/api/v1/credentials")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    given().when().delete("/api/v1/credentials/" + id).then().statusCode(204);
    given().when().get("/api/v1/credentials/" + id).then().statusCode(404);
  }
}

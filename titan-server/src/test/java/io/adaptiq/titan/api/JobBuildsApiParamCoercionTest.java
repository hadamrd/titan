package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for issue #61 (spec 27) — the trigger boundary's parameter type-check in {@link
 * JobBuildsApi} claimed to "mirror the coercion rules the bake's ParameterResolver applies" but
 * actually rejected the string forms the bake happily coerces. The #778 trigger-with-params
 * contract (and the UI's params modal) submit EVERY value as a string — {@code VERBOSE: "true"} for
 * a boolean param — which yielded a spurious {@code 400 "parameter 'VERBOSE' expects boolean, got
 * String"} for a build the bake would have run fine.
 *
 * <p>Contract pinned here, adversarially paired:
 *
 * <ul>
 *   <li>coercible strings ({@code "true"}, {@code "7"}) → 201, persisted verbatim (bake coerces);
 *   <li>native JSON types (boolean / number) → still 201;
 *   <li>UNcoercible strings ({@code "yes"} for boolean, {@code "abc"} for number) → still 400 with
 *       a parameter-name-bearing message — the boundary must not become a no-op;
 *   <li>structured values (object / array) → 400 (the bake would stringify them into JSON noise).
 * </ul>
 *
 * <p>H2-backed {@link TitanStores} via {@link H2StoresProducer} — same wiring as {@link
 * BuildsApiTest}.
 */
@QuarkusTest
@TestSecurity(user = "coercion-test", roles = "ADMIN")
class JobBuildsApiParamCoercionTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Mirrors the e2e with-params fixture: string + choice + boolean. */
  private static final String WITH_PARAMS_YAML =
      """
      agent: linux

      parameters:
        - name: GREETING
          type: string
          default: "hello"
        - name: MODE
          type: choice
          default: "dev"
          choices: ["dev", "prod"]
        - name: VERBOSE
          type: boolean
          default: false
        - name: RETRIES
          type: number
          default: 3

      stages:
        - stage: Greet
          steps:
            - sh: "echo ${{ params.GREETING }} ${{ params.MODE }}"
            - sh: "echo verbose"
              when: "params.VERBOSE"
      """;

  @Inject TitanStores stores;

  // ── coercible string forms must be ACCEPTED (the spec-27 regression) ────────

  @Test
  void booleanParamAsStringTrue_isAccepted_andPersistedVerbatim() throws Exception {
    long jobId = seedJob();

    // The exact payload spec 27 sends: every value a string (#778 Map<String,String> contract).
    long buildId =
        triggerExpecting201(
            jobId,
            "{\"parameters\":{\"GREETING\":\"world\",\"MODE\":\"prod\",\"VERBOSE\":\"true\"}}");

    BuildRow row = stores.builds().findById(buildId).orElseThrow();
    assertNotNull(row.parametersJson);
    JsonNode params = JSON.readTree(row.parametersJson);
    // Persisted verbatim as the string form — the bake's ParameterResolver coerces to boolean.
    assertEquals("true", params.get("VERBOSE").asText());
    assertEquals("world", params.get("GREETING").asText());
    assertEquals("prod", params.get("MODE").asText());
  }

  @Test
  void booleanParamAsStringFalse_caseInsensitiveAndTrimmed_isAccepted() {
    long jobId = seedJob();
    // ParameterResolver lowercases + trims before matching — the boundary must too.
    triggerExpecting201(jobId, "{\"parameters\":{\"VERBOSE\":\" False \"}}");
  }

  @Test
  void numberParamAsNumericString_isAccepted() {
    long jobId = seedJob();
    triggerExpecting201(jobId, "{\"parameters\":{\"RETRIES\":\"7\"}}");
  }

  @Test
  void nativeJsonBooleanAndNumber_stillAccepted() {
    long jobId = seedJob();
    triggerExpecting201(jobId, "{\"parameters\":{\"VERBOSE\":true,\"RETRIES\":7}}");
  }

  @Test
  void stringParamAsNativeNumber_isAccepted_bakeStringifies() {
    // ParameterResolver coerces string params via String.valueOf — never fails. A JSON number
    // for a string param must therefore pass the boundary too.
    long jobId = seedJob();
    triggerExpecting201(jobId, "{\"parameters\":{\"GREETING\":42}}");
  }

  // ── UNcoercible values must still be REJECTED with a precise message ────────

  @Test
  void booleanParamWithUncoercibleString_returns400NamingTheParameter() {
    long jobId = seedJob();

    given()
        .contentType("application/json")
        .body("{\"parameters\":{\"VERBOSE\":\"yes\"}}")
        .when()
        .post("/api/v1/jobs/" + jobId + "/builds")
        .then()
        .statusCode(400)
        .contentType(containsString("problem+json"))
        .body("detail", containsString("VERBOSE"))
        .body("detail", containsString("boolean"));
  }

  @Test
  void numberParamWithUncoercibleString_returns400NamingTheParameter() {
    long jobId = seedJob();

    given()
        .contentType("application/json")
        .body("{\"parameters\":{\"RETRIES\":\"abc\"}}")
        .when()
        .post("/api/v1/jobs/" + jobId + "/builds")
        .then()
        .statusCode(400)
        .contentType(containsString("problem+json"))
        .body("detail", containsString("RETRIES"))
        .body("detail", containsString("number"));
  }

  @Test
  void choiceParamOutsideDeclaredChoices_returns400() {
    long jobId = seedJob();

    given()
        .contentType("application/json")
        .body("{\"parameters\":{\"MODE\":\"staging\"}}")
        .when()
        .post("/api/v1/jobs/" + jobId + "/builds")
        .then()
        .statusCode(400)
        .contentType(containsString("problem+json"))
        .body("detail", containsString("MODE"))
        .body("detail", containsString("staging"));
  }

  @Test
  void structuredValueForScalarParam_returns400() {
    long jobId = seedJob();

    // An object for a boolean param: the bake would String.valueOf it into "{...}" noise for
    // string params or fail opaquely for boolean — reject loudly at the boundary instead.
    given()
        .contentType("application/json")
        .body("{\"parameters\":{\"VERBOSE\":{\"nested\":true}}}")
        .when()
        .post("/api/v1/jobs/" + jobId + "/builds")
        .then()
        .statusCode(400)
        .contentType(containsString("problem+json"))
        .body("detail", containsString("VERBOSE"));
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private long seedJob() {
    JobRow r = new JobRow();
    r.fullName = "org/param-coercion-" + System.nanoTime();
    r.enabled = true;
    r.pipelineScript = WITH_PARAMS_YAML;
    r.configJson = "{}";
    return stores.jobs().insert(r);
  }

  private static long triggerExpecting201(long jobId, String body) {
    return ((Number)
            given()
                .contentType("application/json")
                .body(body)
                .when()
                .post("/api/v1/jobs/" + jobId + "/builds")
                .then()
                .statusCode(201)
                .body("status", equalTo("QUEUED"))
                .extract()
                .path("buildId"))
        .longValue();
  }
}

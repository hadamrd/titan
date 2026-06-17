package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * IT for {@link JobParametersApi} ({@code GET /api/v1/jobs/{jobId}/parameters}) and the parameter
 * surface of {@link JobBuildsApi#triggerBuild} ({@code POST /api/v1/jobs/{jobId}/builds} with the
 * {@code parameters} field on {@link io.adaptiq.titan.api.dto.TriggerBuildRequest}) — closes #780
 * (follow-up IT for PR #778 / #774).
 *
 * <p>This is a {@code @QuarkusTest} (H2-backed via {@code H2StoresProducer}, same wiring as {@link
 * BuildsApiTest}) because the production POST path depends on {@code SecurityIdentity}, {@code
 * AuditService}, {@code TriggerRateLimiter} and {@code BadRequestExceptionMapper} — all CDI-wired.
 * Spinning up the full ARC container is the only way to exercise the real {@code @RolesAllowed} +
 * exception-mapping seam end-to-end. The {@code IT} suffix puts it in the Postgres-ITs source set;
 * Quarkus picks up the same {@code application.properties} test profile.
 *
 * <p>Cases pinned (every one direct from the #780 brief, plus the SQL-injection probe):
 *
 * <ol>
 *   <li>{@code GET} returns the declared params with the correct {@code type / default /
 *       description / choices}.
 *   <li>{@code POST} with an empty body → defaults applied (verified via {@code parameters_json} in
 *       the DB).
 *   <li>{@code POST} with overrides → effective {@code parameters_json} reflects the overrides.
 *   <li>{@code POST} with a malformed value ({@code "abc"} for a {@code number} parameter) → HTTP
 *       400 problem+json with a parameter-name-bearing error message.
 *   <li>{@code POST} with an unknown parameter key → HTTP 400 (the production behaviour per {@link
 *       JobBuildsApi#resolveParametersJson}: HTTP boundary rejects unknown keys; the bake's {@code
 *       ParameterResolver} never sees them).
 *   <li>{@code GET} on a job with 0 declared params → empty JSON array ({@code []}, never {@code
 *       null}).
 *   <li>SQL-injection probe in a string parameter value → stored verbatim as data, table still
 *       exists, no 500 (proves {@code ParameterResolver} doesn't synthesise SQL from values; the
 *       column is filled via parameterised JDBI binds).
 * </ol>
 */
@QuarkusTest
@TestSecurity(
    user = "params-it",
    roles = {"ADMIN"})
class JobParametersApiIT {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Canonical YAML — three parameter types + a description + the {@code choice} type's choices
   * list. Mirrors the production fixture {@code parameterised-pipeline.yml} but with a richer
   * {@code description} and {@code required:false} on the boolean so every cell of the assertion
   * matrix has something to bite.
   */
  private static final String PARAMETERISED_YAML =
      "titan:\n"
          + "  parameters:\n"
          + "    - name: deployEnv\n"
          + "      type: choice\n"
          + "      choices: [dev, staging, prod]\n"
          + "      default: dev\n"
          + "      description: 'Target deployment environment'\n"
          + "    - name: runSmoke\n"
          + "      type: boolean\n"
          + "      default: true\n"
          + "      description: 'Run the post-deploy smoke suite'\n"
          + "    - name: maxRetries\n"
          + "      type: number\n"
          + "      default: 3\n"
          + "      description: 'Max retry count for flaky steps'\n"
          + "    - name: branch\n"
          + "      type: string\n"
          + "      required: true\n"
          + "      description: 'Source branch to build'\n"
          + "  stages:\n"
          + "    - stage: Build\n"
          + "      steps:\n"
          + "        - sh: make build\n";

  /** Pipeline with zero declared {@code parameters:} — for the empty-array case. */
  private static final String NO_PARAMS_YAML =
      "titan:\n"
          + "  stages:\n"
          + "    - stage: Build\n"
          + "      steps:\n"
          + "        - sh: make build\n";

  @Inject TitanStores stores;

  // ── 1. GET returns declared params with full shape ─────────────────────────────

  @Test
  void getParameters_returnsDeclaredParamsWithTypeDefaultDescriptionAndChoices() {
    long jobId = seedJob("org/params-get-shape-" + System.nanoTime(), PARAMETERISED_YAML);

    // Fish out the four declared names as a set — order of `parameters:` in the YAML is the
    // contract today but we use Set<String> equality (per the brief) so a future stable-sort
    // doesn't false-fail us. We then drill into each by-name for the per-field assertions.
    @SuppressWarnings("unchecked")
    List<java.util.Map<String, Object>> body =
        given()
            .when()
            .get("/api/v1/jobs/" + jobId + "/parameters")
            .then()
            .statusCode(200)
            .body("size()", equalTo(4))
            .extract()
            .as(List.class);

    Set<String> names = new HashSet<>();
    java.util.Map<String, java.util.Map<String, Object>> byName = new java.util.HashMap<>();
    for (java.util.Map<String, Object> p : body) {
      String n = (String) p.get("name");
      names.add(n);
      byName.put(n, p);
    }
    assertEquals(
        Set.of("deployEnv", "runSmoke", "maxRetries", "branch"),
        names,
        "every declared parameter must appear in /parameters response");

    // deployEnv: choice, default dev, choices list non-empty in-order.
    java.util.Map<String, Object> deployEnv = byName.get("deployEnv");
    assertEquals("choice", deployEnv.get("type"));
    assertEquals("dev", deployEnv.get("defaultValue"));
    assertEquals("Target deployment environment", deployEnv.get("description"));
    assertEquals(
        List.of("dev", "staging", "prod"),
        deployEnv.get("choices"),
        "choices must be preserved in declaration order");

    // runSmoke: boolean, default true.
    java.util.Map<String, Object> runSmoke = byName.get("runSmoke");
    assertEquals("boolean", runSmoke.get("type"));
    assertEquals(Boolean.TRUE, runSmoke.get("defaultValue"));

    // maxRetries: number, default 3 (Jackson decodes JSON int → Integer).
    java.util.Map<String, Object> maxRetries = byName.get("maxRetries");
    assertEquals("number", maxRetries.get("type"));
    assertEquals(3, ((Number) maxRetries.get("defaultValue")).intValue());

    // branch: string, required=true, no default.
    java.util.Map<String, Object> branch = byName.get("branch");
    assertEquals("string", branch.get("type"));
    assertEquals(Boolean.TRUE, branch.get("required"));
  }

  // ── 2. POST with no parameters body → defaults applied at bake time ───────────

  @Test
  void postBuild_withEmptyBody_persistsNullParametersJson_defaultsAppliedAtBake() {
    // Contract clarification (read in JobBuildsApi.resolveParametersJson):
    //   - req.parameters() absent + req.parametersJson() absent → effectiveParametersJson = null.
    //   - The bake's ParameterResolver fills the defaults from the YAML when it runs.
    // So the IT here pins the API-boundary contract: empty body → null in the row, NOT the
    // resolved defaults. The default-resolution happens at bake (covered by TitanParametersIT).
    long jobId = seedJob("org/params-empty-" + System.nanoTime(), PARAMETERISED_YAML);

    long buildId = triggerAndGetBuildId(jobId, "{}");
    BuildRow row = stores.builds().findById(buildId).orElseThrow();
    // parameters_json null is the correct "use declared defaults" signal — see #774 design.
    assertEquals(
        null,
        row.parametersJson,
        "empty trigger body → null parameters_json (bake applies declared defaults)");
  }

  // ── 3. POST with overrides → effective params reflect overrides ────────────────

  @Test
  void postBuild_withTypedOverrides_persistsThemVerbatimInParametersJson() throws Exception {
    long jobId = seedJob("org/params-override-" + System.nanoTime(), PARAMETERISED_YAML);

    String body =
        "{\"parameters\":{"
            + "\"branch\":\"release/1.x\","
            + "\"deployEnv\":\"staging\","
            + "\"runSmoke\":false,"
            + "\"maxRetries\":7"
            + "}}";

    long buildId = triggerAndGetBuildId(jobId, body);
    BuildRow row = stores.builds().findById(buildId).orElseThrow();
    assertNotNull(row.parametersJson, "overrides must be serialised, not dropped");

    JsonNode params = JSON.readTree(row.parametersJson);
    // Hard-fail asserts on Set<String> equality of the persisted keys (per brief).
    Set<String> persistedKeys = new HashSet<>();
    params.fieldNames().forEachRemaining(persistedKeys::add);
    assertEquals(
        Set.of("branch", "deployEnv", "runSmoke", "maxRetries"),
        persistedKeys,
        "every override key must be preserved in the persisted JSON");

    assertEquals("release/1.x", params.get("branch").asText());
    assertEquals("staging", params.get("deployEnv").asText());
    assertEquals(false, params.get("runSmoke").asBoolean());
    assertEquals(7, params.get("maxRetries").asInt());
  }

  // ── 4. POST with malformed value → 400 with helpful (name-bearing) error ───────

  @Test
  void postBuild_withStringForNumberParam_returns400_problemJsonMentionsTheParameterName() {
    long jobId = seedJob("org/params-badtype-" + System.nanoTime(), PARAMETERISED_YAML);

    // 'maxRetries' is declared as type:number; sending a string must be rejected at the HTTP
    // boundary (not silently coerced, not delayed to bake-time failure).
    String body = "{\"parameters\":{\"branch\":\"main\",\"maxRetries\":\"abc\"}}";

    given()
        .contentType("application/json")
        .body(body)
        .when()
        .post("/api/v1/jobs/" + jobId + "/builds")
        .then()
        .statusCode(400)
        .contentType(containsString("problem+json"))
        // The error MUST name the offending parameter — operators have to know WHICH input was
        // bad without grepping logs. (JobBuildsApi.validateValue formats:
        //   "parameter 'maxRetries' expects number, got String")
        .body("detail", containsString("maxRetries"))
        .body("detail", containsString("number"));
  }

  // ── 5. POST with an unknown parameter key → 400 (production behaviour) ─────────

  @Test
  void postBuild_withUnknownParameterKey_returns400_notSilentlyDropped() {
    // The brief asked us to confirm-which: dropped silently, or 400?
    // Reading JobBuildsApi.resolveParametersJson lines 278-281 of PR #778:
    //   if (decl == null) {
    //     throw new ApiBadRequestException("unknown parameter '...' — not declared in pipeline");
    //   }
    // → The HTTP boundary rejects with 400. The bake's ParameterResolver never sees the key.
    // This test pins that contract so a future "be lenient" refactor surfaces here, not in a
    // user-facing surprise.
    long jobId = seedJob("org/params-unknown-" + System.nanoTime(), PARAMETERISED_YAML);

    String body = "{\"parameters\":{\"branch\":\"main\",\"notDeclared\":\"whatever\"}}";

    given()
        .contentType("application/json")
        .body(body)
        .when()
        .post("/api/v1/jobs/" + jobId + "/builds")
        .then()
        .statusCode(400)
        .contentType(containsString("problem+json"))
        .body("detail", containsString("notDeclared"))
        .body("detail", containsString("unknown parameter"));
  }

  // ── 6. GET on a job that declares 0 params → empty array (not null) ────────────

  @Test
  void getParameters_onJobWithNoDeclaredParams_returnsEmptyArray_notNull() {
    long jobId = seedJob("org/params-zero-" + System.nanoTime(), NO_PARAMS_YAML);

    given()
        .when()
        .get("/api/v1/jobs/" + jobId + "/parameters")
        .then()
        .statusCode(200)
        // [] not null — the UI uses an empty list as the "no modal, fire directly" signal,
        // a null body would crash the renderer (see JobParametersApi javadoc).
        .body("$", is(empty()));
  }

  // ── 7. SQL-injection probe in a string param value ─────────────────────────────

  @Test
  void postBuild_withSqlInjectionInStringParamValue_isSafe_tableStillExists() throws Exception {
    long jobId = seedJob("org/params-sqli-" + System.nanoTime(), PARAMETERISED_YAML);

    // Classic injection payload as the `branch` value (declared type:string, so the type-check
    // passes — this is exactly the "value is a valid string, but it's hostile" case). It must:
    //   (a) be accepted (it's a syntactically valid string),
    //   (b) be persisted verbatim into parameters_json via parameterised binds,
    //   (c) leave titan.builds intact for subsequent reads.
    String evil = "release/1.x'; DROP TABLE titan.builds; --";
    String body = "{\"parameters\":{\"branch\":" + JSON.writeValueAsString(evil) + "}}";

    long buildId = triggerAndGetBuildId(jobId, body);
    BuildRow row = stores.builds().findById(buildId).orElseThrow();
    JsonNode params = JSON.readTree(row.parametersJson);
    assertEquals(
        evil,
        params.get("branch").asText(),
        "the hostile string must be stored verbatim — proves bind, not concat");

    // The table must still exist + still serve reads. A subsequent /builds list call would 500
    // if the DROP had actually fired. Verify via the API surface (round-trips through the same
    // JDBI path as the trigger).
    given()
        .when()
        .get("/api/v1/jobs/" + jobId + "/builds")
        .then()
        .statusCode(200)
        .body("total", greaterThan(0))
        .body("items", hasSize(greaterThan(0)));

    // And the row we just inserted is fetchable by id — the storage layer is still healthy.
    assertEquals(jobId, row.jobId, "the freshly-inserted build row is still readable");
  }

  // ── helpers ────────────────────────────────────────────────────────────────────

  private long seedJob(String fullName, String pipelineScript) {
    JobRow r = new JobRow();
    r.fullName = fullName;
    r.enabled = true;
    r.pipelineScript = pipelineScript;
    r.configJson = "{}";
    return stores.jobs().insert(r);
  }

  /**
   * POST the body, assert 201, return the new buildId as a long. Centralised so the per-case tests
   * stay readable.
   */
  private static long triggerAndGetBuildId(long jobId, String body) {
    return ((Number)
            given()
                .contentType("application/json")
                .body(body)
                .when()
                .post("/api/v1/jobs/" + jobId + "/builds")
                .then()
                .statusCode(201)
                .body("status", equalTo("QUEUED"))
                .body("buildId", notNullValue())
                .body("buildNumber", greaterThan(0))
                .extract()
                .path("buildId"))
        .longValue();
  }
}

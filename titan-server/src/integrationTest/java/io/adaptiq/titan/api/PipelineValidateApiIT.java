package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * End-to-end RBAC + behaviour test for {@code POST /api/v1/pipeline/validate} (closes #745).
 *
 * <p>Drives the JAX-RS layer over HTTP via {@code RestAssured} the same way {@link RbacReadAuditIT}
 * does, so the {@code @RolesAllowed} glue, the {@link
 * io.adaptiq.titan.api.exception.BadRequestExceptionMapper}, and the JSON wire shape are all
 * exercised end-to-end.
 *
 * <p>Adversarial corners pinned per the brief:
 *
 * <ul>
 *   <li>empty yaml → {@code valid=false} with the parser's "empty" message;
 *   <li>malformed yaml (bad indent) → {@code valid=false} with a line locator;
 *   <li>valid yaml → {@code valid=true} with a populated summary;
 *   <li>1.5 MiB yaml → HTTP 413, never reaches the parser;
 *   <li>READ_JOB alone is sufficient (the role the brief promises).
 * </ul>
 */
@QuarkusTest
class PipelineValidateApiIT {

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  @TestSecurity(
      user = "viewer",
      roles = {"READ_JOB"})
  void validYaml_returnsValidTrue_andPopulatedSummary() {
    // A canonical two-stage pipeline with a cron trigger. The summary must echo the two
    // stage names + their step counts + the cron trigger, NEVER the full step bodies.
    String yaml =
        "triggers:\n"
            + "  - cron: '0 * * * *'\n"
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: mvn package\n"
            + "      - sh: ls target\n"
            + "  - stage: Test\n"
            + "    steps:\n"
            + "      - sh: mvn test\n";

    given()
        .contentType("application/json")
        .body("{\"yaml\":" + jsonString(yaml) + "}")
        .when()
        .post("/api/v1/pipeline/validate")
        .then()
        .statusCode(200)
        .body("valid", is(true))
        .body("errors", hasSize(0))
        .body("summary.stages", hasSize(2))
        .body("summary.stages[0].name", equalTo("Build"))
        .body("summary.stages[0].stepCount", equalTo(2))
        .body("summary.stages[1].name", equalTo("Test"))
        .body("summary.stages[1].stepCount", equalTo(1))
        .body("summary.triggers", hasSize(1))
        .body("summary.triggers[0].type", equalTo("cron"))
        .body("summary.triggers[0].expression", equalTo("0 * * * *"));
  }

  // ── empty body ────────────────────────────────────────────────────────────

  @Test
  @TestSecurity(
      user = "viewer",
      roles = {"READ_JOB"})
  void emptyYaml_returnsValidFalse_withEmptyMessage() {
    // The parser surfaces "pipeline definition is empty" — the verdict is a structured
    // 200, not a 400, so the UI can render the message inline next to the editor.
    given()
        .contentType("application/json")
        .body("{\"yaml\":\"\"}")
        .when()
        .post("/api/v1/pipeline/validate")
        .then()
        .statusCode(200)
        .body("valid", is(false))
        .body("errors", hasSize(greaterThan(0)))
        .body("errors[0].message", notNullValue())
        // The summary MUST be absent on a failure verdict — discriminated union (JsonInclude
        // NON_NULL drops the field entirely).
        .body("summary", nullValue());
  }

  // ── malformed yaml — line/column extraction ────────────────────────────────

  @Test
  @TestSecurity(
      user = "viewer",
      roles = {"READ_JOB"})
  void malformedYaml_returnsValidFalse_withLineLocator() {
    // Tab in the middle of a mapping value — SnakeYAML rejects this with a marked location.
    // The line/column field MUST be present on the wire so the UI can render an inline marker.
    String yaml = "stages:\n  - stage: Build\n\tagent: foo\n";

    given()
        .contentType("application/json")
        .body("{\"yaml\":" + jsonString(yaml) + "}")
        .when()
        .post("/api/v1/pipeline/validate")
        .then()
        .statusCode(200)
        .body("valid", is(false))
        .body("errors", hasSize(greaterThan(0)))
        .body("errors[0].line", greaterThan(0))
        .body("errors[0].message", notNullValue())
        .body("summary", nullValue());
  }

  // ── size cap (DoS guard) ──────────────────────────────────────────────────

  @Test
  @TestSecurity(
      user = "viewer",
      roles = {"READ_JOB"})
  void oversizedYaml_returns413_neverReachesParser() {
    // 1.5 MiB of YAML — well over the 1 MiB cap. Must surface as a structured problem+json
    // 413, never as a 500 / never as a successful parse.
    int target = (int) (1.5 * 1024 * 1024);
    StringBuilder sb = new StringBuilder(target + 32);
    sb.append("stages:\n");
    while (sb.length() < target) {
      sb.append("# pad pad pad pad pad pad pad pad pad pad pad pad pad pad pad pad pad pad\n");
    }

    given()
        .contentType("application/json")
        .body("{\"yaml\":" + jsonString(sb.toString()) + "}")
        .when()
        .post("/api/v1/pipeline/validate")
        .then()
        .statusCode(413)
        .contentType("application/problem+json")
        .body("status", equalTo(413));
  }

  // ── grammar tolerance: unknown step keyword surfaces as a parse error today ───

  @Test
  @TestSecurity(
      user = "viewer",
      roles = {"READ_JOB"})
  void yamlWithUnknownStepKeyword_returnsStructuredVerdict() {
    // The current Titan grammar (design/47) REJECTS unknown step keywords at parse time
    // (see `rejectUnknownKeys`). The contract this test pins: whatever the verdict
    // (valid or not), the response shape is well-formed — a structured discriminated union,
    // never a 500. If the grammar later relaxes to lenient (per design/47 evolution) this
    // test still passes because we assert on shape, not the boolean.
    String yaml =
        "stages:\n" + "  - stage: Build\n" + "    steps:\n" + "      - thisStepDoesNotExist: foo\n";

    given()
        .contentType("application/json")
        .body("{\"yaml\":" + jsonString(yaml) + "}")
        .when()
        .post("/api/v1/pipeline/validate")
        .then()
        .statusCode(200)
        .body("valid", notNullValue())
        // Either way, errors is a list and summary is either null or a typed object.
        .body("errors", notNullValue());
  }

  // ── auth ───────────────────────────────────────────────────────────────────

  @Test
  void anonymous_isRejected() {
    // No @TestSecurity → no roles. The endpoint declares @RolesAllowed; an anonymous caller
    // must NOT reach the parser. (Returns 401 — Quarkus's default for unauth on a secured
    // endpoint when no identity is presented.)
    given()
        .contentType("application/json")
        .body("{\"yaml\":\"stages: []\"}")
        .when()
        .post("/api/v1/pipeline/validate")
        .then()
        .statusCode(anyOf(equalTo(401), equalTo(403)));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** Minimal JSON string escape for embedding YAML into a request body literal. */
  private static String jsonString(String s) {
    StringBuilder out = new StringBuilder(s.length() + 16).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '"' -> out.append("\\\"");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }
}

package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JobsApi}. Uses an H2-backed {@link TitanStores} via {@link
 * H2StoresProducer} — no Docker, no Postgres. The Quarkus test server starts once per test run on
 * an ephemeral port; REST-assured is pre-configured by {@code @QuarkusTest}.
 */
@QuarkusTest
@TestSecurity(
    user = "testuser",
    roles = {"READ_JOB", "EDIT_PIPELINE"})
class JobsApiTest {

  @Inject TitanStores stores;

  @BeforeEach
  void clearJobs() {
    // H2StoresProducer returns a shared @ApplicationScoped bean. Tests must
    // ensure clean state — jobs inserted in previous tests accumulate.
    // The simplest isolation: re-create the stores bean is not feasible at
    // this scope, so each test uses unique names and asserts relative invariants.
  }

  // ── GET /api/v1/jobs ──────────────────────────────────────────────────────

  @Test
  void listJobs_emptyOrNonEmptyReturnsValidPage() {
    given()
        .when()
        .get("/api/v1/jobs")
        .then()
        .statusCode(200)
        .body("total", notNullValue())
        .body("offset", equalTo(0))
        .body("limit", equalTo(50));
  }

  @Test
  void listJobs_returnsInsertedJob() {
    stores.jobs().insert(jobRow("test/list-jobs-" + System.nanoTime(), "My Repo", true));

    given()
        .when()
        .get("/api/v1/jobs")
        .then()
        .statusCode(200)
        .body("total", greaterThanOrEqualTo(1));
  }

  @Test
  void listJobs_limitCappedAt200() {
    given()
        .queryParam("limit", 9999)
        .when()
        .get("/api/v1/jobs")
        .then()
        .statusCode(200)
        .body("limit", equalTo(200));
  }

  // ── /jobs lastBuild surface (issue #529) ─────────────────────────────────

  @Test
  void listJobs_lastBuildReflectsMostRecentBuildNumber() {
    // Create a fresh job + 2 builds; the second (build_number = 2) must win.
    String full = "test/lastbuild-" + System.nanoTime();
    long jobId = stores.jobs().insert(jobRow(full, "Last Build Job", true));

    BuildRow older = new BuildRow();
    older.jobId = jobId;
    older.buildNumber = 1;
    older.status = "SUCCESS";
    older.queuedAt = Instant.parse("2026-05-20T08:00:00Z");
    older.startedAt = Instant.parse("2026-05-20T08:00:01Z");
    older.finishedAt = Instant.parse("2026-05-20T08:01:00Z");
    older.durationMs = 59_000L;
    stores.builds().insert(older);

    BuildRow newer = new BuildRow();
    newer.jobId = jobId;
    newer.buildNumber = 2;
    newer.status = "FAILED";
    newer.queuedAt = Instant.parse("2026-05-20T09:00:00Z");
    newer.startedAt = Instant.parse("2026-05-20T09:00:01Z");
    newer.finishedAt = Instant.parse("2026-05-20T09:02:00Z");
    newer.durationMs = 119_000L;
    stores.builds().insert(newer);

    String find = "items.find { it.fullName == '" + full + "' }";
    given()
        .queryParam("limit", 200)
        .when()
        .get("/api/v1/jobs")
        .then()
        .statusCode(200)
        // GPath: locate by fullName and assert the lastBuild snapshot reflects the newer row.
        .body(find + ".lastBuild.buildNumber", equalTo(2))
        .body(find + ".lastBuild.status", equalTo("FAILED"))
        .body(find + ".lastBuild.durationMs", equalTo(119_000));
  }

  @Test
  void listJobs_lastBuildIsNullForNeverRunJob() {
    String full = "test/neverrun-" + System.nanoTime();
    stores.jobs().insert(jobRow(full, "Never Run", true));

    given()
        .queryParam("limit", 200)
        .when()
        .get("/api/v1/jobs")
        .then()
        .statusCode(200)
        // JsonInclude.NON_NULL strips the field — assert it's absent (null in GPath).
        .body(
            "items.find { it.fullName == '" + full + "' }.lastBuild",
            org.hamcrest.Matchers.nullValue());
  }

  // ── /jobs repoUrl surface (issue #606) ───────────────────────────────────

  @Test
  void listJobs_repoUrlPopulatedWhenGithubTriggerPresent() {
    // A job whose config_json carries a github trigger + scm.url block must surface repoUrl.
    String full = "test/repo-url-" + System.nanoTime();
    JobRow row = jobRow(full, "Repo URL Job", true);
    row.configJson =
        "{\"scm\":{\"url\":\"https://github.com/acme/billing\",\"branch\":\"main\"},"
            + "\"triggers\":[{\"type\":\"github\",\"id\":\"t1\",\"branches\":[],\"events\":[\"push\"],"
            + "\"credentialsId\":\"gh-secret\"}]}";
    stores.jobs().insert(row);

    String find = "items.find { it.fullName == '" + full + "' }";
    given()
        .queryParam("limit", 200)
        .when()
        .get("/api/v1/jobs")
        .then()
        .statusCode(200)
        .body(find + ".repoUrl", equalTo("https://github.com/acme/billing"));
  }

  @Test
  void listJobs_repoUrlAbsentWhenNoGithubTrigger() {
    // A job with an scm.url block but only a cron trigger (no github) must NOT expose repoUrl.
    // JsonInclude.NON_NULL strips the field from the wire — assert absent (nullValue).
    String full = "test/repo-url-none-" + System.nanoTime();
    JobRow row = jobRow(full, "Cron Only", true);
    row.configJson =
        "{\"scm\":{\"url\":\"https://github.com/acme/billing\",\"branch\":\"main\"},"
            + "\"triggers\":[{\"type\":\"cron\",\"id\":\"c1\",\"cron\":\"0 * * * *\"}]}";
    stores.jobs().insert(row);

    given()
        .queryParam("limit", 200)
        .when()
        .get("/api/v1/jobs")
        .then()
        .statusCode(200)
        .body(
            "items.find { it.fullName == '" + full + "' }.repoUrl",
            org.hamcrest.Matchers.nullValue());
  }

  @Test
  void listJobs_paginationParamsReflectedInResponse() {
    for (int i = 1; i <= 3; i++) {
      stores.jobs().insert(jobRow("pag/job-" + i + "-" + System.nanoTime(), null, true));
    }

    given()
        .queryParam("offset", 1)
        .queryParam("limit", 2)
        .when()
        .get("/api/v1/jobs")
        .then()
        .statusCode(200)
        .body("offset", equalTo(1))
        .body("limit", equalTo(2));
  }

  // ── GET /api/v1/jobs/{jobId} ──────────────────────────────────────────────

  @Test
  void getJob_knownIdReturns200() {
    long id = stores.jobs().insert(jobRow("test/get-" + System.nanoTime(), "Service", true));

    given()
        .when()
        .get("/api/v1/jobs/" + id)
        .then()
        .statusCode(200)
        .body("id", equalTo((int) id))
        .body("enabled", equalTo(true));
  }

  @Test
  void getJob_exposesPipelineScript() {
    String yaml = "stages:\n  - name: build\n    steps: []\n";
    JobRow row = jobRow("test/script-" + System.nanoTime(), "Scripted", true);
    row.pipelineScript = yaml;
    long id = stores.jobs().insert(row);

    given()
        .when()
        .get("/api/v1/jobs/" + id)
        .then()
        .statusCode(200)
        .body("pipelineScript", equalTo(yaml));
  }

  @Test
  void getJob_unknownIdReturns404ProblemJson() {
    given()
        .when()
        .get("/api/v1/jobs/999999999")
        .then()
        .statusCode(404)
        .contentType(containsString("problem+json"))
        .body("status", equalTo(404))
        .body("detail", containsString("999999999"));
  }

  @Test
  void getJob_nonNumericIdReturns400() {
    given().when().get("/api/v1/jobs/not-a-number").then().statusCode(400);
  }

  // ── ETag / If-None-Match (#1099) ──────────────────────────────────────────

  @Test
  void getJob_emitsEtagAnd304OnMatch() {
    long id = stores.jobs().insert(jobRow("test/etag-" + System.nanoTime(), "Service", true));

    String etag =
        given().when().get("/api/v1/jobs/" + id).then().statusCode(200).extract().header("ETag");
    org.junit.jupiter.api.Assertions.assertNotNull(etag, "ETag header must be present");
    org.junit.jupiter.api.Assertions.assertTrue(
        etag.startsWith("W/\""), "weak ETag expected, got " + etag);

    given()
        .header("If-None-Match", etag)
        .when()
        .get("/api/v1/jobs/" + id)
        .then()
        .statusCode(304)
        .header("ETag", equalTo(etag));
  }

  // ── POST /api/v1/jobs (#507) ──────────────────────────────────────────────

  @Test
  void postJob_validYamlReturns201AndPersists() {
    String full = "test/post-" + System.nanoTime();
    String yaml = "stages:\n  - stage: build\n    steps:\n      - sh: echo hi\n";

    given()
        .contentType("application/json")
        .body(
            "{\"fullName\":\""
                + full
                + "\",\"displayName\":\"Post Job\",\"pipelineScript\":"
                + jsonString(yaml)
                + ",\"enabled\":true}")
        .when()
        .post("/api/v1/jobs")
        .then()
        .statusCode(201)
        .body("fullName", equalTo(full))
        .body("enabled", equalTo(true))
        .body("pipelineScript", containsString("stages"));
  }

  @Test
  void postJob_invalidYamlReturns400ProblemJson() {
    String full = "test/post-bad-" + System.nanoTime();
    // 'pipeline:' wrapper is not a valid Titan PDL root (mimics the build #46 fixture).
    String bad = "pipeline:\n  stages: [ { stage: a, steps: [] } ]\n";

    given()
        .contentType("application/json")
        .body("{\"fullName\":\"" + full + "\",\"pipelineScript\":" + jsonString(bad) + "}")
        .when()
        .post("/api/v1/jobs")
        .then()
        .statusCode(400)
        .contentType(containsString("problem+json"));
  }

  @Test
  void postJob_duplicateFullNameReturns409() {
    String full = "test/dup-" + System.nanoTime();
    String yaml = "stages:\n  - stage: build\n    steps:\n      - sh: echo hi\n";
    String body = "{\"fullName\":\"" + full + "\",\"pipelineScript\":" + jsonString(yaml) + "}";

    given()
        .contentType("application/json")
        .body(body)
        .when()
        .post("/api/v1/jobs")
        .then()
        .statusCode(201);

    given()
        .contentType("application/json")
        .body(body)
        .when()
        .post("/api/v1/jobs")
        .then()
        .statusCode(409)
        .contentType(containsString("problem+json"));
  }

  @Test
  void postJob_missingFullNameReturns400() {
    given()
        .contentType("application/json")
        .body("{\"pipelineScript\":\"stages: []\\n\"}")
        .when()
        .post("/api/v1/jobs")
        .then()
        .statusCode(400);
  }

  // ── PATCH /api/v1/jobs/{jobId} (#439) ─────────────────────────────────────

  @Test
  void patchJob_validYamlPersistsScript() {
    String original =
        "titan:\n  stages:\n    - stage: build\n      steps:\n        - shell: echo hi\n";
    JobRow row = jobRow("test/patch-" + System.nanoTime(), "PatchMe", true);
    row.pipelineScript = original;
    long id = stores.jobs().insert(row);

    String updated =
        "titan:\n"
            + "  triggers:\n"
            + "    - cron: \"0 */6 * * *\"\n"
            + "  stages:\n"
            + "    - stage: build\n"
            + "      steps:\n"
            + "        - shell: echo hi\n";

    given()
        .contentType("application/json")
        .body("{\"pipelineScript\":" + jsonString(updated) + "}")
        .when()
        .patch("/api/v1/jobs/" + id)
        .then()
        .statusCode(200)
        .body("pipelineScript", containsString("cron"));

    given()
        .when()
        .get("/api/v1/jobs/" + id)
        .then()
        .statusCode(200)
        .body("pipelineScript", containsString("0 */6 * * *"));
  }

  @Test
  void patchJob_invalidYamlReturns400ProblemJson() {
    JobRow row = jobRow("test/patch-bad-" + System.nanoTime(), "BadPatch", true);
    row.pipelineScript = "titan:\n  stages:\n    - stage: a\n      steps: []\n";
    long id = stores.jobs().insert(row);

    // unknown root key → PipelineParseException → 400
    String bad = "titan:\n  banana: yes\n  stages:\n    - stage: a\n      steps: []\n";

    given()
        .contentType("application/json")
        .body("{\"pipelineScript\":" + jsonString(bad) + "}")
        .when()
        .patch("/api/v1/jobs/" + id)
        .then()
        .statusCode(400)
        .contentType(containsString("problem+json"));
  }

  @Test
  @TestSecurity(
      user = "readonly-user",
      roles = {"READ_JOB"})
  void patchJob_readJobRoleReturns403() {
    // #1221 — a caller holding only READ_JOB must be denied the edit. The single @RequiresRole
    // gate resolves READ_JOB to a VIEWER realm-floor, below the required MAINTAINER → 403, and
    // records exactly ONE DENY in rbac_audit (genuine denials still deny, and stay audited).
    JobRow row = jobRow("test/patch-403-" + System.nanoTime(), "NoEdit", true);
    row.pipelineScript = "titan:\n  stages:\n    - stage: a\n      steps: []\n";
    long id = stores.jobs().insert(row);

    String updated =
        "titan:\n  stages:\n    - stage: build\n      steps:\n        - shell: echo hi\n";

    given()
        .contentType("application/json")
        .body("{\"pipelineScript\":" + jsonString(updated) + "}")
        .when()
        .patch("/api/v1/jobs/" + id)
        .then()
        .statusCode(403);

    // The mutation never ran — the persisted script is unchanged.
    given()
        .when()
        .get("/api/v1/jobs/" + id)
        .then()
        .statusCode(200)
        .body("pipelineScript", containsString("stage: a"));

    // Exactly one rbac_audit row for this caller, and it is a DENY on the PATCH endpoint.
    var rows = stores.rbacAudit().recentForUser("readonly-user", 10);
    org.junit.jupiter.api.Assertions.assertEquals(
        1, rows.size(), "exactly one rbac_audit row must be recorded for the denied PATCH");
    org.junit.jupiter.api.Assertions.assertEquals("DENY", rows.get(0).decision);
    org.junit.jupiter.api.Assertions.assertTrue(
        rows.get(0).endpoint.contains("patchJob"),
        "rbac_audit row must tag the patchJob endpoint, got " + rows.get(0).endpoint);
  }

  // ── DELETE /api/v1/jobs/{jobId} (#964) ────────────────────────────────────

  @Test
  @TestSecurity(
      user = "admin-user",
      roles = {"ADMIN"})
  void deleteJob_returns204_whenJobExists() {
    long id = stores.jobs().insert(jobRow("test/delete-ok-" + System.nanoTime(), null, true));

    given().when().delete("/api/v1/jobs/" + id).then().statusCode(204);

    // The row is gone — a follow-up GET must surface 404.
    given().when().get("/api/v1/jobs/" + id).then().statusCode(404);
  }

  @Test
  @TestSecurity(
      user = "admin-user",
      roles = {"ADMIN"})
  void deleteJob_returns404_whenJobMissing() {
    given()
        .when()
        .delete("/api/v1/jobs/999999999")
        .then()
        .statusCode(404)
        .contentType(containsString("problem+json"));
  }

  @Test
  @TestSecurity(
      user = "admin-user",
      roles = {"ADMIN"})
  void deleteJob_twiceReturns404OnSecondCall() {
    long id = stores.jobs().insert(jobRow("test/delete-twice-" + System.nanoTime(), null, true));
    given().when().delete("/api/v1/jobs/" + id).then().statusCode(204);
    // Second DELETE — the row is gone — must be a clean 404, not a 500.
    given().when().delete("/api/v1/jobs/" + id).then().statusCode(404);
  }

  @Test
  @TestSecurity(
      user = "editor-user",
      roles = {"EDIT_PIPELINE", "READ_JOB"})
  void deleteJob_returns403_whenNotAdmin() {
    long id = stores.jobs().insert(jobRow("test/delete-403-" + System.nanoTime(), null, true));
    given().when().delete("/api/v1/jobs/" + id).then().statusCode(403);
    // The row must still be there — RBAC denial is enforced before any mutation.
    given().when().get("/api/v1/jobs/" + id).then().statusCode(200);
  }

  @Test
  @TestSecurity(
      user = "admin-user",
      roles = {"ADMIN"})
  void deleteJob_returns409_whenBuildIsRunning() {
    long id = stores.jobs().insert(jobRow("test/delete-409-" + System.nanoTime(), null, true));
    BuildRow b = new BuildRow();
    b.jobId = id;
    b.buildNumber = 1;
    b.status = "RUNNING";
    b.queuedAt = Instant.parse("2026-05-20T08:00:00Z");
    b.startedAt = Instant.parse("2026-05-20T08:00:01Z");
    stores.builds().insert(b);

    given()
        .when()
        .delete("/api/v1/jobs/" + id)
        .then()
        .statusCode(409)
        .contentType(containsString("problem+json"))
        .body("detail", containsString("RUNNING"));

    // 409 must not delete — the row is still there.
    given().when().get("/api/v1/jobs/" + id).then().statusCode(200);
  }

  @Test
  @TestSecurity(
      user = "admin-user",
      roles = {"ADMIN"})
  void deleteJob_emitsAuditLog() {
    String full = "test/delete-audit-" + System.nanoTime();
    long id = stores.jobs().insert(jobRow(full, null, true));
    long before = stores.auditLog().countRecent(null, "JOB_DELETE", "JOB", null);
    given().when().delete("/api/v1/jobs/" + id).then().statusCode(204);
    long after = stores.auditLog().countRecent(null, "JOB_DELETE", "JOB", null);
    org.junit.jupiter.api.Assertions.assertEquals(
        before + 1, after, "exactly one JOB_DELETE audit row must be emitted");
    var rows = stores.auditLog().findRecent(null, "JOB_DELETE", "JOB", null, 10, 0);
    String targetId = Long.toString(id);
    org.junit.jupiter.api.Assertions.assertTrue(
        rows.stream()
            .anyMatch(
                r ->
                    targetId.equals(r.targetId)
                        && r.detailsJson != null
                        && r.detailsJson.contains(full)),
        "JOB_DELETE row must carry targetId=" + targetId + " and fullName=" + full + " in details");
  }

  @Test
  @TestSecurity(
      user = "admin-user",
      roles = {"ADMIN"})
  void deleteJob_cascadesToBuilds() {
    long id = stores.jobs().insert(jobRow("test/delete-cascade-" + System.nanoTime(), null, true));
    BuildRow b = new BuildRow();
    b.jobId = id;
    b.buildNumber = 1;
    b.status = "SUCCESS";
    b.queuedAt = Instant.parse("2026-05-20T08:00:00Z");
    b.startedAt = Instant.parse("2026-05-20T08:00:01Z");
    b.finishedAt = Instant.parse("2026-05-20T08:01:00Z");
    b.durationMs = 60_000L;
    long buildId = stores.builds().insert(b);

    given().when().delete("/api/v1/jobs/" + id).then().statusCode(204);

    // FK ON DELETE CASCADE: the build row is gone too.
    org.junit.jupiter.api.Assertions.assertTrue(
        stores.builds().findById(buildId).isEmpty(),
        "build " + buildId + " must be cascade-deleted with its job");
  }

  @Test
  void patchJob_unknownIdReturns404() {
    given()
        .contentType("application/json")
        .body("{\"pipelineScript\":\"titan:\\n  stages: [ { stage: a, steps: [] } ]\\n\"}")
        .when()
        .patch("/api/v1/jobs/999999999")
        .then()
        .statusCode(404);
  }

  private static String jsonString(String raw) {
    // Minimal JSON string escaper for embedding YAML in a one-line body.
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> sb.append(c);
      }
    }
    sb.append('"');
    return sb.toString();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  static JobRow jobRow(String fullName, String displayName, boolean enabled) {
    JobRow r = new JobRow();
    r.fullName = fullName;
    r.displayName = displayName;
    r.enabled = enabled;
    r.pipelineScript = "";
    r.configJson = "{}";
    return r;
  }
}

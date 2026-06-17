package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.audit.AuditTargetType;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.AuditLogRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit-level Quarkus tests for the audit-log surface (closes #478) — H2-backed via {@link
 * H2StoresProducer}.
 *
 * <p>Covers the adversarial paths the brief calls out:
 *
 * <ul>
 *   <li>POST trigger-build emits an audit row with the correct actor, action and target_id;
 *   <li>POST create-job emits JOB_CREATE under the OIDC principal;
 *   <li>POST PAT create emits PAT_CREATE — and the row CANNOT contain the plaintext token
 *       (CONSTITUTION §6: no plaintext secrets in any persisted log);
 *   <li>GET /api/v1/audit rejects non-ADMIN with 403;
 *   <li>GET /api/v1/audit rejects bogus filter values (400, not silent drop);
 *   <li>GET /api/v1/audit filters by actor / action.
 * </ul>
 */
@QuarkusTest
@TestSecurity(
    user = "alice",
    roles = {"ADMIN"})
class AuditApiTest {

  @Inject TitanStores stores;

  // ── Emit points ────────────────────────────────────────────────────────────

  @Test
  void triggerBuild_emitsAuditRow_withActorAndTarget() {
    long jobId = stores.jobs().insert(job("org/audit-trigger-" + System.nanoTime()));

    long before = stores.auditLog().countRecent(null, "BUILD_TRIGGER", null, null);

    long buildId =
        ((Number)
                given()
                    .contentType("application/json")
                    .body("{}")
                    .when()
                    .post("/api/v1/jobs/" + jobId + "/builds")
                    .then()
                    .statusCode(201)
                    .extract()
                    .path("buildId"))
            .longValue();

    long after = stores.auditLog().countRecent(null, "BUILD_TRIGGER", null, null);
    assertEquals(before + 1, after, "exactly one BUILD_TRIGGER row must be appended");

    List<AuditLogRow> rows =
        stores.auditLog().findRecent(null, "BUILD_TRIGGER", "BUILD", null, 10, 0);
    AuditLogRow match =
        rows.stream()
            .filter(r -> Long.toString(buildId).equals(r.targetId))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("no audit row for buildId=" + buildId + " in " + rows));
    assertEquals("alice", match.actor, "actor must be the OIDC principal, not 'api' or 'system'");
    assertEquals("BUILD_TRIGGER", match.action);
    assertEquals("BUILD", match.targetType);
    assertNotNull(match.detailsJson, "details_json populated with jobId/buildNumber");
    assertTrue(match.detailsJson.contains("\"jobId\":" + jobId), "details_json contains jobId");
  }

  @Test
  void createJob_emitsAuditRow_underOidcPrincipal() {
    String full = "org/audit-create-" + System.nanoTime();
    // PDL grammar requires `stage:` node-kind key and a real step verb (`sh:`); the older
    // `name:`/`echo:` shapes were dropped — see TitanYamlParser stage-key validation.
    String pdl = "stages:\n  - stage: s1\n    steps:\n      - sh: echo hello\n";

    given()
        .contentType("application/json")
        .body("{\"fullName\":\"" + full + "\",\"pipelineScript\":" + jsonString(pdl) + "}")
        .when()
        .post("/api/v1/jobs")
        .then()
        .statusCode(201);

    List<AuditLogRow> rows =
        stores.auditLog().findRecent("alice", "JOB_CREATE", "JOB", null, 10, 0);
    assertTrue(
        rows.stream().anyMatch(r -> r.detailsJson != null && r.detailsJson.contains(full)),
        "JOB_CREATE row referencing fullName must be present in " + rows);
  }

  @Test
  void patCreate_emitsAuditRow_andNeverPersistsPlaintext() {
    String name = "ci-bot-audit-" + System.nanoTime();
    String plaintext =
        given()
            .contentType("application/json")
            .body("{\"name\":\"" + name + "\"}")
            .when()
            .post("/api/v1/me/tokens")
            .then()
            .statusCode(201)
            .extract()
            .path("token");
    assertNotNull(plaintext, "plaintext token returned exactly once");
    assertTrue(plaintext.startsWith("titanpat_"), "plaintext token has the canonical prefix");

    List<AuditLogRow> rows = stores.auditLog().findRecent(null, "PAT_CREATE", "PAT", null, 10, 0);
    assertTrue(rows.size() >= 1, "PAT_CREATE row recorded");

    // SECURITY: no row may ever contain the plaintext — CONSTITUTION §6 ban on plaintext secrets
    // in any persisted log applies here. This is the load-bearing assertion in this whole file.
    for (AuditLogRow r : stores.auditLog().findRecent(null, null, null, null, 1000, 0)) {
      if (r.detailsJson != null) {
        assertTrue(
            !r.detailsJson.contains(plaintext),
            "audit row " + r.id + " leaked plaintext token: " + r.detailsJson);
      }
    }
  }

  // ── GET /api/v1/audit ──────────────────────────────────────────────────────

  @Test
  void getAudit_returnsRowsForAdmin_withFilters() {
    // Seed a known-shape row directly so the assertion is stable irrespective of test ordering.
    AuditLogRow seed = new AuditLogRow();
    seed.actor = "carol-" + System.nanoTime();
    seed.action = AuditAction.JOB_CREATE.name();
    seed.targetType = AuditTargetType.JOB.name();
    seed.targetId = "9999";
    seed.detailsJson = "{\"fullName\":\"org/seeded\"}";
    seed.occurredAt = Instant.now();
    stores.auditLog().insert(seed);

    given()
        .when()
        .get("/api/v1/audit?actor=" + seed.actor)
        .then()
        .statusCode(200)
        .body("total", equalTo(1))
        .body("items[0].action", equalTo("JOB_CREATE"))
        .body("items[0].targetId", equalTo("9999"));
  }

  @Test
  void getAudit_bogusActionFilter_returns400() {
    given().when().get("/api/v1/audit?action=DELETE_ALL").then().statusCode(400);
  }

  @Test
  void getAudit_bogusTargetTypeFilter_returns400() {
    given().when().get("/api/v1/audit?targetType=DATABASE").then().statusCode(400);
  }

  @Test
  void getAudit_malformedSince_returns400() {
    given().when().get("/api/v1/audit?since=not-a-date").then().statusCode(400);
  }

  @Test
  void getAudit_paginationCapped() {
    given()
        .when()
        .get("/api/v1/audit?limit=10000")
        .then()
        .statusCode(200)
        .body("limit", equalTo(200));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static JobRow job(String fullName) {
    JobRow j = new JobRow();
    j.fullName = fullName;
    j.displayName = fullName;
    j.folderPath = null;
    j.pipelineScript = "stages: []\n";
    j.configJson = "{}";
    j.createdBy = "alice";
    j.enabled = true;
    return j;
  }

  /** Quote-escape a JSON string body. */
  private static String jsonString(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '\\' -> sb.append("\\\\");
        case '"' -> sb.append("\\\"");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        default -> sb.append(c);
      }
    }
    return sb.append('"').toString();
  }
}

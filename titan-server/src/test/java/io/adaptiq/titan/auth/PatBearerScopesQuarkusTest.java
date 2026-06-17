package io.adaptiq.titan.auth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.PersonalAccessTokenRow;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Adversarial REST-layer test for per-PAT scopes (closes #500).
 *
 * <p>Bypasses {@code @TestSecurity} so the real {@link PatAuthenticationMechanism} runs end-to-end:
 * a PAT row is seeded directly into the H2-backed {@link TitanStores}, then the test issues HTTP
 * requests with {@code Authorization: Bearer titanpat_...}. The Quarkus security pipeline maps the
 * bearer to a {@link io.quarkus.security.identity.SecurityIdentity} whose roles are (PAT_ROLES) ∩
 * (PAT scopes).
 *
 * <p>Asserts the central security claim of #500: a PAT minted with {@code scopes=["READ_JOB"]} can
 * read jobs (200) but CANNOT trigger builds (403 problem+json) — proving the resolved roles are the
 * intersection, not the union.
 */
@QuarkusTest
class PatBearerScopesQuarkusTest {

  @Inject TitanStores stores;

  @Test
  void readOnlyPat_canListJobs_butCannotTriggerBuilds() {
    // Seed: a job to act on, and a PAT scoped to READ_JOB only.
    long jobId = stores.jobs().insert(seedJob("it/scoped-pat-" + System.nanoTime()));
    String plaintext = "titanpat_ROXYPATAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    seedPat("alice-sub-" + System.nanoTime(), "ro-bot", plaintext, "[\"READ_JOB\"]");

    // GET /api/v1/jobs → 200. READ_JOB is in the scope set.
    given()
        .header("Authorization", "Bearer " + plaintext)
        .when()
        .get("/api/v1/jobs")
        .then()
        .statusCode(200);

    // POST /api/v1/jobs/{id}/builds → 403. TRIGGER_BUILD is NOT in the scope set, so the
    // intersection drops it. The response MUST be RFC 7807 problem+json (no 500, no bypass).
    given()
        .header("Authorization", "Bearer " + plaintext)
        .contentType("application/json")
        .body("{\"triggeredBy\":\"pat-scope-it\"}")
        .when()
        .post("/api/v1/jobs/" + jobId + "/builds")
        .then()
        .statusCode(403)
        .contentType(containsString("problem+json"))
        .body("status", equalTo(403));
  }

  @Test
  void legacyPat_noScopes_keepsFullRoleSet_canTrigger() {
    // Belt-and-braces: a legacy PAT (scopes_json IS NULL) must behave exactly as #477 did.
    long jobId = stores.jobs().insert(seedJob("it/legacy-pat-" + System.nanoTime()));
    String plaintext = "titanpat_LEGACYZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ";
    seedPat("bob-sub-" + System.nanoTime(), "legacy-bot", plaintext, null);

    // Trigger works — the bearer projects PAT_ROLES (including TRIGGER_BUILD) without narrowing.
    given()
        .header("Authorization", "Bearer " + plaintext)
        .contentType("application/json")
        .body("{\"triggeredBy\":\"pat-legacy-it\"}")
        .when()
        .post("/api/v1/jobs/" + jobId + "/builds")
        .then()
        .statusCode(201);
  }

  @Test
  void scopedPatWithEmptyIntersection_cannotEvenRead() {
    // Edge case: scope = APPROVE_GATE only. Intersected with PAT_ROLES this is just APPROVE_GATE,
    // which doesn't grant READ_JOB → GET /jobs must be 403, not 200.
    String plaintext = "titanpat_GATEONLYAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    seedPat("carol-sub-" + System.nanoTime(), "gate-only", plaintext, "[\"APPROVE_GATE\"]");

    int status =
        given()
            .header("Authorization", "Bearer " + plaintext)
            .when()
            .get("/api/v1/jobs")
            .then()
            .extract()
            .statusCode();
    assertTrue(status == 403, "gate-only PAT must not read jobs; got " + status);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void seedPat(String subject, String name, String plaintext, String scopesJson) {
    PersonalAccessTokenRow row = new PersonalAccessTokenRow();
    row.userSubject = subject;
    row.name = name + "-" + System.nanoTime();
    row.tokenHash = BcryptUtil.bcryptHash(plaintext);
    row.prefix = plaintext.substring(0, 13);
    row.scopesJson = scopesJson;
    stores.personalAccessTokens().insert(row);
  }

  private static JobRow seedJob(String fullName) {
    JobRow r = new JobRow();
    r.fullName = fullName;
    r.displayName = fullName;
    r.enabled = true;
    r.pipelineScript = "stages:\n  - stage: hello\n    steps:\n      - sh: echo hi\n";
    r.configJson = "{}";
    return r;
  }
}

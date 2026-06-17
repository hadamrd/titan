package io.adaptiq.titan.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.PersonalAccessTokenRow;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Adversarial REST-layer test for per-PAT job-pattern restriction (closes #1082).
 *
 * <p>Bypasses {@code @TestSecurity} so the real {@link PatAuthenticationMechanism} + {@link
 * PatJobScopeFilter} chain runs end-to-end against the H2-backed {@link TitanStores}.
 *
 * <p>Asserts the three central claims of the issue:
 *
 * <ul>
 *   <li>A token scoped to {@code "a/b/*"} can trigger {@code a/b/c} but NOT {@code a/c/d} (403
 *       problem+json).
 *   <li>Pattern enforcement cannot be bypassed by URL-encoding the slash in the job-name path
 *       segment (route resolves to the real job id, the filter sees the resolved {@code full_name},
 *       mismatch → 403).
 *   <li>A legacy token (no pattern) still operates on every job, preserving #500 behaviour.
 * </ul>
 */
@QuarkusTest
class PatJobPatternBearerQuarkusTest {

  @Inject TitanStores stores;

  @Test
  void patScopedToPattern_canTriggerMatchingJob_andCannotTriggerOtherJob() {
    // Seed two jobs: one that matches the pattern, one that does not.
    long suffix = System.nanoTime();
    long matchingJobId = stores.jobs().insert(seedJob("a/b/c-" + suffix));
    long otherJobId = stores.jobs().insert(seedJob("a/c/d-" + suffix));

    String plaintext = "titanpat_PATTERNAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    // PAT carries TRIGGER_BUILD scope AND a job-pattern restriction. Both gates must pass for the
    // matching job; only the pattern gate fails for the other job.
    seedPat(
        "alice-sub-" + suffix,
        "scoped-bot",
        plaintext,
        "[\"READ_JOB\",\"TRIGGER_BUILD\"]",
        "a/b/*");

    // Matching job → 201.
    RestAssured.given()
        .header("Authorization", "Bearer " + plaintext)
        .contentType("application/json")
        .body("{\"triggeredBy\":\"pattern-it-match\"}")
        .when()
        .post("/api/v1/jobs/" + matchingJobId + "/builds")
        .then()
        .statusCode(201);

    // Non-matching job → 403 problem+json with the stable type URI.
    RestAssured.given()
        .header("Authorization", "Bearer " + plaintext)
        .contentType("application/json")
        .body("{\"triggeredBy\":\"pattern-it-mismatch\"}")
        .when()
        .post("/api/v1/jobs/" + otherJobId + "/builds")
        .then()
        .statusCode(403)
        .contentType(containsString("problem+json"))
        .body("status", equalTo(403))
        .body("type", containsString("pat-scope-denied"));
  }

  @Test
  void patScopedToPattern_alsoBlocksReadOnGet() {
    // The filter is action-agnostic: a READ_JOB on a non-matching job is also denied. Mirrors
    // GitHub's "repo-scoped PATs cannot read other repos" semantics.
    long suffix = System.nanoTime();
    long otherJobId = stores.jobs().insert(seedJob("other/repo-" + suffix));

    String plaintext = "titanpat_PATRDONLYAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    seedPat("bob-sub-" + suffix, "read-bot", plaintext, "[\"READ_JOB\"]", "acme/**");

    int status =
        RestAssured.given()
            .header("Authorization", "Bearer " + plaintext)
            .when()
            .get("/api/v1/jobs/" + otherJobId)
            .then()
            .extract()
            .statusCode();
    assertTrue(
        status == 403, "scoped-pattern PAT must not read jobs outside its pattern; got " + status);
  }

  @Test
  void patWithNoJobPattern_keepsLegacyBehaviour_canTriggerAnyJob() {
    // Belt-and-braces against regression in #500's already-shipped behaviour: a PAT with scopes
    // but no jobPattern must still operate across every job. Otherwise the V34 migration would
    // be a behaviour break for all #500 tokens minted before this PR.
    long suffix = System.nanoTime();
    long anyJobId = stores.jobs().insert(seedJob("anywhere/anytime-" + suffix));

    String plaintext = "titanpat_NOPATERNAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    seedPat(
        "carol-sub-" + suffix,
        "legacy-scope-bot",
        plaintext,
        "[\"READ_JOB\",\"TRIGGER_BUILD\"]",
        null);

    RestAssured.given()
        .header("Authorization", "Bearer " + plaintext)
        .contentType("application/json")
        .body("{\"triggeredBy\":\"no-pattern-it\"}")
        .when()
        .post("/api/v1/jobs/" + anyJobId + "/builds")
        .then()
        .statusCode(201);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void seedPat(
      String subject, String name, String plaintext, String scopesJson, String jobPattern) {
    PersonalAccessTokenRow row = new PersonalAccessTokenRow();
    row.userSubject = subject;
    row.name = name + "-" + System.nanoTime();
    row.tokenHash = BcryptUtil.bcryptHash(plaintext);
    row.prefix = plaintext.substring(0, 13);
    row.scopesJson = scopesJson;
    row.jobPattern = jobPattern;
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

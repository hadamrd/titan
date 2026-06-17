package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.RbacAuditRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deny-by-default RBAC integration coverage for the newly-gated mutating surfaces (#1174).
 *
 * <p>{@code @QuarkusTest} + {@code @TestSecurity} (H2-backed, same wiring as {@link
 * RbacAuditApiIT}) so the full {@code @RolesAllowed} → {@code @RequiresRole} interceptor → {@code
 * ScopedAuthz} → {@code rbac_audit} DAO path runs. Each test uses a UNIQUE {@code @TestSecurity}
 * user so the scoped {@code rbac_user_role} seeds and the {@code rbac_audit} rows it writes never
 * bleed across methods on the shared H2 JVM.
 *
 * <p>Two complementary deny shapes are exercised, because the realm-role floor makes them the only
 * two ways a caller can be denied by {@code @RequiresRole} rather than by the coarse Quarkus gate:
 *
 * <ul>
 *   <li><strong>Realm-role demotion</strong> ({@link
 *       #credsCreate_editPipeline_denied_withAuditRow}) — a caller whose realm role clears the
 *       coarse {@code @RolesAllowed} but floors BELOW the {@code @RequiresRole} role (EDIT_PIPELINE
 *       → MAINTAINER &lt; ADMIN on {@code CredentialsApi.create}).
 *   <li><strong>Scoped demotion</strong> ({@link
 *       #workersDrain_scopedMaintainer_denied_withAuditRow}) — an ADMIN-realm caller carrying an
 *       explicit {@code rbac_user_role} scope grant lower than the gate (MAINTAINER on {@code
 *       ORG:global}); the scoped grant (being above VIEWER) wins over the realm floor, so the
 *       caller is denied an ADMIN-only worker op.
 * </ul>
 *
 * <p>Scoped demotion is exercised across both scope shapes: {@code ORG:global} ({@code
 * CredentialsApi}, {@code WorkersApi}, {@code SsoMappingApi}) and {@code REPO:<jobId>} ({@code
 * JobDiscoveryApi}, whose gate keys the scope off the {@code {jobId}} path param) — the #1174
 * matrix calls the REPO shape out explicitly.
 *
 * <p>The sad-path assertion checks the DENY row's {@code endpoint}, {@code requiredRole}, {@code
 * effectiveRole}, {@code scopeKind}/{@code scopeId} and {@code decision} — not merely that "a row
 * exists" — guarding against a gate that 403s but mislabels the audit trail. The complementary
 * coarse-gate case ({@link #ssoMappingCreate_nonAdmin_forbiddenByCoarseGate}) asserts the inverse
 * invariant: a denial at the Quarkus {@code @RolesAllowed} gate (before the interceptor runs)
 * writes NO {@code rbac_audit} row, so coarse-gate denials are never double-logged.
 */
@QuarkusTest
class RbacMutatingEndpointsIT {

  @Inject TitanStores stores;

  private static String credBody() {
    return "{\"kind\":\"secret-text\",\"scope\":\"global\",\"key\":\"rbac-1174-"
        + System.nanoTime()
        + "\",\"plaintext\":\"hunter2\"}";
  }

  /** Find the single DENY audit row this user wrote for {@code endpoint}, or fail. */
  private RbacAuditRow soleDenyRow(String user, String endpoint) {
    List<RbacAuditRow> rows = stores.rbacAudit().recentForUser(user, 50);
    List<RbacAuditRow> denies =
        rows.stream()
            .filter(r -> endpoint.equals(r.endpoint) && "DENY".equals(r.decision))
            .toList();
    assertEquals(
        1,
        denies.size(),
        () -> "expected exactly one DENY rbac_audit row for " + user + " on " + endpoint);
    return denies.get(0);
  }

  // ── CredentialsApi (ADMIN gate) — realm-role demotion ───────────────────────

  @Test
  @TestSecurity(
      user = "creds-editpipeline",
      roles = {"EDIT_PIPELINE"})
  void credsCreate_editPipeline_denied_withAuditRow() {
    // EDIT_PIPELINE clears the coarse @RolesAllowed on POST /credentials but floors to MAINTAINER,
    // below the @RequiresRole(ADMIN) gate → 403 from the interceptor (not the coarse gate).
    given()
        .contentType("application/json")
        .body(credBody())
        .when()
        .post("/api/v1/credentials")
        .then()
        .statusCode(403);

    RbacAuditRow row = soleDenyRow("creds-editpipeline", "CredentialsApi.create");
    assertEquals("ADMIN", row.requiredRole, "DENY row must record the required ADMIN floor");
    assertEquals("MAINTAINER", row.effectiveRole, "EDIT_PIPELINE floors to MAINTAINER");
    assertEquals("ORG", row.scopeKind);
    assertEquals("global", row.scopeId);
    assertEquals("DENY", row.decision);
  }

  @Test
  @TestSecurity(
      user = "creds-admin",
      roles = {"ADMIN"})
  void credsCreate_admin_reachesHandlerAndEmitsAllowRow() {
    // ADMIN clears the coarse @RolesAllowed AND the @RequiresRole(ADMIN) gate, so the request
    // reaches the handler. The H2-backed test store has no real credential-encryption backend, so
    // the SERVICE rejects the create with a 4xx — but that is post-RBAC: the RBAC contract under
    // test is "the gate permitted ADMIN", which we prove with the ALLOW rbac_audit row (the gate
    // fired and allowed) plus the absence of a 403/5xx. Asserting a concrete 201 is impossible on
    // this harness (no KEK) — the ALLOW row is the load-bearing, infra-independent assertion.
    int status =
        given()
            .contentType("application/json")
            .body(credBody())
            .when()
            .post("/api/v1/credentials")
            .then()
            .extract()
            .statusCode();
    assertNotEquals(403, status, "ADMIN must satisfy both the coarse and scoped gates");
    assertTrue(status < 500, () -> "gate must not produce an infra 5xx; got " + status);
    boolean sawAllow =
        stores.rbacAudit().recentForUser("creds-admin", 50).stream()
            .anyMatch(
                r -> "CredentialsApi.create".equals(r.endpoint) && "ALLOW".equals(r.decision));
    assertTrue(
        sawAllow, "the @RequiresRole gate must emit an ALLOW rbac_audit row when it permits");
  }

  // ── WorkersApi (ADMIN gate) — scoped demotion ───────────────────────────────

  @Test
  @TestSecurity(
      user = "worker-demoted-admin",
      roles = {"ADMIN"})
  void workersDrain_scopedMaintainer_denied_withAuditRow() {
    // ADMIN realm role clears the coarse gate, but an explicit MAINTAINER grant on ORG:global
    // (above VIEWER, so it wins over the realm floor) demotes the caller below the ADMIN gate.
    stores.rbacUserRoles().grant("worker-demoted-admin", "ORG", "global", "MAINTAINER");

    given().when().post("/api/v1/workers/nonexistent-worker-id/drain").then().statusCode(403);

    RbacAuditRow row = soleDenyRow("worker-demoted-admin", "WorkersApi.drain");
    assertEquals("ADMIN", row.requiredRole);
    assertEquals(
        "MAINTAINER", row.effectiveRole, "scoped MAINTAINER grant wins over the realm floor");
    assertEquals("ORG", row.scopeKind);
    assertEquals("global", row.scopeId);
    assertEquals("DENY", row.decision);
  }

  @Test
  @TestSecurity(
      user = "worker-admin",
      roles = {"ADMIN"})
  void workersDrain_admin_reachesHandler() {
    // No scoped demotion → ADMIN floor satisfies the gate; the handler runs and 404s on the
    // unknown worker id (proves we passed RBAC, not 403).
    given().when().post("/api/v1/workers/nonexistent-worker-id/drain").then().statusCode(404);
  }

  // ── AdminQueueApi / SsoMappingApi / JobDiscoveryApi — ALLOW row proves the gate fired ──

  @Test
  @TestSecurity(
      user = "queue-admin",
      roles = {"ADMIN"})
  void adminQueueDrain_admin_succeedsAndEmitsAllowRow() {
    // drain() returns QueueDrainResponse → 200. Asserting the concrete code (not !=403) means an
    // infra 500/503 fails the test rather than passing as a false "gate satisfied".
    given().when().post("/api/v1/queue/drain").then().statusCode(200);
    boolean sawAllow =
        stores.rbacAudit().recentForUser("queue-admin", 50).stream()
            .anyMatch(r -> "AdminQueueApi.drain".equals(r.endpoint) && "ALLOW".equals(r.decision));
    assertTrue(sawAllow, "the @RequiresRole gate must emit an ALLOW rbac_audit row on success");
  }

  @Test
  @TestSecurity(
      user = "queue-demoted-admin",
      roles = {"ADMIN"})
  void adminQueueDrain_scopedMaintainer_denied_withAuditRow() {
    // AdminQueueApi.drain is @RequiresRole(ADMIN, ORG:global) — the #1174 matrix names
    // AdminQueueApi
    // in the slice requiring BOTH an allow- and a deny-path IT. ADMIN realm role clears the coarse
    // @RolesAllowed(ADMIN) gate; an explicit MAINTAINER grant on ORG:global (above VIEWER, so it
    // wins over the realm floor) demotes the caller below the ADMIN gate → 403 from the
    // interceptor,
    // with a DENY row recorded. Mirrors workersDrain_scopedMaintainer_denied_withAuditRow.
    stores.rbacUserRoles().grant("queue-demoted-admin", "ORG", "global", "MAINTAINER");

    given().when().post("/api/v1/queue/drain").then().statusCode(403);

    RbacAuditRow row = soleDenyRow("queue-demoted-admin", "AdminQueueApi.drain");
    assertEquals("ADMIN", row.requiredRole, "DENY row must record the required ADMIN floor");
    assertEquals(
        "MAINTAINER", row.effectiveRole, "scoped MAINTAINER grant wins over the realm floor");
    assertEquals("ORG", row.scopeKind);
    assertEquals("global", row.scopeId);
    assertEquals("DENY", row.decision);
  }

  @Test
  @TestSecurity(
      user = "sso-readjob",
      roles = {"READ_JOB"})
  void ssoMappingCreate_nonAdmin_forbiddenByCoarseGate() {
    // A pure READ_JOB caller cannot even clear the coarse @RolesAllowed(ADMIN) on the SSO mapping
    // surface — deny-by-default holds end to end (403, no state change).
    given()
        .contentType("application/json")
        .body("{\"group\":\"g\",\"role\":\"ADMIN\"}")
        .when()
        .post("/api/v1/orgs/global/sso/mappings")
        .then()
        .statusCode(403);

    // sev3 invariant: the deny fires at the Quarkus coarse @RolesAllowed gate BEFORE the
    // @RequiresRole interceptor runs, so NO rbac_audit row must exist — coarse-gate denials are not
    // double-logged into the scoped-RBAC trail. (Contrast the scoped-demotion test below, which
    // clears the coarse gate and therefore DOES write exactly one DENY row.)
    assertTrue(
        stores.rbacAudit().recentForUser("sso-readjob", 50).isEmpty(),
        "coarse-gate denial must NOT write an rbac_audit row — the interceptor never ran");
  }

  // ── SsoMappingApi (ADMIN gate, ORG scope) — scoped demotion exercises the interceptor ──

  @Test
  @TestSecurity(
      user = "sso-demoted-admin",
      roles = {"ADMIN"})
  void ssoMappingCreate_scopedMaintainer_denied_withAuditRow() {
    // ADMIN realm role clears the coarse @RolesAllowed(ADMIN) gate, so the request reaches the
    // @RequiresRole(ADMIN, ORG:orgId) interceptor. An explicit MAINTAINER grant on ORG:global
    // (above VIEWER, so it wins over the realm floor) demotes the caller below the ADMIN gate →
    // 403 from the interceptor, with a DENY row recorded. This is the path the coarse-gate test
    // above can never reach.
    stores.rbacUserRoles().grant("sso-demoted-admin", "ORG", "global", "MAINTAINER");

    given()
        .contentType("application/json")
        .body("{\"groupPath\":\"/dev\",\"role\":\"ADMIN\"}")
        .when()
        .post("/api/v1/orgs/global/sso/mappings")
        .then()
        .statusCode(403);

    RbacAuditRow row = soleDenyRow("sso-demoted-admin", "SsoMappingApi.create");
    assertEquals("ADMIN", row.requiredRole, "DENY row must record the required ADMIN floor");
    assertEquals(
        "MAINTAINER", row.effectiveRole, "scoped MAINTAINER grant wins over the realm floor");
    assertEquals("ORG", row.scopeKind);
    assertEquals("global", row.scopeId, "ORG scope id resolves from the {orgId} path param");
    assertEquals("DENY", row.decision);
  }

  // ── JobDiscoveryApi (ADMIN gate, REPO scope) — REPO-scoped demotion (#1174 test matrix) ──

  @Test
  @TestSecurity(
      user = "discover-demoted-admin",
      roles = {"ADMIN"})
  void jobDiscoveryDiscover_scopedMaintainer_denied_withAuditRow() {
    // JobDiscoveryApi.discover is @RequiresRole(ADMIN, REPO scopeIdParam="jobId") — a DIFFERENT
    // scope shape (REPO, not ORG:global) from every other case here, which the #1174 matrix calls
    // out explicitly. ADMIN realm role clears the coarse gate; a scoped MAINTAINER grant on the
    // REPO scope keyed by the {jobId} path param demotes the caller below the ADMIN gate → 403 from
    // the interceptor, before the handler's job lookup runs.
    stores.rbacUserRoles().grant("discover-demoted-admin", "REPO", "777", "MAINTAINER");

    given().when().post("/api/v1/jobs/777/discover").then().statusCode(403);

    RbacAuditRow row = soleDenyRow("discover-demoted-admin", "JobDiscoveryApi.discover");
    assertEquals("ADMIN", row.requiredRole);
    assertEquals(
        "MAINTAINER", row.effectiveRole, "scoped MAINTAINER grant wins over the realm floor");
    assertEquals("REPO", row.scopeKind, "JobDiscoveryApi uses REPO scope, not ORG");
    assertEquals("777", row.scopeId, "REPO scope id resolves from the {jobId} path param");
    assertEquals("DENY", row.decision);
  }

  @Test
  @TestSecurity(
      user = "discover-admin",
      roles = {"ADMIN"})
  void jobDiscoveryDiscover_admin_reachesHandler() {
    // No scoped demotion → ADMIN floor satisfies the REPO-scoped gate; the handler runs and 404s on
    // the unknown job id (proves we passed RBAC, not 403).
    given().when().post("/api/v1/jobs/999999/discover").then().statusCode(404);
  }

  // ── PipelineValidateApi (VIEWER gate) — coarse-gate rejects anonymous BEFORE the scoped floor ──

  @Test
  void pipelineValidate_anonymous_rejectedByCoarseGate() {
    // sev2/security guard: PipelineValidateApi.validate is @RequiresRole(VIEWER, ORG:global), and
    // VIEWER is the realm floor an identity with NO roles maps to. If an unauthenticated caller
    // could reach RequiresRoleFilter, the VIEWER floor would admit them. The class-level
    // @RolesAllowed({READ_JOB, TRIGGER_BUILD, EDIT_PIPELINE, ADMIN}) is the coarse gate that fires
    // FIRST: an anonymous request is rejected (401/403) by Quarkus security before the scoped
    // filter ever runs — it must never reach the parser and never return a 200 verdict. No
    // @TestSecurity on this method = anonymous caller.
    int status =
        given()
            .contentType("application/json")
            .body("{\"yaml\":\"pipeline:\\n  stages: []\"}")
            .when()
            .post("/api/v1/pipeline/validate")
            .then()
            .extract()
            .statusCode();
    assertTrue(
        status == 401 || status == 403,
        () -> "anonymous caller must be rejected by the coarse @RolesAllowed gate; got " + status);
    assertNotEquals(200, status, "an unauthenticated caller must never reach the validate handler");
  }
}

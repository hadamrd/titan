package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration coverage for the #126 fix: {@code Authz.requires(BUILD_RERUN)} must resolve roles
 * from the CANONICAL {@code titan.rbac_user_role} table (the one {@code AdminUsersApi} writes) —
 * not the orphaned flat {@code titan.user_roles} table nothing in the product seeds.
 *
 * <p>{@code @QuarkusTest} + {@code @TestSecurity} (H2-backed, same wiring as {@link
 * RbacMutatingEndpointsIT}) so the full chain runs per request: coarse {@code @RolesAllowed} →
 * {@code @RequiresRole} interceptor → in-handler {@code Authz.requires(BUILD_RERUN)} → {@code
 * ScopedAuthz} role resolution against the real RBAC tables.
 *
 * <p>Every caller here carries ONLY the {@code TRIGGER_BUILD} realm role — enough to clear the
 * coarse gate and the {@code @RequiresRole(DEVELOPER)} gate via the realm floor, but BELOW the
 * MAINTAINER+ {@code BUILD_RERUN} policy — so the scoped {@code rbac_user_role} grant is the single
 * load-bearing input to the allow/deny under test:
 *
 * <ul>
 *   <li>{@link #replayFromFailed_grantedViaRbacUserRole_returns201} — a MAINTAINER grant written to
 *       {@code rbac_user_role (ORG, 'global')} (the exact row an {@code AdminUsersApi} PUT
 *       produces) authorizes {@code POST /replay-from-failed} → 201 with the replay's BuildDto.
 *   <li>{@link #replayFromFailed_ungrantedUser_returns403} — no grant anywhere → default-deny 403.
 *   <li>{@link #replayFromFailed_developerGrant_belowPolicy_returns403} — adversarial: a scoped
 *       DEVELOPER grant exists but sits below the MAINTAINER+ policy → still 403. Proves the fix
 *       reads the right store WITHOUT loosening the policy table.
 * </ul>
 *
 * <p>Distinct {@code @TestSecurity} users per test + {@code @AfterEach} revocation keep the shared
 * H2 JVM free of leaked grants (same hygiene rule as {@link RbacMutatingEndpointsIT}).
 */
@QuarkusTest
class BuildReplayRbacIT {

  private static final String GRANTED_USER = "replay-rbac-granted";
  private static final String UNGRANTED_USER = "replay-rbac-ungranted";
  private static final String DEV_GRANT_USER = "replay-rbac-dev-grant";

  @Inject TitanStores stores;

  @AfterEach
  void revokeSeededGrants() {
    stores.rbacUserRoles().revoke(GRANTED_USER, "ORG", "global", "MAINTAINER");
    stores.rbacUserRoles().revoke(DEV_GRANT_USER, "ORG", "global", "DEVELOPER");
  }

  @Test
  @TestSecurity(
      user = GRANTED_USER,
      roles = {"TRIGGER_BUILD"})
  void replayFromFailed_grantedViaRbacUserRole_returns201() {
    // The canonical grant path: the same row `PUT /api/v1/admin/users/{u}/roles
    // {role: MAINTAINER, scopeKind: ORG, scopeId: global}` upserts.
    stores.rbacUserRoles().grant(GRANTED_USER, "ORG", "global", "MAINTAINER");
    long parentId = parentWithFailedStage();

    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/builds/" + parentId + "/replay-from-failed")
        .then()
        .statusCode(201)
        .body("id", greaterThan(0))
        .body("status", equalTo("QUEUED"))
        .body("triggerType", equalTo("replay"));
  }

  @Test
  @TestSecurity(
      user = UNGRANTED_USER,
      roles = {"TRIGGER_BUILD"})
  void replayFromFailed_ungrantedUser_returns403() {
    long parentId = parentWithFailedStage();

    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/builds/" + parentId + "/replay-from-failed")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(
      user = DEV_GRANT_USER,
      roles = {"TRIGGER_BUILD"})
  void replayFromFailed_developerGrant_belowPolicy_returns403() {
    // A grant IS present in the canonical table — but DEVELOPER < MAINTAINER, and the #126 fix
    // must not loosen the BUILD_RERUN policy while converging the store.
    stores.rbacUserRoles().grant(DEV_GRANT_USER, "ORG", "global", "DEVELOPER");
    long parentId = parentWithFailedStage();

    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/api/v1/builds/" + parentId + "/replay-from-failed")
        .then()
        .statusCode(403);
  }

  // ── fixture ────────────────────────────────────────────────────────────────

  /**
   * A baked, finished-FAILED parent build with one FAILED stage — the minimum shape {@code
   * BuildService.replayFromFirstFailed} accepts (mirrors {@code BuildServiceReplayFromFailedIT}'s
   * fixture).
   */
  private long parentWithFailedStage() {
    JobRow j = new JobRow();
    j.fullName = "replay-rbac-it/" + System.nanoTime();
    j.pipelineScript = "";
    j.configJson = "{}";
    j.enabled = true;
    long jobId = stores.jobs().insert(j);

    BuildRow b = new BuildRow();
    b.jobId = jobId;
    b.buildNumber = stores.builds().nextBuildNumber(jobId);
    b.status = "FAILED";
    b.queuedAt = Instant.now();
    b.triggeredBy = "test";
    b.triggerType = "manual";
    b.pipelineModelJson =
        "{\"stages\":[{\"name\":\"stage-1\",\"id\":\"stage-1\",\"steps\":[],"
            + "\"parentStageIds\":[],\"parallel\":false,\"dependsOn\":[]}],"
            + "\"parameters\":[],\"triggers\":[],\"gates\":[],\"preconditions\":[],"
            + "\"failurePolicy\":\"blockOnFailure\"}";
    long buildId = stores.withTransaction(c -> stores.builds().insert(c, b));

    FlowNodeRow node = new FlowNodeRow();
    node.buildId = buildId;
    node.nodeId = "stage-1";
    node.nodeType = "STAGE";
    node.displayName = "stage-1";
    node.status = "FAILED";
    stores.flowNodes().insert(node);

    return buildId;
  }
}

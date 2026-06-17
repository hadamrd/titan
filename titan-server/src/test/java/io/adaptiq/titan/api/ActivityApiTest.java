package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * REST-layer tests for {@link ActivityApi}: JSON shape, cursor handling, RBAC matrix (closes #304).
 *
 * <p>H2-backed real {@link TitanStores} via {@link H2StoresProducer} — same pattern as {@link
 * StatsApiTest} and {@link BuildsApiTest}. The Postgres-specific assertions (real {@code <}
 * pagination across distinct timestamps) live in {@code ActivityApiIT}.
 *
 * <p>The {@code @QuarkusTest} producer is application-scoped: the in-memory H2 store is shared
 * across this test class's lifetime. Per the #367 carry-over note, fixture job names are randomised
 * ({@code System.nanoTime}) so this class's assertions are order-independent against any data other
 * tests in the suite may have left behind.
 */
@QuarkusTest
@TestSecurity(user = "testuser", roles = "ADMIN")
class ActivityApiTest {

  @Inject TitanStores stores;

  // ── shape ───────────────────────────────────────────────────────────────

  @Test
  void list_returnsItemsWithCanonicalShape() {
    long jobId = stores.jobs().insert(job("act-shape/job-" + System.nanoTime()));
    long buildId = insertTerminalBuild(stores, jobId, "SUCCESS", 5_000L);

    given()
        .queryParam("limit", 100)
        .when()
        .get("/api/v1/activity")
        .then()
        .statusCode(200)
        .body("items", notNullValue())
        .body("items.find { it.buildId == " + buildId + " }.id", equalTo("build-" + buildId))
        .body("items.find { it.buildId == " + buildId + " }.type", equalTo("build.terminal"))
        .body("items.find { it.buildId == " + buildId + " }.status", equalTo("SUCCESS"))
        .body("items.find { it.buildId == " + buildId + " }.durationMs", equalTo(5_000))
        .body("items.find { it.buildId == " + buildId + " }.ts", notNullValue())
        .body("items.find { it.buildId == " + buildId + " }.jobName", notNullValue());
  }

  @Test
  void list_nonTerminalBuildsAreExcluded() {
    long jobId = stores.jobs().insert(job("act-nonterm/job-" + System.nanoTime()));
    Instant nowMinus = Instant.now().minusSeconds(5);
    long runningId = insertBuild(stores, jobId, "RUNNING", nowMinus, null);
    long queuedId = insertBuild(stores, jobId, "QUEUED", null, null);

    given()
        .queryParam("limit", 100)
        .when()
        .get("/api/v1/activity")
        .then()
        .statusCode(200)
        .body("items.find { it.buildId == " + runningId + " }", nullValue())
        .body("items.find { it.buildId == " + queuedId + " }", nullValue());
  }

  // ── pagination / limit ──────────────────────────────────────────────────

  @Test
  void list_limitCapsItemsAndExposesNextCursor() {
    long jobId = stores.jobs().insert(job("act-page/job-" + System.nanoTime()));
    Instant base = Instant.now();
    insertTerminalBuildAt(stores, jobId, "SUCCESS", 100L, base.minusSeconds(30));
    insertTerminalBuildAt(stores, jobId, "FAILED", 200L, base.minusSeconds(20));
    insertTerminalBuildAt(stores, jobId, "SUCCESS", 300L, base.minusSeconds(10));

    // limit=2 → page is "full" → nextCursor must be non-null
    given()
        .queryParam("limit", 2)
        .when()
        .get("/api/v1/activity")
        .then()
        .statusCode(200)
        .body("items", hasSize(2))
        .body("nextCursor", notNullValue());
  }

  @Test
  void list_limitClampedToMaximum() {
    given()
        .queryParam("limit", 9999)
        .when()
        .get("/api/v1/activity")
        .then()
        .statusCode(200)
        // shouldn't error; if it returned anything, the page can't exceed MAX_LIMIT
        .body("items.size()", org.hamcrest.Matchers.lessThanOrEqualTo(ActivityApi.MAX_LIMIT));
  }

  @Test
  void list_invalidCursorReturns400() {
    given()
        .queryParam("before", "###not-base64###")
        .when()
        .get("/api/v1/activity")
        .then()
        .statusCode(400);
  }

  // ── cursor helpers (pure-Java) ─────────────────────────────────────────

  @Test
  void cursor_roundTripPreservesEpochMs() {
    Instant ts = Instant.parse("2026-05-21T10:14:00Z");
    String cursor = ActivityApi.encodeCursor(ts);
    assertNotNull(cursor);
    Instant back = ActivityApi.decodeCursor(cursor);
    assertEquals(ts, back);
  }

  @Test
  void cursor_nullOrBlankDecodesToNull() {
    assertNull(ActivityApi.decodeCursor(null));
    assertNull(ActivityApi.decodeCursor(""));
    assertNull(ActivityApi.decodeCursor("   "));
  }

  @Test
  void cursor_invalidThrowsBadRequest() {
    assertThrows(ApiBadRequestException.class, () -> ActivityApi.decodeCursor("###not-b64###"));
  }

  // ── RBAC matrix ────────────────────────────────────────────────────────

  @Test
  @TestSecurity(user = "reader", roles = "READ_JOB")
  void list_readJobIsAllowed() {
    given().when().get("/api/v1/activity").then().statusCode(200);
  }

  @Test
  @TestSecurity(user = "trigger", roles = "TRIGGER_BUILD")
  void list_triggerBuildIsAllowed() {
    given().when().get("/api/v1/activity").then().statusCode(200);
  }

  @Test
  @TestSecurity(user = "outsider", roles = "APPROVE_GATE")
  void list_unrelatedRoleIsForbidden() {
    given().when().get("/api/v1/activity").then().statusCode(403);
  }

  // ── helpers ────────────────────────────────────────────────────────────

  private static JobRow job(String fullName) {
    JobRow r = new JobRow();
    r.fullName = fullName;
    r.enabled = true;
    r.pipelineScript = "";
    r.configJson = "{}";
    return r;
  }

  /** Insert a terminal build with realistic started/finished timestamps. */
  static long insertTerminalBuild(TitanStores stores, long jobId, String status, long durationMs) {
    return insertTerminalBuildAt(stores, jobId, status, durationMs, Instant.now());
  }

  static long insertTerminalBuildAt(
      TitanStores stores, long jobId, String status, long durationMs, Instant finishedAt) {
    Instant startedAt = finishedAt.minusMillis(durationMs);
    return insertBuild(stores, jobId, status, startedAt, finishedAt);
  }

  /** Generic build insert; pass {@code null} for started/finished to leave them unset. */
  static long insertBuild(
      TitanStores stores, long jobId, String status, Instant startedAt, Instant finishedAt) {
    BuildRow r = new BuildRow();
    r.jobId = jobId;
    r.buildNumber = stores.builds().nextBuildNumber(jobId);
    r.status = status;
    r.queuedAt = startedAt != null ? startedAt.minusSeconds(1) : Instant.now();
    r.triggeredBy = "test";
    r.triggerType = "manual";
    long buildId = stores.withTransaction(conn -> stores.builds().insert(conn, r));
    if (finishedAt != null) {
      long durationMs =
          startedAt != null ? finishedAt.toEpochMilli() - startedAt.toEpochMilli() : 0L;
      stores.builds().updateStatus(buildId, status, startedAt, finishedAt, durationMs, null);
    }
    return buildId;
  }
}

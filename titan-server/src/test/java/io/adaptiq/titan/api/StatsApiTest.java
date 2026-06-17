package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

import io.adaptiq.titan.store.TitanStores;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * REST-layer tests for {@link StatsApi}: JSON shape + RBAC matrix (closes #346).
 *
 * <p>The aggregate-correctness assertions live in {@code StatsApiIT} (Postgres Testcontainers) —
 * H2's {@code PERCENTILE_CONT} path is exercised here as a smoke test only, since the API contract
 * the UI cares about is "three numbers, never null". Seeding live builds + asserting the math
 * happens against the production engine (Postgres) in the IT.
 *
 * <p>The {@code @QuarkusTest} producer is application-scoped: the in-memory H2 store is shared
 * across this test class's lifetime AND across other {@code @QuarkusTest} suites in the same Gradle
 * invocation. The aggregate stats endpoint has no per-tenant/per-job filter, so the only
 * order-independent assertions possible at this tier are the JSON-shape ones below — the exact
 * "empty store returns zeros" contract is covered in isolation by {@code StatsApiIT} against a
 * fresh Postgres container (closes #367).
 */
@QuarkusTest
@TestSecurity(user = "testuser", roles = "ADMIN")
class StatsApiTest {

  @Inject TitanStores stores;

  @Test
  void overview_returnsThreeNumericFieldsNeverNull() {
    given()
        .when()
        .get("/api/v1/stats")
        .then()
        .statusCode(200)
        .body("buildsToday", notNullValue())
        .body("buildsToday", greaterThanOrEqualTo(0))
        .body("successRate", notNullValue())
        .body("successRate", greaterThanOrEqualTo(0.0f))
        .body("successRate", lessThanOrEqualTo(1.0f))
        .body("medianDurationMs", notNullValue())
        .body("medianDurationMs", greaterThanOrEqualTo(0));
  }

  // ── RBAC matrix ────────────────────────────────────────────────────────────

  @Test
  @TestSecurity(user = "reader", roles = "READ_JOB")
  void overview_readJobIsAllowed() {
    given().when().get("/api/v1/stats").then().statusCode(200);
  }

  @Test
  @TestSecurity(user = "trigger", roles = "TRIGGER_BUILD")
  void overview_triggerBuildIsAllowed() {
    given().when().get("/api/v1/stats").then().statusCode(200);
  }

  @Test
  @TestSecurity(user = "outsider", roles = "APPROVE_GATE")
  void overview_unrelatedRoleIsForbidden() {
    given().when().get("/api/v1/stats").then().statusCode(403);
  }
}

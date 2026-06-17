package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * REST-layer tests for {@link ArtifactsApi}.
 *
 * <p>The H2-backed {@link TitanStores} from {@link H2StoresProducer} is application-scoped and
 * shared across the whole test source set. Each test in this class is responsible for its own
 * isolation — every build is freshly inserted and every assertion is keyed by the freshly minted
 * build id (so artifacts from sibling tests do not bleed into the {@code total} count).
 */
@QuarkusTest
@TestSecurity(
    user = "testuser",
    roles = {"READ_JOB", "ADMIN"})
class ArtifactsApiTest {

  @Inject TitanStores stores;

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void list_unknownBuildReturns404() {
    given().when().get("/api/v1/builds/9999999/artifacts").then().statusCode(404);
  }

  @Test
  void list_buildWithNoArtifactsReturnsEmptyPage() {
    long buildId = insertBuild();

    given()
        .when()
        .get("/api/v1/builds/" + buildId + "/artifacts")
        .then()
        .statusCode(200)
        .body("items", is(empty()))
        .body("total", equalTo(0));
  }

  @Test
  void list_fiveArtifactsReturnsFiveOrderedByIdDesc() {
    long buildId = insertBuild();
    for (int i = 1; i <= 5; i++) {
      insertArtifact(buildId, "ARTIFACT", "a-" + i + ".bin", 100L * i);
    }

    Integer firstId =
        given()
            .when()
            .get("/api/v1/builds/" + buildId + "/artifacts")
            .then()
            .statusCode(200)
            .body("total", equalTo(5))
            .body("items", hasSize(5))
            .body("items[0].name", equalTo("a-5.bin"))
            .body("items[4].name", equalTo("a-1.bin"))
            .extract()
            .path("items[0].id");

    // newest id first
    Integer lastId =
        given()
            .when()
            .get("/api/v1/builds/" + buildId + "/artifacts")
            .then()
            .extract()
            .path("items[4].id");
    org.junit.jupiter.api.Assertions.assertTrue(
        firstId > lastId, "items must be ordered id DESC: " + firstId + " > " + lastId);
  }

  @Test
  void list_paginationWindowsAcrossLargeSet() {
    long buildId = insertBuild();
    for (int i = 0; i < 250; i++) {
      insertArtifact(buildId, "ARTIFACT", "p-" + i + ".bin", 10L);
    }

    given()
        .queryParam("limit", 100)
        .when()
        .get("/api/v1/builds/" + buildId + "/artifacts")
        .then()
        .statusCode(200)
        .body("items", hasSize(100))
        .body("total", equalTo(250));

    given()
        .queryParam("offset", 200)
        .queryParam("limit", 100)
        .when()
        .get("/api/v1/builds/" + buildId + "/artifacts")
        .then()
        .statusCode(200)
        .body("items", hasSize(50))
        .body("total", equalTo(250));
  }

  @Test
  void list_limitClampedAt500() {
    long buildId = insertBuild();
    // ask for an absurd limit — the server must cap the slice silently. The total
    // is still the truth: zero artifacts here, so {items: []} but the cap is on the
    // result shape, not the total count.
    insertArtifact(buildId, "ARTIFACT", "single.bin", 7L);

    given()
        .queryParam("limit", 999)
        .when()
        .get("/api/v1/builds/" + buildId + "/artifacts")
        .then()
        .statusCode(200)
        // 1 row exists; with limit clamped at 500 we still see exactly 1
        .body("items", hasSize(1))
        .body("total", equalTo(1));
  }

  // ── STASH rows must never bleed into the artifact list ────────────────────

  @Test
  void list_stashRowsAreInvisible() {
    long buildId = insertBuild();
    insertArtifact(buildId, "ARTIFACT", "real.bin", 100L);
    insertArtifact(buildId, "STASH", "intra-build", 200L);

    given()
        .when()
        .get("/api/v1/builds/" + buildId + "/artifacts")
        .then()
        .statusCode(200)
        .body("total", equalTo(1))
        .body("items", hasSize(1))
        .body("items[0].name", equalTo("real.bin"));
  }

  // ── wire-format invariant: internal columns never leak ─────────────────────

  @Test
  void list_responseDoesNotLeakInternalColumns() {
    long buildId = insertBuild();
    insertArtifact(buildId, "ARTIFACT", "leak-check.bin", 42L);

    String body =
        given()
            .when()
            .get("/api/v1/builds/" + buildId + "/artifacts")
            .then()
            .statusCode(200)
            .extract()
            .asString();

    assertFalse(body.contains("\"storage\""), "leaked storage column: " + body);
    assertFalse(body.contains("storage_ref"), "leaked storage_ref column: " + body);
    assertFalse(body.contains("storageRef"), "leaked storageRef field: " + body);
    assertFalse(body.contains("nodeId"), "leaked nodeId field: " + body);
    assertFalse(body.contains("\"kind\""), "leaked kind discriminator: " + body);
    assertFalse(body.contains("buildId"), "leaked buildId field: " + body);
    // contentType is null until the schema gains the column; @JsonInclude(NON_NULL)
    // must strip it from the wire today.
    assertFalse(body.contains("contentType"), "contentType must be omitted while null: " + body);
  }

  @Test
  void list_downloadUrlShapeMatchesContract() {
    long buildId = insertBuild();
    insertArtifact(buildId, "ARTIFACT", "u.bin", 1L);

    given()
        .when()
        .get("/api/v1/builds/" + buildId + "/artifacts")
        .then()
        .statusCode(200)
        .body("items[0].downloadUrl", matchesPattern("^/api/v1/artifacts/\\d+/download$"))
        .body("items[0].sizeBytes", equalTo(1))
        .body("items[0].name", equalTo("u.bin"))
        .body("items[0].id", greaterThan(0));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private long insertBuild() {
    JobRow j = new JobRow();
    j.fullName = "art/test-" + System.nanoTime();
    j.enabled = true;
    j.pipelineScript = "";
    j.configJson = "{}";
    long jobId = stores.jobs().insert(j);

    BuildRow b = new BuildRow();
    b.jobId = jobId;
    b.buildNumber = stores.builds().nextBuildNumber(jobId);
    b.status = "SUCCESS";
    b.queuedAt = Instant.now();
    b.triggeredBy = "test";
    b.triggerType = "manual";
    return stores.withTransaction(c -> stores.builds().insert(c, b));
  }

  /**
   * Insert one {@code titan.artifact} row directly through the dataSource — the production
   * ArtifactDao exposes no insert (writes go through the worker's {@code DbArtifactSink}), and the
   * REST layer's contract is a read-side projection, so a raw insert in a test is fine.
   */
  private void insertArtifact(long buildId, String kind, String name, long sizeBytes) {
    stores.withTransaction(
        conn -> {
          try (java.sql.PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO titan.artifact "
                      + "(build_id, node_id, kind, name, size_bytes, sha256, storage, storage_ref) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, buildId);
            ps.setString(2, "n1");
            ps.setString(3, kind);
            ps.setString(4, name);
            ps.setLong(5, sizeBytes);
            // 64-char zero-padded hex placeholder — schema is CHAR(64) NOT NULL
            ps.setString(6, "0000000000000000000000000000000000000000000000000000000000000000");
            ps.setString(7, "fs");
            ps.setString(8, "/tmp/" + name);
            ps.executeUpdate();
            return null;
          } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
          }
        });
  }
}

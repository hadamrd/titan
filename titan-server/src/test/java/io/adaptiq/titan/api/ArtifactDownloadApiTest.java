package io.adaptiq.titan.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.adaptiq.titan.artifact.ArtifactDownloadSigner;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * REST-layer tests for {@link ArtifactDownloadApi} — the {@code GET
 * /api/v1/artifacts/{id}/download} endpoint that closes the loop with PR #391's e2e spec.
 *
 * <p>Stores are H2-backed via {@link H2StoresProducer}; the artifact-store backend is stubbed by
 * {@link FakeArtifactStoreResolver}, so the bytes a row points at are seeded in-memory — no
 * filesystem, no env-driven {@code TITAN_ARTIFACT_*} config.
 */
@QuarkusTest
@TestSecurity(
    user = "testuser",
    roles = {"READ_JOB"})
class ArtifactDownloadApiTest {

  @Inject TitanStores stores;
  @Inject FakeArtifactStoreResolver fakeResolver;
  @Inject ArtifactDownloadSigner signer;

  @BeforeEach
  void resetBackend() {
    fakeResolver.reset();
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void download_existingArtifactStreamsBytesWithDispositionAndContentType() {
    long buildId = insertBuild();
    byte[] payload = "hello-titan".getBytes(StandardCharsets.UTF_8);
    String ref = buildId + "/ARTIFACT/dir/output.txt";
    fakeResolver.put("fs", ref, payload);
    long artifactId = insertArtifact(buildId, "ARTIFACT", "dir/output.txt", payload.length, ref);

    Response r =
        given()
            .when()
            .get("/api/v1/artifacts/" + artifactId + "/download")
            .then()
            .extract()
            .response();

    assertEquals(200, r.statusCode());
    // basename of "dir/output.txt" is "output.txt"; .txt → text/plain
    assertEquals("text/plain", r.header("Content-Type").split(";")[0].trim());
    assertEquals("attachment; filename=\"output.txt\"", r.header("Content-Disposition"));
    assertEquals(String.valueOf(payload.length), r.header("Content-Length"));
    assertArrayEquals(payload, r.asByteArray());
  }

  @Test
  void download_unknownExtensionFallsBackToOctetStream() {
    long buildId = insertBuild();
    byte[] payload = new byte[] {1, 2, 3, 4, 5};
    String ref = buildId + "/ARTIFACT/blob.unknownext";
    fakeResolver.put("fs", ref, payload);
    long id = insertArtifact(buildId, "ARTIFACT", "blob.unknownext", payload.length, ref);

    given()
        .when()
        .get("/api/v1/artifacts/" + id + "/download")
        .then()
        .statusCode(200)
        .header("Content-Type", "application/octet-stream")
        .header("Content-Length", equalTo(String.valueOf(payload.length)));
  }

  // ── RBAC matrix ────────────────────────────────────────────────────────────

  @Test
  @TestSecurity // no roles → anonymous
  void download_unauthenticated_returns401() {
    given().when().get("/api/v1/artifacts/1/download").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "alice", roles = "EDIT_PIPELINE")
  void download_wrongRole_returns403() {
    given().when().get("/api/v1/artifacts/1/download").then().statusCode(403);
  }

  // ── error branches ────────────────────────────────────────────────────────

  @Test
  void download_missingId_returns404() {
    given().when().get("/api/v1/artifacts/9999999/download").then().statusCode(404);
  }

  @Test
  void download_stashRow_isNotAddressable() {
    long buildId = insertBuild();
    byte[] payload = "stash".getBytes(StandardCharsets.UTF_8);
    String ref = buildId + "/STASH/internal";
    fakeResolver.put("fs", ref, payload);
    long stashId = insertArtifact(buildId, "STASH", "internal", payload.length, ref);

    // findById filters out STASH rows — only ARTIFACT is addressable.
    given().when().get("/api/v1/artifacts/" + stashId + "/download").then().statusCode(404);
  }

  @Test
  void download_rowExistsButBytesMissing_returns404() {
    long buildId = insertBuild();
    // Insert the row but deliberately do NOT seed bytes in the fake backend.
    long id = insertArtifact(buildId, "ARTIFACT", "vanished.bin", 7L, "missing-ref");

    given().when().get("/api/v1/artifacts/" + id + "/download").then().statusCode(404);
  }

  @Test
  void download_unconfiguredBackend_returns503() {
    long buildId = insertBuild();
    fakeResolver.markUnconfigured("s3");
    long id = insertArtifact(buildId, "ARTIFACT", "remote.bin", 4L, "some/ref", "s3");

    given().when().get("/api/v1/artifacts/" + id + "/download").then().statusCode(503);
  }

  // ── #849 signed-URL flow ─────────────────────────────────────────────────

  @Test
  void signDownload_returnsUrlAndExpiry_andUrlVerifiesViaTokenParam() {
    long buildId = insertBuild();
    byte[] payload = "hello\n".getBytes(StandardCharsets.UTF_8);
    String ref = buildId + "/ARTIFACT/out.txt";
    fakeResolver.put("fs", ref, payload);
    long id = insertArtifact(buildId, "ARTIFACT", "out.txt", payload.length, ref);

    String url =
        given()
            .when()
            .post("/api/v1/artifacts/" + id + "/sign-download")
            .then()
            .statusCode(200)
            .body(
                "url",
                org.hamcrest.Matchers.startsWith("/api/v1/artifacts/" + id + "/download?token="))
            .body("expiresAt", org.hamcrest.Matchers.notNullValue())
            .extract()
            .path("url");

    // The signed URL must succeed with NO bearer header — that's the entire point of the fix.
    given()
        .auth()
        .none()
        .when()
        .get(url)
        .then()
        .statusCode(200)
        .body(org.hamcrest.Matchers.equalTo("hello\n"));
  }

  @Test
  @TestSecurity // anonymous
  void signDownload_unauthenticated_returns401() {
    given().when().post("/api/v1/artifacts/1/sign-download").then().statusCode(401);
  }

  @Test
  void download_validBearerNoToken_stillWorks_backCompat() {
    // The legacy CLI path: bearer + no ?token=. Must keep working.
    long buildId = insertBuild();
    byte[] payload = "backcompat".getBytes(StandardCharsets.UTF_8);
    String ref = buildId + "/ARTIFACT/cli.txt";
    fakeResolver.put("fs", ref, payload);
    long id = insertArtifact(buildId, "ARTIFACT", "cli.txt", payload.length, ref);

    given()
        .when()
        .get("/api/v1/artifacts/" + id + "/download")
        .then()
        .statusCode(200)
        .body(org.hamcrest.Matchers.equalTo("backcompat"));
  }

  @Test
  @TestSecurity // anonymous — the token alone must authorise (or not)
  void download_expiredToken_returns401() {
    long buildId = insertBuild();
    byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
    String ref = buildId + "/ARTIFACT/expired.txt";
    fakeResolver.put("fs", ref, payload);
    long id = insertArtifact(buildId, "ARTIFACT", "expired.txt", payload.length, ref);

    // Mint a token already expired (TTL is a Duration; signer accepts negative durations as
    // "expired before mint" via the test seam).
    String expiredToken = signer.sign(id, java.time.Duration.ofSeconds(-1)).token();

    given()
        .auth()
        .none()
        .when()
        .get("/api/v1/artifacts/" + id + "/download?token=" + expiredToken)
        .then()
        .statusCode(401);
  }

  @Test
  @TestSecurity // anonymous — the token alone must authorise (or not)
  void download_tamperedToken_returns401() {
    long buildId = insertBuild();
    byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
    String ref = buildId + "/ARTIFACT/tamper.txt";
    fakeResolver.put("fs", ref, payload);
    long id = insertArtifact(buildId, "ARTIFACT", "tamper.txt", payload.length, ref);

    String token = signer.sign(id).token();
    char last = token.charAt(token.length() - 1);
    char swapped = (last == 'A') ? 'B' : 'A';
    String tampered = token.substring(0, token.length() - 1) + swapped;

    given()
        .auth()
        .none()
        .when()
        .get("/api/v1/artifacts/" + id + "/download?token=" + tampered)
        .then()
        .statusCode(401);
  }

  @Test
  @TestSecurity // anonymous — the token alone must authorise (or not)
  void download_tokenForArtifactA_rejectedForArtifactB() {
    long buildId = insertBuild();
    byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
    String refA = buildId + "/ARTIFACT/a.txt";
    String refB = buildId + "/ARTIFACT/b.txt";
    fakeResolver.put("fs", refA, payload);
    fakeResolver.put("fs", refB, payload);
    long idA = insertArtifact(buildId, "ARTIFACT", "a.txt", payload.length, refA);
    long idB = insertArtifact(buildId, "ARTIFACT", "b.txt", payload.length, refB);

    String tokenForA = signer.sign(idA).token();

    given()
        .auth()
        .none()
        .when()
        .get("/api/v1/artifacts/" + idB + "/download?token=" + tokenForA)
        .then()
        .statusCode(401);
  }

  // ── header-injection guard ─────────────────────────────────────────────────

  @Test
  void download_filenameWithQuotesIsSanitized() {
    long buildId = insertBuild();
    byte[] payload = new byte[] {0x42};
    String name = "a\"b\\c.bin";
    String ref = buildId + "/ARTIFACT/" + name;
    fakeResolver.put("fs", ref, payload);
    long id = insertArtifact(buildId, "ARTIFACT", name, payload.length, ref);

    String disposition =
        given()
            .when()
            .get("/api/v1/artifacts/" + id + "/download")
            .then()
            .statusCode(200)
            .extract()
            .header("Content-Disposition");

    // quotes and backslashes are replaced by underscores
    assertEquals("attachment; filename=\"a_b_c.bin\"", disposition);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private long insertBuild() {
    JobRow j = new JobRow();
    j.fullName = "art-dl/test-" + System.nanoTime();
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

  private long insertArtifact(
      long buildId, String kind, String name, long sizeBytes, String storageRef) {
    return insertArtifact(buildId, kind, name, sizeBytes, storageRef, "fs");
  }

  /**
   * Insert a {@code titan.artifact} row and return its generated id — the production DAO has no
   * insert (writes go through the worker's {@code DbArtifactSink}), so the test goes direct.
   */
  private long insertArtifact(
      long buildId,
      String kind,
      String name,
      long sizeBytes,
      String storageRef,
      String storageKind) {
    return stores.withTransaction(
        conn -> {
          try (java.sql.PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO titan.artifact "
                      + "(build_id, node_id, kind, name, size_bytes, sha256, storage, storage_ref) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                  java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, buildId);
            ps.setString(2, "n1");
            ps.setString(3, kind);
            ps.setString(4, name);
            ps.setLong(5, sizeBytes);
            ps.setString(6, "0000000000000000000000000000000000000000000000000000000000000000");
            ps.setString(7, storageKind);
            ps.setString(8, storageRef);
            ps.executeUpdate();
            try (java.sql.ResultSet keys = ps.getGeneratedKeys()) {
              keys.next();
              return keys.getLong(1);
            }
          } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
          }
        });
  }
}

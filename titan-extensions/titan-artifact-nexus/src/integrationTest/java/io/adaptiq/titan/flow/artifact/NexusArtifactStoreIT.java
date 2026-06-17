package io.adaptiq.titan.flow.artifact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for {@link NexusArtifactStore} against a real {@code sonatype/nexus3}
 * Testcontainer (design/49 §Testing).
 *
 * <p>The fixture waits for Nexus to finish its (slow) first boot, reads the first-boot admin
 * password from {@code /nexus-data/admin.password} inside the container, then creates a raw hosted
 * repository via the Nexus REST API before exercising the store.
 *
 * <p>Needs Docker, so it runs under WSL maven on the dev rig. Nexus boots slowly — the startup
 * timeout below is generous.
 */
@Testcontainers
class NexusArtifactStoreIT {

  private static final String REPO = "titan-artifacts";
  private static final int NEXUS_PORT = 8081;

  // Static so the Testcontainers extension starts it once, before @BeforeAll.
  // Nexus first-boot is slow (schema init, admin password generation); allow several minutes.
  @Container
  private static final GenericContainer<?> nexus =
      new GenericContainer<>("sonatype/nexus3:3.70.1")
          .withExposedPorts(NEXUS_PORT)
          .waitingFor(
              Wait.forHttp("/")
                  .forPort(NEXUS_PORT)
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(8)));

  private static NexusArtifactStore store;
  private static HttpClient http;
  private static String baseUrl;
  private static String adminPassword;
  private static String adminAuth;

  @BeforeAll
  static void setUp() throws Exception {
    baseUrl = "http://" + nexus.getHost() + ":" + nexus.getMappedPort(NEXUS_PORT);

    // Read the first-boot admin password Nexus writes inside the container.
    adminPassword =
        nexus
            .copyFileFromContainer(
                "/nexus-data/admin.password",
                in -> new String(in.readAllBytes(), StandardCharsets.UTF_8))
            .trim();

    http = HttpClient.newHttpClient();
    adminAuth =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(("admin:" + adminPassword).getBytes(StandardCharsets.UTF_8));

    // Create the default raw hosted repository (overwrite-on-PUT, like the rig).
    createRawRepo(REPO, "ALLOW");

    // Build the store the same way NexusArtifactStoreProvider does.
    store = storeForRepo(REPO);
  }

  /**
   * Create a raw hosted repository via the Nexus REST API as the admin user. {@code writePolicy} is
   * {@code ALLOW} (overwrite — the snapshot-like default the rig uses), {@code ALLOW_ONCE} (create
   * but reject redeploy — the immutable release-repo policy), or {@code DENY} (read-only).
   */
  private static void createRawRepo(String name, String writePolicy) throws Exception {
    String createBody =
        "{"
            + "\"name\":\""
            + name
            + "\","
            + "\"online\":true,"
            + "\"storage\":{\"blobStoreName\":\"default\","
            + "\"strictContentTypeValidation\":false,\"writePolicy\":\""
            + writePolicy
            + "\"}"
            + "}";
    HttpResponse<String> create =
        http.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/service/rest/v1/repositories/raw/hosted"))
                .header("Authorization", adminAuth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(createBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (create.statusCode() != 201) {
      throw new IllegalStateException(
          "failed to create raw repo '"
              + name
              + "': HTTP "
              + create.statusCode()
              + " "
              + create.body());
    }
  }

  private static NexusArtifactStore storeForRepo(String repo) {
    Map<String, String> cfg =
        Map.of(
            NexusArtifactStoreProvider.CONFIG_URL, baseUrl,
            NexusArtifactStoreProvider.CONFIG_REPOSITORY, repo,
            NexusArtifactStoreProvider.CONFIG_USERNAME, "admin",
            NexusArtifactStoreProvider.CONFIG_PASSWORD, adminPassword);
    return (NexusArtifactStore) new NexusArtifactStoreProvider().create(cfg);
  }

  /**
   * Independently fetch a stored blob straight from the Nexus raw content endpoint (bypassing the
   * store under test) and return its {@code Content-Length} header alongside the served bytes — the
   * oracle the e2e (spec 49) compares against.
   */
  private static HttpResponse<byte[]> fetchRawFromNexus(String repo, String storageRef)
      throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/repository/" + repo + "/" + storageRef))
            .header("Authorization", adminAuth)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  @Test
  void putOpenRoundTrips() throws Exception {
    byte[] content = "hello from titan".getBytes(StandardCharsets.UTF_8);
    StoredBlob blob =
        store.put(
            new ArtifactKey(1, ArtifactKey.ARTIFACT, "dir/output.txt"),
            new ByteArrayInputStream(content));

    assertEquals(content.length, blob.sizeBytes());
    assertEquals(sha256Hex(content), blob.sha256());
    assertEquals("1/ARTIFACT/dir/output.txt", blob.storageRef());

    Optional<InputStream> opened = store.open(blob.storageRef());
    assertTrue(opened.isPresent());
    try (InputStream in = opened.get()) {
      assertArrayEquals(content, in.readAllBytes());
    }
  }

  @Test
  void openMissingIsEmpty() throws Exception {
    assertTrue(store.open("9999/ARTIFACT/nope.txt").isEmpty());
  }

  @Test
  void overwriteConverges() throws Exception {
    ArtifactKey key = new ArtifactKey(2, ArtifactKey.ARTIFACT, "a.bin");
    store.put(key, new ByteArrayInputStream("first".getBytes(StandardCharsets.UTF_8)));
    byte[] second = "second-and-longer".getBytes(StandardCharsets.UTF_8);
    StoredBlob blob = store.put(key, new ByteArrayInputStream(second));

    assertEquals(second.length, blob.sizeBytes());
    try (InputStream in = store.open(blob.storageRef()).orElseThrow()) {
      assertArrayEquals(second, in.readAllBytes());
    }
  }

  @Test
  void deleteRemovesBlob() throws Exception {
    StoredBlob blob =
        store.put(
            new ArtifactKey(3, ArtifactKey.ARTIFACT, "del.txt"),
            new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
    assertTrue(store.delete(blob.storageRef()));
    assertFalse(store.delete(blob.storageRef()));
    assertTrue(store.open(blob.storageRef()).isEmpty());
  }

  @Test
  void pruneStashesDropsOnlyStashes() throws Exception {
    long buildId = 4;
    store.put(
        new ArtifactKey(buildId, ArtifactKey.STASH, "s1/file"),
        new ByteArrayInputStream("s1".getBytes(StandardCharsets.UTF_8)));
    store.put(
        new ArtifactKey(buildId, ArtifactKey.STASH, "s2/file"),
        new ByteArrayInputStream("s2".getBytes(StandardCharsets.UTF_8)));
    StoredBlob artifact =
        store.put(
            new ArtifactKey(buildId, ArtifactKey.ARTIFACT, "keep.txt"),
            new ByteArrayInputStream("keep".getBytes(StandardCharsets.UTF_8)));

    store.pruneStashes(buildId);

    assertTrue(store.open(buildId + "/STASH/s1/file").isEmpty());
    assertTrue(store.open(buildId + "/STASH/s2/file").isEmpty());
    // The archived artifact survives.
    assertTrue(store.open(artifact.storageRef()).isPresent());
    store.delete(artifact.storageRef());
  }

  @Test
  void deleteBuildDropsEveryBlobOfThatBuildOnly() throws Exception {
    long buildId = 6;
    store.put(
        new ArtifactKey(buildId, ArtifactKey.ARTIFACT, "build/app.jar"),
        new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)));
    store.put(
        new ArtifactKey(buildId, ArtifactKey.STASH, "src/file"),
        new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8)));
    StoredBlob survivor =
        store.put(
            new ArtifactKey(7, ArtifactKey.ARTIFACT, "build/app.jar"),
            new ByteArrayInputStream("c".getBytes(StandardCharsets.UTF_8)));

    store.deleteBuild(buildId);

    assertTrue(store.open(buildId + "/ARTIFACT/build/app.jar").isEmpty(), "artifact reaped");
    assertTrue(store.open(buildId + "/STASH/src/file").isEmpty(), "stash reaped");
    assertTrue(store.open(survivor.storageRef()).isPresent(), "another build untouched");
    store.delete(survivor.storageRef());
  }

  @Test
  void deleteBuildIsIdempotentAndSafeOnAnUnknownBuild() throws Exception {
    store.put(
        new ArtifactKey(8, ArtifactKey.ARTIFACT, "x"),
        new ByteArrayInputStream("y".getBytes(StandardCharsets.UTF_8)));
    store.deleteBuild(8);
    store.deleteBuild(8); // a reaper retried after a crash converges
    store.deleteBuild(424242); // a build that stored nothing
  }

  @Test
  void putRecordsMetadataMatchingNexusServedContent() throws Exception {
    // The contract spec 49's e2e relies on: the size + SHA-256 that put() records in StoredBlob
    // are exactly what an INDEPENDENT reader (here, a raw GET straight at Nexus, bypassing the
    // store) observes — same Content-Length header, same byte-for-byte SHA-256. If they ever
    // diverge, the e2e's checksum oracle would be comparing against a lie.
    byte[] content = "metadata-contract-éèê".getBytes(StandardCharsets.UTF_8);
    StoredBlob blob =
        store.put(
            new ArtifactKey(42, ArtifactKey.ARTIFACT, "meta/contract.bin"),
            new ByteArrayInputStream(content));

    HttpResponse<byte[]> served = fetchRawFromNexus(REPO, blob.storageRef());
    assertEquals(200, served.statusCode(), "Nexus did not serve the just-stored blob");

    long servedLength =
        served
            .headers()
            .firstValueAsLong("content-length")
            .orElseThrow(() -> new AssertionError("Nexus response carried no Content-Length"));
    assertEquals(content.length, servedLength, "Content-Length mismatch vs stored bytes");
    assertEquals(content.length, blob.sizeBytes(), "StoredBlob.sizeBytes mismatch vs stored bytes");
    assertEquals(servedLength, blob.sizeBytes(), "StoredBlob.sizeBytes mismatch vs Nexus");

    assertEquals(sha256Hex(content), blob.sha256(), "StoredBlob.sha256 mismatch vs stored bytes");
    assertEquals(
        sha256Hex(served.body()),
        blob.sha256(),
        "SHA-256 of Nexus-served bytes mismatch vs StoredBlob.sha256");

    store.delete(blob.storageRef());
  }

  @Test
  void republishSameCoordinatesToImmutableRepoIsRejected() throws Exception {
    // Adversarial repo-policy test (acceptance criterion 4). A raw hosted repo with
    // writePolicy=ALLOW_ONCE is the "release / immutable" policy: create succeeds, redeploy of the
    // SAME path is refused (HTTP 400). The store must surface that as an IOException, never
    // silently swallow it as a converged overwrite (the snapshot-repo behaviour, covered by
    // overwriteConverges above).
    String immutableRepo = "titan-artifacts-immutable";
    createRawRepo(immutableRepo, "ALLOW_ONCE");
    NexusArtifactStore immutable = storeForRepo(immutableRepo);

    ArtifactKey key = new ArtifactKey(99, ArtifactKey.ARTIFACT, "release/app-1.0.0.jar");
    StoredBlob first =
        immutable.put(key, new ByteArrayInputStream("v1".getBytes(StandardCharsets.UTF_8)));
    assertEquals("99/ARTIFACT/release/app-1.0.0.jar", first.storageRef());

    IOException rejected =
        assertThrows(
            IOException.class,
            () ->
                immutable.put(key, new ByteArrayInputStream("v2".getBytes(StandardCharsets.UTF_8))),
            "redeploy to an ALLOW_ONCE (immutable) repo must fail, not converge");
    assertTrue(
        rejected.getMessage().contains("nexus"),
        "IOException should name the failing nexus put: " + rejected.getMessage());

    // The original coordinates still serve the FIRST bytes — the rejected redeploy left them
    // intact.
    try (InputStream in = immutable.open(first.storageRef()).orElseThrow()) {
      assertArrayEquals("v1".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
    }
  }

  @Test
  void traversalRejected() {
    // Defence in depth — ArtifactKey rejects a '..' segment at construction, before any store
    // call can be made with it.
    assertThrows(
        IllegalArgumentException.class,
        () -> new ArtifactKey(5, ArtifactKey.ARTIFACT, "../escape.txt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ArtifactKey(5, ArtifactKey.ARTIFACT, "a/../../escape.txt"));
  }

  private static String sha256Hex(byte[] content) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
  }
}

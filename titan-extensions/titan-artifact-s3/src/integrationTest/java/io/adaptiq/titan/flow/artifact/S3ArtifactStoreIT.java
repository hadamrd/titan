package io.adaptiq.titan.flow.artifact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for {@link S3ArtifactStore} against MinIO via Testcontainers — MinIO is the S3
 * API, API-identical to AWS S3 / Cloudflare R2 for the store's purposes (design/46 §Testing).
 *
 * <p>Needs Docker, so it runs under WSL maven on the dev rig.
 */
@Testcontainers
class S3ArtifactStoreIT {

  private static final String BUCKET = "titan-artifacts";

  // Static so the Testcontainers extension starts it once, before @BeforeAll.
  // A 2025 MinIO release — supports the AWS SDK v2 flexible (trailing CRC32)
  // checksums the SDK now sends; older releases demand the dropped Content-MD5.
  @Container
  private static final MinIOContainer minio =
      new MinIOContainer("minio/minio:RELEASE.2025-04-22T22-12-26Z");

  private static S3ArtifactStore store;
  // An independent admin client, kept around so tests can inspect raw object
  // metadata (HeadObject) the way an out-of-band verifier (e.g. the e2e R2
  // round-trip in spec-54, #1226) does — i.e. NOT through the store's own API.
  private static software.amazon.awssdk.services.s3.S3Client admin;

  @BeforeAll
  static void setUp() {
    // Build the store the same way S3ArtifactStoreProvider does — path-style addressing is
    // required for MinIO (no virtual-host DNS), exactly as for many R2 setups.
    Map<String, String> cfg =
        Map.of(
            S3ArtifactStoreProvider.CONFIG_BUCKET,
            BUCKET,
            S3ArtifactStoreProvider.CONFIG_ENDPOINT,
            minio.getS3URL(),
            S3ArtifactStoreProvider.CONFIG_REGION,
            "us-east-1",
            S3ArtifactStoreProvider.CONFIG_ACCESS_KEY,
            minio.getUserName(),
            S3ArtifactStoreProvider.CONFIG_SECRET_KEY,
            minio.getPassword(),
            S3ArtifactStoreProvider.CONFIG_PATH_STYLE,
            "true");
    store = (S3ArtifactStore) new S3ArtifactStoreProvider().create(cfg);
    // Create the bucket — the provider does not (a bucket is a deployment artifact).
    admin =
        software.amazon.awssdk.services.s3.S3Client.builder()
            .httpClient(
                software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.builder().build())
            .region(software.amazon.awssdk.regions.Region.US_EAST_1)
            .endpointOverride(java.net.URI.create(minio.getS3URL()))
            .credentialsProvider(
                software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                    software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                        minio.getUserName(), minio.getPassword())))
            .serviceConfiguration(
                software.amazon.awssdk.services.s3.S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build())
            .build();
    admin.createBucket(b -> b.bucket(BUCKET));
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

    // The returned StoredBlob round-trips through open().
    Optional<InputStream> opened = store.open(blob.storageRef());
    assertTrue(opened.isPresent());
    try (InputStream in = opened.get()) {
      assertArrayEquals(content, in.readAllBytes());
    }
  }

  @Test
  void putSetsContentLengthAndChecksumMetadataOnTheStoredObject() throws Exception {
    // #1226: an independent verifier (the e2e R2 round-trip) reads the object's
    // metadata straight from the bucket and compares it to what the build wrote.
    // For that to mean anything, put() must land an object whose server-side
    // metadata actually reflects the bytes — a non-zero Content-Length equal to
    // the payload and a non-blank ETag (S3's per-object integrity checksum).
    // This test pins that contract from OUTSIDE the store, via a raw HeadObject,
    // so a regression to a truncated/zero-length/fallback write is caught here
    // and not only out on the rig.
    byte[] content = "independent-verify-oracle".getBytes(StandardCharsets.UTF_8);
    StoredBlob blob =
        store.put(
            new ArtifactKey(11, ArtifactKey.ARTIFACT, "verify/object.bin"),
            new ByteArrayInputStream(content));

    software.amazon.awssdk.services.s3.model.HeadObjectResponse head =
        admin.headObject(b -> b.bucket(BUCKET).key(blob.storageRef()));

    assertEquals(
        (long) content.length,
        head.contentLength().longValue(),
        "stored object Content-Length must equal the payload length");
    assertEquals(
        content.length,
        blob.sizeBytes(),
        "StoredBlob.sizeBytes must agree with the bytes actually written");
    assertNotNull(head.eTag(), "stored object must carry an ETag (checksum metadata)");
    assertFalse(head.eTag().isBlank(), "stored object ETag must not be blank");

    store.delete(blob.storageRef());
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
    long buildId = 8;
    store.put(
        new ArtifactKey(buildId, ArtifactKey.ARTIFACT, "build/app.jar"),
        new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)));
    store.put(
        new ArtifactKey(buildId, ArtifactKey.STASH, "src/file"),
        new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8)));
    StoredBlob survivor =
        store.put(
            new ArtifactKey(9, ArtifactKey.ARTIFACT, "build/app.jar"),
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
        new ArtifactKey(7, ArtifactKey.ARTIFACT, "x"),
        new ByteArrayInputStream("y".getBytes(StandardCharsets.UTF_8)));
    store.deleteBuild(7);
    store.deleteBuild(7); // a reaper retried after a crash converges
    store.deleteBuild(424242); // a build that stored nothing
  }

  private static String sha256Hex(byte[] content) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
  }
}

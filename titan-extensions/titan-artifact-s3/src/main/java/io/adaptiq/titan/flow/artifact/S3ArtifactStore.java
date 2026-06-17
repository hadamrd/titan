package io.adaptiq.titan.flow.artifact;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * An {@link ArtifactStore} backed by an S3-compatible object store — real AWS S3 or Cloudflare R2
 * (design/46). One backend serves both: R2 is the S3 API plus a configured {@code endpoint}.
 *
 * <p><strong>Reachability.</strong> Unlike {@link FilesystemArtifactStore}, this store needs no
 * shared volume — the bucket is reachable over HTTPS, so workers and the controller talk to it
 * independently. That removes the single constraint bounding distributed Titan (design/46 §Why).
 *
 * <p><strong>Layout (D5).</strong> The {@code storageRef} is the S3 object key {@code
 * <buildId>/<kind>/<name>} — the same layout as the {@code fs} store. It is opaque to callers,
 * persisted verbatim in {@code titan.artifact.storage_ref}.
 *
 * <p><strong>{@code put} buffers to a temp file (D4).</strong> {@link #put} streams the content
 * once through a {@link DigestInputStream} into a JVM temp file — computing the SHA-256 in that
 * single pass, exactly as the {@code fs} store does — then issues one {@code PutObject} with the
 * now-known length, then deletes the temp file. No multipart complexity. A single {@code PutObject}
 * caps at 5 GiB; multipart upload for larger blobs is a documented future enhancement (design/46
 * §Out of scope), not in this chunk. {@code PutObject} overwrites, so a re-run of the producing
 * step converges.
 *
 * <p><strong>{@code pruneStashes} / {@code deleteBuild}.</strong> Both are a prefix delete — {@code
 * ListObjectsV2} under {@code <buildId>/STASH/} or the whole {@code <buildId>/}, then {@code
 * DeleteObjects} in batches of 1000.
 */
public final class S3ArtifactStore implements ArtifactStore {

  private static final String KIND = "s3";

  /** S3 {@code DeleteObjects} accepts at most 1000 keys per request. */
  private static final int DELETE_BATCH = 1000;

  private final S3Client s3;
  private final String bucket;

  /**
   * @param s3 the configured S3 client — owns the credentials, region and endpoint
   * @param bucket the bucket every artifact and stash of this store lives in
   */
  public S3ArtifactStore(@NonNull S3Client s3, @NonNull String bucket) {
    this.s3 = s3;
    this.bucket = bucket;
  }

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public StoredBlob put(@NonNull ArtifactKey key, @NonNull InputStream content) throws IOException {
    String ref = key.buildId() + "/" + key.kind() + "/" + key.name().replace('\\', '/');

    // D4: stream once into a temp file, folding a SHA-256 digest into the same pass. A single
    // S3 PutObject needs a known content length, which the InputStream does not carry.
    MessageDigest digest = sha256();
    Path tmp = Files.createTempFile(".titan-art-", ".tmp");
    long size;
    try {
      try (DigestInputStream in = new DigestInputStream(content, digest);
          OutputStream out = Files.newOutputStream(tmp)) {
        size = in.transferTo(out);
      }
      try {
        s3.putObject(b -> b.bucket(bucket).key(ref), RequestBody.fromFile(tmp));
      } catch (S3Exception e) {
        throw new IOException("failed to put artifact to s3://" + bucket + "/" + ref, e);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
    return new StoredBlob(size, HexFormat.of().formatHex(digest.digest()), ref);
  }

  @Override
  public Optional<InputStream> open(@NonNull String storageRef) throws IOException {
    try {
      ResponseInputStream<GetObjectResponse> in =
          s3.getObject(b -> b.bucket(bucket).key(storageRef));
      return Optional.of(in);
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (S3Exception e) {
      throw new IOException("failed to open s3://" + bucket + "/" + storageRef, e);
    }
  }

  @Override
  public boolean delete(@NonNull String storageRef) throws IOException {
    boolean existed;
    try {
      s3.headObject(b -> b.bucket(bucket).key(storageRef));
      existed = true;
    } catch (NoSuchKeyException e) {
      existed = false;
    } catch (S3Exception e) {
      throw new IOException("failed to stat s3://" + bucket + "/" + storageRef, e);
    }
    if (!existed) {
      return false;
    }
    try {
      s3.deleteObject(b -> b.bucket(bucket).key(storageRef));
    } catch (S3Exception e) {
      throw new IOException("failed to delete s3://" + bucket + "/" + storageRef, e);
    }
    return true;
  }

  @Override
  public void pruneStashes(long buildId) throws IOException {
    try {
      deletePrefix(buildId + "/" + ArtifactKey.STASH + "/");
    } catch (S3Exception e) {
      throw new IOException("failed to prune stashes for build " + buildId, e);
    }
  }

  @Override
  public void deleteBuild(long buildId) throws IOException {
    // Layout is <buildId>/<kind>/<name>, so the whole build is one prefix.
    try {
      deletePrefix(buildId + "/");
    } catch (S3Exception e) {
      throw new IOException("failed to delete build " + buildId, e);
    }
  }

  /**
   * {@code ListObjectsV2} every key under {@code prefix}, then {@code DeleteObjects} in batches.
   */
  private void deletePrefix(String prefix) {
    List<ObjectIdentifier> batch = new ArrayList<>(DELETE_BATCH);
    ListObjectsV2Request.Builder list =
        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix);
    String continuation = null;
    do {
      ListObjectsV2Response page = s3.listObjectsV2(list.continuationToken(continuation).build());
      for (S3Object o : page.contents()) {
        batch.add(ObjectIdentifier.builder().key(o.key()).build());
        if (batch.size() == DELETE_BATCH) {
          deleteBatch(batch);
          batch.clear();
        }
      }
      continuation = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
    } while (continuation != null);
    if (!batch.isEmpty()) {
      deleteBatch(batch);
    }
  }

  /**
   * Close the underlying {@link S3Client} — releases its HTTP connection pool. Called when this
   * store is evicted from the controller's per-kind cache (a JCasC reconfigure) or when a worker
   * shuts down; without it every reconfigure would leak a pool.
   */
  @Override
  public void close() {
    s3.close();
  }

  private void deleteBatch(List<ObjectIdentifier> keys) {
    s3.deleteObjects(
        DeleteObjectsRequest.builder()
            .bucket(bucket)
            .delete(Delete.builder().objects(keys).build())
            .build());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable in this JVM", e); // never happens
    }
  }
}

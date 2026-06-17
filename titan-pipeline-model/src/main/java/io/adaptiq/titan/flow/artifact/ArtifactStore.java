package io.adaptiq.titan.flow.artifact;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Persists and serves build artifacts and stashes — the pluggable storage backend behind {@code
 * archiveArtifacts} / {@code stash} (design/41 §3, decision E2).
 *
 * <p><strong>Shape of the SPI.</strong> A Titan worker is channel-less and has no run-bound
 * launcher (design/41 §0a), so the backend SPI is intentionally minimal: a content key, bytes in,
 * bytes out, delete — and nothing format-aware (design/41 E9).
 *
 * <p><strong>Used by both sides.</strong> The worker calls {@link #put} (it holds the workspace
 * files and a direct connection); the controller calls {@link #open} to serve the artifact browser
 * (design/41 §8.4). An implementation therefore runs on a worker and on the controller.
 *
 * <p><strong>Locator discipline.</strong> {@link #put} takes the semantic {@link ArtifactKey} and
 * returns a {@link StoredBlob} carrying a backend-minted {@code storageRef}; {@link #open} and
 * {@link #delete} take that {@code storageRef} back. The store is the sole authority on its own
 * locator format — callers persist it ({@code titan.artifact.storage_ref}) and never parse it.
 *
 * <p>Implementations: {@code FilesystemArtifactStore} (the default — design/41 E2), a
 * Postgres-large-object store, S3/GCS/Azure, Nexus/Artifactory raw repos (§8.6). All are selected
 * by controller config; the backend is a deployment choice, not a code change.
 *
 * <p><strong>Lifecycle.</strong> A store may hold resources that outlive a single call — an S3
 * client owns an HTTP connection pool, for instance. {@code ArtifactStore} is therefore {@link
 * Closeable}: a holder that caches stores (the controller's {@code ArtifactStores}, the worker)
 * must {@link #close()} a store it evicts or discards. The default {@link #close()} is a no-op, so
 * a resource-free backend like {@code FilesystemArtifactStore} need not implement it.
 */
public interface ArtifactStore extends Closeable {

  /**
   * The short discriminator written to {@code titan.artifact.storage} — {@code "fs"}, {@code "pg"},
   * {@code "s3"}, … A read dispatches to the store whose {@code kind()} matches the row.
   */
  String kind();

  /**
   * Stream {@code content} into the store under {@code key}, computing its SHA-256 in the same
   * pass. Overwrites any blob previously stored under the same key — so a re-run of the producing
   * step converges (design/32 §4 idempotency). The caller is responsible for closing {@code
   * content}.
   *
   * @return the stored size, the content SHA-256, and the backend locator to persist
   * @throws IOException if the content cannot be stored
   */
  StoredBlob put(ArtifactKey key, InputStream content) throws IOException;

  /**
   * Open the blob a prior {@link #put} stored, addressed by the {@link StoredBlob#storageRef()} it
   * returned. Empty if no blob is stored there. The caller closes the stream.
   *
   * @throws IOException if the blob exists but cannot be read
   */
  Optional<InputStream> open(String storageRef) throws IOException;

  /**
   * Delete the blob at {@code storageRef}.
   *
   * @return {@code true} if a blob existed and was deleted, {@code false} if there was none
   * @throws IOException if a blob exists but cannot be deleted
   */
  boolean delete(String storageRef) throws IOException;

  /**
   * Drop every {@link ArtifactKey#STASH}-kind blob for a build. A stash is build-scoped — it exists
   * only to hand files between stages of one build — so the build finaliser calls this when the
   * build reaches a terminal state (design/41 E5). Archived artifacts are untouched.
   *
   * @throws IOException if the stashes cannot be removed
   */
  void pruneStashes(long buildId) throws IOException;

  /**
   * Drop <em>every</em> blob a build owns in this store — archived artifacts and stashes alike.
   * Called by the build reaper when a retention policy ({@code BuildDiscarder} / {@code
   * LogRotator}) drops a whole build (design/53). After this returns the build owns no storage in
   * this backend.
   *
   * <p>All three layouts ({@code fs}, {@code s3}, {@code nexus}) key blobs under a {@code
   * <buildId>/} prefix, so this is a single prefix delete — the same shape as {@link
   * #pruneStashes}, one level up. It is idempotent: a build with nothing stored, or a second call
   * after the first, is a no-op, so a reaper retried after a crash converges.
   *
   * @throws IOException if the build's blobs cannot be removed
   */
  void deleteBuild(long buildId) throws IOException;

  /**
   * Release any resources the store holds open — connection pools, clients, file handles. Called by
   * whatever caches the store when it is evicted (e.g. on a JCasC reconfigure) or discarded.
   *
   * <p>Default: a no-op, for backends that hold nothing open between calls (the filesystem store).
   * A backend with a long-lived client (S3, …) overrides this to close it.
   */
  @Override
  default void close() throws IOException {
    // No-op by default — resource-free backends need not implement this.
  }
}

package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.artifact} (design/41 32E-2). Metadata only — the
 * blob's bytes live in the {@code ArtifactStore}, located by {@link #storage} + {@link
 * #storageRef}.
 */
public class ArtifactRow {
  public long id;
  public long buildId;

  @Nullable public String nodeId;

  /** {@code ARTIFACT} (browsable, persists with the build) or {@code STASH} (build-scoped). */
  public String kind;

  /** Workspace-relative path for an artifact, or the stash name for a stash. */
  public String name;

  public long sizeBytes;
  public String sha256;

  /**
   * The {@code ArtifactStore} backend the bytes are in — {@code fs} / {@code pg} / {@code s3} / …
   */
  public String storage;

  /** Backend-specific locator: a filesystem path, an object key, a large-object oid. */
  public String storageRef;

  public Instant createdAt;
}

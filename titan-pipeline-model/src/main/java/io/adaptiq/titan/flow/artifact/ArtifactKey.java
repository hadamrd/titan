package io.adaptiq.titan.flow.artifact;

/**
 * The semantic identity of one stored blob — what an {@link ArtifactStore} is asked to persist
 * (design/41 §3, 32E-2).
 *
 * <p>It is <em>not</em> the storage locator: {@link ArtifactStore#put} takes an {@code ArtifactKey}
 * and returns a {@link StoredBlob} whose {@code storageRef} is the backend's own locator. The key
 * carries only the build-domain coordinates a store uses to lay a blob out — for the filesystem
 * store, {@code <root>/<buildId>/<kind>/<name>}.
 *
 * @param buildId the owning build ({@code titan.builds.id})
 * @param kind {@link #ARTIFACT} (persists with the build) or {@link #STASH} (build-scoped, pruned
 *     at build end — design/41 E5)
 * @param name the workspace-relative path for an artifact, or the stash name for a stash; may
 *     contain {@code /} but never a {@code ..} segment or a leading {@code /}
 */
public record ArtifactKey(long buildId, String kind, String name) {

  /** A {@code kind} value — an archived artifact, which persists with the build record. */
  public static final String ARTIFACT = "ARTIFACT";

  /** A {@code kind} value — a stash, build-scoped and pruned when the build finishes. */
  public static final String STASH = "STASH";

  public ArtifactKey {
    if (buildId <= 0) {
      throw new IllegalArgumentException("buildId must be positive: " + buildId);
    }
    if (!ARTIFACT.equals(kind) && !STASH.equals(kind)) {
      throw new IllegalArgumentException("kind must be ARTIFACT or STASH: " + kind);
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    // Defence in depth: name is a workspace-relative path; a traversal segment or an absolute
    // path would let a blob escape the store root. Stores also guard, but reject it at the door.
    String normalised = name.replace('\\', '/');
    if (normalised.startsWith("/")
        || normalised.equals("..")
        || normalised.startsWith("../")
        || normalised.endsWith("/..")
        || normalised.contains("/../")) {
      throw new IllegalArgumentException("name must be a relative path with no '..': " + name);
    }
  }
}

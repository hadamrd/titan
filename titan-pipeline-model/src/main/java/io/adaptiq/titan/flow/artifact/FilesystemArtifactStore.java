package io.adaptiq.titan.flow.artifact;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The default {@link ArtifactStore} — blobs on a mounted filesystem (design/41 E2 / §8.6).
 *
 * <p>It is the default for a hard reason: the Titan schema is locked H2/PostgreSQL-portable (V1),
 * and a filesystem store needs no binary DDL at all. Every install has a disk; the backend is the
 * directory it points at — a local disk, an NFS export, a CephFS / RBD volume, a Kubernetes PVC.
 * The store sees only a {@link Path}.
 *
 * <p><strong>Reachability (design/41 §8.6).</strong> The worker writes and the controller reads, so
 * in a distributed deployment the {@code root} must be a ReadWriteMany mount both reach at the same
 * path; a single-writer RBD block device works only when worker and controller are co-located. This
 * class does not police that — it is a deployment property — but it is the documented contract.
 *
 * <p>Layout: {@code <root>/<buildId>/<kind>/<name>}. {@code name} may contain {@code /}; it never
 * escapes {@code root} — every resolved path is checked against the root, and {@link ArtifactKey}
 * already rejects {@code ..}. Writes are atomic: content streams to a sibling temp file and is then
 * moved into place, so a concurrent reader never sees a half-written blob and a re-run cleanly
 * overwrites.
 */
public final class FilesystemArtifactStore implements ArtifactStore {

  private static final String KIND = "fs";

  private final Path root;

  /**
   * @param root the artifacts directory — created on first write if absent. Must be distinct from
   *     the build workspace root: artifacts persist with the build, workspaces are reaped.
   */
  public FilesystemArtifactStore(@NonNull Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public StoredBlob put(@NonNull ArtifactKey key, @NonNull InputStream content) throws IOException {
    String ref = key.buildId() + "/" + key.kind() + "/" + key.name().replace('\\', '/');
    Path target = resolve(ref);
    Files.createDirectories(target.getParent());

    // Stream once: a sibling temp file for the bytes, a SHA-256 digest folded into the same
    // pass. transferTo() returns the byte count, so size is never a second stat() call.
    MessageDigest digest = sha256();
    Path tmp = Files.createTempFile(target.getParent(), ".titan-art-", ".tmp");
    long size;
    try {
      try (DigestInputStream in = new DigestInputStream(content, digest);
          OutputStream out = Files.newOutputStream(tmp)) {
        size = in.transferTo(out);
      }
      move(tmp, target);
    } catch (IOException | RuntimeException e) {
      Files.deleteIfExists(tmp);
      throw e;
    }
    grantSharedRead(target);
    return new StoredBlob(size, HexFormat.of().formatHex(digest.digest()), ref);
  }

  /**
   * Make a freshly stored blob readable by the controller. The worker (which writes) and the
   * controller (which serves downloads) run as different uids on the shared volume, and {@link
   * Files#createTempFile} yields an owner-only {@code 0600} file — after the atomic move the
   * artifact keeps that, so the controller cannot read it. Widen the file to {@code rw-r--r--} and
   * its directory chain, up to (but not including) the store root, to {@code rwxr-xr-x}. POSIX-only
   * and best-effort: on a filesystem without POSIX permissions (a Windows test) it is a no-op, and
   * there the cross-uid problem does not arise.
   */
  private void grantSharedRead(Path file) {
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
      for (Path dir = file.getParent();
          dir != null && dir.startsWith(root) && !dir.equals(root);
          dir = dir.getParent()) {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
      }
    } catch (UnsupportedOperationException | IOException e) {
      // Non-POSIX filesystem, or a path this process does not own — best-effort.
    }
  }

  @Override
  public Optional<InputStream> open(@NonNull String storageRef) throws IOException {
    Path target = resolve(storageRef);
    if (!Files.isRegularFile(target)) {
      return Optional.empty();
    }
    return Optional.of(Files.newInputStream(target));
  }

  @Override
  public boolean delete(@NonNull String storageRef) throws IOException {
    return Files.deleteIfExists(resolve(storageRef));
  }

  @Override
  public void pruneStashes(long buildId) throws IOException {
    deleteTree(resolve(buildId + "/" + ArtifactKey.STASH));
  }

  @Override
  public void deleteBuild(long buildId) throws IOException {
    // Layout is <root>/<buildId>/<kind>/<name>, so the whole build is one subtree.
    deleteTree(resolve(String.valueOf(buildId)));
  }

  /** Recursively delete {@code dir} and its contents; a no-op if it does not exist. */
  private static void deleteTree(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return;
    }
    // Depth-first delete — children before their parent.
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
        Files.deleteIfExists(p);
      }
    }
  }

  /**
   * Resolve a store-relative locator under {@code root} and verify it stays inside it — defence in
   * depth against a traversal that slipped past {@link ArtifactKey}'s own check.
   */
  private Path resolve(@NonNull String relative) {
    Path resolved = root.resolve(relative).normalize();
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException("artifact locator escapes the store root: " + relative);
    }
    return resolved;
  }

  private static void move(Path tmp, Path target) throws IOException {
    try {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      // Some filesystems cannot move atomically; tmp is a sibling of target, so a plain
      // replacing move is still a single rename within one directory.
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable in this JVM", e); // never happens
    }
  }
}

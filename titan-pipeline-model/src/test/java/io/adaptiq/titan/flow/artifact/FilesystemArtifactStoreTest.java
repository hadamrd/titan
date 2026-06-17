package io.adaptiq.titan.flow.artifact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract tests for {@link FilesystemArtifactStore} — the default {@link ArtifactStore} (design/41
 * §8.6). Covers the round-trip, the streamed SHA-256, idempotent overwrite, the stash/artifact
 * lifetime split, and the path-traversal guard.
 */
class FilesystemArtifactStoreTest {

  // SHA-256 of the ASCII string "hello" — the well-known vector.
  private static final String HELLO_SHA256 =
      "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

  private static InputStream bytes(String s) {
    return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
  }

  private static String read(InputStream in) throws IOException {
    try (in) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void putThenOpenRoundTripsContentAndComputesSizeAndSha(@TempDir Path root) throws IOException {
    FilesystemArtifactStore store = new FilesystemArtifactStore(root);
    ArtifactKey key = new ArtifactKey(7, ArtifactKey.ARTIFACT, "build/app.jar");

    StoredBlob blob = store.put(key, bytes("hello"));

    assertEquals(5, blob.sizeBytes());
    assertEquals(HELLO_SHA256, blob.sha256(), "SHA-256 is computed in the same stream pass");
    assertEquals("7/ARTIFACT/build/app.jar", blob.storageRef());

    Optional<InputStream> opened = store.open(blob.storageRef());
    assertTrue(opened.isPresent());
    assertEquals("hello", read(opened.get()));
  }

  @Test
  void putOverwritesTheSameKeyIdempotently(@TempDir Path root) throws IOException {
    FilesystemArtifactStore store = new FilesystemArtifactStore(root);
    ArtifactKey key = new ArtifactKey(1, ArtifactKey.ARTIFACT, "out.txt");

    store.put(key, bytes("first"));
    StoredBlob second = store.put(key, bytes("second-and-longer"));

    // A re-run of the producing step converges — same locator, new content (design/32 §4).
    assertEquals("second-and-longer", read(store.open(second.storageRef()).orElseThrow()));
  }

  @Test
  void openOfAMissingBlobIsEmpty(@TempDir Path root) throws IOException {
    assertTrue(new FilesystemArtifactStore(root).open("1/ARTIFACT/none").isEmpty());
  }

  @Test
  void deleteReportsWhetherABlobExisted(@TempDir Path root) throws IOException {
    FilesystemArtifactStore store = new FilesystemArtifactStore(root);
    StoredBlob blob = store.put(new ArtifactKey(1, ArtifactKey.ARTIFACT, "x"), bytes("y"));

    assertTrue(store.delete(blob.storageRef()), "first delete removes it");
    assertFalse(store.delete(blob.storageRef()), "second delete finds nothing");
    assertTrue(store.open(blob.storageRef()).isEmpty());
  }

  @Test
  void pruneStashesDropsStashesButKeepsArtifacts(@TempDir Path root) throws IOException {
    FilesystemArtifactStore store = new FilesystemArtifactStore(root);
    StoredBlob artifact = store.put(new ArtifactKey(9, ArtifactKey.ARTIFACT, "keep"), bytes("a"));
    StoredBlob stash = store.put(new ArtifactKey(9, ArtifactKey.STASH, "src"), bytes("b"));

    store.pruneStashes(9);

    assertTrue(store.open(stash.storageRef()).isEmpty(), "the stash is pruned");
    assertTrue(store.open(artifact.storageRef()).isPresent(), "the artifact survives");
  }

  @Test
  void pruneStashesIsANoOpWhenThereAreNoStashes(@TempDir Path root) throws IOException {
    new FilesystemArtifactStore(root).pruneStashes(123); // must not throw
  }

  @Test
  void deleteBuildDropsEveryBlobOfThatBuildOnly(@TempDir Path root) throws IOException {
    FilesystemArtifactStore store = new FilesystemArtifactStore(root);
    StoredBlob artifact =
        store.put(new ArtifactKey(9, ArtifactKey.ARTIFACT, "build/app.jar"), bytes("a"));
    StoredBlob stash = store.put(new ArtifactKey(9, ArtifactKey.STASH, "src"), bytes("b"));
    StoredBlob other =
        store.put(new ArtifactKey(10, ArtifactKey.ARTIFACT, "build/app.jar"), bytes("c"));

    store.deleteBuild(9);

    assertTrue(store.open(artifact.storageRef()).isEmpty(), "the build's artifact is reaped");
    assertTrue(store.open(stash.storageRef()).isEmpty(), "the build's stash is reaped too");
    assertTrue(store.open(other.storageRef()).isPresent(), "another build's blobs are untouched");
  }

  @Test
  void deleteBuildIsIdempotentAndSafeOnAnUnknownBuild(@TempDir Path root) throws IOException {
    FilesystemArtifactStore store = new FilesystemArtifactStore(root);
    store.put(new ArtifactKey(5, ArtifactKey.ARTIFACT, "x"), bytes("y"));

    store.deleteBuild(5);
    store.deleteBuild(5); // a reaper retried after a crash converges
    store.deleteBuild(999); // a build that stored nothing
  }

  @Test
  void aTraversingNameIsRejectedByTheKey() {
    // ArtifactKey is the first gate — a name with '..' never reaches a store.
    assertThrows(
        IllegalArgumentException.class,
        () -> new ArtifactKey(1, ArtifactKey.ARTIFACT, "../../etc/passwd"));
  }

  @Test
  void aTraversingStorageRefIsRejectedOnOpen(@TempDir Path root) {
    // Defence in depth: even a hand-crafted locator cannot escape the root.
    FilesystemArtifactStore store = new FilesystemArtifactStore(root);
    assertThrows(IllegalArgumentException.class, () -> store.open("../../../etc/passwd"));
  }

  @Test
  void binaryContentRoundTripsByteForByte(@TempDir Path root) throws IOException {
    FilesystemArtifactStore store = new FilesystemArtifactStore(root);
    byte[] payload = new byte[4096];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = (byte) (i * 31 + 7);
    }
    StoredBlob blob =
        store.put(
            new ArtifactKey(2, ArtifactKey.ARTIFACT, "bin/data"),
            new ByteArrayInputStream(payload));
    assertEquals(payload.length, blob.sizeBytes());
    try (InputStream in = store.open(blob.storageRef()).orElseThrow()) {
      assertArrayEquals(payload, in.readAllBytes());
    }
  }
}

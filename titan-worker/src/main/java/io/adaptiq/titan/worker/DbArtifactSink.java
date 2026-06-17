package io.adaptiq.titan.worker;

import io.adaptiq.titan.flow.artifact.ArtifactKey;
import io.adaptiq.titan.flow.artifact.ArtifactStore;
import io.adaptiq.titan.flow.artifact.StoredBlob;
import io.adaptiq.titan.worker.step.ArtifactSink;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

/**
 * The worker's {@link ArtifactSink} — the bridge from a {@link
 * io.adaptiq.titan.worker.step.StepHandler}'s {@code archive(...)} call to the configured {@link
 * ArtifactStore} and the {@code titan.artifact} table (design/41 §8.5, 32E-2).
 *
 * <p>It owns the build/node coordinates and the store + DB handles so a handler never has to — that
 * is what keeps a handler free of worker internals (design/32 §3.2). One sink is built per task by
 * {@code TaskExecutor}.
 *
 * <p><strong>Order matters.</strong> The blob is stored <em>before</em> the row is written: a
 * reader (the controller's artifact browser) following a {@code titan.artifact} row must always
 * find its bytes. If the row write then fails, the blob is an orphan — but the {@code storageRef}
 * is deterministic ({@code buildId/ARTIFACT/name}), so a re-run overwrites that exact blob rather
 * than accumulating orphans. Unlike {@code DbLogSink}, failures here are <em>not</em> swallowed:
 * losing a build's artifact is a real failure and must fail the step (the contract — {@link
 * ArtifactSink#archive} declares {@code throws IOException}).
 */
final class DbArtifactSink implements ArtifactSink {

  private final ArtifactStore store;
  private final WorkerDb db;
  private final long buildId;
  private final String nodeId;

  DbArtifactSink(ArtifactStore store, WorkerDb db, long buildId, String nodeId) {
    this.store = store;
    this.db = db;
    this.buildId = buildId;
    this.nodeId = nodeId;
  }

  @Override
  public void archive(String name, Path file, boolean fingerprint) throws IOException {
    if (!Files.isRegularFile(file)) {
      throw new IOException("cannot archive '" + name + "': not a readable file (" + file + ")");
    }

    ArtifactKey key = new ArtifactKey(buildId, ArtifactKey.ARTIFACT, name);
    StoredBlob blob;
    try (InputStream in = Files.newInputStream(file)) {
      blob = store.put(key, in); // bytes first — a row must never out-exist its content
    }

    try {
      db.upsertArtifact(
          buildId,
          nodeId,
          ArtifactKey.ARTIFACT,
          name,
          blob.sizeBytes(),
          blob.sha256(),
          store.kind(),
          blob.storageRef());
      if (fingerprint) {
        db.recordFingerprint(blob.sha256(), name, buildId, nodeId);
      }
    } catch (SQLException e) {
      throw new IOException(
          "stored artifact '" + name + "' but failed to record it: " + e.getMessage(), e);
    }
  }
}

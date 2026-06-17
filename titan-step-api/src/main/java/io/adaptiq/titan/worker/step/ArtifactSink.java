package io.adaptiq.titan.worker.step;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The handler-facing collaborator for publishing build artifacts — the {@code archiveArtifacts}
 * side of the {@link StepRequest} contract (design/41 §5 / §8.5).
 *
 * <p>It is the same pattern as {@link LogSink} and {@link OutputSink}: a {@link StepHandler}
 * <em>declares</em> intent through the sink; the <em>worker</em> owns the storage and the database
 * and performs the write. A handler never holds an {@code ArtifactStore} or a DB connection — its
 * classloader could not reach one anyway (design/32 §3.2). Because the capability is on the SPI, a
 * third-party handler that produces a report (coverage HTML, a SBOM, …) gets archival — and
 * fingerprinting — for free, exactly as a built-in does.
 *
 * <p>The file is local to the worker, so the parameter is a {@link Path}, not a pre-opened stream:
 * the worker's implementation opens it, streams it into the configured {@code ArtifactStore},
 * writes the {@code titan.artifact} row, and — when asked — the fingerprint rows.
 */
@FunctionalInterface
public interface ArtifactSink {

  /**
   * Archive one workspace file.
   *
   * @param name the archive path — workspace-relative; the key the artifact is browsed and
   *     downloaded by, and the {@code name} of its {@code titan.artifact} row. Re-archiving the
   *     same {@code name} overwrites, so the calling step stays idempotent (design/32 §4).
   * @param file the local file on the worker to store; must exist and be readable
   * @param fingerprint when {@code true}, also record a content fingerprint — the file's SHA-256
   *     indexed across builds (design/41 §8.3)
   * @throws IOException if the file cannot be read or the artifact cannot be stored
   */
  void archive(String name, Path file, boolean fingerprint) throws IOException;

  /**
   * The sink a {@link StepRequest} carries when the deployment has no artifact store wired — a test
   * request, or a misconfigured worker. It fails loud rather than silently dropping a build's
   * artifacts: a step that tries to archive against it gets a clear, recorded failure.
   */
  ArtifactSink UNCONFIGURED =
      (name, file, fingerprint) -> {
        throw new IOException(
            "cannot archive '" + name + "': no artifact store is configured for this worker");
      };
}

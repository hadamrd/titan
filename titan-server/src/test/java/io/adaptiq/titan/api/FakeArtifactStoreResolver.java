package io.adaptiq.titan.api;

import io.adaptiq.titan.artifact.ArtifactStoreResolver;
import io.adaptiq.titan.flow.artifact.ArtifactKey;
import io.adaptiq.titan.flow.artifact.ArtifactStore;
import io.adaptiq.titan.flow.artifact.StoredBlob;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-scope CDI alternative producer: replaces {@link
 * io.adaptiq.titan.boot.ArtifactStoreResolverProducer} during unit tests. The resolved store is a
 * pure-memory map of {@code storageRef → bytes}, exposed to tests via {@link #put(String, byte[])}
 * so a test can seed the bytes its DAO row points at without touching the filesystem or the
 * environment-driven {@code TITAN_ARTIFACT_*} config.
 */
@Mock
@ApplicationScoped
public class FakeArtifactStoreResolver {

  /** Keyed by storage kind so a single test can exercise multiple backends without a global. */
  private final Map<String, Map<String, byte[]>> byKind = new ConcurrentHashMap<>();

  /** Backends a test deliberately wants to be "unconfigured" — the resolver throws for these. */
  private final Map<String, Boolean> unconfigured = new ConcurrentHashMap<>();

  @Produces
  @ApplicationScoped
  public ArtifactStoreResolver artifactStoreResolver() {
    return this::forKind;
  }

  /** Seed a fixture: the {@code storageRef} a DAO row will carry, plus its bytes. */
  public void put(String kind, String storageRef, byte[] bytes) {
    byKind.computeIfAbsent(kind, k -> new ConcurrentHashMap<>()).put(storageRef, bytes);
  }

  /** Drop every fixture so the next test starts clean. */
  public void reset() {
    byKind.clear();
    unconfigured.clear();
  }

  /** Force {@link #forKind(String)} to throw for {@code kind} — exercises the 503 branch. */
  public void markUnconfigured(String kind) {
    unconfigured.put(kind, true);
  }

  public ArtifactStore forKind(String kind) {
    if (unconfigured.containsKey(kind)) {
      throw new IllegalArgumentException("fake: backend '" + kind + "' is not configured");
    }
    Map<String, byte[]> blobs = byKind.computeIfAbsent(kind, k -> new ConcurrentHashMap<>());
    return new InMemoryArtifactStore(kind, blobs);
  }

  /** Read-only-for-the-endpoint in-memory store — {@code put}/{@code delete} are not used here. */
  private static final class InMemoryArtifactStore implements ArtifactStore {
    private final String kind;
    private final Map<String, byte[]> blobs;

    InMemoryArtifactStore(String kind, Map<String, byte[]> blobs) {
      this.kind = kind;
      this.blobs = blobs;
    }

    @Override
    public String kind() {
      return kind;
    }

    @Override
    public StoredBlob put(ArtifactKey key, InputStream content) {
      throw new UnsupportedOperationException("fake store is read-only for download tests");
    }

    @Override
    public Optional<InputStream> open(String storageRef) {
      byte[] bytes = blobs.get(storageRef);
      return bytes == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(bytes));
    }

    @Override
    public boolean delete(String storageRef) {
      return blobs.remove(storageRef) != null;
    }

    @Override
    public void pruneStashes(long buildId) {
      // no-op
    }

    @Override
    public void deleteBuild(long buildId) {
      // no-op
    }

    @Override
    public void close() {
      // no-op
    }
  }
}

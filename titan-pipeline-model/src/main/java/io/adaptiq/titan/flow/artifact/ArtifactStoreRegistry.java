package io.adaptiq.titan.flow.artifact;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Resolves a configured artifact backend to a live {@link ArtifactStore} — the single entry point
 * the worker and the controller both call (design/41 §8.6).
 *
 * <p>Backends are discovered through {@link ServiceLoader}: every {@link ArtifactStoreProvider} on
 * the path is a candidate, matched by {@link ArtifactStoreProvider#kind()}. Adding a backend is
 * therefore a pure extension — a new provider module — with no edit here.
 */
public final class ArtifactStoreRegistry {

  private ArtifactStoreRegistry() {}

  /**
   * Build the {@link ArtifactStore} for {@code kind}, configured by {@code config}.
   *
   * @param kind the backend discriminator — {@code "fs"}, {@code "pg"}, {@code "s3"}, …
   * @param config backend-specific configuration, passed through to the provider
   * @return a live store
   * @throws IllegalArgumentException if no provider on the path declares {@code kind}
   */
  public static ArtifactStore resolve(String kind, Map<String, String> config) {
    for (ArtifactStoreProvider provider : load()) {
      if (provider.kind().equals(kind)) {
        return provider.create(config);
      }
    }
    throw new IllegalArgumentException(
        "no ArtifactStore backend for kind '" + kind + "' — registered backends: " + kinds());
  }

  /** Every backend kind a provider on the path declares — for diagnostics and config validation. */
  public static Set<String> kinds() {
    Set<String> kinds = new LinkedHashSet<>();
    for (ArtifactStoreProvider provider : load()) {
      kinds.add(provider.kind());
    }
    return kinds;
  }

  private static ServiceLoader<ArtifactStoreProvider> load() {
    // Load against this SPI's own classloader so the built-in providers bundled alongside it
    // are always found — on the hermetic worker and on the controller alike.
    return ServiceLoader.load(
        ArtifactStoreProvider.class, ArtifactStoreProvider.class.getClassLoader());
  }
}

package io.adaptiq.titan.flow.artifact;

import java.util.Map;

/**
 * Constructs one {@link ArtifactStore} backend from configuration — the discovery seam that makes
 * the backend a deployment choice, and lets new backends be added without a core change (design/41
 * §8.6, decision E2).
 *
 * <p>Providers are discovered by {@link java.util.ServiceLoader} — a {@code META-INF/services}
 * entry — exactly as {@code SecretProvider} backends are. A new backend (an S3 store, a Nexus-raw
 * store, an in-house store) is therefore a self-contained module: one {@code ArtifactStoreProvider}
 * implementation plus its services entry, dropped on the path. Nothing in Titan's core changes.
 *
 * <p>The built-in {@code FilesystemArtifactStoreProvider} ({@code kind = "fs"}) ships here; a
 * Postgres-large-object and an S3 provider are the next backends, each its own provider.
 */
public interface ArtifactStoreProvider {

  /**
   * The backend discriminator this provider builds — {@code "fs"}, {@code "pg"}, {@code "s3"}, … It
   * must equal the {@link ArtifactStore#kind()} of the store {@link #create} returns, and is what
   * the controller config selects and what a {@code titan.artifact.storage} value records.
   */
  String kind();

  /**
   * Build the store from backend-specific configuration — for the filesystem backend, the artifacts
   * root; for S3, the bucket and region; and so on. Keys are the provider's own contract.
   *
   * @throws IllegalArgumentException if the configuration is missing or invalid
   */
  ArtifactStore create(Map<String, String> config);
}

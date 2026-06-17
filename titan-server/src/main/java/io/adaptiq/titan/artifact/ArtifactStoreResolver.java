package io.adaptiq.titan.artifact;

import io.adaptiq.titan.flow.artifact.ArtifactStore;

/**
 * CDI-injectable indirection over {@link ArtifactStores#forKind(String)} — production binds to the
 * static registry, tests bind a stub returning an in-memory or temp-file store.
 *
 * <p>The download endpoint resolves the {@link ArtifactStore} for a row's {@code storage} kind (an
 * enum-like discriminator written by the worker, never a URL substring) through this seam.
 */
public interface ArtifactStoreResolver {

  /**
   * The store for a given backend kind ({@code "fs"} / {@code "s3"} / …).
   *
   * @throws IllegalArgumentException if no provider serves {@code kind}, or its configuration is
   *     missing
   */
  ArtifactStore forKind(String kind);
}

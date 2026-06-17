package io.adaptiq.titan.boot;

import io.adaptiq.titan.artifact.ArtifactStoreResolver;
import io.adaptiq.titan.artifact.ArtifactStores;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * CDI producer wiring the production {@link ArtifactStoreResolver} to the static {@link
 * ArtifactStores} registry — the same per-kind cache the rest of the controller uses. Tests
 * override this with a {@code @Mock @ApplicationScoped} alternative.
 */
@ApplicationScoped
public class ArtifactStoreResolverProducer {

  @Produces
  @ApplicationScoped
  public ArtifactStoreResolver artifactStoreResolver() {
    return ArtifactStores::forKind;
  }
}

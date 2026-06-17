package io.adaptiq.titan.api;

import io.adaptiq.titan.store.TitanStores;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Test-scope CDI alternative producer: replaces {@link io.adaptiq.titan.boot.StoresProducer} during
 * unit tests ({@code @QuarkusTest} without a real datasource).
 *
 * <p>{@link io.quarkus.test.Mock @Mock} is a Quarkus shorthand for {@code @Alternative
 * + @Priority(1)} — it wins over the production producer without needing a beans.xml entry. The
 * produced {@link TitanStores} is backed by a fresh in-memory H2 database (via {@link
 * FakeTitanStores#create()}) so each test class gets an isolated store.
 *
 * <p>Note: all {@code @QuarkusTest} classes in this source set share this single application-scoped
 * bean. Tests that need an empty store must seed it themselves via the injected {@link TitanStores}
 * instance.
 */
@Mock
@ApplicationScoped
public class H2StoresProducer {

  @Produces
  @ApplicationScoped
  public TitanStores titanStores() {
    return FakeTitanStores.create();
  }
}

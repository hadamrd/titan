package io.adaptiq.titan.trigger.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Lifecycle coverage for {@link TriggerSourceBootstrap} (design/51 D3) — proves a registered {@link
 * TriggerSource} CDI bean is {@code start()}ed on Quarkus startup. The fake source below is a
 * {@code @ApplicationScoped} CDI bean discovered by Arc the same way a real {@code TriggerSource}
 * would be.
 */
@QuarkusTest
class TriggerSourceBootstrapTest {

  @Inject FakeBootstrapSource source;

  @Test
  void registeredSourcesAreStartedAtBoot() {
    assertTrue(
        source.wasStarted(),
        "TriggerSourceBootstrap must call start() on every @ApplicationScoped TriggerSource"
            + " during the Quarkus StartupEvent");
  }
}

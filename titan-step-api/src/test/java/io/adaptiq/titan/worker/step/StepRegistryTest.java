package io.adaptiq.titan.worker.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link StepRegistry} — the controller-side {@link java.util.ServiceLoader} consumer for
 * {@link StepHandlerProvider} (design/32 §8, design/42 §4.2).
 *
 * <p>The two fixture providers under {@code src/test/resources/META-INF/services/...} stand in for
 * built-in + third-party step jars; the registry must find both, expose their descriptors keyed by
 * {@code descriptorId}, and ignore providers that throw on {@code handlers()} (resilience contract
 * — design/42 §4.3 rule 4: a broken plugin must not crash the controller).
 */
class StepRegistryTest {

  @Test
  void discoversBothFixtureProvidersViaServiceLoader() {
    List<StepHandlerProvider> providers = StepRegistry.providers();
    assertTrue(providers.size() >= 2, "expected at least two providers, got: " + providers);
    boolean foundOk = false;
    boolean foundBroken = false;
    for (StepHandlerProvider p : providers) {
      if (p instanceof FixtureProvider) foundOk = true;
      if (p instanceof BrokenProvider) foundBroken = true;
    }
    assertTrue(foundOk, "FixtureProvider must be discovered");
    assertTrue(foundBroken, "BrokenProvider must be discovered");
  }

  @Test
  void descriptorsAreKeyedByDescriptorId() {
    Map<String, StepDescriptor> map = StepRegistry.descriptors();
    StepDescriptor d = map.get(FixtureStepHandler.ID);
    assertNotNull(d, "FixtureStepHandler's descriptor must be registered: " + map.keySet());
    assertEquals(FixtureStepHandler.ID, d.descriptorId());
    assertEquals("Fixture step", d.displayName());
    assertEquals("value", d.scalarShorthandKey());
  }

  @Test
  void findReturnsThePresentDescriptor() {
    Optional<StepDescriptor> found = StepRegistry.find(FixtureStepHandler.ID);
    assertTrue(found.isPresent());
    assertEquals(FixtureStepHandler.ID, found.get().descriptorId());
  }

  @Test
  void findReturnsEmptyForUnknownId() {
    Optional<StepDescriptor> none = StepRegistry.find("no-such-step-id-xyz");
    assertTrue(none.isEmpty());
  }

  @Test
  void aProviderThatThrowsIsSkippedNotPropagated() {
    // BrokenProvider throws on handlers(); the registry must absorb it. The fixture descriptor
    // must still be present — i.e. a downstream provider is not blocked by the upstream throw.
    Map<String, StepDescriptor> map = StepRegistry.descriptors();
    assertTrue(
        map.containsKey(FixtureStepHandler.ID),
        "broken provider must not block the fixture descriptor from being registered");
  }
}

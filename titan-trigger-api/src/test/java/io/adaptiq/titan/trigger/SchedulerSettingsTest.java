package io.adaptiq.titan.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Ported from {@code io.adaptiq.scheduler.SchedulerSettingsTest} (Wave 1). */
class SchedulerSettingsTest {

  @Test
  void moduleDefaultsAreSane() {
    SchedulerSettings defaults = new DefaultSchedulerSettings();
    assertEquals(60_000L, defaults.pollIntervalMillis(), "default poll: one minute");
    assertEquals(
        Duration.ofHours(25), defaults.catchUpWindow(), "default catch-up: a day + an hour");
    assertEquals(
        Duration.ofSeconds(30), defaults.triggerEvaluationTimeout(), "default eval budget");
    assertFalse(defaults.paused(), "the engine runs by default");
  }

  @Test
  void currentFallsBackToDefaultsWithoutAnOverride() {
    // No higher-ordinal SchedulerSettings is registered via ServiceLoader in this test source set,
    // so current() must hand back the default. (The Quarkus runtime is not started here — this is
    // a pure unit test; SchedulerSettings.current() must not depend on a live container.)
    SchedulerSettings current = SchedulerSettings.current();
    assertNotNull(current);
    assertEquals(60_000L, current.pollIntervalMillis());
    assertEquals(Duration.ofHours(25), current.catchUpWindow());
    assertEquals(Duration.ofSeconds(30), current.triggerEvaluationTimeout());
    assertFalse(current.paused());
  }
}

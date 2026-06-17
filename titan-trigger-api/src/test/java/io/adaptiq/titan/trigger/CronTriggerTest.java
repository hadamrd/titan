package io.adaptiq.titan.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Ported from {@code io.adaptiq.scheduler.CronTriggerTest} (Wave 1). */
class CronTriggerTest {

  private static final String SEED = "folder/job";

  @Test
  void firesWhenAMinuteElapsedSinceLastFired() {
    CronTrigger trigger = new CronTrigger(null, "* * * * *");
    Instant now = Instant.parse("2026-06-15T12:05:30Z");
    TriggerOutcome outcome =
        trigger.evaluate(new TriggerContext(now, now.minus(Duration.ofMinutes(2)), SEED));
    assertEquals(TriggerOutcome.Kind.FIRE, outcome.kind());
  }

  @Test
  void skipsWhenNotDue() {
    CronTrigger trigger = new CronTrigger(null, "* * * * *");
    Instant now = Instant.parse("2026-06-15T12:05:30Z");
    TriggerOutcome outcome = trigger.evaluate(new TriggerContext(now, now, SEED));
    assertEquals(TriggerOutcome.Kind.SKIP, outcome.kind());
  }

  @Test
  void invalidSpecSkipsRatherThanThrows() {
    CronTrigger trigger = new CronTrigger(null, "not-a-cron");
    Instant now = Instant.parse("2026-06-15T12:05:30Z");
    TriggerOutcome outcome =
        trigger.evaluate(new TriggerContext(now, now.minus(Duration.ofHours(1)), SEED));
    assertEquals(
        TriggerOutcome.Kind.SKIP,
        outcome.kind(),
        "a spec that no longer parses must not wedge the engine");
    assertNotNull(outcome.reason());
  }

  @Test
  void typeDiscriminatorIsCron() {
    assertEquals("cron", new CronTrigger(null, "@daily").getType());
  }

  @Test
  void idIsMintedWhenAbsentAndPreservedWhenGiven() {
    assertNotNull(new CronTrigger(null, "@daily").getId());
    assertNotNull(new CronTrigger("  ", "@daily").getId());
    assertEquals("fixed-id", new CronTrigger("fixed-id", "@daily").getId());
  }

  @Test
  void specIsTrimmed() {
    assertEquals("@daily", new CronTrigger(null, "  @daily  ").getSpec());
  }

  @Test
  void fireOutcomeIsASingleton() {
    assertSame(TriggerOutcome.fire(), TriggerOutcome.fire());
  }

  @Test
  void deferOutcomeCarriesItsInstant() {
    Instant until = Instant.parse("2026-06-15T13:00:00Z");
    TriggerOutcome deferred = TriggerOutcome.defer(until);
    assertEquals(TriggerOutcome.Kind.DEFER, deferred.kind());
    assertEquals(until, deferred.deferUntil());
  }

  @Test
  void skipOutcomeNotDueByDefault() {
    CronTrigger trigger = new CronTrigger(null, "* * * * *");
    Instant now = Instant.parse("2026-06-15T12:05:30Z");
    assertFalse(
        trigger.evaluate(new TriggerContext(now, now, SEED)).kind() == TriggerOutcome.Kind.FIRE);
  }
}

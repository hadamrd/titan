package io.adaptiq.titan.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link TriggerOutcome} — the three-armed verdict the engine and filters speak.
 * Ported verbatim from {@code io.adaptiq.scheduler.TriggerOutcomeTest} (Wave 1).
 */
class TriggerOutcomeTest {

  @Test
  void fireHasKindFireAndNoPayload() {
    TriggerOutcome o = TriggerOutcome.fire();
    assertEquals(TriggerOutcome.Kind.FIRE, o.kind());
    assertNull(o.reason());
    assertNull(o.deferUntil());
  }

  @Test
  void fireIsASharedSingleton() {
    assertSame(
        TriggerOutcome.fire(),
        TriggerOutcome.fire(),
        "FIRE is stateless — it should not allocate per call");
  }

  @Test
  void skipCarriesItsReason() {
    TriggerOutcome o = TriggerOutcome.skip("not due");
    assertEquals(TriggerOutcome.Kind.SKIP, o.kind());
    assertEquals("not due", o.reason());
    assertNull(o.deferUntil());
  }

  @Test
  void deferCarriesItsInstant() {
    Instant until = Instant.parse("2026-06-15T13:00:00Z");
    TriggerOutcome o = TriggerOutcome.defer(until);
    assertEquals(TriggerOutcome.Kind.DEFER, o.kind());
    assertEquals(until, o.deferUntil());
    assertNull(o.reason());
  }

  @Test
  void toStringNamesTheKindAndPayload() {
    assertEquals("FIRE", TriggerOutcome.fire().toString());
    assertTrue(TriggerOutcome.skip("x").toString().contains("SKIP"));
    assertTrue(TriggerOutcome.skip("because").toString().contains("because"));
    Instant until = Instant.parse("2026-06-15T13:00:00Z");
    assertTrue(TriggerOutcome.defer(until).toString().contains("DEFER"));
    assertTrue(TriggerOutcome.defer(until).toString().contains(until.toString()));
  }

  @Test
  void everyKindIsCovered() {
    assertEquals(3, TriggerOutcome.Kind.values().length);
  }
}

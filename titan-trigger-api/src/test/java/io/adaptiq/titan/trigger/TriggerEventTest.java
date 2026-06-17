package io.adaptiq.titan.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ported from {@code io.adaptiq.scheduler.TriggerEventTest} (Wave 1). */
class TriggerEventTest {

  @Test
  void webhookFactorySetsKindAndKey() {
    TriggerEvent e = TriggerEvent.webhook("secret-token", Map.of());
    assertEquals(TriggerEvent.WEBHOOK, e.kind());
    assertEquals("secret-token", e.key());
    assertNotNull(e.instant());
  }

  @Test
  void attributesAreReadableByName() {
    TriggerEvent e = TriggerEvent.webhook("t", Map.of("branch", "main", "commit", "abc123"));
    assertEquals("main", e.attribute("branch"));
    assertEquals("abc123", e.attribute("commit"));
    assertNull(e.attribute("absent"));
  }

  @Test
  void attributesAreDefensivelyCopied() {
    Map<String, String> mutable = new HashMap<>();
    mutable.put("branch", "main");
    TriggerEvent e = TriggerEvent.webhook("t", mutable);
    mutable.put("branch", "tampered");
    assertEquals(
        "main", e.attribute("branch"), "the event must not see post-construction mutation");
  }

  @Test
  void attributeMapIsUnmodifiable() {
    TriggerEvent e = TriggerEvent.webhook("t", Map.of("k", "v"));
    assertThrows(UnsupportedOperationException.class, () -> e.attributes().put("x", "y"));
  }

  @Test
  void nullAttributesAndInstantAreNormalised() {
    TriggerEvent e = new TriggerEvent("custom", "k", null, null);
    assertTrue(e.attributes().isEmpty(), "null attributes normalise to an empty map");
    assertNotNull(e.instant(), "null instant normalises to now");
  }

  @Test
  void carriesAnExplicitInstant() {
    Instant when = Instant.parse("2026-06-15T12:00:00Z");
    TriggerEvent e = new TriggerEvent("custom", "k", Map.of(), when);
    assertEquals(when, e.instant());
  }
}

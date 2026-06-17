package io.adaptiq.titan.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opentelemetry.api.trace.TraceFlags;
import org.junit.jupiter.api.Test;

/**
 * Unit-level pin for {@link TraceContext} — verifies the W3C traceparent encoding is exactly the
 * 55-char {@code 00-<32hex>-<16hex>-<2hex>} the V17 column expects, and that the public capture
 * helper returns {@code null} when no OTel SDK is initialised (the path every unit test takes).
 */
class TraceContextTest {

  @Test
  void formatTraceParent_buildsCanonicalW3CString() {
    // Known fixtures from the W3C trace-context test vectors.
    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
    String spanId = "00f067aa0ba902b7";
    String formatted = TraceContext.formatTraceParent(traceId, spanId, TraceFlags.getSampled());

    assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", formatted);
    assertEquals(55, formatted.length(), "W3C traceparent v0 is exactly 55 chars");
  }

  @Test
  void formatTraceParent_unsampledFlags_encodesAsZeroTwo() {
    String traceId = "0af7651916cd43dd8448eb211c80319c";
    String spanId = "b7ad6b7169203331";
    String formatted = TraceContext.formatTraceParent(traceId, spanId, TraceFlags.getDefault());

    assertEquals("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-00", formatted);
  }

  @Test
  void currentTraceParent_returnsNull_whenNoSdkInitialised() {
    // The unit-test JVM has no OTel SDK registered (only the API), so Span.current() returns the
    // invalid no-op span. The bridge must report null — the column must stay empty, never the
    // all-zeros sentinel that would falsely claim a trace.
    assertNull(TraceContext.currentTraceParent());
  }
}

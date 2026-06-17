package io.adaptiq.titan.observability;

import edu.umd.cs.findbugs.annotations.Nullable;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tiny bridge between the OpenTelemetry API and Titan's task_queue row format (issue #314).
 *
 * <p>Captures the current span's W3C {@code traceparent} at enqueue time so the worker that later
 * claims the task can re-attach to the originating trace and emit child spans into it. Propagation
 * is always best-effort: if there is no active span (e.g. a background reaper, a unit test without
 * the SDK initialised, a caller outside any OTel scope), {@link #currentTraceParent()} returns
 * {@code null} and the column is simply not populated — task dispatch must never block on trace
 * plumbing.
 *
 * <p>Format reference: <a href="https://www.w3.org/TR/trace-context/#traceparent-header">W3C Trace
 * Context — {@code traceparent}</a>. Version is the constant {@code 00}; the trace-id is 32 lower-
 * case hex chars, the parent-id (span-id) is 16, and trace flags is 2 — joined by dashes for a
 * total of 55 characters. The column is {@code VARCHAR(64)} so a future W3C version up-rev would
 * still fit without a migration.
 */
public final class TraceContext {

  private static final Logger LOGGER = Logger.getLogger(TraceContext.class.getName());

  /** Current W3C trace-context version — only "00" is defined. */
  private static final String VERSION = "00";

  private TraceContext() {}

  /**
   * Returns the W3C {@code traceparent} for the current OTel span, or {@code null} if no valid span
   * is in scope. Never throws — a missing or invalid SpanContext returns null.
   */
  @Nullable
  public static String currentTraceParent() {
    try {
      SpanContext ctx = Span.current().getSpanContext();
      if (!ctx.isValid()) {
        return null;
      }
      return formatTraceParent(ctx.getTraceId(), ctx.getSpanId(), ctx.getTraceFlags());
    } catch (RuntimeException e) {
      // Trace plumbing must never break task dispatch. Surface the silent miss as a WARN log +
      // bounded-cardinality counter (issue #1081 adversarial criterion: an unreachable collector
      // or a botched SDK install previously dropped propagation silently — now it leaves a
      // breadcrumb operators can scrape from /q/metrics).
      LOGGER.log(Level.WARNING, "[titan] traceparent capture failed: {0}", e.getMessage());
      StepMetrics.recordOtelExportFailure("traceparent_capture");
      return null;
    }
  }

  /**
   * Format a W3C traceparent string from its components. Visible for tests — the unit test pins
   * down the exact byte-for-byte encoding the column carries.
   */
  static String formatTraceParent(String traceId, String spanId, TraceFlags flags) {
    return VERSION + "-" + traceId + "-" + spanId + "-" + flags.asHex();
  }
}

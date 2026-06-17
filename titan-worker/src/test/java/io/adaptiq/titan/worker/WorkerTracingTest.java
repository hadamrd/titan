package io.adaptiq.titan.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkerTracing} — span continuation from a W3C traceparent (issue #315).
 *
 * <p>A minimal {@link OpenTelemetrySdk} is installed once for the class so the {@link
 * WorkerTracing#startTaskSpan} assertions exercise a real {@link SdkTracerProvider} (the global
 * no-op tracer is identity-propagating: child spans share the parent's span-id, which would mask
 * the "fresh span-id" check that proves a true parent/child relationship). Extraction tests don't
 * need the SDK — they exercise the {@link W3CTraceContextPropagator} directly.
 */
class WorkerTracingTest {

  /** A valid W3C traceparent — version-00, 32-hex trace-id, 16-hex parent-id, 01 (sampled). */
  private static final String VALID_TRACEPARENT =
      "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

  private static final String EXPECTED_TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
  private static final String EXPECTED_PARENT_SPAN_ID = "b7ad6b7169203331";

  private static OpenTelemetrySdk sdk;

  @BeforeAll
  static void installSdk() {
    sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().build())
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
    WorkerTracing.setOpenTelemetryForTesting(sdk);
  }

  @AfterAll
  static void resetSdk() {
    if (sdk != null) {
      sdk.close();
    }
    WorkerTracing.setOpenTelemetryForTesting(OpenTelemetry.noop());
  }

  @Test
  void extract_validTraceparent_yieldsRemoteSpanContextWithSameTraceId() {
    Context extracted = WorkerTracing.extract(VALID_TRACEPARENT);
    SpanContext remote = Span.fromContext(extracted).getSpanContext();

    assertEquals(EXPECTED_TRACE_ID, remote.getTraceId(), "trace-id must survive extraction");
    assertEquals(
        EXPECTED_PARENT_SPAN_ID,
        remote.getSpanId(),
        "the extracted span-id is the *parent* span-id from the traceparent");
    assertTrue(remote.isRemote(), "extracted context must be flagged remote");
  }

  @Test
  void extract_nullOrBlank_fallsBackToCurrentContext() {
    // No exception, no remote context — just the local current context.
    assertEquals(Context.current(), WorkerTracing.extract(null));
    assertEquals(Context.current(), WorkerTracing.extract(""));
    assertEquals(Context.current(), WorkerTracing.extract("   "));
  }

  @Test
  void startTaskSpan_withTraceparent_spanInheritsParentTraceId() {
    try (WorkerTracing.TaskSpan task =
        WorkerTracing.startTaskSpan("worker.runTask.test", VALID_TRACEPARENT)) {

      SpanContext ctx = task.span().getSpanContext();
      assertNotNull(ctx, "span must have a context");
      // The KEY assertion of #315: the worker's span shares the controller's
      // trace-id (continuation), with its own fresh span-id (child relationship).
      assertEquals(
          EXPECTED_TRACE_ID,
          ctx.getTraceId(),
          "worker span MUST inherit the controller's trace-id from traceparent");
      assertNotEquals(
          EXPECTED_PARENT_SPAN_ID,
          ctx.getSpanId(),
          "worker span must have a fresh span-id distinct from the parent");
    }
  }

  @Test
  void startTaskSpan_nullTraceparent_doesNotCrash() {
    // A task with no traceparent (server enqueued from a non-traced context)
    // must still run — the worker just opens a root-context span.
    try (WorkerTracing.TaskSpan task =
        WorkerTracing.startTaskSpan("worker.runTask.untraced", null)) {
      assertNotNull(task.span());
    }
  }

  @Test
  void startTaskSpan_malformedTraceparent_doesNotCrash() {
    // Robustness: a garbage traceparent (truncation, corrupt bytes) must fall
    // back to the local context, never throw.
    try (WorkerTracing.TaskSpan task =
        WorkerTracing.startTaskSpan("worker.runTask.bad", "not-a-traceparent")) {
      assertNotNull(task.span());
    }
  }
}

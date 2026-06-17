package io.adaptiq.titan.worker;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import java.util.Collections;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker-side OpenTelemetry plumbing (issue #315).
 *
 * <p>Two responsibilities, deliberately kept in one place so the worker boot path stays a single
 * line:
 *
 * <ol>
 *   <li><strong>SDK boot.</strong> {@link #init()} runs the SDK's {@link
 *       AutoConfiguredOpenTelemetrySdk} (the same env-var contract the server uses via the Quarkus
 *       OTel extension) and stores the resulting {@link OpenTelemetry}. Boot is gated on {@code
 *       TITAN_OTEL_ENDPOINT} — when unset, autoconfigure is skipped entirely and {@link
 *       OpenTelemetry#noop()} is installed. A no-op SDK still hands out a {@link Tracer} whose
 *       spans are silently dropped, so the rest of the worker can call {@link #startTaskSpan}
 *       unconditionally without an env-var branch.
 *   <li><strong>Span continuation.</strong> {@link #startTaskSpan} takes the W3C {@code
 *       traceparent} string the controller stamped onto a {@code task_queue} row (PR #382) and
 *       opens a child span under that remote context. Logs emitted inside the returned {@link
 *       Scope} carry the inherited trace-id via Logback's MDC bridge.
 * </ol>
 *
 * <p>Failures are never propagated: a missing endpoint, an invalid traceparent, or an OTel runtime
 * error must not crash the worker — the trace is best-effort, the work is not.
 */
final class WorkerTracing {

  private static final Logger LOG = LoggerFactory.getLogger(WorkerTracing.class);

  /** Env var that gates SDK boot — same name + contract as titan-server (see #314 / PR #382). */
  static final String ENDPOINT_ENV = "TITAN_OTEL_ENDPOINT";

  /** Logical service name surfaced on every exported span. */
  private static final String SERVICE_NAME = "titan-worker";

  /** Instrumentation scope — names this worker's spans in the OTLP feed. */
  private static final String INSTRUMENTATION_SCOPE = "io.adaptiq.titan.worker";

  private static volatile OpenTelemetry openTelemetry = OpenTelemetry.noop();

  private WorkerTracing() {}

  /**
   * Boot the OTel SDK if {@code TITAN_OTEL_ENDPOINT} is set; otherwise install a no-op
   * OpenTelemetry. Idempotent: safe to call once at process start.
   *
   * <p>Property-binding strategy: we look up {@code TITAN_OTEL_ENDPOINT} ourselves and translate it
   * into the SDK-native {@code otel.exporter.otlp.endpoint} system property the autoconfigure layer
   * expects, so callers only need to know the Titan-flavoured env var. We also force {@code
   * otel.service.name=titan-server-worker} so the service identity is set even without an
   * OTEL_SERVICE_NAME env var.
   */
  static synchronized void init() {
    String endpoint = System.getenv(ENDPOINT_ENV);
    if (endpoint == null || endpoint.isBlank()) {
      LOG.info("OTel: {} unset — exporter disabled (no-op tracer)", ENDPOINT_ENV);
      openTelemetry = OpenTelemetry.noop();
      return;
    }
    try {
      // Translate the Titan-flavoured env var into the SDK's native property
      // contract. setIfAbsent so an operator can still override via the
      // canonical otel.* / OTEL_* mechanisms.
      setIfAbsent("otel.exporter.otlp.endpoint", endpoint);
      setIfAbsent("otel.service.name", SERVICE_NAME);
      // Logs go through Logback's own appender; the SDK should only export
      // traces + metrics, matching the server's exporter scope.
      setIfAbsent("otel.logs.exporter", "none");

      openTelemetry =
          AutoConfiguredOpenTelemetrySdk.builder()
              .setResultAsGlobal()
              .build()
              .getOpenTelemetrySdk();
      LOG.info("OTel: SDK autoconfigured (endpoint={})", endpoint);
    } catch (RuntimeException e) {
      // A botched autoconfigure must not break worker boot — the worker
      // still runs tasks; only trace export is lost. Fall back to no-op.
      LOG.warn("OTel: autoconfigure failed — running with no-op tracer", e);
      openTelemetry = OpenTelemetry.noop();
    }
  }

  private static void setIfAbsent(String key, String value) {
    if (System.getProperty(key) == null) {
      System.setProperty(key, value);
    }
  }

  /**
   * Open a span for a claimed task, continuing the controller-side trace when a {@code traceparent}
   * is present. Caller MUST close the returned scope when done (try-with-resources around the task
   * body).
   *
   * @param spanName logical name of the span (e.g. {@code "worker.runTask"}).
   * @param traceParent W3C traceparent from the {@code task_queue} row, or {@code null}.
   * @return an {@link AutoCloseable} closing the span scope + ending the span; never null.
   */
  static TaskSpan startTaskSpan(String spanName, String traceParent) {
    Tracer tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    Context parent = extract(traceParent);
    Span span =
        tracer.spanBuilder(spanName).setSpanKind(SpanKind.CONSUMER).setParent(parent).startSpan();
    Scope scope = span.makeCurrent();
    return new TaskSpan(span, scope);
  }

  /**
   * Decode a W3C {@code traceparent} string into an OTel {@link Context} the SpanBuilder can use as
   * a parent. {@code null} / blank / malformed values yield {@link Context#current()}, so a task
   * without a traceparent still runs (under the worker's own root context).
   *
   * <p>Visible for tests.
   */
  static Context extract(String traceParent) {
    if (traceParent == null || traceParent.isBlank()) {
      return Context.current();
    }
    // Use the W3C propagator directly rather than going through
    // openTelemetry.getPropagators() — the no-op OpenTelemetry installs a
    // no-op propagator that drops the carrier. Since traceparent IS the W3C
    // wire format by definition (the column stores exactly that — see
    // TraceContext.formatTraceParent), pinning the W3C propagator here is
    // correct + decoupled from SDK boot state.
    TextMapPropagator propagator = W3CTraceContextPropagator.getInstance();
    Map<String, String> carrier = Collections.singletonMap("traceparent", traceParent);
    try {
      return propagator.extract(Context.current(), carrier, MAP_GETTER);
    } catch (RuntimeException e) {
      LOG.warn("OTel: failed to extract traceparent='{}' — running without parent", traceParent, e);
      return Context.current();
    }
  }

  /** Read-only getter for tests to inspect the currently installed OpenTelemetry. */
  static OpenTelemetry openTelemetry() {
    return openTelemetry;
  }

  /**
   * Test-only setter so a unit test can install a real {@link
   * io.opentelemetry.sdk.OpenTelemetrySdk} without touching global state or env vars.
   * Package-private — not part of the worker API.
   */
  static void setOpenTelemetryForTesting(OpenTelemetry overrideForTest) {
    openTelemetry = overrideForTest;
  }

  private static final TextMapGetter<Map<String, String>> MAP_GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
          return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
          return carrier == null ? null : carrier.get(key);
        }
      };

  /** AutoCloseable bundle of a span + its scope — close ends the scope and the span together. */
  static final class TaskSpan implements AutoCloseable {
    private final Span span;
    private final Scope scope;

    private TaskSpan(Span span, Scope scope) {
      this.span = span;
      this.scope = scope;
    }

    /** The underlying span — exposed so callers can set attributes / record errors. */
    Span span() {
      return span;
    }

    @Override
    public void close() {
      try {
        scope.close();
      } finally {
        span.end();
      }
    }
  }
}

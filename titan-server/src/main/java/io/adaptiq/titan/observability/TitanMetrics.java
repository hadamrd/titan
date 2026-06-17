package io.adaptiq.titan.observability;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.TitanStores;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central Micrometer wiring for Titan's Prometheus exposition (#649).
 *
 * <p>Owns the gauge registrations (queue depth, worker count by status) at app start and exposes
 * helpers for the counter/timer call sites in {@link io.adaptiq.titan.audit.AuditService} and
 * {@link io.adaptiq.titan.build.BuildServiceImpl}.
 *
 * <p>Metric set (v1):
 *
 * <ul>
 *   <li>{@code titan_builds_total{status}} — counter, incremented on terminal build transition.
 *   <li>{@code titan_build_duration_seconds{status}} — timer/histogram, observed at terminal.
 *   <li>{@code titan_queue_depth{queue}} — gauge, lazy DAO read per scrape.
 *   <li>{@code titan_worker_count{status}} — gauge, lazy DAO read per scrape.
 *   <li>{@code titan_audit_events_total{action}} — counter, incremented per AuditService.record.
 * </ul>
 *
 * <p>CONSTITUTION §6: {@code status} / {@code action} labels are bounded by the {@link
 * io.adaptiq.titan.audit.AuditAction} enum + the canonical terminal statuses (SUCCESS, FAILED,
 * ABORTED, CANCELLED). No plaintext-secret value ever reaches a label.
 */
@ApplicationScoped
public class TitanMetrics {

  private static final Logger LOGGER = Logger.getLogger(TitanMetrics.class.getName());

  /** Canonical queues we publish a depth gauge for. v1 ships "default" only. */
  static final String[] KNOWN_QUEUES = {"default"};

  /** Canonical agent/worker statuses we publish gauges for. */
  static final String[] KNOWN_WORKER_STATUSES = {"ONLINE", "BUSY", "DRAINING", "OFFLINE"};

  private final MeterRegistry registry;
  private final TitanStores stores;

  TitanMetrics(MeterRegistry registry, TitanStores stores) {
    this.registry = registry;
    this.stores = stores;
  }

  /** Register the lazy-eval gauges once the CDI container is up. */
  void onStart(@Observes StartupEvent ev) {
    for (String queue : KNOWN_QUEUES) {
      Gauge.builder("titan.queue.depth", stores, s -> safeCountQueue(queue))
          .description("Currently-claimable QUEUED tasks for a given queue")
          .tag("queue", queue)
          .register(registry);
    }
    for (String status : KNOWN_WORKER_STATUSES) {
      Gauge.builder("titan.worker.count", stores, s -> safeCountAgents(status))
          .description("Agents bucketed by status")
          .tag("status", status)
          .register(registry);
    }
    LOGGER.log(Level.INFO, "[titan] Prometheus meters registered on /q/metrics");
  }

  /** Increment {@code titan_builds_total{status}} on terminal transition. */
  public void recordBuildTerminal(@NonNull String terminalStatus, long durationMs) {
    Counter.builder("titan.builds.total")
        .description("Builds reaching a terminal status")
        .tag("status", terminalStatus)
        .register(registry)
        .increment();
    Timer.builder("titan.build.duration")
        .description("Wall-clock duration of completed builds")
        .tag("status", terminalStatus)
        .publishPercentileHistogram()
        .register(registry)
        .record(durationMs, TimeUnit.MILLISECONDS);
  }

  /** Increment {@code titan_audit_events_total{action}} per audit emit. */
  public void recordAuditEvent(@NonNull String action) {
    Counter.builder("titan.audit.events.total")
        .description("Audit events emitted")
        .tag("action", action)
        .register(registry)
        .increment();
  }

  private double safeCountQueue(@NonNull String queue) {
    try {
      return stores.taskQueue().countQueuedByQueue(queue);
    } catch (RuntimeException e) {
      LOGGER.log(Level.FINE, "[titan] queue-depth gauge read failed: {0}", e.getMessage());
      return Double.NaN;
    }
  }

  private double safeCountAgents(@NonNull String status) {
    try {
      return stores.agents().countByStatus(status);
    } catch (RuntimeException e) {
      LOGGER.log(Level.FINE, "[titan] worker-count gauge read failed: {0}", e.getMessage());
      return Double.NaN;
    }
  }
}

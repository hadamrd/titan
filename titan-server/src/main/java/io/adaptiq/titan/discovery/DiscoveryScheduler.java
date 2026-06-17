package io.adaptiq.titan.discovery;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * Quarkus {@code @Scheduled} driver for {@link DiscoveryService} (closes #275).
 *
 * <p>Cadence is configurable via {@code quarkus.scheduler.titan.discovery.every}; defaults to one
 * minute. A non-reentrant guard skips a tick while the previous one is still running, so a long
 * network call cannot stack up overlapping passes.
 */
@ApplicationScoped
public class DiscoveryScheduler {

  private static final Logger LOG = Logger.getLogger(DiscoveryScheduler.class);

  private final DiscoveryService service;
  private final AtomicBoolean running = new AtomicBoolean(false);

  public DiscoveryScheduler(DiscoveryService service) {
    this.service = service;
  }

  @Scheduled(
      every = "{quarkus.scheduler.titan.discovery.every:1m}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  public void tick() {
    if (!running.compareAndSet(false, true)) {
      // Defensive: SKIP above already serialises Quarkus, but this guards a programmatic call.
      return;
    }
    try {
      int triggered = service.pollAll();
      if (triggered > 0) {
        LOG.infof("[titan-discovery] tick: %d build(s) triggered", triggered);
      }
    } catch (RuntimeException e) {
      LOG.error("[titan-discovery] scheduled tick failed", e);
    } finally {
      running.set(false);
    }
  }
}

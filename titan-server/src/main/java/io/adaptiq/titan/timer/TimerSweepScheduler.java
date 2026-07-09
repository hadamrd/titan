package io.adaptiq.titan.timer;

import io.adaptiq.titan.store.TitanStores;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Quarkus {@code @Scheduled} driver for {@link TimerSweepWorker} — the production wiring of the
 * durable-timer firing loop AND the approval-timeout sweep (closes #81).
 *
 * <p>Without this bean {@link TimerSweepWorker#sweep} is never invoked in {@code titan-server}: the
 * worker's javadoc claims "the server's ScheduledExecutorService drives it at the PERIOD_MS
 * cadence", but — exactly like the {@code QueueProcessor} gap fixed by {@code
 * QueueProcessorScheduler} (#486) — that executor was never written when the engine was lifted out
 * of titan-plugin. Consequence on every rig: an {@code approval: { timeout: 5s }} gate parked
 * PENDING forever (spec 25's timeout path), and durable {@code sleep:} timers on {@code
 * titan.timers} never fired.
 *
 * <p>Cadence is configurable via {@code quarkus.scheduler.titan.timer-sweep.every}; defaults to 5s
 * ({@link TimerSweepWorker#PERIOD_MS}) — approval timeouts have second-level granularity, so a
 * few-second sweep latency is within contract. Idempotency and multi-controller safety live in the
 * layers below: the timer DAO's token-correlated claim makes each timer fire exactly once across
 * concurrent controllers, and the approvals sweep is a {@code WHERE status = 'PENDING'} CAS — a row
 * decided by a human a moment before the deadline is never overwritten. Both passes are no-ops on
 * empty tables. {@code SKIP} concurrency plus the worker's own {@link AtomicBoolean} guard keep a
 * slow sweep from stacking; the observable-failure log lines ("timer ... fired", "approval sweep:
 * ... timed out") are emitted by the worker/service only on ticks that actually flip something.
 */
@ApplicationScoped
public class TimerSweepScheduler {

  private static final Logger LOG = Logger.getLogger(TimerSweepScheduler.class.getName());

  private final TitanStores stores;
  private final TimerSweepWorker worker = new TimerSweepWorker();
  private final AtomicBoolean running = new AtomicBoolean(false);

  public TimerSweepScheduler(TitanStores stores) {
    this.stores = stores;
  }

  @Scheduled(
      every = "{quarkus.scheduler.titan.timer-sweep.every:5s}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  public void tick() {
    if (!running.compareAndSet(false, true)) {
      // Defensive: SKIP above already serialises Quarkus, but this guards a programmatic call.
      return;
    }
    try {
      worker.sweep(stores);
    } catch (RuntimeException e) {
      LOG.log(Level.SEVERE, "[titan] scheduled timer sweep failed", e);
    } finally {
      running.set(false);
    }
  }
}

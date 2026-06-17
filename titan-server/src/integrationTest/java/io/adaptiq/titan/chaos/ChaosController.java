package io.adaptiq.titan.chaos;

import io.adaptiq.titan.queue.QueueProcessor;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.timer.TimerSweepWorker;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One controller thread for the chaos rig — drives the real {@link QueueProcessor#tick(TitanStores,
 * String, int)} loop and the {@link TimerSweepWorker} against its own {@link TitanStores} (own
 * connection pool). N of these run concurrently against one PostgreSQL, exercising the real
 * claim/reap/dispatch layer including multi-controller SKIP LOCKED contention and stale-task
 * recovery.
 */
final class ChaosController implements Runnable {

  private final String controllerId;
  private final QueueProcessor queueProcessor;
  private final TitanStores stores;
  private final int reapTimeoutSeconds;
  private final AtomicBoolean running;
  private final AtomicBoolean paused;

  ChaosController(
      String controllerId,
      QueueProcessor queueProcessor,
      TitanStores stores,
      int reapTimeoutSeconds,
      AtomicBoolean running,
      AtomicBoolean paused) {
    this.controllerId = controllerId;
    this.queueProcessor = queueProcessor;
    this.stores = stores;
    this.reapTimeoutSeconds = reapTimeoutSeconds;
    this.running = running;
    this.paused = paused;
  }

  @Override
  public void run() {
    while (running.get()) {
      try {
        if (paused.get()) {
          Thread.sleep(50L);
          continue;
        }
        try {
          queueProcessor.tick(stores, controllerId, reapTimeoutSeconds);
        } catch (RuntimeException e) {
          // Transient Toxiproxy fault mid-claim — tick is idempotent, next pass re-derives.
        }
        try {
          new TimerSweepWorker().sweep(stores);
        } catch (RuntimeException e) {
          // Ditto — stale timer claims are reclaimed by a later sweep.
        }
        Thread.sleep(100L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  String name() {
    return controllerId;
  }
}

package io.adaptiq.titan.chaos;

import io.adaptiq.titan.queue.QueueProcessor;
import io.adaptiq.titan.store.TitanStores;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The chaos cluster — N {@link ChaosController} threads and M {@link ChaosWorker} threads against
 * one database. Controllers drive the real {@link QueueProcessor#tick(TitanStores, String, int)}
 * loop; workers claim and execute EXECUTE_COMMAND tasks. Owns thread lifecycle and the calm-period
 * quiesce.
 */
final class ChaosRig {

  private final ChaosLedger ledger;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final List<AtomicBoolean> controllerPaused = new ArrayList<>();
  private final List<AtomicBoolean> workerPaused = new ArrayList<>();
  private final List<AtomicInteger> workerCrashSignals = new ArrayList<>();
  private final List<TitanStores> controllerStores = new ArrayList<>();
  private final List<Thread> threads = new ArrayList<>();

  /**
   * @param controllers number of controller threads
   * @param workers number of worker threads
   * @param reapTimeoutSeconds stale-task reap timeout passed to each controller's tick; use a
   *     test-scale value (e.g. 20) so crash-abandoned tasks recover quickly
   * @param ledger records every worker task execution
   * @param controllerStoresFactory supplies a fresh TitanStores (own pool) per controller
   * @param workerStores a shared TitanStores for all workers (claim is itself a CAS)
   */
  ChaosRig(
      int controllers,
      int workers,
      int reapTimeoutSeconds,
      ChaosLedger ledger,
      Supplier<TitanStores> controllerStoresFactory,
      TitanStores workerStores) {
    this.ledger = ledger;
    for (int i = 0; i < controllers; i++) {
      AtomicBoolean paused = new AtomicBoolean(false);
      TitanStores stores = controllerStoresFactory.get();
      controllerPaused.add(paused);
      controllerStores.add(stores);
      threads.add(
          new Thread(
              new ChaosController(
                  "chaos-ctl-" + i,
                  new QueueProcessor(),
                  stores,
                  reapTimeoutSeconds,
                  running,
                  paused),
              "chaos-ctl-" + i));
    }
    for (int i = 0; i < workers; i++) {
      AtomicBoolean paused = new AtomicBoolean(false);
      AtomicInteger crashSignal = new AtomicInteger(0);
      workerPaused.add(paused);
      workerCrashSignals.add(crashSignal);
      threads.add(
          new Thread(
              new ChaosWorker("wrk-" + i, workerStores, ledger, running, paused, crashSignal),
              "chaos-wrk-" + i));
    }
  }

  void start() {
    threads.forEach(Thread::start);
  }

  List<AtomicBoolean> controllerPauseFlags() {
    return controllerPaused;
  }

  List<AtomicBoolean> workerPauseFlags() {
    return workerPaused;
  }

  List<AtomicInteger> workerCrashSignals() {
    return Collections.unmodifiableList(workerCrashSignals);
  }

  /** Calm period — unpause everything and let the cluster run undisturbed for the given time. */
  void quiesce(long millis) throws InterruptedException {
    controllerPaused.forEach(p -> p.set(false));
    workerPaused.forEach(p -> p.set(false));
    Thread.sleep(millis);
  }

  void stop() throws InterruptedException {
    running.set(false);
    threads.forEach(Thread::interrupt);
    java.util.List<String> stuck = new java.util.ArrayList<>();
    for (Thread t : threads) {
      t.join(15_000L);
      if (t.isAlive()) {
        stuck.add(t.getName());
      }
    }
    if (!stuck.isEmpty()) {
      throw new AssertionError("chaos rig threads did not exit within 15s: " + stuck);
    }
  }
}

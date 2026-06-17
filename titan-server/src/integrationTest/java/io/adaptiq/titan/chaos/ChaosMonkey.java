package io.adaptiq.titan.chaos;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Seeded-RNG fault injector for the chaos rig. Process chaos pauses controller/worker threads (a
 * paused thread stops claiming — its DB claims expire and are reclaimed, faithfully modelling a
 * crash). Network chaos applies Toxiproxy toxics to the DB link. Every fault is transient; {@link
 * #chaosOff()} removes all of them. The seed is printed so any run replays exactly.
 */
final class ChaosMonkey {

  private final Random rng;
  private final Proxy dbProxy;
  private final List<AtomicBoolean> controllerPaused;
  private final List<AtomicBoolean> workerPaused;
  private final List<AtomicInteger> workerCrashSignals;

  ChaosMonkey(
      long seed,
      Proxy dbProxy,
      List<AtomicBoolean> controllerPaused,
      List<AtomicBoolean> workerPaused,
      List<AtomicInteger> workerCrashSignals) {
    this.rng = new Random(seed);
    this.dbProxy = dbProxy;
    this.controllerPaused = controllerPaused;
    this.workerPaused = workerPaused;
    this.workerCrashSignals = workerCrashSignals;
    System.out.println("[chaos] ChaosMonkey seed = " + seed + " (set -Dchaos.seed to replay)");
  }

  /**
   * Run one chaos window: alternate process and network faults for the given duration, each fault
   * transient and healed before the next. Always ends with {@link #chaosOff()}.
   */
  void runChaosWindow(long windowMillis) throws Exception {
    long deadline = System.currentTimeMillis() + windowMillis;
    try {
      while (System.currentTimeMillis() < deadline) {
        switch (rng.nextInt(6)) {
          case 0 -> pauseAControllerBriefly();
          case 1 -> pauseAWorkerBriefly();
          case 2 -> latencyToxic();
          case 3 -> partitionToxic();
          case 4 -> bandwidthToxic();
          case 5 -> crashAWorker();
          default -> throw new AssertionError("unreachable: nextInt(6) out of range");
        }
      }
    } finally {
      chaosOff();
    }
  }

  private void pauseAControllerBriefly() throws InterruptedException {
    if (controllerPaused.isEmpty()) {
      return;
    }
    AtomicBoolean p = controllerPaused.get(rng.nextInt(controllerPaused.size()));
    p.set(true);
    Thread.sleep(300L + rng.nextInt(700));
    p.set(false);
  }

  private void pauseAWorkerBriefly() throws InterruptedException {
    if (workerPaused.isEmpty()) {
      return;
    }
    AtomicBoolean p = workerPaused.get(rng.nextInt(workerPaused.size()));
    p.set(true);
    Thread.sleep(300L + rng.nextInt(700));
    p.set(false);
  }

  private void latencyToxic() throws Exception {
    dbProxy.toxics().latency("lat", ToxicDirection.DOWNSTREAM, 200 + rng.nextInt(800));
    Thread.sleep(400L + rng.nextInt(600));
    removeToxic("lat");
  }

  private void partitionToxic() throws Exception {
    dbProxy.toxics().timeout("part", ToxicDirection.DOWNSTREAM, 0);
    Thread.sleep(300L + rng.nextInt(500));
    removeToxic("part");
  }

  private void crashAWorker() {
    if (workerCrashSignals.isEmpty()) {
      return;
    }
    workerCrashSignals.get(rng.nextInt(workerCrashSignals.size())).incrementAndGet();
  }

  private void bandwidthToxic() throws Exception {
    dbProxy.toxics().bandwidth("bw", ToxicDirection.DOWNSTREAM, 1 + rng.nextInt(8));
    Thread.sleep(400L + rng.nextInt(600));
    removeToxic("bw");
  }

  private void removeToxic(String name) {
    try {
      dbProxy.toxics().get(name).remove();
    } catch (Exception ignored) {
      // already removed / never applied — fine
    }
  }

  /** Heal every fault — remove all toxics, unpause every thread, zero all crash signals. */
  void chaosOff() {
    for (String n : List.of("lat", "part", "bw")) {
      removeToxic(n);
    }
    controllerPaused.forEach(p -> p.set(false));
    workerPaused.forEach(p -> p.set(false));
    workerCrashSignals.forEach(s -> s.set(0));
  }
}

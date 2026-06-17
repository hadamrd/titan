package io.adaptiq.titan.scm.pulsar;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.scm.reconcile.ScmReconcileException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Poll driver for {@link PulsarRepoScanner} — issue #1280. Mirrors {@link
 * io.adaptiq.titan.scm.github.GithubScannerScheduler}: a non-reentrant guard around a periodic
 * {@code tick()} that runs the scan and hands each fresh discovery to a sink.
 *
 * <p><strong>Wiring note.</strong> The reconcile subsystem (and its {@code EventDedupeStore} /
 * dispatch tail) is not yet CDI-wired into boot, so this scheduler is a plain class rather than a
 * Quarkus {@code @ApplicationScoped @Scheduled} bean — the {@code @Scheduled} cadence + CDI
 * producer land with the Pulsar build-trigger slice (#T2) that also wires the dispatch sink. The
 * {@link #tick()} loop, the non-reentrant guard, and the typed-error isolation are all in place and
 * tested here so #T2 only has to add the annotations + the real sink.
 */
public final class PulsarScannerScheduler {

  private static final Logger LOGGER = Logger.getLogger(PulsarScannerScheduler.class.getName());

  private final PulsarRepoScanner scanner;
  private final Consumer<PulsarChangeDiscovery> sink;
  private final AtomicBoolean running = new AtomicBoolean(false);

  public PulsarScannerScheduler(
      @NonNull PulsarRepoScanner scanner, @NonNull Consumer<PulsarChangeDiscovery> sink) {
    this.scanner = Objects.requireNonNull(scanner, "scanner");
    this.sink = Objects.requireNonNull(sink, "sink");
  }

  /**
   * Run one scan pass. Non-reentrant: a tick that fires while the previous pass is still running is
   * skipped (returns {@code 0}). A {@link ScmReconcileException} from the scanner is caught +
   * logged so the periodic driver survives a node outage and the next tick retries.
   *
   * @return the number of fresh discoveries handed to the sink this pass
   */
  public int tick() {
    if (!running.compareAndSet(false, true)) {
      return 0;
    }
    try {
      List<PulsarChangeDiscovery> discoveries = scanner.scanAll();
      for (PulsarChangeDiscovery d : discoveries) {
        sink.accept(d);
      }
      if (!discoveries.isEmpty()) {
        LOGGER.log(Level.INFO, "[pulsar-scan] tick discovered {0} change(s)", discoveries.size());
      }
      return discoveries.size();
    } catch (ScmReconcileException e) {
      LOGGER.log(
          Level.WARNING,
          "[pulsar-scan] tick failed for repo {0}: {1}",
          new Object[] {e.repoExternalId(), e.getMessage()});
      return 0;
    } finally {
      running.set(false);
    }
  }
}

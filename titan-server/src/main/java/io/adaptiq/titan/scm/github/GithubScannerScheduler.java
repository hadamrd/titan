package io.adaptiq.titan.scm.github;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * Quarkus {@code @Scheduled} driver for {@link GithubRepoScanner} — Child B of #831.
 *
 * <p><strong>Fallback role (design 65 / issue #886).</strong> Pipeline discovery is primarily
 * event-driven: a push webhook re-parses the affected repo synchronously via {@link
 * GithubRepoScanner#scanSingleRepo(long, long)}. This scheduled crawler is the stale-state fallback
 * for installations whose webhook deliveries failed (or where the App was offline at delivery
 * time). Cadence dropped from 5m to 1h to reflect that role.
 *
 * <p>Cadence is configurable via {@code quarkus.scheduler.titan.github-scan.every} (env override
 * {@code TITAN_GITHUB_SCAN_EVERY}); defaults to one hour. A non-reentrant guard skips a tick while
 * the previous scan is still running, so a slow GitHub round-trip cannot stack up overlapping
 * passes.
 *
 * <p>Per-install / per-repo failures inside {@link GithubRepoScanner#scanAll()} are already
 * isolated; this scheduler only catches the catastrophic case ({@link Error} or a wrapper
 * RuntimeException) so the scheduler thread itself survives.
 */
@ApplicationScoped
public class GithubScannerScheduler {

  private static final Logger LOG = Logger.getLogger(GithubScannerScheduler.class);

  private final GithubRepoScanner scanner;
  private final AtomicBoolean running = new AtomicBoolean(false);

  public GithubScannerScheduler(GithubRepoScanner scanner) {
    this.scanner = scanner;
  }

  @Scheduled(
      every = "{quarkus.scheduler.titan.github-scan.every:1h}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  public void tick() {
    if (!running.compareAndSet(false, true)) {
      return; // defence-in-depth — SKIP above already serialises
    }
    try {
      GithubRepoScanner.ScanReport report = scanner.scanAll();
      if (report.installsScanned() > 0 || report.suspendedInstalls() > 0) {
        LOG.infof(
            "[titan-github] scan tick: %d install(s), %d repo(s), %d pipeline(s), %d suspended",
            report.installsScanned(),
            report.reposScanned(),
            report.pipelinesFound(),
            report.suspendedInstalls());
      }
    } catch (RuntimeException e) {
      LOG.error("[titan-github] scheduled scan failed", e);
    } finally {
      running.set(false);
    }
  }
}

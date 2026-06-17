package io.adaptiq.titan.queue;

import io.adaptiq.titan.store.TitanStores;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Quarkus {@code @Scheduled} driver for {@link QueueProcessor} — the production wiring of the
 * controller-side ORCHESTRATE claim loop.
 *
 * <p>Without this bean the {@link QueueProcessor#tick()} method is never invoked in {@code
 * titan-server}: builds inserted as {@code QUEUED} (either by {@code JobBuildsApi.triggerBuild} or
 * by {@code rig/local/dogfood-fire.sh}) sit forever on {@code task_queue} because nothing claims
 * them. The class-level javadoc on {@link QueueProcessor} states "the server's App schedules it via
 * a plain ScheduledExecutorService" — that App scheduler was never written when the engine was
 * lifted out of titan-plugin. This {@code @Scheduled} bean is the production fix (closes #486).
 *
 * <p>Cadence is configurable via {@code quarkus.scheduler.titan.queue.every}; defaults to {@code
 * 1s} (Quarkus {@code SimpleScheduler} floors sub-second cadences anyway; {@link
 * QueueProcessor#PERIOD_MS} is 500 ms but the controller-loop latency from 500 ms → 1 s is
 * negligible compared to actual step execution time). A non-reentrant guard skips a tick while the
 * previous one is still running so a long bake/synthesise pass cannot stack up overlapping claim
 * passes — mirrors {@code DiscoveryScheduler}.
 */
@ApplicationScoped
public class QueueProcessorScheduler {

  private static final Logger LOG = Logger.getLogger(QueueProcessorScheduler.class.getName());

  /** Floor for the {@code REAP_VISIBILITY_TIMEOUT_SECONDS} the processor uses (3600s). */
  private static final int REAP_VISIBILITY_TIMEOUT_SECONDS = 3600;

  private final TitanStores stores;
  private final QueueProcessor processor;
  private final String controllerId;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicBoolean pruning = new AtomicBoolean(false);
  private final AtomicBoolean buildRetentionRunning = new AtomicBoolean(false);

  /**
   * Retention horizon for {@code titan.task_archive} pruning (issue #633). Defaults to 30 days; set
   * to {@code 0} to disable the pruner entirely (operator opt-out — keeps full archive history).
   * Env: {@code TITAN_TASK_ARCHIVE_RETENTION_DAYS}.
   */
  @ConfigProperty(name = "titan.task-archive.retention-days", defaultValue = "30")
  int archiveRetentionDays;

  /**
   * Per-job build-retention cap (issue #637) — keep at most this many builds per job; older builds
   * (and their flow_nodes / artifact / test_result / task_queue / logs / task_archive rows) are
   * dropped by the daily prune. Defaults to 100; set to {@code 0} to disable the pruner entirely
   * (operator opt-out — keeps full history). Env: {@code TITAN_JOB_BUILD_RETENTION}.
   *
   * <p>Half A — single global default. Half B (follow-up) will read a per-job {@code
   * buildRetention:} block from the pipeline script and fall back to this value.
   */
  @ConfigProperty(name = "titan.job-build-retention", defaultValue = "100")
  int jobBuildRetention;

  public QueueProcessorScheduler(TitanStores stores) {
    this.stores = stores;
    this.processor = new QueueProcessor();
    this.controllerId = defaultControllerId();
  }

  private static String defaultControllerId() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "controller-unknown";
    }
  }

  @Scheduled(
      every = "{quarkus.scheduler.titan.queue.every:1s}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  public void tick() {
    if (!running.compareAndSet(false, true)) {
      // Defensive: SKIP above already serialises Quarkus, but this guards a programmatic call.
      return;
    }
    try {
      processor.tick(stores, controllerId, REAP_VISIBILITY_TIMEOUT_SECONDS);
    } catch (RuntimeException e) {
      LOG.log(Level.SEVERE, "[titan-queue] scheduled tick failed", e);
    } finally {
      running.set(false);
    }
  }

  /**
   * Daily prune of {@code titan.task_archive} past the retention horizon (issue #633). Runs at
   * 03:00 server time so the sweep does not collide with a busy office-hours window. {@code SKIP}
   * concurrency guarantees only one prune runs at a time even if a sweep ever runs long.
   */
  @Scheduled(
      cron = "{quarkus.scheduler.titan.archive-prune.cron:0 0 3 * * ?}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  public void pruneArchive() {
    if (!pruning.compareAndSet(false, true)) {
      return;
    }
    try {
      TaskArchivePruner.prune(stores, archiveRetentionDays);
    } catch (RuntimeException e) {
      LOG.log(Level.SEVERE, "[titan-queue] scheduled archive prune failed", e);
    } finally {
      pruning.set(false);
    }
  }

  /**
   * Daily per-job build-retention prune (issue #637). Runs at 03:30 server time — offset from the
   * 03:00 archive prune so the two sweeps cannot pile up under the same idle window. {@code SKIP}
   * concurrency guarantees one prune at a time.
   */
  @Scheduled(
      cron = "{quarkus.scheduler.titan.build-retention.cron:0 30 3 * * ?}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  public void pruneJobBuildRetention() {
    if (!buildRetentionRunning.compareAndSet(false, true)) {
      return;
    }
    try {
      JobBuildRetentionPruner.prune(stores, jobBuildRetention);
    } catch (RuntimeException e) {
      LOG.log(Level.SEVERE, "[titan-queue] scheduled job-build retention prune failed", e);
    } finally {
      buildRetentionRunning.set(false);
    }
  }
}

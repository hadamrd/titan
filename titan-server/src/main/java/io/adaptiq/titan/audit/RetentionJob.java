package io.adaptiq.titan.audit;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.TitanStores;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Nightly audit-log retention purge (closes #1104).
 *
 * <p>{@code titan.audit_log} (V20) grows unbounded; on a busy rig that is 1M+ rows after a few
 * months → query slowdown + disk pressure. This cron deletes rows older than the per-kind retention
 * horizon resolved from {@code titan.audit_retention_policy} (kind-specific &gt; default; see
 * {@link AuditRetentionPolicy}).
 *
 * <p><strong>Best-effort &amp; bounded.</strong> The sweep is best-effort: a failure on one kind is
 * logged and the remaining kinds still run; the next nightly tick retries leftovers. Each kind is
 * drained in batches of {@code titan.audit-retention.batch-size} rows (default 10 000), one
 * transaction per batch (each {@code deleteOlderThan} call is its own statement / implicit
 * transaction), so a huge backlog never holds one giant lock.
 *
 * <p><strong>0-day guard.</strong> A kind whose resolved horizon is {@code <= 0} is an operator
 * misconfiguration that would purge the kind's entire history. {@link AuditRetentionPolicy#resolve}
 * raises {@link InvalidRetentionPolicyException}; this job catches it per-kind, logs it loudly at
 * {@code SEVERE}, and skips that kind — it never deletes everything. (The override endpoint and the
 * DB CHECK reject {@code <= 0} up front; this is defence in depth against a manual DB edit.)
 *
 * <p>Cadence is configurable via {@code quarkus.scheduler.titan.audit-retention.cron}; defaults to
 * {@code 0 45 3 * * ?} (03:45 server time) — offset from the 03:00 task-archive prune and the 03:30
 * build-retention prune so the nightly sweeps do not pile up under the same idle window. Set the
 * property to an empty string to disable the cron entirely.
 */
@ApplicationScoped
public class RetentionJob {

  private static final Logger LOG = Logger.getLogger(RetentionJob.class.getName());

  /** Hard floor on the batch size so a misconfigured 0/negative value cannot busy-loop. */
  static final int MIN_BATCH_SIZE = 100;

  private final TitanStores stores;
  private final int batchSize;
  private final AtomicBoolean running = new AtomicBoolean(false);

  public RetentionJob(
      TitanStores stores,
      @ConfigProperty(name = "titan.audit-retention.batch-size", defaultValue = "10000")
          int batchSize) {
    this.stores = stores;
    this.batchSize = Math.max(batchSize, MIN_BATCH_SIZE);
  }

  @Scheduled(
      cron = "{quarkus.scheduler.titan.audit-retention.cron:0 45 3 * * ?}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  public void tick() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      purge(Instant.now());
    } catch (RuntimeException e) {
      LOG.log(Level.SEVERE, "[titan-audit-retention] scheduled purge failed", e);
    } finally {
      running.set(false);
    }
  }

  /**
   * One purge pass. Exposed package-private so the IT can drive it deterministically against a
   * fixed {@code now} without waiting for a real cron tick.
   *
   * <p>For every distinct action present in {@code titan.audit_log}, resolve its retention horizon
   * and delete rows older than {@code now - horizon} in {@link #batchSize}-row batches. A kind
   * whose policy is invalid (0-day) is skipped loudly; everything else still runs.
   *
   * @param now the reference instant (cutoff = {@code now - horizonDays})
   * @return a small audit record of work done this pass
   */
  @NonNull
  PurgePass purge(@NonNull Instant now) {
    AuditRetentionPolicy policy = AuditRetentionPolicy.of(stores.auditRetentionPolicy().findAll());
    List<String> kinds = stores.auditLog().distinctActions();
    return purge(now, policy, kinds, stores.auditLog()::deleteOlderThan);
  }

  /**
   * The pure purge loop, decoupled from {@link TitanStores} so the best-effort isolation guarantee
   * can be unit-tested without a database: a {@link BatchDeleter} that throws for one kind must not
   * stop the rest of the sweep. The {@link #purge(Instant)} overload wires the real store
   * collaborators.
   *
   * @param now the reference instant (cutoff = {@code now - horizonDays})
   * @param policy resolved retention horizons (kind-specific &gt; default)
   * @param kinds the distinct action codes present in the audit log
   * @param deleter the batched delete primitive (real: {@code auditLog()::deleteOlderThan})
   * @return a small audit record of work done this pass
   */
  @NonNull
  PurgePass purge(
      @NonNull Instant now,
      @NonNull AuditRetentionPolicy policy,
      @NonNull List<String> kinds,
      @NonNull BatchDeleter deleter) {
    int totalDeleted = 0;
    int kindsPurged = 0;
    int kindsSkipped = 0;
    int kindsFailed = 0;

    for (String kind : kinds) {
      if (kind == null) {
        continue;
      }
      int days;
      try {
        days = policy.resolve(kind);
      } catch (InvalidRetentionPolicyException e) {
        // 0-day / negative policy — skip loudly, never delete the whole kind.
        LOG.log(Level.SEVERE, "[titan-audit-retention] skipping kind with invalid policy", e);
        kindsSkipped++;
        continue;
      }
      Instant cutoff = now.minus(Duration.ofDays(days));
      try {
        int deletedForKind = deleteAllOlderThan(deleter, kind, cutoff);
        if (deletedForKind > 0) {
          kindsPurged++;
          totalDeleted += deletedForKind;
        }
      } catch (RuntimeException e) {
        // Best-effort: a poison kind cannot block the rest of the sweep; the next tick retries.
        // The lazy supplier keeps message assembly off the hot path and lets us pass the cause
        // (java.util.logging has no log(Level, String, Object, Throwable) overload). kindsFailed
        // makes the partial failure observable to callers/operators rather than swallowed silently.
        kindsFailed++;
        LOG.log(
            Level.WARNING,
            e,
            () -> "[titan-audit-retention] purge failed for kind " + kind + " — continuing");
      }
    }

    if (totalDeleted > 0 || kindsSkipped > 0 || kindsFailed > 0) {
      LOG.log(
          Level.INFO,
          "[titan-audit-retention] purged {0} row(s) across {1} kind(s); {2} kind(s) skipped"
              + " (invalid policy); {3} kind(s) failed (best-effort, retried next tick)",
          new Object[] {totalDeleted, kindsPurged, kindsSkipped, kindsFailed});
    }
    return new PurgePass(totalDeleted, kindsPurged, kindsSkipped, kindsFailed);
  }

  /**
   * Drain one kind in batches until a batch comes back short of {@link #batchSize}. Each batch is a
   * single bounded DELETE (its own transaction).
   */
  private int deleteAllOlderThan(
      @NonNull BatchDeleter deleter, @NonNull String kind, @NonNull Instant cutoff) {
    int deleted = 0;
    int batch;
    do {
      batch = deleter.deleteOlderThan(kind, cutoff, batchSize);
      deleted += batch;
    } while (batch == batchSize);
    return deleted;
  }

  /**
   * The batched-delete primitive the purge loop drives. In production this is bound to {@code
   * stores.auditLog()::deleteOlderThan}; tests supply a fake to exercise the per-kind isolation
   * guarantee without a database.
   */
  @FunctionalInterface
  interface BatchDeleter {
    int deleteOlderThan(@NonNull String kind, @NonNull Instant cutoff, int limit);
  }

  /**
   * Package-private audit record returned by {@link #purge(Instant)} — used by the IT and the
   * isolation unit test.
   *
   * @param rowsDeleted total rows deleted across all kinds this pass
   * @param kindsPurged kinds that had at least one row deleted
   * @param kindsSkipped kinds skipped because their policy was invalid (0-day / negative)
   * @param kindsFailed kinds whose delete threw and were skipped best-effort (retried next tick)
   */
  record PurgePass(int rowsDeleted, int kindsPurged, int kindsSkipped, int kindsFailed) {}
}

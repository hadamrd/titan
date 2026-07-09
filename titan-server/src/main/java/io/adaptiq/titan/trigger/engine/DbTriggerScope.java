package io.adaptiq.titan.trigger.engine;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.build.BuildEnqueuer;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobTriggerRow;
import java.sql.Connection;
import java.time.Instant;

/**
 * Db-backed {@link TriggerScope} — the engine's view of one job's trigger state, bound to the
 * transactional connection that {@link DbTriggerStore} locked (design/50, Tier 3).
 *
 * <p>Every operation runs on {@code conn}, the connection holding the {@code FOR UPDATE} lock on
 * the {@code titan.jobs} row: the last-fired read, the coalescing check, the build insert and the
 * state update all commit as one atomic unit (design/50 D4). The instance is valid only for the
 * duration of the {@code inLockedScope} callback.
 */
final class DbTriggerScope implements TriggerScope {

  private final Connection conn;
  private final TitanStores stores;
  private final long jobId;

  DbTriggerScope(@NonNull Connection conn, @NonNull TitanStores stores, long jobId) {
    this.conn = conn;
    this.stores = stores;
    this.jobId = jobId;
  }

  @Override
  @CheckForNull
  public Instant lastFiredAt(@NonNull String triggerId) {
    return stores.jobTriggers().find(conn, jobId, triggerId).map(r -> r.lastFiredAt).orElse(null);
  }

  @Override
  public boolean buildInFlight() {
    return stores.builds().hasNonTerminalBuild(conn, jobId);
  }

  @Override
  public void fire() {
    int buildNumber = stores.builds().nextBuildNumber(conn, jobId);
    BuildRow build = new BuildRow();
    build.jobId = jobId;
    build.buildNumber = buildNumber;
    build.status = "QUEUED";
    build.queuedAt = Instant.now();
    build.triggeredBy = "trigger";
    build.triggerType = "TitanTimerCause";
    long buildId = stores.builds().insert(conn, build);

    // Issue #106: this used to hand-roll the ORCHESTRATE row with a {"buildId":N} payload — no
    // "action" key — so every cron-fired build fail-closed in QueueProcessor.dispatch with
    // "unknown orchestration action 'null'" before the worker ever saw it. The shared helper is
    // the single definition of the entry-task contract (action=SYNTHESIZE, priority, lease).
    BuildEnqueuer.enqueueSynthesizeEntryTask(conn, buildId);
  }

  @Override
  public void recordFired(@NonNull String triggerId, @NonNull Instant when) {
    stores.jobTriggers().recordState(conn, jobId, triggerId, when, null);
  }

  @Override
  public void recordError(@NonNull String triggerId, @NonNull String message) {
    JobTriggerRow existing = stores.jobTriggers().find(conn, jobId, triggerId).orElse(null);
    Instant lastFired = existing != null ? existing.lastFiredAt : null;
    stores.jobTriggers().recordState(conn, jobId, triggerId, lastFired, message);
  }
}

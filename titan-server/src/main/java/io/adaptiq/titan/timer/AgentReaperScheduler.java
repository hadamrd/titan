package io.adaptiq.titan.timer;

import io.adaptiq.titan.store.AgentLifecycle;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.AgentRow;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Quarkus {@code @Scheduled} driver that wires {@link AgentLifecycle} into the worker-heartbeat
 * loop (closes #719).
 *
 * <p>Workers do NOT call any controller-side register/drop REST endpoint today — {@code
 * titan-worker}'s {@code WorkerDb} writes {@code titan.agents} directly over JDBC (register +
 * heartbeat). So the controller has no synchronous codepath in which to fire {@code JOINED} /
 * {@code LEFT}. Instead, this scheduler is the indirection layer:
 *
 * <ul>
 *   <li><b>JOINED backfill</b> — every tick, walk {@code titan.agents} and call {@link
 *       AgentLifecycle#recordJoinedIfAbsent(String)} for each agent_id. The {@code recordJoined}
 *       dedup window (5min, #714) makes this naturally idempotent and effectively detects "first
 *       heartbeat from a new agent": a brand-new agent row created by {@code WorkerDb.register}'s
 *       INSERT path will get one {@code JOINED} on the next tick; subsequent ticks are no-ops.
 *   <li><b>Stale sweep</b> — {@link io.adaptiq.titan.store.AgentDao#findStale(int)} returns agents
 *       still marked {@code ONLINE} whose {@code last_heartbeat} is older than the cutoff (or never
 *       set). For each, call {@link AgentLifecycle#markOffline(String)} — flips the agents row to
 *       {@code OFFLINE} and emits {@code LEFT}. Because {@code markOffline} flips status before the
 *       next sweep, a second tick won't double-emit {@code LEFT} (the agent is no longer {@code
 *       ONLINE} so {@link io.adaptiq.titan.store.AgentDao#findStale(int)} skips it).
 * </ul>
 *
 * <p>Cadence is configurable via {@code quarkus.scheduler.titan.agent-reaper.every} (default 30s).
 * The stale threshold is {@code titan.agent-reaper.stale-seconds} (default 90s — 3x the worker's
 * 30s heartbeat tick, matching the existing liveness window used elsewhere).
 *
 * <p>Non-reentrant guard so a slow DB call cannot stack overlapping passes.
 */
@ApplicationScoped
public class AgentReaperScheduler {

  private static final Logger LOG = Logger.getLogger(AgentReaperScheduler.class);

  private final TitanStores stores;
  private final AgentLifecycle lifecycle;
  private final int staleSeconds;
  private final AtomicBoolean running = new AtomicBoolean(false);

  public AgentReaperScheduler(
      TitanStores stores,
      @ConfigProperty(name = "titan.agent-reaper.stale-seconds", defaultValue = "90")
          int staleSeconds) {
    this.stores = stores;
    this.lifecycle = AgentLifecycle.of(stores);
    this.staleSeconds = staleSeconds;
  }

  @Scheduled(
      every = "{quarkus.scheduler.titan.agent-reaper.every:30s}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  public void tick() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      runOnce();
    } catch (RuntimeException e) {
      LOG.error("[titan-agent-reaper] scheduled tick failed", e);
    } finally {
      running.set(false);
    }
  }

  /**
   * One reaper pass — JOINED backfill + LEFT-on-stale. Exposed package-private so the IT can drive
   * the loop deterministically without waiting for a real scheduler tick.
   *
   * @return a small audit record of work done this pass
   */
  ReaperPass runOnce() {
    int joinedEmitted = 0;
    int leftEmitted = 0;

    // Pass 1 — JOINED backfill for every known agent. Dedup inside recordJoined keeps it cheap.
    List<AgentRow> all = stores.agents().listAll();
    for (AgentRow row : all) {
      int inserted = stores.agentEvents().recordJoined(row.agentId);
      if (inserted == 1) {
        joinedEmitted++;
      }
    }

    // Pass 2 — LEFT emission for stale ONLINE agents.
    List<AgentRow> stale = stores.agents().findStale(staleSeconds);
    for (AgentRow row : stale) {
      lifecycle.markOffline(row.agentId);
      leftEmitted++;
    }

    if (joinedEmitted > 0 || leftEmitted > 0) {
      LOG.infof(
          "[titan-agent-reaper] tick: agents=%d joined=%d left=%d (stale>%ds)",
          all.size(), joinedEmitted, leftEmitted, staleSeconds);
    }
    return new ReaperPass(all.size(), joinedEmitted, leftEmitted);
  }

  /** Package-private audit record returned by {@link #runOnce()} — used by the IT. */
  record ReaperPass(int agentsScanned, int joinedEmitted, int leftEmitted) {}
}

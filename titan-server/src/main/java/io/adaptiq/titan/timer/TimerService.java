package io.adaptiq.titan.timer;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.TimerDao;
import java.time.Instant;

/**
 * The consumer-facing façade for the durable-timer subsystem — the API the orchestrator calls to
 * arm and cancel timers. A thin, typed layer over {@link TimerDao}: it owns the {@link Kind} enum
 * so callers never pass a raw string, and it names the idempotent-arm contract.
 *
 * <p>Arming is idempotent on {@code (buildId, nodeId, kind)} — a re-run of the orchestrator's
 * {@code advance()} never produces a duplicate timer.
 */
public final class TimerService {

  /** What a fired timer means — resolved by the orchestrator, not the sweeper. */
  public enum Kind {
    SLEEP,
    TIMEOUT,
    RETRY_BACKOFF,
    GATE_RESUME
  }

  private final TimerDao dao;

  public TimerService(@NonNull TimerDao dao) {
    this.dao = dao;
  }

  /**
   * Arm a timer that fires at {@code fireAt}. Idempotent on {@code (buildId,nodeId,kind)}: returns
   * {@code true} if a new timer was armed, {@code false} if an active timer already covered that
   * triple.
   */
  public boolean arm(
      @NonNull Kind kind,
      long buildId,
      @NonNull String nodeId,
      @NonNull Instant fireAt,
      @Nullable String payloadJson) {
    return dao.armIfAbsent(buildId, nodeId, kind.name(), fireAt, payloadJson) == 1;
  }

  /**
   * Cancel the {@code ARMED} timer for {@code (buildId,nodeId,kind)}; {@code true} if one was
   * cancelled.
   */
  public boolean cancel(@NonNull Kind kind, long buildId, @NonNull String nodeId) {
    return dao.cancel(buildId, nodeId, kind.name()) > 0;
  }

  /**
   * Cancel every {@code ARMED} timer of a build (used when the build is aborted). Returns the
   * count.
   */
  public int cancelAll(long buildId) {
    return dao.cancelAll(buildId);
  }
}

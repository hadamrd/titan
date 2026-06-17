package io.adaptiq.titan.trigger;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ServiceLoader;

/**
 * The operator-tunable knobs of the scheduler engine (design/52 D1).
 *
 * <p>The neutral {@code trigger} module declares the SPI and ships {@link
 * DefaultSchedulerSettings}; a consumer supplies real values by registering a higher-{@link
 * #ordinal()} implementation via JDK {@code ServiceLoader}.
 *
 * <p>{@link #current()} resolves the effective settings — the highest-ordinal registered instance,
 * or the defaults when none is registered (the engine reads this from hot paths that must never
 * fail mid-boot).
 */
public interface SchedulerSettings {

  /** How often the engine's periodic poll runs. */
  long pollIntervalMillis();

  /**
   * How far back a missed schedule is caught up after a controller outage. An outage longer than
   * this collapses to "the next occurrence" rather than replaying every missed slot.
   */
  @NonNull
  Duration catchUpWindow();

  /**
   * The budget for a single {@code Trigger.evaluate()} call. A trigger that exceeds it is cut off
   * and treated as a skip (design/52 D3) — a hung trigger can never freeze the engine.
   */
  @NonNull
  Duration triggerEvaluationTimeout();

  /** When {@code true} the engine evaluates nothing — an operator kill-switch (design/52 D5). */
  boolean paused();

  /**
   * Higher-ordinal implementations win when several are discovered via ServiceLoader. Default is 0;
   * {@link DefaultSchedulerSettings} returns 0, an operator override returns >0.
   */
  default int ordinal() {
    return 0;
  }

  /** The effective settings — the highest-ordinal registered instance, else the defaults. */
  @NonNull
  static SchedulerSettings current() {
    SchedulerSettings winner = DefaultSchedulerSettings.INSTANCE;
    for (SchedulerSettings s : ServiceLoader.load(SchedulerSettings.class)) {
      if (s.ordinal() > winner.ordinal()) {
        winner = s;
      }
    }
    return winner;
  }
}

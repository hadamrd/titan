package io.adaptiq.titan.trigger;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * The built-in {@link SchedulerSettings} — safe defaults the trigger module ships so the engine
 * runs with no configuration at all (design/52 D1). Registered at ordinal 0; any consumer-supplied
 * settings register higher and win.
 */
public class DefaultSchedulerSettings implements SchedulerSettings {

  /** A standalone instance for the {@link SchedulerSettings#current()} fallback. */
  static final SchedulerSettings INSTANCE = new DefaultSchedulerSettings();

  @Override
  public long pollIntervalMillis() {
    return TimeUnit.MINUTES.toMillis(1);
  }

  @Override
  @NonNull
  public Duration catchUpWindow() {
    return Duration.ofHours(25);
  }

  @Override
  @NonNull
  public Duration triggerEvaluationTimeout() {
    return Duration.ofSeconds(30);
  }

  @Override
  public boolean paused() {
    return false;
  }
}

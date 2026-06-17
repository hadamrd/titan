package io.adaptiq.titan.trigger;

/**
 * Server-side scheduler-engine policy (design/52 D2). Plain POJO — no JCasC binding in the
 * standalone product; the server reads scheduler config from environment / Quarkus
 * {@code @ConfigMapping} and constructs a {@link SchedulerConfig} from it.
 *
 * <p>Every getter clamps to a sane floor: a bad config value degrades to a safe default rather than
 * breaking the engine (e.g. a 1-second poll, or a zero evaluation budget that would skip every
 * trigger).
 */
public class SchedulerConfig {

  private int pollIntervalSeconds = 60;
  private int catchUpHours = 25;
  private int triggerEvaluationTimeoutSeconds = 30;
  private boolean paused;

  public SchedulerConfig() {}

  /** Engine poll cadence. Floor 15s — a hotter poll only burns CPU; cron granularity is 1 min. */
  public int getPollIntervalSeconds() {
    return Math.max(15, pollIntervalSeconds);
  }

  public void setPollIntervalSeconds(int pollIntervalSeconds) {
    this.pollIntervalSeconds = pollIntervalSeconds;
  }

  /** How far back a missed cron is caught up after an outage. Floor 1h. */
  public int getCatchUpHours() {
    return Math.max(1, catchUpHours);
  }

  public void setCatchUpHours(int catchUpHours) {
    this.catchUpHours = catchUpHours;
  }

  /** Per-trigger {@code evaluate()} budget. Floor 1s — zero would skip every trigger. */
  public int getTriggerEvaluationTimeoutSeconds() {
    return Math.max(1, triggerEvaluationTimeoutSeconds);
  }

  public void setTriggerEvaluationTimeoutSeconds(int triggerEvaluationTimeoutSeconds) {
    this.triggerEvaluationTimeoutSeconds = triggerEvaluationTimeoutSeconds;
  }

  /** Whether the whole scheduler engine is paused — an operator kill-switch. */
  public boolean isPaused() {
    return paused;
  }

  public void setPaused(boolean paused) {
    this.paused = paused;
  }
}

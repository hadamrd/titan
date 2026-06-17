package io.adaptiq.titan.trigger;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The cause stamped on a build started by the scheduler engine (design/50).
 *
 * <p>The orchestrator records {@link #getShortDescription()} into {@code titan.builds.triggered_by}
 * and the simple class name into {@code trigger_type}, so a timer-started build is distinguishable
 * from a manual or webhook one in the build record and UI.
 *
 * <p>This is a plain short-description record — its only role is to carry the human-readable reason
 * a build was started by a timer.
 */
public class TimerCause {

  @NonNull
  public String getShortDescription() {
    return "Started by Titan timer";
  }
}

package io.adaptiq.titan.trigger.cron;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * A recurrence rule — the pure, persistence-free core of the trigger module (design/50, Tier 1).
 *
 * <p>A {@code Schedule} answers one question: <em>has a scheduled instant elapsed since I last
 * acted?</em> It holds no state and knows nothing of jobs, triggers or databases, which makes it
 * exhaustively unit-testable in isolation. {@link CronSchedule} is the only implementation today;
 * an interval- or calendar-based schedule would be a sibling.
 */
public interface Schedule {

  /**
   * Whether a scheduled instant lies in the half-open interval {@code (since, now]} — i.e. the
   * schedule became due at some point after it last acted and at or before now.
   *
   * @param since when the owner last acted on this schedule; {@code null} means "never" — a fresh
   *     schedule is not retroactively due, it becomes due only at a future instant.
   * @param now the evaluation instant.
   */
  boolean isDue(@CheckForNull Instant since, @NonNull Instant now);
}

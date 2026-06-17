package io.adaptiq.titan.trigger.cron;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.trigger.cron.internal.CronTabList;
import io.adaptiq.titan.trigger.cron.internal.Hash;
import java.time.Duration;
import java.time.Instant;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Optional;

/**
 * A {@link Schedule} backed by the vendored cron grammar (design/50 D3) — under {@link
 * io.adaptiq.titan.trigger.cron.internal}, supporting {@code H} hashing, the {@code @daily}/{@code
 * @hourly} aliases, the {@code TZ=} prefix and multi-line specs.
 *
 * <p>{@link #isDue} walks minute boundaries from {@code since} to {@code now}; the walk is clamped
 * to the {@link #catchUpWindow}, so a long controller outage collapses to a single catch-up rather
 * than a runaway loop (design/52 makes the window operator-configurable). Instances are immutable
 * and cheap; construct one per evaluation.
 */
public final class CronSchedule implements Schedule {

  /** The default catch-up window when none is supplied — a day and an hour. */
  public static final Duration DEFAULT_CATCH_UP = Duration.ofHours(25);

  private final CronTabList tabs;
  private final Duration catchUp;

  private CronSchedule(@NonNull CronTabList tabs, @NonNull Duration catchUp) {
    this.tabs = tabs;
    this.catchUp = catchUp;
  }

  /**
   * Parse a cron spec into a schedule with the {@link #DEFAULT_CATCH_UP default} catch-up window.
   *
   * @param spec the cron expression — {@code H}/{@code @alias}/{@code TZ=} all honoured.
   * @param hashSeed the {@code H}-hash seed; pass a stable per-owner string (design/50 D3).
   * @throws IllegalArgumentException if the spec is not a valid cron expression.
   */
  @NonNull
  public static CronSchedule of(@NonNull String spec, @NonNull String hashSeed) {
    return of(spec, hashSeed, DEFAULT_CATCH_UP);
  }

  /**
   * Parse a cron spec into a schedule with an explicit catch-up window (design/52).
   *
   * @throws IllegalArgumentException if the spec is not a valid cron expression.
   */
  @NonNull
  public static CronSchedule of(
      @NonNull String spec, @NonNull String hashSeed, @NonNull Duration catchUp) {
    try {
      return new CronSchedule(
          CronTabList.create(spec, Hash.from(hashSeed)),
          catchUp.isNegative() || catchUp.isZero() ? DEFAULT_CATCH_UP : catchUp);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          e.getMessage() != null ? e.getMessage() : "invalid cron expression", e);
    }
  }

  /** How far back {@link #isDue} looks for a missed occurrence — the catch-up clamp. */
  @NonNull
  public Duration catchUpWindow() {
    return catchUp;
  }

  @Override
  public boolean isDue(@CheckForNull Instant since, @NonNull Instant now) {
    Instant from = since != null ? since : now;
    Instant floor = now.minus(catchUp);
    if (from.isBefore(floor)) {
      from = floor;
    }
    // The first minute boundary strictly after `from` — never re-matches the minute the owner
    // last acted on (last-fired is stamped mid-minute, so flooring then +1 excludes it).
    Calendar cursor = new GregorianCalendar();
    cursor.setTimeInMillis(from.toEpochMilli());
    cursor.set(Calendar.SECOND, 0);
    cursor.set(Calendar.MILLISECOND, 0);
    cursor.add(Calendar.MINUTE, 1);

    Calendar end = new GregorianCalendar();
    end.setTimeInMillis(now.toEpochMilli());

    while (!cursor.after(end)) {
      if (tabs.check(cursor)) {
        return true;
      }
      cursor.add(Calendar.MINUTE, 1);
    }
    return false;
  }

  /** How far {@link #nextRun} looks ahead before giving up — a year and a day. */
  private static final int NEXT_RUN_HORIZON_DAYS = 366;

  /** The next occurrence strictly after now — for the config-page "would next run at…" hint. */
  @NonNull
  public Optional<Instant> nextRun() {
    Calendar cursor = new GregorianCalendar();
    cursor.set(Calendar.SECOND, 0);
    cursor.set(Calendar.MILLISECOND, 0);
    cursor.add(Calendar.MINUTE, 1);
    Calendar limit = (Calendar) cursor.clone();
    limit.add(Calendar.DAY_OF_YEAR, NEXT_RUN_HORIZON_DAYS);
    while (!cursor.after(limit)) {
      if (tabs.check(cursor)) {
        return Optional.of(cursor.toInstant());
      }
      cursor.add(Calendar.MINUTE, 1);
    }
    return Optional.empty();
  }

  /** A human-readable warning if the spec is valid but suspicious. {@code null} when fine. */
  @CheckForNull
  public String sanityWarning() {
    return tabs.checkSanity();
  }
}

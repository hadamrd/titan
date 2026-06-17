package io.adaptiq.titan.trigger.cron;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Ported from {@code io.adaptiq.scheduler.cron.CronScheduleTest} (Wave 1). */
class CronScheduleTest {

  private static final String SEED = "folder/my-job";

  private static CronSchedule everyMinute() {
    return CronSchedule.of("* * * * *", SEED);
  }

  @Test
  void dueWhenAMatchingMinuteElapsedSinceLastFired() {
    Instant now = Instant.parse("2026-06-15T12:05:30Z");
    Instant since = now.minus(Duration.ofMinutes(2));
    assertTrue(
        everyMinute().isDue(since, now), "two every-minute slots lie in the (since, now] window");
  }

  @Test
  void notDueWhenNoMinuteElapsedSinceLastFired() {
    Instant now = Instant.parse("2026-06-15T12:05:30Z");
    assertFalse(everyMinute().isDue(now, now), "the half-open (now, now] window contains no slot");
  }

  @Test
  void catchesUpAcrossAGap() {
    Instant now = Instant.parse("2026-06-15T12:05:30Z");
    Instant since = now.minus(Duration.ofMinutes(30));
    assertTrue(
        everyMinute().isDue(since, now),
        "a slot missed during a 30-minute outage is still due on return");
  }

  @Test
  void catchUpIsClampedAndTerminatesFast() {
    Instant now = Instant.parse("2026-06-15T12:05:30Z");
    Instant longAgo = now.minus(Duration.ofDays(40));
    assertTimeout(Duration.ofSeconds(2), () -> assertTrue(everyMinute().isDue(longAgo, now)));
  }

  @Test
  void neverRetroactivelyDueWhenNeverFired() {
    Instant now = Instant.parse("2026-06-15T12:05:30Z");
    assertFalse(
        everyMinute().isDue(null, now), "a schedule that never fired is not retroactively due");
  }

  @Test
  void notDueWhenTheCronDoesNotMatchTheWindow() {
    CronSchedule newYear = CronSchedule.of("0 0 1 1 *", SEED);
    Instant now = Instant.parse("2026-06-15T12:05:30Z");
    assertFalse(newYear.isDue(now.minus(Duration.ofMinutes(5)), now));
  }

  @Test
  void sinceOnAnExactMinuteBoundaryExcludesThatMinute() {
    Instant since = Instant.parse("2026-06-15T12:03:00Z");
    Instant now = Instant.parse("2026-06-15T12:03:30Z");
    assertFalse(
        everyMinute().isDue(since, now),
        "the last-acted minute is excluded from the dueness window");
  }

  @Test
  void multiLineSpecIsDueWhenAnyLineMatches() {
    CronSchedule multi = CronSchedule.of("0 0 1 1 *\n* * * * *", SEED);
    Instant now = Instant.parse("2026-06-15T12:05:30Z");
    assertTrue(
        multi.isDue(now.minus(Duration.ofMinutes(2)), now),
        "a multi-line spec is due when any of its lines matches the window");
  }

  @Test
  void catchUpWindowIsConfigurable() {
    assertEquals(
        Duration.ofMinutes(5),
        CronSchedule.of("* * * * *", SEED, Duration.ofMinutes(5)).catchUpWindow(),
        "the catch-up window honours the constructor argument (design/52)");
  }

  @Test
  void theTwoArgFactoryUsesTheDefaultCatchUpWindow() {
    assertEquals(CronSchedule.DEFAULT_CATCH_UP, CronSchedule.of("* * * * *", SEED).catchUpWindow());
  }

  @Test
  void aNonPositiveCatchUpWindowFallsBackToTheDefault() {
    assertEquals(
        CronSchedule.DEFAULT_CATCH_UP,
        CronSchedule.of("* * * * *", SEED, Duration.ZERO).catchUpWindow(),
        "a zero/negative window would disable catch-up entirely — clamp to the default");
  }

  @Test
  void hashedSpecIsAcceptedAndStablePerSeed() {
    CronSchedule a1 = CronSchedule.of("H 2 * * *", SEED);
    CronSchedule a2 = CronSchedule.of("H 2 * * *", SEED);
    assertTrue(
        a1.nextRun().equals(a2.nextRun()),
        "the H hash is stable for a given seed — identical next-run instants");
  }

  @Test
  void aliasAndTimezoneAndMultilineSpecsAreAccepted() {
    assertDoesNotThrow(() -> CronSchedule.of("@daily", SEED));
    assertDoesNotThrow(() -> CronSchedule.of("TZ=Europe/Paris\nH 2 * * *", SEED));
    assertDoesNotThrow(() -> CronSchedule.of("H 2 * * *\nH 14 * * *", SEED));
  }

  @Test
  void invalidSpecIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> CronSchedule.of("not a cron", SEED));
    assertThrows(IllegalArgumentException.class, () -> CronSchedule.of("99 99 * * *", SEED));
  }

  @Test
  void everyMinuteSpecRaisesASanityWarning() {
    assertTrue(
        everyMinute().sanityWarning() != null,
        "'* * * * *' is valid but suspicious — CronTabList flags it");
  }

  @Test
  void nextRunIsPreviewableForAValidSpec() {
    assertTrue(CronSchedule.of("H 2 * * *", SEED).nextRun().isPresent());
  }
}

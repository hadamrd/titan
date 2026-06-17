package io.adaptiq.titan.timer;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Classifies the durable-wait pipeline steps and resolves the wall-clock instant a parked node
 * should wake at. Pure and side-effect-free — the orchestrator calls it, catches {@link
 * IllegalArgumentException} on bad pipeline input, and fails the node.
 *
 * <p>{@code sleep} takes a relative duration: a scalar ({@code sleep: 30}, {@code sleep: 5m}) which
 * the parser stores under {@code value}, or the object form {@code time} + {@code unit}. A bare
 * number is seconds; the suffixes {@code s|m|h|d} select the unit. {@code waitUntil} takes an
 * absolute ISO-8601 instant ({@code waitUntil: 2026-06-01T09:00:00Z}). Both are capped at one year
 * to catch a typo'd duration or timestamp; a {@code waitUntil} already in the past resolves to
 * {@code now} (it fires on the next sweep).
 */
public final class WaitResolver {

  private WaitResolver() {}

  private static final Set<String> WAIT_STEPS = Set.of("sleep", "waitUntil");

  /** Sanity ceiling — a wait longer than this is almost certainly a mistake. */
  private static final Duration MAX_WAIT = Duration.ofDays(366);

  /** Whether {@code descriptorId} is a control-plane wait step the orchestrator parks itself. */
  public static boolean isWait(@Nullable String descriptorId) {
    return descriptorId != null && WAIT_STEPS.contains(descriptorId);
  }

  /**
   * Resolve the instant a wait node should wake at.
   *
   * @throws IllegalArgumentException if the step arguments are malformed or out of range
   */
  @NonNull
  public static Instant resolveWakeAt(
      @NonNull String descriptorId, @NonNull Map<String, Object> arguments, @NonNull Instant now) {
    return switch (descriptorId) {
      case "sleep" -> now.plus(sleepDuration(arguments));
      case "waitUntil" -> waitUntilInstant(arguments, now);
      default -> throw new IllegalArgumentException("not a wait step: " + descriptorId);
    };
  }

  // ── sleep ──────────────────────────────────────────────────────────────────

  private static Duration sleepDuration(Map<String, Object> args) {
    Object value = args.get("value");
    if (value != null) {
      return capped(parseDurationString(String.valueOf(value).trim()));
    }
    Object time = args.get("time");
    if (time != null) {
      long amount = parsePositiveLong(String.valueOf(time).trim(), String.valueOf(time));
      String unit = args.get("unit") == null ? "SECONDS" : String.valueOf(args.get("unit"));
      Duration d =
          switch (unit.trim().toUpperCase(Locale.ROOT)) {
            case "SECONDS" -> Duration.ofSeconds(amount);
            case "MINUTES" -> Duration.ofMinutes(amount);
            case "HOURS" -> Duration.ofHours(amount);
            case "DAYS" -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("unknown sleep unit: " + unit);
          };
      return capped(d);
    }
    throw new IllegalArgumentException("sleep needs a duration ('value', or 'time' + 'unit')");
  }

  /**
   * Parse {@code "30"}, {@code "30s"}, {@code "5m"}, {@code "2h"}, {@code "3d"} — bare number is
   * seconds.
   */
  private static Duration parseDurationString(String s) {
    if (s.isEmpty()) {
      throw new IllegalArgumentException("sleep duration is empty");
    }
    char unit = s.charAt(s.length() - 1);
    if (Character.isDigit(unit)) {
      return Duration.ofSeconds(parsePositiveLong(s, s));
    }
    long n = parsePositiveLong(s.substring(0, s.length() - 1), s);
    return switch (unit) {
      case 's' -> Duration.ofSeconds(n);
      case 'm' -> Duration.ofMinutes(n);
      case 'h' -> Duration.ofHours(n);
      case 'd' -> Duration.ofDays(n);
      default -> throw new IllegalArgumentException("unknown sleep unit '" + unit + "' in: " + s);
    };
  }

  private static long parsePositiveLong(String digits, String original) {
    long n;
    try {
      n = Long.parseLong(digits.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("sleep duration is not a number: " + original);
    }
    if (n < 0) {
      throw new IllegalArgumentException("sleep duration must not be negative: " + original);
    }
    return n;
  }

  // ── waitUntil ──────────────────────────────────────────────────────────────

  private static Instant waitUntilInstant(Map<String, Object> args, Instant now) {
    Object value = args.get("value");
    if (value == null) {
      throw new IllegalArgumentException("waitUntil needs an ISO-8601 instant");
    }
    Instant target;
    try {
      target = Instant.parse(String.valueOf(value).trim());
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("waitUntil is not an ISO-8601 instant: " + value);
    }
    if (target.isAfter(now.plus(MAX_WAIT))) {
      throw new IllegalArgumentException("waitUntil is more than a year out: " + target);
    }
    // A target already in the past fires on the next sweep.
    return target.isBefore(now) ? now : target;
  }

  private static Duration capped(Duration d) {
    if (d.compareTo(MAX_WAIT) > 0) {
      throw new IllegalArgumentException("sleep duration exceeds the one-year maximum: " + d);
    }
    return d;
  }
}

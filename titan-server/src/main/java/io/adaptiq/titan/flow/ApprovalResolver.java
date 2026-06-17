package io.adaptiq.titan.flow;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Classifies and parses the {@code approval:} pipeline step (#715) — the human-signoff sibling of
 * {@link io.adaptiq.titan.timer.WaitResolver}. Pure, side-effect-free: the orchestrator calls it,
 * catches {@link IllegalArgumentException} on bad pipeline input, and fails the node.
 *
 * <p>{@code approval:} takes a scalar prompt ({@code approval: "Deploy to prod?"} — which the
 * parser folds into the conventional {@code value} argument key, design/42 §4.6) <em>or</em> an
 * object form ({@code approval: {prompt: "Deploy?", approvers: ["alice"], timeout: "2h"}}). Both
 * shapes yield the same {@link ParsedApproval}.
 *
 * <p>Defaults: missing {@code timeout} is {@code 24h} (the issue brief); missing {@code approvers}
 * is an empty list (any APPROVE_BUILD / ADMIN holder may decide); the prompt is mandatory.
 *
 * <p>The timeout grammar mirrors {@code sleep:} duration parsing — a bare number is seconds, the
 * suffixes {@code s|m|h|d} pick the unit. Anything longer than one year is rejected as almost
 * certainly a typo (same ceiling {@code WaitResolver} uses for sleep).
 */
public final class ApprovalResolver {

  private ApprovalResolver() {}

  /** The single canonical descriptor id for the approval step. */
  public static final String DESCRIPTOR_ID = "approval";

  /** Default expiry if the pipeline omits {@code timeout:} — 24 hours (issue #715 brief). */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofHours(24);

  /** Sanity ceiling — an approval timeout longer than this is almost certainly a typo. */
  private static final Duration MAX_TIMEOUT = Duration.ofDays(366);

  /**
   * Parsed projection of the step's arguments — what the orchestrator inserts into {@code
   * titan.approvals} when parking the step.
   *
   * @param prompt the human-facing question (non-blank)
   * @param approvers the list of subject strings authorised to decide; empty means any
   *     APPROVE_BUILD / ADMIN holder
   * @param timeout duration after which a PENDING row auto-rejects (TIMED_OUT)
   */
  public record ParsedApproval(
      @NonNull String prompt, @NonNull List<String> approvers, @NonNull Duration timeout) {}

  /** Whether {@code descriptorId} is the controller-native approval step. */
  public static boolean isApproval(@Nullable String descriptorId) {
    return DESCRIPTOR_ID.equals(descriptorId);
  }

  /**
   * Parse the step's resolved argument map.
   *
   * @throws IllegalArgumentException if the prompt is missing/blank, approvers contains a
   *     non-string entry, or the timeout is malformed / out of range
   */
  @NonNull
  public static ParsedApproval parse(@NonNull Map<String, Object> arguments) {
    Object rawPrompt =
        arguments.containsKey("prompt") ? arguments.get("prompt") : arguments.get("value");
    if (rawPrompt == null) {
      throw new IllegalArgumentException(
          "approval: requires a prompt — `approval: \"Deploy?\"` or `approval: {prompt: \"…\"}`");
    }
    String prompt = String.valueOf(rawPrompt).trim();
    if (prompt.isEmpty()) {
      throw new IllegalArgumentException("approval: prompt is blank");
    }

    List<String> approvers = parseApprovers(arguments.get("approvers"));
    Duration timeout = parseTimeout(arguments.get("timeout"));
    return new ParsedApproval(prompt, approvers, timeout);
  }

  private static List<String> parseApprovers(@Nullable Object raw) {
    if (raw == null) {
      return Collections.emptyList();
    }
    if (raw instanceof Collection<?> col) {
      List<String> out = new ArrayList<>(col.size());
      int idx = 0;
      for (Object o : col) {
        if (o == null) {
          throw new IllegalArgumentException(
              "approval: approvers[" + idx + "] is null — expected a subject string");
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
          throw new IllegalArgumentException("approval: approvers[" + idx + "] is blank");
        }
        out.add(s);
        idx++;
      }
      return List.copyOf(out);
    }
    if (raw instanceof String s) {
      // Tolerant: a single approver may be written as a scalar (`approvers: alice`). The grammar
      // calls for a list, but a bare string is unambiguous and a common typo.
      String trimmed = s.trim();
      if (trimmed.isEmpty()) {
        throw new IllegalArgumentException("approval: approvers is blank");
      }
      return List.of(trimmed);
    }
    throw new IllegalArgumentException(
        "approval: approvers must be a list of subject strings, got "
            + raw.getClass().getSimpleName());
  }

  private static Duration parseTimeout(@Nullable Object raw) {
    if (raw == null) {
      return DEFAULT_TIMEOUT;
    }
    String s = String.valueOf(raw).trim();
    if (s.isEmpty()) {
      throw new IllegalArgumentException("approval: timeout is blank");
    }
    Duration d = parseDurationString(s);
    if (d.isZero() || d.isNegative()) {
      throw new IllegalArgumentException("approval: timeout must be positive, got " + s);
    }
    if (d.compareTo(MAX_TIMEOUT) > 0) {
      throw new IllegalArgumentException("approval: timeout exceeds the one-year maximum: " + d);
    }
    return d;
  }

  private static Duration parseDurationString(String s) {
    char unit = s.charAt(s.length() - 1);
    if (Character.isDigit(unit)) {
      return Duration.ofSeconds(parsePositiveLong(s, s));
    }
    long n = parsePositiveLong(s.substring(0, s.length() - 1), s);
    return switch (Character.toLowerCase(unit)) {
      case 's' -> Duration.ofSeconds(n);
      case 'm' -> Duration.ofMinutes(n);
      case 'h' -> Duration.ofHours(n);
      case 'd' -> Duration.ofDays(n);
      default ->
          throw new IllegalArgumentException(
              "approval: unknown timeout unit '" + unit + "' in: " + s.toLowerCase(Locale.ROOT));
    };
  }

  private static long parsePositiveLong(String digits, String original) {
    long n;
    try {
      n = Long.parseLong(digits.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("approval: timeout is not a number: " + original);
    }
    if (n < 0) {
      throw new IllegalArgumentException("approval: timeout must not be negative: " + original);
    }
    return n;
  }
}

package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.Set;

/**
 * Shared, location-aware YAML-tree helpers used by {@link TitanYamlParser} and every {@link
 * StepScope}. Pure parser machinery — generic-model only, no step-descriptor knowledge.
 */
final class ParseSupport {

  private ParseSupport() {}

  /** Fail if {@code obj} carries any key outside {@code allowed}. */
  static void rejectUnknownKeys(
      @NonNull JsonNode obj, @NonNull Set<String> allowed, @NonNull String context) {
    Iterator<String> it = obj.fieldNames();
    while (it.hasNext()) {
      String key = it.next();
      if (!allowed.contains(key)) {
        throw new PipelineParseException(
            context + ": unknown key '" + key + "' (allowed: " + allowed + ")");
      }
    }
  }

  @NonNull
  static String requireText(@NonNull JsonNode obj, @NonNull String key, @NonNull String context) {
    JsonNode v = obj.get(key);
    if (v == null || v.isNull() || !v.isValueNode() || v.asText().isBlank()) {
      throw new PipelineParseException(context + ": missing or empty required key '" + key + "'");
    }
    return v.asText();
  }

  @Nullable
  static String optText(@NonNull JsonNode obj, @NonNull String key) {
    JsonNode v = obj.get(key);
    return (v == null || v.isNull()) ? null : v.asText();
  }

  /**
   * Parse a suffixed duration string — {@code "10s"}, {@code "5m"}, {@code "2h"} — into
   * milliseconds. The {@code s}/{@code m}/{@code h} suffixes are the usual Titan duration grammar
   * (design/44 §2); a bare unsuffixed integer is rejected so a unit is always explicit. A negative
   * or non-numeric value is rejected with a located error. This is the single duration parser for
   * the parser package — scopes that take durations call it rather than hand-rolling.
   */
  static long parseDurationMillis(@NonNull String raw, @NonNull String context) {
    String s = raw.trim();
    if (s.isEmpty()) {
      throw new PipelineParseException(context + ": empty duration");
    }
    char unit = s.charAt(s.length() - 1);
    long factor;
    switch (unit) {
      case 's':
        factor = 1_000L;
        break;
      case 'm':
        factor = 60_000L;
        break;
      case 'h':
        factor = 3_600_000L;
        break;
      default:
        throw new PipelineParseException(
            context
                + ": duration '"
                + raw
                + "' must end in a unit — 's' (seconds), 'm' (minutes) or 'h' (hours)");
    }
    String digits = s.substring(0, s.length() - 1).trim();
    long value;
    try {
      value = Long.parseLong(digits);
    } catch (NumberFormatException e) {
      throw new PipelineParseException(
          context + ": duration '" + raw + "' is not a number followed by s/m/h", e);
    }
    if (value < 0) {
      throw new PipelineParseException(context + ": duration '" + raw + "' must not be negative");
    }
    return value * factor;
  }
}

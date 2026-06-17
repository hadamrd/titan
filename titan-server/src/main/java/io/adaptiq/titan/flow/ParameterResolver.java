package io.adaptiq.titan.flow;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.ParameterModel;
import io.adaptiq.titan.flow.parser.PipelineParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves a build's <em>declared</em> parameters against the values a build <em>supplied</em>,
 * into the effective {@code params} map (design/29 §5) — Chunk 6E (parameters).
 *
 * <p>Run once, at bake time. It applies defaults, enforces {@code required}, coerces each value to
 * its declared type, and checks {@code choice} membership. Anything wrong — a missing required
 * parameter, an out-of-range choice, an uncoercible value, an undeclared parameter — <strong>fails
 * the bake</strong> (design/26 Tier B: validate at bake, never thirty minutes into a run).
 *
 * <p>The resulting map is what {@code when:} (bake time) and {@code ${{ params.* }}} (dispatch
 * time) read — so a parameter with a default is visible to expressions even when the build did not
 * supply it.
 */
public final class ParameterResolver {

  private ParameterResolver() {}

  /**
   * @param declared the pipeline's {@code parameters:} block
   * @param supplied the values this build was submitted with (may be empty)
   * @return the effective parameter map — declared name → coerced value (or {@code null} for an
   *     optional parameter with neither a supplied value nor a default)
   * @throws PipelineParseException on any validation failure.
   */
  @NonNull
  public static Map<String, Object> resolve(
      @NonNull List<ParameterModel> declared, @NonNull Map<String, Object> supplied) {
    Map<String, Object> effective = new LinkedHashMap<>();
    for (ParameterModel p : declared) {
      Object raw = supplied.get(p.getName());
      if (raw == null) {
        if (p.isRequired() && p.getDefaultValue() == null) {
          throw new PipelineParseException(
              "required parameter '" + p.getName() + "' was not supplied");
        }
        raw = p.getDefaultValue();
      }
      effective.put(p.getName(), raw == null ? null : coerce(p, raw));
    }
    // Reject anything the build supplied that the pipeline does not declare — catches typos
    // in trigger / submit configs rather than silently ignoring them.
    for (String key : supplied.keySet()) {
      if (!isDeclared(declared, key)) {
        throw new PipelineParseException(
            "parameter '" + key + "' was supplied but is not declared by the pipeline");
      }
    }
    return effective;
  }

  private static boolean isDeclared(@NonNull List<ParameterModel> declared, @NonNull String name) {
    return declared.stream().anyMatch(p -> p.getName().equals(name));
  }

  /** Coerce a raw value to the parameter's declared type, or fail the bake. */
  @Nullable
  private static Object coerce(@NonNull ParameterModel p, @NonNull Object raw) {
    switch (p.getType().toLowerCase(Locale.ROOT)) {
      case "string":
        return String.valueOf(raw);
      case "boolean":
        if (raw instanceof Boolean) {
          return raw;
        }
        String b = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (b.equals("true") || b.equals("false")) {
          return Boolean.parseBoolean(b);
        }
        throw new PipelineParseException(
            "parameter '" + p.getName() + "' must be a boolean, got: " + raw);
      case "number":
        if (raw instanceof Number) {
          return ((Number) raw).doubleValue();
        }
        try {
          return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
          throw new PipelineParseException(
              "parameter '" + p.getName() + "' must be a number, got: " + raw);
        }
      case "choice":
        if (p.getChoices().isEmpty()) {
          throw new PipelineParseException(
              "choice parameter '" + p.getName() + "' declares no choices");
        }
        String value = String.valueOf(raw);
        if (!p.getChoices().contains(value)) {
          throw new PipelineParseException(
              "parameter '"
                  + p.getName()
                  + "' value '"
                  + value
                  + "' is not one of "
                  + p.getChoices());
        }
        return value;
      default:
        throw new PipelineParseException(
            "parameter '"
                + p.getName()
                + "' has unknown type '"
                + p.getType()
                + "' (expected string, boolean, number or choice)");
    }
  }
}

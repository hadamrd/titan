package io.adaptiq.titan.worker.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates a {@link StepRequest}'s arguments against a step's declared {@link ParamSpec}s
 * <em>before</em> the handler runs (design/42 §4.5 — "{@code ParamSpec} becomes load-bearing").
 *
 * <p>Today a {@link StepHandler} is handed a raw {@code arguments} map and must re-validate it by
 * hand. Chunk 42-V makes argument validation the engine's job: it runs identically for every
 * handler, before {@link StepHandler#execute} and therefore before any side effect (no process
 * spawned, no file written). A handler can then trust the request inside {@code execute}, and a
 * pipeline typo gets a precise, located message instead of a mid-build crash.
 *
 * <p>This type is deliberately JDK-only — it lives in {@code titan-step-api} so the SPI module
 * stays the single home of the step contract. It interprets data ({@link StepDescriptor}, {@link
 * ParamSpec}, a {@code Map}); it spawns nothing and reaches nowhere.
 *
 * <p>The rules (design/42 §4.5):
 *
 * <ul>
 *   <li>every {@link ParamSpec} with {@code required == true} must be present and non-null —
 *       missing/null is a <strong>failure</strong>;
 *   <li>a parameter typed {@code boolean} or {@code number} must hold a value coercible to that
 *       type (a {@code Boolean}/{@code Number}, or a {@code String} that parses) — a bad type is a
 *       <strong>failure</strong>;
 *   <li>a parameter with non-empty {@code choices} must hold one of the allowed values — an
 *       out-of-range value is a <strong>failure</strong>;
 *   <li>an argument key not declared by any {@code ParamSpec} is a <strong>warning</strong>, not a
 *       failure — a handler may accept the scalar-shorthand {@code value} key or be deliberately
 *       lenient.
 * </ul>
 */
public final class StepArgumentValidator {

  /** The scalar-shorthand key (design/42 §4.6) — never warned about as "unknown". */
  private static final String SHORTHAND_KEY = "value";

  /**
   * The reserved prefix for engine-internal argument keys. The worker may inject a {@code titan.*}
   * argument that no step's {@code ParamSpec} declares (e.g. the {@code titan.displayScript}
   * carrying an {@code sshAgent:}-wrapped step's original command for log hygiene — design/41 §4).
   * Such a key is engine plumbing, not a pipeline typo, so it is never warned about as "unknown".
   */
  private static final String RESERVED_PREFIX = "titan.";

  private StepArgumentValidator() {}

  /**
   * The outcome of validating one request's arguments.
   *
   * @param errors fatal violations — a non-empty list means the step must fail before execute
   * @param warnings non-fatal notes (unknown argument keys) — logged, never failing
   */
  public record Result(List<String> errors, List<String> warnings) {

    public Result {
      errors = List.copyOf(errors);
      warnings = List.copyOf(warnings);
    }

    /** Whether validation found no fatal violation. */
    public boolean ok() {
      return errors.isEmpty();
    }

    /** The combined fatal message, located per parameter — {@code null} when {@link #ok()}. */
    public String failureMessage() {
      return errors.isEmpty() ? null : String.join("; ", errors);
    }
  }

  /**
   * Validate {@code arguments} against {@code descriptor}'s parameter schema.
   *
   * @param descriptor the resolved handler's descriptor — its {@link ParamSpec}s are the schema
   * @param arguments the {@link StepRequest#arguments()} map about to be handed to the handler
   * @return the {@link Result} — fatal {@code errors} plus non-fatal {@code warnings}
   */
  public static Result validate(StepDescriptor descriptor, Map<String, Object> arguments) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Map<String, Object> args = arguments == null ? Map.of() : arguments;
    List<ParamSpec> params = descriptor == null ? List.of() : descriptor.parameters();
    String stepId = descriptor == null ? "step" : descriptor.descriptorId();

    // Per-parameter checks: required-presence, type coercion, enum range.
    for (ParamSpec param : params) {
      boolean present = args.containsKey(param.name());
      Object value = args.get(param.name());

      if (param.required() && (!present || value == null)) {
        errors.add(
            "parameter '"
                + param.name()
                + "' of step '"
                + stepId
                + "' is required but "
                + (present ? "is null" : "is missing"));
        continue;
      }
      if (!present || value == null) {
        // an optional, omitted parameter — nothing more to check
        continue;
      }

      String typeError = checkType(stepId, param, value);
      if (typeError != null) {
        errors.add(typeError);
        // a value that is not even the right type cannot be a valid choice — skip enum
        continue;
      }

      String choiceError = checkChoice(stepId, param, value);
      if (choiceError != null) {
        errors.add(choiceError);
      }
    }

    // Unknown-key check: any argument not declared by a ParamSpec is a warning, not a failure.
    for (String key : args.keySet()) {
      if (SHORTHAND_KEY.equals(key) || key.startsWith(RESERVED_PREFIX)) {
        continue;
      }
      boolean declared = params.stream().anyMatch(p -> p.name().equals(key));
      if (!declared) {
        warnings.add(
            "argument '"
                + key
                + "' is not declared by step '"
                + stepId
                + "' — it will be passed through unchecked");
      }
    }

    return new Result(errors, warnings);
  }

  /**
   * Check that {@code value} is coercible to {@code param}'s declared type. Only {@code boolean}
   * and {@code number} are constrained — {@code string} accepts anything (everything has a string
   * form) and {@code list} / unknown types are not type-checked here.
   *
   * @return a located error message, or {@code null} if the value is acceptable
   */
  private static String checkType(String stepId, ParamSpec param, Object value) {
    String type = param.type() == null ? "" : param.type().trim().toLowerCase();
    switch (type) {
      case "boolean" -> {
        if (value instanceof Boolean) {
          return null;
        }
        if (value instanceof String s) {
          String t = s.trim();
          if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) {
            return null;
          }
        }
        return located(stepId, param, value, "is not a boolean " + "(expected true/false)");
      }
      case "number" -> {
        if (value instanceof Number) {
          return null;
        }
        if (value instanceof String s) {
          try {
            Double.parseDouble(s.trim());
            return null;
          } catch (NumberFormatException ignored) {
            // falls through to the error below
          }
        }
        return located(stepId, param, value, "is not a number");
      }
      default -> {
        // string, list, or an unrecognised type — no coercion constraint
        return null;
      }
    }
  }

  /**
   * Check that {@code value} is one of {@code param}'s {@code choices}. The comparison is on the
   * string form, so a YAML scalar typed loosely still matches a declared choice.
   *
   * @return a located error message, or {@code null} if the value is in range (or unconstrained)
   */
  private static String checkChoice(String stepId, ParamSpec param, Object value) {
    List<String> choices = param.choices();
    if (choices == null || choices.isEmpty()) {
      return null;
    }
    String asText = String.valueOf(value);
    if (choices.contains(asText)) {
      return null;
    }
    return located(stepId, param, value, "is not one of the allowed values " + choices);
  }

  private static String located(String stepId, ParamSpec param, Object value, String problem) {
    return "parameter '"
        + param.name()
        + "' of step '"
        + stepId
        + "' "
        + problem
        + " (got: '"
        + value
        + "')";
  }
}

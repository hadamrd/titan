package io.adaptiq.titan.worker.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit coverage for {@link StepArgumentValidator} — design/42 §4.5. */
class StepArgumentValidatorTest {

  private static StepDescriptor desc(ParamSpec... params) {
    return new StepDescriptor("demo", "Demo", "help", List.of(params));
  }

  @Test
  void missingRequiredIsAFailure() {
    StepArgumentValidator.Result r =
        StepArgumentValidator.validate(desc(ParamSpec.required("name", "string", "")), Map.of());
    assertFalse(r.ok());
    assertTrue(r.failureMessage().contains("name"));
    assertTrue(r.failureMessage().contains("missing"));
  }

  @Test
  void nullRequiredIsAFailure() {
    Map<String, Object> args = new HashMap<>();
    args.put("name", null);
    StepArgumentValidator.Result r =
        StepArgumentValidator.validate(desc(ParamSpec.required("name", "string", "")), args);
    assertFalse(r.ok());
    assertTrue(r.failureMessage().contains("null"));
  }

  @Test
  void omittedOptionalIsFine() {
    assertTrue(
        StepArgumentValidator.validate(desc(ParamSpec.optional("name", "string", "")), Map.of())
            .ok());
  }

  @Test
  void badBooleanIsAFailure() {
    StepArgumentValidator.Result r =
        StepArgumentValidator.validate(
            desc(ParamSpec.optional("flag", "boolean", "")), Map.of("flag", "yes"));
    assertFalse(r.ok());
    assertTrue(r.failureMessage().contains("boolean"));
  }

  @Test
  void coercibleBooleanStringIsFine() {
    assertTrue(
        StepArgumentValidator.validate(
                desc(ParamSpec.optional("flag", "boolean", "")), Map.of("flag", "TRUE"))
            .ok());
    assertTrue(
        StepArgumentValidator.validate(
                desc(ParamSpec.optional("flag", "boolean", "")), Map.of("flag", Boolean.FALSE))
            .ok());
  }

  @Test
  void badNumberIsAFailure() {
    StepArgumentValidator.Result r =
        StepArgumentValidator.validate(
            desc(ParamSpec.optional("count", "number", "")), Map.of("count", "abc"));
    assertFalse(r.ok());
    assertTrue(r.failureMessage().contains("number"));
  }

  @Test
  void coercibleNumberIsFine() {
    assertTrue(
        StepArgumentValidator.validate(
                desc(ParamSpec.optional("count", "number", "")), Map.of("count", "42"))
            .ok());
    assertTrue(
        StepArgumentValidator.validate(
                desc(ParamSpec.optional("count", "number", "")), Map.of("count", 7))
            .ok());
  }

  @Test
  void outOfRangeChoiceIsAFailure() {
    ParamSpec enumP = new ParamSpec("mode", "string", false, "", List.of("fast", "slow"));
    StepArgumentValidator.Result r =
        StepArgumentValidator.validate(desc(enumP), Map.of("mode", "turbo"));
    assertFalse(r.ok());
    assertTrue(r.failureMessage().contains("allowed values"));
  }

  @Test
  void inRangeChoiceIsFine() {
    ParamSpec enumP = new ParamSpec("mode", "string", false, "", List.of("fast", "slow"));
    assertTrue(StepArgumentValidator.validate(desc(enumP), Map.of("mode", "fast")).ok());
  }

  @Test
  void unknownKeyIsAWarningNotAFailure() {
    StepArgumentValidator.Result r =
        StepArgumentValidator.validate(
            desc(ParamSpec.optional("name", "string", "")), Map.of("typo", "x"));
    assertTrue(r.ok());
    assertEquals(1, r.warnings().size());
    assertTrue(r.warnings().get(0).contains("typo"));
  }

  @Test
  void shorthandValueKeyIsNotWarned() {
    StepArgumentValidator.Result r =
        StepArgumentValidator.validate(
            desc(ParamSpec.optional("name", "string", "")), Map.of("value", "echo hi"));
    assertTrue(r.ok());
    assertTrue(r.warnings().isEmpty());
  }
}

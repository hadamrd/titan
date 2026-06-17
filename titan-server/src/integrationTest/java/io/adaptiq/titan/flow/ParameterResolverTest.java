package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.ParameterModel;
import io.adaptiq.titan.flow.parser.PipelineParseException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ParameterResolver} — Chunk 6E (parameters). Pure: no DB, no container. */
class ParameterResolverTest {

  private static ParameterModel param(String name, String type) {
    ParameterModel p = new ParameterModel();
    p.setName(name);
    p.setType(type);
    return p;
  }

  @Test
  void defaultIsAppliedWhenNotSupplied() {
    ParameterModel env = param("env", "string");
    env.setDefaultValue("dev");
    Map<String, Object> effective = ParameterResolver.resolve(List.of(env), Map.of());
    assertEquals("dev", effective.get("env"));
  }

  @Test
  void suppliedValueOverridesTheDefault() {
    ParameterModel env = param("env", "string");
    env.setDefaultValue("dev");
    Map<String, Object> effective = ParameterResolver.resolve(List.of(env), Map.of("env", "prod"));
    assertEquals("prod", effective.get("env"));
  }

  @Test
  void anOptionalParameterWithNoValueResolvesToNull() {
    Map<String, Object> effective =
        ParameterResolver.resolve(List.of(param("opt", "string")), Map.of());
    assertTrue(effective.containsKey("opt"));
    assertNull(effective.get("opt"));
  }

  @Test
  void aRequiredParameterWithNeitherValueNorDefaultFailsTheBake() {
    ParameterModel branch = param("branch", "string");
    branch.setRequired(true);
    assertTrue(violation(List.of(branch), Map.of()).contains("required parameter 'branch'"));
  }

  @Test
  void aRequiredParameterThatIsSuppliedIsAccepted() {
    ParameterModel branch = param("branch", "string");
    branch.setRequired(true);
    assertEquals(
        "main", ParameterResolver.resolve(List.of(branch), Map.of("branch", "main")).get("branch"));
  }

  @Test
  void choiceAcceptsAListedValueAndRejectsAnythingElse() {
    ParameterModel env = param("env", "choice");
    env.setChoices(List.of("dev", "staging", "prod"));
    env.setDefaultValue("dev");
    assertEquals("prod", ParameterResolver.resolve(List.of(env), Map.of("env", "prod")).get("env"));
    assertTrue(violation(List.of(env), Map.of("env", "qa")).contains("not one of"));
  }

  @Test
  void booleanCoercionAcceptsBooleansAndBooleanStrings() {
    ParameterModel flag = param("flag", "boolean");
    assertEquals(
        Boolean.TRUE, ParameterResolver.resolve(List.of(flag), Map.of("flag", "true")).get("flag"));
    assertEquals(
        Boolean.FALSE, ParameterResolver.resolve(List.of(flag), Map.of("flag", false)).get("flag"));
    assertTrue(violation(List.of(flag), Map.of("flag", "yes")).contains("must be a boolean"));
  }

  @Test
  void numberCoercionAcceptsNumbersAndNumericStrings() {
    ParameterModel count = param("count", "number");
    assertEquals(3.0, ParameterResolver.resolve(List.of(count), Map.of("count", "3")).get("count"));
    assertTrue(violation(List.of(count), Map.of("count", "abc")).contains("must be a number"));
  }

  @Test
  void aParameterSuppliedButNotDeclaredFailsTheBake() {
    assertTrue(
        violation(List.of(param("known", "string")), Map.of("ghost", "x"))
            .contains("not declared by the pipeline"));
  }

  @Test
  void anUnknownParameterTypeFailsTheBake() {
    ParameterModel bad = param("p", "weird");
    bad.setDefaultValue("v");
    assertTrue(violation(List.of(bad), Map.of()).contains("unknown type"));
  }

  private static String violation(List<ParameterModel> declared, Map<String, Object> supplied) {
    return assertThrows(
            PipelineParseException.class, () -> ParameterResolver.resolve(declared, supplied))
        .getMessage();
  }
}

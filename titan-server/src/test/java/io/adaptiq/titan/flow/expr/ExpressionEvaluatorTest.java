package io.adaptiq.titan.flow.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ExpressionEvaluator} — design/31 6B. Pure: no DB, no container. */
class ExpressionEvaluatorTest {

  private static boolean evalBool(String expr, Map<String, Object> ctx) {
    return ExpressionEvaluator.evaluateBoolean(expr, ctx);
  }

  private static Object eval(String expr) {
    return ExpressionEvaluator.evaluate(expr, Map.of());
  }

  // ---- literals & arithmetic ---------------------------------------------

  @Test
  void arithmeticRespectsPrecedenceAndParentheses() {
    assertEquals(14.0, eval("2 + 3 * 4"));
    assertEquals(20.0, eval("(2 + 3) * 4"));
    assertEquals(1.0, eval("7 % 3"));
    assertEquals(2.5, eval("5 / 2"));
    assertEquals(-6.0, eval("-2 * 3"));
  }

  @Test
  void stringConcatenation() {
    assertEquals("1.4.2", eval("'1.4.' + '2'"));
  }

  // ---- booleans -----------------------------------------------------------

  @Test
  void booleanOperatorsAndPrecedence() {
    assertTrue((Boolean) eval("true && (false || true)"));
    assertFalse((Boolean) eval("!true"));
    assertTrue((Boolean) eval("!(1 > 2)"));
  }

  @Test
  void andShortCircuitsBeforeATypeError() {
    // The right operand would be a type error, but && must not evaluate it.
    assertFalse(evalBool("false && (1 + 'x' == 'x')", Map.of()));
  }

  @Test
  void orShortCircuitsBeforeATypeError() {
    assertTrue(evalBool("true || (1 + 'x' == 'x')", Map.of()));
  }

  // ---- comparison & equality ---------------------------------------------

  @Test
  void numericComparison() {
    assertTrue((Boolean) eval("3 >= 3"));
    assertTrue((Boolean) eval("2 < 10"));
    assertFalse((Boolean) eval("5 <= 4"));
  }

  @Test
  void stringComparisonIsLexicographic() {
    assertTrue((Boolean) eval("'a' < 'b'"));
  }

  @Test
  void equalityIsNullSafeAndCrossesIntAndDouble() {
    assertTrue((Boolean) eval("null == null"));
    assertFalse((Boolean) eval("null == 0"));
    assertTrue((Boolean) eval("2 == 2"));
    assertTrue((Boolean) eval("'x' != 'y'"));
  }

  // ---- context: member access & indexing --------------------------------

  @Test
  void whenExpressionAgainstParams() {
    Map<String, Object> ctx = Map.of("params", Map.of("runSmokeTests", true));
    assertTrue(evalBool("params.runSmokeTests == true", ctx));

    Map<String, Object> ctxFalse = Map.of("params", Map.of("runSmokeTests", false));
    assertFalse(evalBool("params.runSmokeTests == true", ctxFalse));
  }

  @Test
  void preconditionExpressionAgainstStepOutputs() {
    Map<String, Object> ctx =
        Map.of("steps", Map.of("Build", Map.of("outputs", Map.of("passed", true, "count", 3))));
    assertTrue(evalBool("steps['Build'].outputs.passed == true", ctx));
    assertTrue(evalBool("steps['Build'].outputs.count >= 1", ctx));
  }

  @Test
  void missingMapKeyReadsAsNull() {
    Map<String, Object> ctx = Map.of("params", Map.of("known", 1));
    assertTrue(evalBool("params.unknownFlag == null", ctx));
  }

  @Test
  void listIndexing() {
    Map<String, Object> ctx = Map.of("params", Map.of("envs", List.of("dev", "prod")));
    assertEquals("prod", ExpressionEvaluator.evaluate("params.envs[1]", ctx));
  }

  @Test
  void listIndexOutOfBoundsIsAnError() {
    Map<String, Object> ctx = Map.of("params", Map.of("envs", List.of("dev")));
    assertThrows(
        ExpressionException.class, () -> ExpressionEvaluator.evaluate("params.envs[5]", ctx));
  }

  @Test
  void unknownTopLevelVariableIsAnError() {
    assertTrue(
        assertThrows(ExpressionException.class, () -> eval("nope == 1"))
            .getMessage()
            .contains("unknown variable"));
  }

  @Test
  void memberAccessOnNullIsAnError() {
    Map<String, Object> ctx = Map.of("params", Map.of());
    assertThrows(
        ExpressionException.class,
        () -> ExpressionEvaluator.evaluate("params.missing.deeper", ctx));
  }

  // ---- safety: no call syntax, nothing executable ------------------------

  @Test
  void methodCallSyntaxCannotBeParsed() {
    // There is no call grammar — `System.exit(0)` fails to parse, it is not "sandboxed".
    assertThrows(ExpressionException.class, () -> eval("System.exit(0)"));
    assertThrows(ExpressionException.class, () -> eval("params.foo()"));
    assertThrows(ExpressionException.class, () -> eval("Runtime.getRuntime()"));
  }

  @Test
  void syntaxErrorsAreRejectedWithPosition() {
    assertThrows(ExpressionException.class, () -> eval("1 +"));
    assertThrows(ExpressionException.class, () -> eval("1 2 3"));
    assertThrows(ExpressionException.class, () -> eval("'unterminated"));
    assertThrows(ExpressionException.class, () -> eval("1 & 2"));
    assertThrows(ExpressionException.class, () -> eval("(1 + 2"));
  }

  // ---- type mismatches ----------------------------------------------------

  @Test
  void typeMismatchesAreErrors() {
    assertThrows(ExpressionException.class, () -> eval("1 + 'x'"));
    assertThrows(ExpressionException.class, () -> eval("!5"));
    assertThrows(ExpressionException.class, () -> eval("'a' && true"));
    assertThrows(ExpressionException.class, () -> eval("1 < 'a'"));
  }

  @Test
  void divisionByZeroIsAnError() {
    assertThrows(ExpressionException.class, () -> eval("1 / 0"));
    assertThrows(ExpressionException.class, () -> eval("1 % 0"));
  }

  @Test
  void evaluateBooleanRejectsANonBooleanResult() {
    assertTrue(
        assertThrows(ExpressionException.class, () -> evalBool("1 + 1", Map.of()))
            .getMessage()
            .contains("must evaluate to a boolean"));
  }

  @Test
  void compiledExpressionIsReusableAcrossContexts() {
    ExpressionEvaluator.Expr compiled = ExpressionEvaluator.compile("params.n > 10");
    assertTrue((Boolean) compiled.eval(Map.of("params", Map.of("n", 20))));
    assertFalse((Boolean) compiled.eval(Map.of("params", Map.of("n", 5))));
  }

  // ---- mutation-hardening: index access edge cases -----------------------

  @Test
  void indexIntoMapWithIntegralNumericKeyNormalisesToString() {
    Map<String, Object> ctx = Map.of("m", Map.of("0", "zero"));
    assertEquals("zero", ExpressionEvaluator.evaluate("m[0]", ctx));
  }

  @Test
  void indexIntoMapWithNonIntegralNumericKeyUsesRawValue() {
    // 1.5 != floor(1.5) -> stringKey returns the raw Double; String.valueOf(1.5) == "1.5".
    Map<String, Object> ctx = Map.of("m", Map.of("1.5", "half"));
    assertEquals("half", ExpressionEvaluator.evaluate("m[1.5]", ctx));
  }

  @Test
  void indexIntoScalarThrows() {
    Map<String, Object> ctx = Map.of("n", 3.0);
    assertThrows(ExpressionException.class, () -> ExpressionEvaluator.evaluate("n[0]", ctx));
  }

  @Test
  void listIndexWithNonNumericKeyThrows() {
    Map<String, Object> ctx = Map.of("xs", List.of("a", "b"));
    assertThrows(ExpressionException.class, () -> ExpressionEvaluator.evaluate("xs['k']", ctx));
  }

  @Test
  void indexIntoNullThrows() {
    Map<String, Object> ctx = new java.util.HashMap<>();
    ctx.put("x", null);
    assertThrows(ExpressionException.class, () -> ExpressionEvaluator.evaluate("x[0]", ctx));
  }

  @Test
  void listIndexAtExactBoundsBehaves() {
    // pins the i<0 and i>=size() boundary mutants: index 0 valid, index size() out of bounds
    Map<String, Object> ctx = Map.of("xs", List.of("a", "b"));
    assertEquals("a", ExpressionEvaluator.evaluate("xs[0]", ctx));
    assertEquals("b", ExpressionEvaluator.evaluate("xs[1]", ctx));
    assertThrows(ExpressionException.class, () -> ExpressionEvaluator.evaluate("xs[2]", ctx));
  }

  @Test
  void listIndexNegativeIsOutOfBounds() {
    // pins the i<0 boundary: -1 must throw; if i<0 became i<=0 then 0 would wrongly throw.
    Map<String, Object> ctx = Map.of("xs", List.of("a", "b"));
    assertThrows(ExpressionException.class, () -> ExpressionEvaluator.evaluate("xs[-1]", ctx));
  }

  // ---- mutation-hardening: lexer escape sequences ------------------------

  @Test
  void stringEscapesAreDecoded() {
    assertEquals("a\tb", eval("'a\\tb'"));
    assertEquals("a\nb", eval("'a\\nb'"));
    assertEquals("a\rb", eval("'a\\rb'"));
    assertEquals("a\\b", eval("'a\\\\b'"));
    assertEquals("a'b", eval("'a\\'b'"));
    assertEquals("a\"b", eval("\"a\\\"b\""));
  }

  @Test
  void unknownEscapeKeepsTheLiteralCharacter() {
    assertEquals("az", eval("'a\\z'"));
  }

  @Test
  void escapeAdvancesPastBothCharacters() {
    // 'x\qy' lexes: 'x', then '\' with lookahead 'q' -> unknown escape -> default -> 'q',
    // i += 2 advances past both, then 'y'. Result "xqy". If i += 2 became i -= 2 or the
    // i+1 lookahead were wrong the scan would loop or drop characters. Pins L329-L341.
    assertEquals("xqy", eval("'x\\qy'"));
  }

  @Test
  void unterminatedStringThrows() {
    assertThrows(ExpressionException.class, () -> eval("'unclosed"));
  }

  @Test
  void backslashAsFinalCharacterIsUnterminated() {
    // src = "'a\" : opening quote, 'a', then '\' at the last index. i+1 == src.length()
    // so the escape guard `i + 1 < src.length()` is false: the '\' is appended literally
    // and i++ runs off the end -> unterminated. Pins the L329 ConditionalsBoundary mutant.
    assertThrows(ExpressionException.class, () -> eval("'a\\"));
  }

  // ---- mutation-hardening: comparison & equality boundaries --------------

  @Test
  void lessThanOrEqualBoundaryIsInclusive() {
    // pins L224 ConditionalsBoundary: <= must include equality, < must not.
    assertEquals(true, eval("2 <= 2"));
    assertEquals(false, eval("2 < 2"));
    assertEquals(true, eval("3 <= 4"));
    assertEquals(false, eval("4 <= 3"));
  }

  @Test
  void greaterThanOrEqualBoundaryIsInclusive() {
    assertEquals(true, eval("2 >= 2"));
    assertEquals(false, eval("2 > 2"));
  }

  @Test
  void equalValuesMixedNumberTypesCompareByValue() {
    // pins L235 NegateConditionals: both-Number branch uses doubleValue() ==.
    assertEquals(true, eval("2 == 2"));
    assertEquals(false, eval("1 == 2"));
    assertEquals(false, eval("1 == 'x'"));
    assertEquals(false, eval("'x' == 1"));
  }

  // ---- mutation-hardening: numeric type-error branch ---------------------

  @Test
  void subtractingANonNumberThrows() {
    // pins L246 NegateConditionals in num(): a non-Number operand must be a type error.
    assertThrows(ExpressionException.class, () -> eval("'x' - 1"));
    assertThrows(ExpressionException.class, () -> eval("1 - 'x'"));
  }

  @Test
  void numericTypeErrorMessageNamesTheActualType() {
    // pins L246 NegateConditionals on the `v == null ? "null" : getSimpleName()` ternary:
    // a non-null String operand must be reported as "String", not "null".
    assertTrue(
        assertThrows(ExpressionException.class, () -> eval("'x' - 1"))
            .getMessage()
            .contains("String"));
  }

  @Test
  void subtractionComputesDifference() {
    assertEquals(1.0, eval("4 - 3"));
  }

  // ---- mutation-hardening: evaluateBoolean non-boolean guard -------------

  @Test
  void evaluateBooleanAcceptsABooleanAndRejectsANumber() {
    // pins L45 NegateConditionals on the `instanceof Boolean` guard.
    assertTrue(ExpressionEvaluator.evaluateBoolean("true", Map.of()));
    assertThrows(
        ExpressionException.class, () -> ExpressionEvaluator.evaluateBoolean("1 + 1", Map.of()));
  }

  @Test
  void nonBooleanResultMessageNamesTheActualType() {
    // pins L45 NegateConditionals on the `result == null ? "null" : getSimpleName()`
    // ternary: a non-null Double result must be reported as "Double", not "null".
    assertTrue(
        assertThrows(
                ExpressionException.class,
                () -> ExpressionEvaluator.evaluateBoolean("1 + 1", Map.of()))
            .getMessage()
            .contains("Double"));
  }

  // ---- mutation-hardening: ExpressionException two-arg constructor -------

  @Test
  void expressionExceptionCarriesItsCause() {
    Throwable cause = new IllegalStateException("root");
    ExpressionException ex = new ExpressionException("wrapped", cause);
    assertEquals("wrapped", ex.getMessage());
    assertEquals(cause, ex.getCause());
  }
}

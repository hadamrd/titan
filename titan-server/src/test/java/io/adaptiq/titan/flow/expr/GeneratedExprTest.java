package io.adaptiq.titan.flow.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Example-based sanity checks for the GeneratedExpr model itself (the test infrastructure). */
class GeneratedExprTest {

  @Test
  void numLiteralRendersAndValues() {
    GeneratedExpr e = GeneratedExpr.num(3.0);
    assertEquals("3.0", e.render());
    assertEquals(3.0, e.value());
  }

  @Test
  void additionComputesValueViaJava() {
    GeneratedExpr e = GeneratedExpr.bin("+", GeneratedExpr.num(2.0), GeneratedExpr.num(5.0));
    assertEquals(7.0, e.value());
    assertEquals("(2.0 + 5.0)", e.render());
  }

  @Test
  void whitespaceAndParensRenderingsKeepValue() {
    GeneratedExpr e = GeneratedExpr.bin("<", GeneratedExpr.num(1.0), GeneratedExpr.num(2.0));
    assertEquals(Boolean.TRUE, e.value());
    // all three renderings must be parseable to the same value by the real evaluator
    assertEquals(true, ExpressionEvaluator.evaluate(e.render(), java.util.Map.of()));
    assertEquals(true, ExpressionEvaluator.evaluate(e.renderSpaced(), java.util.Map.of()));
    assertEquals(true, ExpressionEvaluator.evaluate(e.renderParens(), java.util.Map.of()));
  }

  @Test
  void andWithFalseLeftComputesFalse() {
    GeneratedExpr e = GeneratedExpr.bin("&&", GeneratedExpr.bool(false), GeneratedExpr.bool(true));
    assertEquals(Boolean.FALSE, e.value());
  }

  @Test
  void notNegatesBooleanValueAndRenders() {
    GeneratedExpr e = GeneratedExpr.not(GeneratedExpr.bool(true));
    assertEquals(Boolean.FALSE, e.value());
    assertEquals("(!true)", e.render());
    assertEquals(false, ExpressionEvaluator.evaluate(e.renderSpaced(), java.util.Map.of()));
  }

  @Test
  void negNegatesNumericValueAndRenders() {
    GeneratedExpr e = GeneratedExpr.neg(GeneratedExpr.num(4.0));
    assertEquals(-4.0, e.value());
    assertEquals("(-4.0)", e.render());
    assertEquals(-4.0, ExpressionEvaluator.evaluate(e.renderSpaced(), java.util.Map.of()));
  }
}

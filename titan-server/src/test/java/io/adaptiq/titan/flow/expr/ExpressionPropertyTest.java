package io.adaptiq.titan.flow.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;

/** Property-based tests for ExpressionEvaluator: oracle agreement + metamorphic invariants. */
class ExpressionPropertyTest {

  private static final Map<String, Object> EMPTY = Map.of();

  /** Numeric leaves — small integral doubles keep equality well-defined. */
  private Arbitrary<GeneratedExpr> numLeaf() {
    return Arbitraries.integers().between(-20, 20).map(i -> GeneratedExpr.num((double) i));
  }

  private Arbitrary<GeneratedExpr> boolLeaf() {
    return Arbitraries.of(true, false).map(GeneratedExpr::bool);
  }

  /** A numeric expression tree of bounded depth. */
  private Arbitrary<GeneratedExpr> numExpr(int depth) {
    if (depth <= 0) {
      return numLeaf();
    }
    Arbitrary<GeneratedExpr> sub = numExpr(depth - 1);
    Arbitrary<GeneratedExpr> binary =
        Combinators.combine(Arbitraries.of("+", "-", "*"), sub, numExpr(depth - 1))
            .as(GeneratedExpr::bin);
    Arbitrary<GeneratedExpr> negated = sub.map(GeneratedExpr::neg);
    return Arbitraries.oneOf(numLeaf(), binary, negated);
  }

  /** A boolean expression tree: comparisons of numbers, and &&/||/! over booleans. */
  private Arbitrary<GeneratedExpr> boolExpr(int depth) {
    if (depth <= 0) {
      return boolLeaf();
    }
    Arbitrary<GeneratedExpr> comparison =
        Combinators.combine(
                Arbitraries.of("<", "<=", ">", ">=", "==", "!="), numExpr(2), numExpr(2))
            .as(GeneratedExpr::bin);
    Arbitrary<GeneratedExpr> sub = boolExpr(depth - 1);
    Arbitrary<GeneratedExpr> logical =
        Combinators.combine(Arbitraries.of("&&", "||"), sub, boolExpr(depth - 1))
            .as(GeneratedExpr::bin);
    Arbitrary<GeneratedExpr> negated = sub.map(GeneratedExpr::not);
    return Arbitraries.oneOf(boolLeaf(), comparison, logical, negated);
  }

  /**
   * Any scalar expression — numeric or boolean — of depth up to 3. {@code oneOf} weights the two
   * shapes equally; within each, {@code oneOf} again gives leaves roughly a one-third share per
   * level, keeping generated trees shallow enough to read in a failure message.
   */
  @Provide
  Arbitrary<GeneratedExpr> anyExpr() {
    return Arbitraries.oneOf(numExpr(3), boolExpr(3));
  }

  /** Oracle property: the evaluator's result equals the value computed by plain Java. */
  @Property(tries = 2000)
  void evaluatorAgreesWithJavaOracle(@ForAll("anyExpr") GeneratedExpr e) {
    Object actual = ExpressionEvaluator.evaluate(e.render(), EMPTY);
    assertEquals(e.value(), actual, () -> "expr: " + e.render());
    Statistics.label("kind").collect(e.value() instanceof Boolean ? "boolean" : "number");
  }

  /** Metamorphic property: extra whitespace never changes the result. */
  @Property(tries = 1000)
  void whitespaceIsInsignificant(@ForAll("anyExpr") GeneratedExpr e) {
    assertEquals(
        ExpressionEvaluator.evaluate(e.render(), EMPTY),
        ExpressionEvaluator.evaluate(e.renderSpaced(), EMPTY),
        () -> "expr: " + e.render());
  }

  /** Metamorphic property: a redundant outer paren pair never changes the result. */
  @Property(tries = 1000)
  void redundantParensAreInsignificant(@ForAll("anyExpr") GeneratedExpr e) {
    assertEquals(
        ExpressionEvaluator.evaluate(e.render(), EMPTY),
        ExpressionEvaluator.evaluate(e.renderParens(), EMPTY),
        () -> "expr: " + e.render());
  }

  /** Metamorphic property: compile-once then eval twice is consistent. */
  @Property(tries = 1000)
  void compiledExprIsReusable(@ForAll("anyExpr") GeneratedExpr e) {
    ExpressionEvaluator.Expr compiled = ExpressionEvaluator.compile(e.render());
    assertEquals(compiled.eval(EMPTY), compiled.eval(EMPTY), () -> "expr: " + e.render());
    assertEquals(e.value(), compiled.eval(EMPTY), () -> "expr: " + e.render());
  }

  /**
   * Guard on the generator itself: every expression it produces must evaluate without throwing. A
   * failure here means the generator (test infrastructure) is broken — it emitted an expression the
   * evaluator rejects — not that the evaluator is wrong.
   */
  @Property(tries = 2000)
  void everyGeneratedExpressionEvaluates(@ForAll("anyExpr") GeneratedExpr e) {
    boolean ok;
    try {
      ExpressionEvaluator.evaluate(e.render(), EMPTY);
      ok = true;
    } catch (ExpressionException ex) {
      ok = false;
    }
    Statistics.label("evaluates").collect(ok);
    assertTrue(ok, () -> "generator produced a non-evaluating expression: " + e.render());
  }
}

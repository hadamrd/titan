package io.adaptiq.titan.flow.expr;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;

/**
 * Single evaluator for {@code when:} guards — used by both bake-time (stage-level, against {@code
 * params}) and run-time (step-level, against the full {@code ${{ … }}} resolution context) so the
 * two phases share one expression semantics (design/29 §4, PR #259 / #260).
 *
 * <p>The expression language is the restricted CEL subset implemented by {@link
 * ExpressionEvaluator} — boolean / comparison / arithmetic / member-access / indexing, no calls.
 *
 * <p>A {@code null} or blank {@code when} means "always run" (i.e. {@link #isSkipped} returns
 * {@code false}). Any non-blank expression is evaluated; a non-boolean result or syntax error
 * propagates from {@link ExpressionEvaluator} as {@link ExpressionException}.
 */
public final class WhenEvaluator {

  private WhenEvaluator() {}

  /**
   * Returns {@code true} when a node carrying {@code when} should be skipped, i.e. the expression
   * evaluates {@code false}. A {@code null} or blank expression yields {@code false} (no guard,
   * never skipped).
   *
   * @param when the {@code when:} expression source ({@code null} / blank = no guard)
   * @param context the evaluation scope — {@code params} at bake time; {@code params + steps +
   *     pipeline} at run time
   * @throws ExpressionException on a syntax error, unknown variable, type mismatch, or a
   *     non-boolean result
   */
  public static boolean isSkipped(@Nullable String when, @NonNull Map<String, Object> context) {
    if (when == null || when.isBlank()) {
      return false;
    }
    return !ExpressionEvaluator.evaluateBoolean(when, context);
  }
}

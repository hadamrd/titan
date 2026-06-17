package io.adaptiq.titan.flow.expr;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Thrown when a {@code when:} / {@code precondition} expression cannot be compiled or evaluated — a
 * syntax error, an unknown variable, a type mismatch (design/29 §4).
 *
 * <p>This never escapes into the orchestrator loop: a {@code when:} failure fails the <em>bake</em>
 * and a {@code precondition} failure fails the <em>node</em>. Callers catch it deliberately.
 */
public class ExpressionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ExpressionException(String message) {
    super(message);
  }

  public ExpressionException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}

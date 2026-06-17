package io.adaptiq.titan.flow.expr;

/**
 * A generated scalar expression carrying its Java-computed value (the independent oracle) and able
 * to render itself three ways. Scalars only: numbers (Double) and booleans. This is test
 * infrastructure — see GeneratedExprTest.
 */
final class GeneratedExpr {

  /** Node value, computed with plain Java operators when the node is built. */
  private final Object value;

  /** Canonical rendering — every binary node fully parenthesised, so precedence is unambiguous. */
  private final String canonical;

  /** Rendering with extra whitespace at every legal token boundary (built recursively). */
  private final String spaced;

  private GeneratedExpr(Object value, String canonical, String spaced) {
    this.value = value;
    this.canonical = canonical;
    this.spaced = spaced;
  }

  Object value() {
    return value;
  }

  String render() {
    return canonical;
  }

  /**
   * Same expression with extra whitespace around parentheses and operators. Whitespace is inserted
   * only between tokens — never inside a number literal or a multi-character operator — so it
   * always parses to the same value. Tests lexer whitespace-skipping.
   */
  String renderSpaced() {
    return spaced;
  }

  /** Same expression wrapped in one extra redundant pair of parentheses. */
  String renderParens() {
    return "(" + canonical + ")";
  }

  static GeneratedExpr num(double d) {
    String lit = Double.toString(d);
    return new GeneratedExpr(d, lit, lit);
  }

  static GeneratedExpr bool(boolean b) {
    String lit = Boolean.toString(b);
    return new GeneratedExpr(b, lit, lit);
  }

  /**
   * Build a binary node. Value is computed here with plain Java — this is the oracle: a PIT mutant
   * of ExpressionEvaluator's operator cannot agree with real Java semantics. Division and modulo
   * are intentionally unsupported — the property generators never produce them; their
   * divide-/modulo-by-zero error paths are covered by targeted tests instead.
   */
  static GeneratedExpr bin(String op, GeneratedExpr l, GeneratedExpr r) {
    Object lv = l.value;
    Object rv = r.value;
    Object v =
        switch (op) {
          case "+" -> (Double) lv + (Double) rv;
          case "-" -> (Double) lv - (Double) rv;
          case "*" -> (Double) lv * (Double) rv;
          case "<" -> (Double) lv < (Double) rv;
          case "<=" -> (Double) lv <= (Double) rv;
          case ">" -> (Double) lv > (Double) rv;
          case ">=" -> (Double) lv >= (Double) rv;
          case "==" -> javaEquals(lv, rv);
          case "!=" -> !javaEquals(lv, rv);
          case "&&" -> (Boolean) lv && (Boolean) rv;
          case "||" -> (Boolean) lv || (Boolean) rv;
          default -> throw new IllegalArgumentException("unsupported op " + op);
        };
    String canonical = "(" + l.canonical + " " + op + " " + r.canonical + ")";
    String spaced = "( " + l.spaced + " " + op + " " + r.spaced + " )";
    return new GeneratedExpr(v, canonical, spaced);
  }

  static GeneratedExpr not(GeneratedExpr e) {
    return new GeneratedExpr(
        !(Boolean) e.value, "(!" + e.canonical + ")", "( ! " + e.spaced + " )");
  }

  static GeneratedExpr neg(GeneratedExpr e) {
    return new GeneratedExpr(-(Double) e.value, "(-" + e.canonical + ")", "( - " + e.spaced + " )");
  }

  private static boolean javaEquals(Object l, Object r) {
    if (l instanceof Double && r instanceof Double) {
      return ((Double) l).doubleValue() == ((Double) r).doubleValue();
    }
    return java.util.Objects.equals(l, r);
  }
}

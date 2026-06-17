package io.adaptiq.titan.flow.expr;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluates the restricted, non-Turing-complete expression language used by {@code when:} and
 * {@code precondition} (design/29 §4). CEL is the reference model; this is a hand-rolled subset.
 *
 * <p><strong>Why it is safe by construction.</strong> The grammar has <em>no call syntax</em> — an
 * identifier path can be followed only by {@code .field} or {@code [key]}, never {@code (...)}. So
 * {@code System.exit()}, {@code Runtime.exec(...)} and friends cannot even be <em>written</em>, let
 * alone reached: there is no sandbox to escape because there is no execution. The evaluation
 * context is plain {@link Map}s and scalars — there is no path to a live Java object.
 *
 * <p>Supported: boolean ({@code && || !}), comparison ({@code == != < <= > >=}), arithmetic ({@code
 * + - * / %}), string concatenation ({@code +}), parentheses, member access ({@code a.b}) and
 * indexing ({@code a['b']}). Unsupported on purpose: method calls, loops, assignment, I/O.
 *
 * <p>Two call sites, one evaluator (design/29 §4): {@code when:} is evaluated at bake time against
 * {@code params}; {@code precondition} at run time against published step outputs. Both go through
 * {@link #evaluateBoolean(String, Map)}.
 */
public final class ExpressionEvaluator {

  private ExpressionEvaluator() {}

  /**
   * Compile and evaluate {@code expression}, requiring a boolean result.
   *
   * @param expression the {@code when:} / {@code precondition} source
   * @param context read-only scopes ({@code params}, {@code steps}, {@code pipeline}) —
   *     Maps/scalars
   * @return the boolean result
   * @throws ExpressionException on a syntax error, unknown variable, type mismatch, or a
   *     non-boolean result.
   */
  public static boolean evaluateBoolean(
      @NonNull String expression, @NonNull Map<String, Object> context) {
    Object result = evaluate(expression, context);
    if (!(result instanceof Boolean)) {
      throw new ExpressionException(
          "expression must evaluate to a boolean, got "
              + (result == null ? "null" : result.getClass().getSimpleName())
              + ": "
              + expression);
    }
    return (Boolean) result;
  }

  /** Compile and evaluate {@code expression} to its raw value. */
  @Nullable
  public static Object evaluate(@NonNull String expression, @NonNull Map<String, Object> context) {
    return compile(expression).eval(context);
  }

  /** Compile {@code expression} into a reusable AST — parse errors surface here. */
  @NonNull
  public static Expr compile(@NonNull String expression) {
    Parser parser = new Parser(new Lexer(expression).tokenize());
    Expr expr = parser.parseExpression();
    parser.expectEnd();
    return expr;
  }

  // =====================================================================
  // AST — every node is a pure function of the evaluation context.
  // =====================================================================

  /** A compiled expression node. */
  public interface Expr {
    @Nullable
    Object eval(@NonNull Map<String, Object> ctx);
  }

  /** A literal — boolean, string, double, or null. */
  record Lit(@Nullable Object value) implements Expr {
    @Override
    public Object eval(Map<String, Object> ctx) {
      return value;
    }
  }

  /** A top-level variable reference ({@code params}, {@code steps}, {@code pipeline}). */
  record Var(String name) implements Expr {
    @Override
    public Object eval(Map<String, Object> ctx) {
      if (!ctx.containsKey(name)) {
        throw new ExpressionException("unknown variable '" + name + "'");
      }
      return ctx.get(name);
    }
  }

  /** Member access {@code target.field}. */
  record Member(Expr target, String field) implements Expr {
    @Override
    public Object eval(Map<String, Object> ctx) {
      Object t = target.eval(ctx);
      if (t == null) {
        throw new ExpressionException("cannot access '." + field + "' on null");
      }
      if (t instanceof Map) {
        return ((Map<?, ?>) t).get(field); // missing key -> null
      }
      throw new ExpressionException(
          "cannot access '." + field + "' on " + t.getClass().getSimpleName());
    }
  }

  /** Index access {@code target[key]}. */
  record Index(Expr target, Expr key) implements Expr {
    @Override
    public Object eval(Map<String, Object> ctx) {
      Object t = target.eval(ctx);
      Object k = key.eval(ctx);
      if (t == null) {
        throw new ExpressionException("cannot index into null");
      }
      if (t instanceof Map) {
        return ((Map<?, ?>) t).get(k == null ? null : String.valueOf(stringKey(k)));
      }
      if (t instanceof List) {
        if (!(k instanceof Double)) {
          throw new ExpressionException("list index must be a number");
        }
        int i = (int) (double) (Double) k;
        List<?> list = (List<?>) t;
        if (i < 0 || i >= list.size()) {
          throw new ExpressionException("list index " + i + " out of bounds");
        }
        return list.get(i);
      }
      throw new ExpressionException("cannot index into " + t.getClass().getSimpleName());
    }

    private static Object stringKey(Object k) {
      // a numeric map key like steps[0] is uncommon but normalise it sanely
      if (k instanceof Double && (Double) k == Math.floor((Double) k)) {
        return String.valueOf((long) (double) (Double) k);
      }
      return k;
    }
  }

  /** Unary {@code !x} / {@code -x}. */
  record Unary(String op, Expr operand) implements Expr {
    @Override
    public Object eval(Map<String, Object> ctx) {
      Object v = operand.eval(ctx);
      if ("!".equals(op)) {
        if (!(v instanceof Boolean)) {
          throw new ExpressionException("'!' requires a boolean operand");
        }
        return !(Boolean) v;
      }
      // unary minus
      if (!(v instanceof Number)) {
        throw new ExpressionException("unary '-' requires a number");
      }
      return -((Number) v).doubleValue();
    }
  }

  /** A binary operator node — short-circuits {@code &&} / {@code ||}. */
  record Binary(String op, Expr left, Expr right) implements Expr {
    @Override
    public Object eval(Map<String, Object> ctx) {
      switch (op) {
        case "&&":
          {
            boolean l = bool(left.eval(ctx), "&&");
            return l && bool(right.eval(ctx), "&&");
          }
        case "||":
          {
            boolean l = bool(left.eval(ctx), "||");
            return l || bool(right.eval(ctx), "||");
          }
        default:
          return applyValue(op, left.eval(ctx), right.eval(ctx));
      }
    }

    private static boolean bool(Object v, String op) {
      if (!(v instanceof Boolean)) {
        throw new ExpressionException("'" + op + "' requires boolean operands");
      }
      return (Boolean) v;
    }

    private static Object applyValue(String op, Object l, Object r) {
      switch (op) {
        case "==":
          return equalValues(l, r);
        case "!=":
          return !equalValues(l, r);
        case "+":
          if (l instanceof String || r instanceof String) {
            if (l instanceof String && r instanceof String) {
              return (String) l + r;
            }
            throw new ExpressionException("'+' cannot mix string and non-string");
          }
          return num(l, "+") + num(r, "+");
        case "-":
          return num(l, "-") - num(r, "-");
        case "*":
          return num(l, "*") * num(r, "*");
        case "/":
          double divisor = num(r, "/");
          if (divisor == 0.0) {
            throw new ExpressionException("division by zero");
          }
          return num(l, "/") / divisor;
        case "%":
          double mod = num(r, "%");
          if (mod == 0.0) {
            throw new ExpressionException("modulo by zero");
          }
          return num(l, "%") % mod;
        case "<":
          return compare(l, r) < 0;
        case "<=":
          return compare(l, r) <= 0;
        case ">":
          return compare(l, r) > 0;
        case ">=":
          return compare(l, r) >= 0;
        default:
          throw new ExpressionException("unknown operator '" + op + "'");
      }
    }

    private static boolean equalValues(Object l, Object r) {
      if (l instanceof Number && r instanceof Number) {
        return ((Number) l).doubleValue() == ((Number) r).doubleValue();
      }
      return java.util.Objects.equals(l, r);
    }

    private static double num(Object v, String op) {
      if (!(v instanceof Number)) {
        throw new ExpressionException(
            "'"
                + op
                + "' requires numeric operands, got "
                + (v == null ? "null" : v.getClass().getSimpleName()));
      }
      return ((Number) v).doubleValue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object l, Object r) {
      if (l instanceof Number && r instanceof Number) {
        double a = ((Number) l).doubleValue();
        double b = ((Number) r).doubleValue();
        // Primitive comparison, not Double.compare: Double.compare imposes IEEE total
        // order (it ranks +0.0 above -0.0), which would make 0.0 <= -0.0 false even
        // though equalValues (primitive ==) treats them equal. Stay consistent with ==.
        return a < b ? -1 : (a > b ? 1 : 0);
      }
      if (l instanceof String && r instanceof String) {
        return ((String) l).compareTo((String) r);
      }
      throw new ExpressionException("comparison requires two numbers or two strings");
    }
  }

  // =====================================================================
  // Lexer
  // =====================================================================

  private enum Kind {
    NUMBER,
    STRING,
    IDENT,
    OP,
    END
  }

  private record Token(Kind kind, String text, @Nullable Object value, int pos) {}

  private static final class Lexer {
    private final String src;
    private int i;

    Lexer(String src) {
      this.src = src;
    }

    List<Token> tokenize() {
      List<Token> tokens = new ArrayList<>();
      while (i < src.length()) {
        char c = src.charAt(i);
        if (Character.isWhitespace(c)) {
          i++;
        } else if (Character.isDigit(c)) {
          tokens.add(number());
        } else if (c == '\'' || c == '"') {
          tokens.add(string(c));
        } else if (Character.isLetter(c) || c == '_') {
          tokens.add(ident());
        } else {
          tokens.add(operator());
        }
      }
      tokens.add(new Token(Kind.END, "", null, i));
      return tokens;
    }

    private Token number() {
      int start = i;
      boolean dot = false;
      while (i < src.length()
          && (Character.isDigit(src.charAt(i)) || (src.charAt(i) == '.' && !dot))) {
        if (src.charAt(i) == '.') {
          dot = true;
        }
        i++;
      }
      String text = src.substring(start, i);
      return new Token(Kind.NUMBER, text, Double.parseDouble(text), start);
    }

    private Token string(char quote) {
      int start = i;
      i++; // opening quote
      StringBuilder sb = new StringBuilder();
      while (i < src.length() && src.charAt(i) != quote) {
        char c = src.charAt(i);
        if (c == '\\' && i + 1 < src.length()) {
          char n = src.charAt(i + 1);
          sb.append(
              switch (n) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case '\\' -> '\\';
                case '\'' -> '\'';
                case '"' -> '"';
                default -> n;
              });
          i += 2;
        } else {
          sb.append(c);
          i++;
        }
      }
      if (i >= src.length()) {
        throw new ExpressionException("unterminated string literal at position " + start);
      }
      i++; // closing quote
      return new Token(Kind.STRING, sb.toString(), sb.toString(), start);
    }

    private Token ident() {
      int start = i;
      while (i < src.length()
          && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) {
        i++;
      }
      return new Token(Kind.IDENT, src.substring(start, i), null, start);
    }

    private Token operator() {
      int start = i;
      // two-char operators first
      if (i + 1 < src.length()) {
        String two = src.substring(i, i + 2);
        if (two.equals("&&")
            || two.equals("||")
            || two.equals("==")
            || two.equals("!=")
            || two.equals("<=")
            || two.equals(">=")) {
          i += 2;
          return new Token(Kind.OP, two, null, start);
        }
      }
      char c = src.charAt(i);
      if ("+-*/%<>!.()[]".indexOf(c) >= 0) {
        i++;
        return new Token(Kind.OP, String.valueOf(c), null, start);
      }
      throw new ExpressionException("unexpected character '" + c + "' at position " + i);
    }
  }

  // =====================================================================
  // Parser — recursive descent, standard precedence climbing.
  // =====================================================================

  private static final class Parser {
    private final List<Token> tokens;
    private int p;

    Parser(List<Token> tokens) {
      this.tokens = tokens;
    }

    Expr parseExpression() {
      return parseOr();
    }

    void expectEnd() {
      if (peek().kind() != Kind.END) {
        throw new ExpressionException(
            "unexpected trailing input '" + peek().text() + "' at position " + peek().pos());
      }
    }

    private Expr parseOr() {
      Expr left = parseAnd();
      while (isOp("||")) {
        next();
        left = new Binary("||", left, parseAnd());
      }
      return left;
    }

    private Expr parseAnd() {
      Expr left = parseEquality();
      while (isOp("&&")) {
        next();
        left = new Binary("&&", left, parseEquality());
      }
      return left;
    }

    private Expr parseEquality() {
      Expr left = parseComparison();
      while (isOp("==") || isOp("!=")) {
        String op = next().text();
        left = new Binary(op, left, parseComparison());
      }
      return left;
    }

    private Expr parseComparison() {
      Expr left = parseAdditive();
      while (isOp("<") || isOp("<=") || isOp(">") || isOp(">=")) {
        String op = next().text();
        left = new Binary(op, left, parseAdditive());
      }
      return left;
    }

    private Expr parseAdditive() {
      Expr left = parseMultiplicative();
      while (isOp("+") || isOp("-")) {
        String op = next().text();
        left = new Binary(op, left, parseMultiplicative());
      }
      return left;
    }

    private Expr parseMultiplicative() {
      Expr left = parseUnary();
      while (isOp("*") || isOp("/") || isOp("%")) {
        String op = next().text();
        left = new Binary(op, left, parseUnary());
      }
      return left;
    }

    private Expr parseUnary() {
      if (isOp("!") || isOp("-")) {
        String op = next().text();
        return new Unary(op, parseUnary());
      }
      return parsePostfix();
    }

    private Expr parsePostfix() {
      Expr e = parsePrimary();
      while (true) {
        if (isOp(".")) {
          next();
          Token field = next();
          if (field.kind() != Kind.IDENT) {
            throw new ExpressionException(
                "expected a field name after '.' at position " + field.pos());
          }
          e = new Member(e, field.text());
        } else if (isOp("[")) {
          next();
          Expr key = parseExpression();
          expectOp("]");
          e = new Index(e, key);
        } else {
          return e;
        }
      }
    }

    private Expr parsePrimary() {
      Token t = peek();
      switch (t.kind()) {
        case NUMBER:
        case STRING:
          next();
          return new Lit(t.value());
        case IDENT:
          next();
          return switch (t.text()) {
            case "true" -> new Lit(Boolean.TRUE);
            case "false" -> new Lit(Boolean.FALSE);
            case "null" -> new Lit(null);
            default -> new Var(t.text());
          };
        case OP:
          if ("(".equals(t.text())) {
            next();
            Expr inner = parseExpression();
            expectOp(")");
            return inner;
          }
          throw new ExpressionException(
              "unexpected operator '" + t.text() + "' at position " + t.pos());
        case END:
        default:
          throw new ExpressionException("unexpected end of expression");
      }
    }

    private Token peek() {
      return tokens.get(p);
    }

    private Token next() {
      return tokens.get(p++);
    }

    private boolean isOp(String op) {
      Token t = peek();
      return t.kind() == Kind.OP && t.text().equals(op);
    }

    private void expectOp(String op) {
      if (!isOp(op)) {
        throw new ExpressionException(
            "expected '" + op + "' but found '" + peek().text() + "' at position " + peek().pos());
      }
      next();
    }
  }
}

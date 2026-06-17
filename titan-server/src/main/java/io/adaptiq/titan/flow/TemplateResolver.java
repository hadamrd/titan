package io.adaptiq.titan.flow;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.expr.ExpressionEvaluator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${{ … }}} templated references in step arguments (design/29 §6) — Chunk 6E.
 *
 * <p>A downstream step reads an upstream step's published output with {@code ${{
 * steps['Build'].outputs.version }}}. The text inside the braces is an ordinary {@link
 * ExpressionEvaluator} expression (member access + indexing), evaluated against a read-only context
 * of scopes — {@code params}, {@code steps}, {@code pipeline}. So there is no second expression
 * language: the 6B evaluator does the work, the same one that is "safe by construction" (no call
 * syntax — nothing executable).
 *
 * <p>References are resolved by the orchestrator <em>at dispatch time</em> — when it builds a
 * step's {@code EXECUTE_COMMAND} payload — never at bake time (design/29 §7.1): the producing
 * step's outputs do not exist until it has run.
 */
public final class TemplateResolver {

  private static final Pattern TEMPLATE = Pattern.compile("\\$\\{\\{(.+?)\\}\\}");

  private TemplateResolver() {}

  /**
   * Substitute every {@code ${{ expr }}} in {@code text} with the string form of {@code expr}
   * evaluated against {@code context}. Text with no template is returned unchanged.
   *
   * @throws io.adaptiq.titan.flow.expr.ExpressionException if a reference does not evaluate
   *     (unknown output, type error) — a step that references a missing output fails.
   */
  @Nullable
  public static String resolve(@Nullable String text, @NonNull Map<String, Object> context) {
    if (text == null || !text.contains("${{")) {
      return text;
    }
    Matcher m = TEMPLATE.matcher(text);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      Object value = ExpressionEvaluator.evaluate(m.group(1).trim(), context);
      m.appendReplacement(
          out, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
    }
    m.appendTail(out);
    return out.toString();
  }

  /**
   * Deep-resolve every string in a step's argument map — recursing into nested maps and lists — so
   * {@code ${{ … }}} references work wherever they appear in a step's arguments.
   */
  @NonNull
  public static Map<String, Object> resolveArguments(
      @NonNull Map<String, Object> arguments, @NonNull Map<String, Object> context) {
    Map<String, Object> resolved = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : arguments.entrySet()) {
      resolved.put(e.getKey(), resolveValue(e.getValue(), context));
    }
    return resolved;
  }

  @Nullable
  private static Object resolveValue(@Nullable Object value, @NonNull Map<String, Object> context) {
    if (value instanceof String) {
      return resolve((String) value, context);
    }
    if (value instanceof Map) {
      Map<String, Object> nested = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
        nested.put(String.valueOf(e.getKey()), resolveValue(e.getValue(), context));
      }
      return nested;
    }
    if (value instanceof List) {
      List<Object> nested = new ArrayList<>();
      for (Object item : (List<?>) value) {
        nested.add(resolveValue(item, context));
      }
      return nested;
    }
    return value;
  }
}

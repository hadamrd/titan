package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.ConcurrencyConfig;
import java.util.Set;

/**
 * The pipeline-root {@code concurrency:} scope (issue #1101) — per-job concurrency limit.
 *
 * <p>Not a {@link StepScope}: like {@code buildRetention:} this scope is pipeline-root only and has
 * no stage- or step-level form, so it lives outside the {@link TitanYamlParser#scopes()} loop. It
 * still owns its own parse logic + grammar key so the addition is one focused class.
 *
 * <p>Accepted shapes:
 *
 * <pre>{@code
 * concurrency:
 *   max: 2
 *   on_overflow: queue           # queue | cancel_oldest | cancel_pending  (default: queue)
 *
 * concurrency: 2                 # short form — equivalent to {max: 2}
 * }</pre>
 *
 * <p>Absent: unlimited (the legacy default — backwards compatible).
 */
public final class ConcurrencyScope {

  /** The grammar key. */
  public static final String KEY = "concurrency";

  private static final Set<String> OBJECT_KEYS = Set.of("max", "on_overflow");

  /** Tolerant pre-bake YAML scanner — see {@link #parseTolerant(String)}. */
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private ConcurrencyScope() {}

  /**
   * Parse a {@code concurrency:} node (object or scalar int). Returns {@code null} when the node is
   * absent. Throws {@link PipelineParseException} on any structural / value error — concurrency is
   * a load-bearing policy, never a silent default.
   */
  @Nullable
  public static ConcurrencyConfig parse(@Nullable JsonNode node, @NonNull String context) {
    if (node == null) {
      return null;
    }
    String where = context + " concurrency";
    if (node.isNull()) {
      throw TypedNodeReader.presentButNull(KEY, context);
    }

    // Short form: `concurrency: 2`
    if (node.isIntegralNumber()) {
      int max = node.intValue();
      validateMax(max, where);
      return new ConcurrencyConfig(max, ConcurrencyConfig.OnOverflow.QUEUE);
    }

    if (!node.isObject()) {
      throw new PipelineParseException(
          where
              + ": must be an integer (short form) or an object {max, on_overflow}, got "
              + TypedNodeReader.describe(node));
    }

    ParseSupport.rejectUnknownKeys(node, OBJECT_KEYS, where);

    JsonNode maxNode = node.get("max");
    if (maxNode == null) {
      throw new PipelineParseException(where + ": missing required key 'max'");
    }
    if (maxNode.isNull()) {
      throw TypedNodeReader.presentButNull("max", where);
    }
    if (!maxNode.isIntegralNumber()) {
      throw new PipelineParseException(
          where + ": 'max' must be an integer >= 1, got " + TypedNodeReader.describe(maxNode));
    }
    int max = maxNode.intValue();
    validateMax(max, where);

    ConcurrencyConfig.OnOverflow onOverflow = ConcurrencyConfig.OnOverflow.QUEUE;
    JsonNode overflowNode = node.get("on_overflow");
    if (overflowNode != null && !overflowNode.isNull()) {
      if (!overflowNode.isTextual()) {
        throw new PipelineParseException(
            where
                + ": 'on_overflow' must be a string (queue / cancel_oldest / cancel_pending), got "
                + TypedNodeReader.describe(overflowNode));
      }
      try {
        onOverflow = ConcurrencyConfig.OnOverflow.fromYaml(overflowNode.asText(), where);
      } catch (IllegalArgumentException e) {
        throw new PipelineParseException(e.getMessage(), e);
      }
    }
    return new ConcurrencyConfig(max, onOverflow);
  }

  private static void validateMax(int max, @NonNull String where) {
    if (max < 1) {
      throw new PipelineParseException(where + ": 'max' must be >= 1, got " + max);
    }
  }

  /**
   * Tolerant pre-bake scanner — extract just the {@code concurrency:} block from a (possibly
   * malformed) Titan pipeline YAML. Mirrors {@link
   * TitanYamlParser#parseNotifyHooksTolerant(String)}. Used by the runtime concurrency gate to
   * decide whether to dispatch synthesis at all, BEFORE the full pipeline bake runs.
   *
   * <p>Returns {@code null} on any failure (invalid YAML, missing block, malformed value, anything)
   * — the gate defaults to unlimited when it cannot read the policy, matching the absent-block
   * legacy default. Hard-fail behaviour stays in {@link #parse(JsonNode, String)} for real bake.
   */
  @Nullable
  public static ConcurrencyConfig parseTolerant(@NonNull String yaml) {
    try {
      if (yaml.isBlank()) {
        return null;
      }
      JsonNode root = YAML.readTree(yaml);
      if (root == null || !root.isObject()) {
        return null;
      }
      JsonNode body =
          (root.has("titan") && root.get("titan").isObject()) ? root.get("titan") : root;
      JsonNode node = body.get(KEY);
      if (node == null) {
        return null;
      }
      return parse(node, "tolerant-prebake");
    } catch (RuntimeException | JacksonException e) {
      return null;
    }
  }
}

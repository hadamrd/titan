package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.PriorityConfig;

/**
 * The pipeline-root {@code priority:} scope (issue #1100) — per-job queue priority.
 *
 * <p>Not a {@link StepScope}: like {@code concurrency:} and {@code buildRetention:} this scope is
 * pipeline-root only and has no stage- or step-level form.
 *
 * <p>Accepted shape — a discriminated-union string enum:
 *
 * <pre>{@code
 * priority: high     # → 10
 * priority: normal   # → 0  (default)
 * priority: low      # → -10
 * }</pre>
 *
 * <p>Bare integers are rejected by design (see {@link PriorityConfig}). Absent: {@code null}, which
 * the engine treats as {@code NORMAL} (priority 0 — same as the column default).
 */
public final class PriorityScope {

  /** The grammar key. */
  public static final String KEY = "priority";

  private PriorityScope() {}

  /**
   * Parse a {@code priority:} node (a single string token). Returns {@code null} when the node is
   * absent. Throws {@link PipelineParseException} on any structural / value error — priority is a
   * load-bearing policy, never a silent default.
   */
  @Nullable
  public static PriorityConfig parse(@Nullable JsonNode node, @NonNull String context) {
    if (node == null) {
      return null;
    }
    String where = context + " priority";
    if (node.isNull()) {
      throw TypedNodeReader.presentButNull(KEY, context);
    }
    if (!node.isTextual()) {
      throw new PipelineParseException(
          where
              + ": must be one of the string tokens 'high', 'normal', or 'low' "
              + "(bare integers are rejected — use the named tier), got "
              + TypedNodeReader.describe(node));
    }
    try {
      return PriorityConfig.fromYaml(node.asText(), where);
    } catch (IllegalArgumentException e) {
      throw new PipelineParseException(e.getMessage(), e);
    }
  }
}

package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.databind.JsonNode;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.parser.grammar.GrammarKey;
import java.util.List;

/**
 * The {@code timeout:} scope — a per-step (or stage-default) execution deadline. When a step
 * overruns it, the orchestrator fails the node and the worker kills the process (timer subsystem,
 * Phase 3). A stage-level {@code timeout:} is flattened onto every step that lacks its own; a
 * step's own {@code timeout:} wins — exactly the {@code retry:} flattening rule.
 *
 * <p>Value grammar: {@code 30s}, {@code 5m}, {@code 2h}, {@code 1d}, or a bare number of seconds.
 */
final class TimeoutScope implements StepScope {

  static final String KEY = "timeout";

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public GrammarKey schema() {
    return GrammarKey.optional(
        KEY,
        "Default execution deadline for every step of this stage (e.g. 30s, 5m, 2h). "
            + "Flattened onto each step; a step's own 'timeout' wins.",
        GrammarKey.string());
  }

  @Override
  public GrammarKey stepSchema() {
    return GrammarKey.optional(
        KEY,
        "Execution deadline for this step (e.g. 30s, 5m, 2h). When the step overruns it, "
            + "the controller fails the node and the worker kills the process.",
        GrammarKey.string());
  }

  @Override
  public void parseStep(JsonNode stepNode, StepModel step, ParseContext ctx) {
    JsonNode node = stepNode.get(KEY);
    if (node != null) {
      step.setTimeoutMillis(parseMillis(node, ctx.location() + " timeout"));
    }
  }

  @Override
  public void parseStageAndFlatten(JsonNode stageNode, List<StepModel> steps, ParseContext ctx) {
    JsonNode node = stageNode.get(KEY);
    if (node == null) {
      return;
    }
    long stageMillis = parseMillis(node, ctx.location() + " timeout");
    for (StepModel step : steps) {
      if (step.getTimeoutMillis() == null) { // a step's own timeout: wins
        step.setTimeoutMillis(stageMillis);
      }
    }
  }

  /**
   * Parse {@code 30s} / {@code 5m} / {@code 2h} / {@code 1d} / bare-seconds into milliseconds.
   *
   * <p>Package-private so {@link TitanYamlParser} can reuse it for the pipeline-root {@code
   * timeout:} field (issue #244) without re-deriving the grammar.
   */
  static long parseMillis(JsonNode node, String context) {
    String raw = node.asText().trim();
    if (raw.isEmpty()) {
      throw new PipelineParseException(context + ": timeout is empty");
    }
    char unit = raw.charAt(raw.length() - 1);
    boolean bareNumber = Character.isDigit(unit);
    String digits = bareNumber ? raw : raw.substring(0, raw.length() - 1);
    long n;
    try {
      n = Long.parseLong(digits.trim());
    } catch (NumberFormatException e) {
      throw new PipelineParseException(context + ": timeout is not a number: " + raw);
    }
    if (n <= 0) {
      throw new PipelineParseException(context + ": timeout must be positive: " + raw);
    }
    return switch (bareNumber ? 's' : Character.toLowerCase(unit)) {
      case 's' -> n * 1_000L;
      case 'm' -> n * 60_000L;
      case 'h' -> n * 3_600_000L;
      case 'd' -> n * 86_400_000L;
      default ->
          throw new PipelineParseException(
              context + ": unknown timeout unit '" + unit + "' in: " + raw);
    };
  }
}

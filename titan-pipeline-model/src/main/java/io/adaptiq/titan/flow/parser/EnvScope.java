package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.parser.grammar.GrammarKey;
import io.adaptiq.titan.flow.parser.grammar.TitanGrammar;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code env:} grammar scope (GH #239) — plain environment-variable injection at three scopes:
 * pipeline, stage, and step. Each level carries a {@code Map<String,String>}.
 *
 * <p>Resolution rule: a step's effective environment is the merged map of pipeline env ← stage env
 * ← step env, with later layers overriding earlier keys. The merge itself is performed by {@link
 * MergedEnv}; this scope only parses and stores the per-level maps. The runtime "pass merged env to
 * the worker subprocess" is tracked separately (follow-up to GH #239).
 *
 * <p>The scope applies at all three levels:
 *
 * <ul>
 *   <li>Pipeline level — parsed directly in {@link TitanYamlParser} (same pattern as {@code
 *       credentials:}), stored on {@link io.adaptiq.titan.flow.model.PipelineModel#setEnv}.
 *   <li>Stage level — {@link #parseStageAndFlatten} sets {@code StageModel.env}; it does NOT
 *       flatten onto individual steps (each step sees the merge at dispatch time, not at parse
 *       time).
 *   <li>Step level — {@link #parseStep} sets {@code StepModel.env}.
 * </ul>
 *
 * <p>Value constraints: every key must be a non-blank string; every value must be a string (not a
 * number, boolean, list, or object). A {@code KEY: 42} or {@code KEY: [a,b]} entry is a located
 * parse error naming the offending key.
 */
final class EnvScope implements StepScope {

  static final String KEY = "env";

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public GrammarKey schema() {
    return GrammarKey.optional(
        KEY,
        "Environment variables injected into every step of this stage (GH #239). "
            + "Each key must be a non-blank string; each value must be a string. "
            + "Step-level env overrides stage-level; stage-level overrides pipeline-level.",
        TitanGrammar.envMapSchema());
  }

  @Override
  public GrammarKey stepSchema() {
    return GrammarKey.optional(
        KEY,
        "Environment variables injected into this step (GH #239). "
            + "Merged with pipeline- and stage-level env at dispatch time "
            + "(step > stage > pipeline precedence). Values must be strings.",
        TitanGrammar.envMapSchema());
  }

  @Override
  public void parseStep(JsonNode stepNode, StepModel step, ParseContext ctx) {
    step.setEnv(parse(stepNode.get(KEY), ctx.location() + " env"));
  }

  @Override
  public void parseStageAndFlatten(JsonNode stageNode, List<StepModel> steps, ParseContext ctx) {
    // Stage-level env: is stored on the StageModel. It is NOT flattened onto individual steps
    // here — the merge (pipeline <- stage <- step) happens at dispatch time via MergedEnv,
    // preserving the per-level maps for introspection and for the worker payload.
    ctx.stage().setEnv(parse(stageNode.get(KEY), ctx.location() + " env"));
  }

  /**
   * Parse a {@code env:} node into a {@code Map<String,String>}, or {@code null} when the key is
   * absent (preserving the absent-vs-empty distinction). An explicit {@code env: null} is a
   * present-and-null error. A non-object value is a type error. Each value must be a string —
   * {@code KEY: 42} or {@code KEY: [a,b]} is a located error naming the offending key.
   */
  @Nullable
  static Map<String, String> parse(@Nullable JsonNode node, @NonNull String context) {
    if (node == null) {
      return null; // genuinely absent — no env declared at this level.
    }
    if (node.isNull()) {
      throw TypedNodeReader.presentButNull(KEY, context);
    }
    if (!node.isObject()) {
      throw new PipelineParseException(
          context
              + ": 'env' must be a map of string keys to string values, got "
              + TypedNodeReader.describe(node));
    }
    Map<String, String> out = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> e = fields.next();
      String envKey = e.getKey();
      if (envKey.isBlank()) {
        throw new PipelineParseException(context + ": 'env' keys must not be blank");
      }
      JsonNode val = e.getValue();
      if (val == null || val.isNull()) {
        throw new PipelineParseException(
            context
                + ": env key '"
                + envKey
                + "' is present but null — omit the key, or give it a string value");
      }
      if (!val.isTextual()) {
        throw new PipelineParseException(
            context
                + ": env key '"
                + envKey
                + "' must be a string, got "
                + TypedNodeReader.describe(val));
      }
      String textValue = val.textValue();
      // GH #1094: a value may be a literal OR a 'secret:<id>' reference resolved at dispatch.
      // The shape is validated here so a typo never reaches the orchestrator — the runtime
      // resolution path (EnvResolver, controller-side) trusts the parser.
      EnvValueRef.validate(envKey, textValue, context);
      out.put(envKey, textValue);
    }
    return out;
  }
}

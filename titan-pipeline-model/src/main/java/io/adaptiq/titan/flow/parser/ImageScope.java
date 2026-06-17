package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.databind.JsonNode;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.parser.grammar.GrammarKey;
import java.util.List;

/**
 * The {@code image:} grammar scope (design/31 §6G, design/42 §4.7) — the container image a step or
 * a stage runs in.
 *
 * <p>Unlike {@code credentials:} / {@code sshAgent:} this scope does not flatten: a stage-level
 * {@code image:} is a stage property a step inherits at runtime (see {@code StepModel.image}), not
 * a per-step value computed at parse time. Step-level {@code image:} sets {@code StepModel.image};
 * stage-level {@code image:} sets {@code StageModel.image}.
 */
final class ImageScope implements StepScope {

  static final String KEY = "image";

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public GrammarKey schema() {
    return GrammarKey.optional(
        KEY, "Default container image for this stage's steps.", GrammarKey.string());
  }

  @Override
  public GrammarKey stepSchema() {
    return GrammarKey.optional(
        KEY,
        "Container image this step runs in (overrides the stage 'image').",
        GrammarKey.string());
  }

  @Override
  public void parseStep(JsonNode stepNode, StepModel step, ParseContext ctx) {
    // design/48 D2/D3/D4: `image` is declared a string — typed-read so `image: null` is a
    // present-and-null error and `image: [a,b]` a located type error, not a coercion to "".
    step.setImage(TypedNodeReader.optString(stepNode, stepSchema(), ctx.location()));
  }

  @Override
  public void parseStageAndFlatten(JsonNode stageNode, List<StepModel> steps, ParseContext ctx) {
    ctx.stage().setImage(TypedNodeReader.optString(stageNode, schema(), ctx.location()));
  }
}

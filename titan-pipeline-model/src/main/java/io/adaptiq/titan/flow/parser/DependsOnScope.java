package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.databind.JsonNode;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.parser.grammar.GrammarKey;
import java.util.List;

/**
 * The {@code dependsOn:} grammar scope (design/29 §3, design/42 §4.7) — the DAG edges a stage
 * declares. Concurrency in Titan is the DAG itself: sibling stages with the same {@code dependsOn}
 * run in parallel.
 *
 * <p>{@code dependsOn:} is a stage-only key — it has no step-level meaning, so {@link #parseStep}
 * is a no-op. {@link #parseStageAndFlatten} sets {@code StageModel.dependsOn}. A string or a list
 * is accepted; the DAG itself (cycles, missing/duplicate ids) is validated separately by {@code
 * PipelineDagValidator}.
 */
final class DependsOnScope implements StepScope {

  static final String KEY = "dependsOn";

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public GrammarKey schema() {
    return GrammarKey.optional(
        KEY,
        "Node ids this stage waits for. Empty = runs immediately.",
        GrammarKey.ref("stringOrList"));
  }

  @Override
  public boolean appliesToStep() {
    return false;
  }

  @Override
  public void parseStep(JsonNode stepNode, StepModel step, ParseContext ctx) {
    // `dependsOn:` is a stage-only key — nothing to do on a step.
  }

  @Override
  public void parseStageAndFlatten(JsonNode stageNode, List<StepModel> steps, ParseContext ctx) {
    // design/48 D2/D3/D4: `dependsOn` is declared string-or-list — typed-read so `dependsOn: 5`
    // is a located type error (no coercion to "5") and `dependsOn: null` a present-and-null
    // error. The DAG itself (cycle/missing-id) stays a PipelineDagValidator concern.
    ctx.stage().setDependsOn(TypedNodeReader.stringList(stageNode, schema(), ctx.location()));
  }
}

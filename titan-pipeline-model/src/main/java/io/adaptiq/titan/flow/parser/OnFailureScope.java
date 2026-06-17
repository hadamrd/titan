package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.databind.JsonNode;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.parser.grammar.GrammarKey;
import java.util.List;

/**
 * The {@code onFailure:} grammar scope (design/68, #947) — flips a stage into a <em>failure
 * handler</em>: the stage advances iff at least one of the listed upstream stages reached a
 * terminal {@code FAILED} state.
 *
 * <p><strong>Stage-only.</strong> Like {@link DependsOnScope}, {@code onFailure:} has no step-level
 * meaning — {@link #appliesToStep()} returns {@code false} and {@link #parseStep} is a no-op.
 *
 * <p><strong>Mutual exclusion with {@code dependsOn} (design/68 D1).</strong> A failure handler has
 * exactly one trigger condition (the listed upstream stages failed); a regular stage advances on
 * upstream success. Mixing both confuses the semantics, so a stage that declares both is rejected
 * at parse time with a located error naming the stage. The exclusion is enforced here so the
 * orchestrator never sees the ambiguous shape.
 *
 * <p><strong>Composes with {@code when:} (design/68 D2).</strong> A stage may declare both {@code
 * onFailure:} and {@code when:} — the conditions compose with AND. {@code when:} is parsed by
 * {@link WhenScope} independently; this scope does not touch it.
 *
 * <p><strong>Empty list / self-reference (design/68 D4).</strong> {@code onFailure: []} is rejected
 * as dead code; a stage may not list itself as an upstream-failure target. Unknown target ids are
 * validated by {@link PipelineDagValidator} (it has the full pipeline view — stage names plus
 * matrix/each prototype names).
 *
 * <p><strong>Pre-expansion references (design/68 D3).</strong> The PDL contract is: the YAML cites
 * the prototype name as the user wrote it. Whether "any cell failed" or "all cells failed" maps to
 * the handler advancing is the orchestrator's contract — out of this PR's PDL scope.
 */
final class OnFailureScope implements StepScope {

  static final String KEY = "onFailure";

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public GrammarKey schema() {
    return GrammarKey.optional(
        KEY,
        "Upstream stage names whose failure triggers this stage (design/68). When set, this "
            + "stage is a failure handler: it advances iff at least one listed upstream "
            + "reached FAILED, AND the optional 'when:' expression is true. Mutually "
            + "exclusive with 'dependsOn:' on the same stage.",
        GrammarKey.ref("stringOrList"));
  }

  @Override
  public boolean appliesToStep() {
    return false;
  }

  @Override
  public void parseStep(JsonNode stepNode, StepModel step, ParseContext ctx) {
    // `onFailure:` is a stage-only key — nothing to do on a step.
  }

  @Override
  public void parseStageAndFlatten(JsonNode stageNode, List<StepModel> steps, ParseContext ctx) {
    JsonNode raw = stageNode.get(KEY);
    if (raw == null) {
      // Absent — stage is a regular advance-on-success node; nothing to set.
      return;
    }
    // design/48 D2/D3/D4: typed-read so wrong-typed values and present-and-null are located
    // type errors, not silent coercions.
    List<String> onFailure = TypedNodeReader.stringList(stageNode, schema(), ctx.location());

    // design/68 D4.1: an empty list is dead code — either omit the key or name the upstreams.
    if (onFailure.isEmpty()) {
      throw ctx.error(
          "'onFailure' must list at least one upstream stage — omit the key entirely if "
              + "this stage is not a failure handler (design/68 D4)");
    }

    // design/68 D1: mutual exclusion with `dependsOn:` — a stage cannot be both an
    // advance-on-success node and a failure handler.
    if (stageNode.has("dependsOn")) {
      throw ctx.error(
          "'onFailure' and 'dependsOn' are mutually exclusive on the same stage — a "
              + "failure-handler stage advances on upstream failure, a regular stage on "
              + "upstream success (design/68 D1)");
    }

    // design/68 D4.3: self-reference is forbidden by analogy with dependsOn self-reference.
    String selfName = ctx.stage().getName();
    for (String target : onFailure) {
      if (selfName.equals(target)) {
        throw ctx.error("'onFailure' must not reference the stage itself ('" + selfName + "')");
      }
    }

    ctx.stage().setOnFailureStages(onFailure);
  }
}

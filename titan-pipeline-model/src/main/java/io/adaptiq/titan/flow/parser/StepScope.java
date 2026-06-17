package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.databind.JsonNode;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.parser.grammar.GrammarKey;
import java.util.List;

/**
 * One modular unit of the Titan YAML grammar (design/42 §4.7).
 *
 * <p>Every <em>scope</em> — {@code credentials}, {@code sshAgent}, {@code image}, {@code when},
 * {@code dependsOn} — owns exactly one YAML key. Before this seam each scope bolted its
 * parse/flatten/validate code straight into {@code TitanYamlParser}; five scopes in, the parser was
 * an append-only monolith (design/42 §1 gap 5). Now {@code TitanYamlParser} is a thin engine that
 * parses the pipeline/stage/step skeleton and the generic {@code descriptorId: args} body, then
 * loops a fixed, explicitly-registered list of {@code StepScope}s.
 *
 * <p>This is parser machinery — it deals in {@link StepModel}/{@link JsonNode}, never in step
 * descriptors — so it is controller- and worker-safe and lives in {@code titan-pipeline-model},
 * <strong>not</strong> {@code titan-step-api}. The scope set is curated <em>core</em> — a fixed
 * registered list, not {@code ServiceLoader}-pluggable: a grammar keyword is global, so the grammar
 * stays portable (design/42 §4.7).
 */
public interface StepScope {

  /** The single YAML key this scope owns — e.g. {@code "sshAgent"}. */
  String key();

  /**
   * The grammar declaration of this scope's key (design/47 §3) — its {@link GrammarKey}: the key
   * name (which <strong>must</strong> equal {@link #key()}), its required-ness, its human
   * description and the draft-2020-12 JSON Schema fragment for the key's value.
   *
   * <p>A scope is a single self-contained unit of grammar (design/42 §4.7): it owns its key's
   * parse, flatten, validate <em>and</em> schema. {@code TitanSchemaGenerator} projects the editor
   * schema's {@code stage}/{@code step} {@code $defs} by splicing each registered scope's {@code
   * schema()} into the skeleton. Non-default — every scope must declare it.
   *
   * <p>This is the declaration as it appears on a <strong>stage</strong> node (every scope applies
   * to a stage). The step-node variant — which for some scopes carries a context-tuned description
   * — is {@link #stepSchema()}; it defaults to this one.
   */
  GrammarKey schema();

  /**
   * The grammar declaration of this scope's key as it appears on a <strong>step</strong> node
   * (design/47 §3). Defaults to {@link #schema()}; a scope whose step-level description differs
   * from its stage-level one (e.g. {@code image}, {@code credentials}, {@code retry}) overrides
   * this. Only consulted for scopes where {@link #appliesToStep()} is {@code true}.
   */
  default GrammarKey stepSchema() {
    return schema();
  }

  /**
   * Whether this scope's key is also valid on a single step node (and not only on a stage). {@code
   * credentials}, {@code sshAgent} and {@code image} are step-and-stage scopes; {@code when} and
   * {@code dependsOn} are stage-only — a {@code when:} key on a step is not a scope key, it is a
   * (bogus) descriptor key, exactly as before this seam existed. Drives the computed {@code
   * STEP_KEYS} set in {@link TitanYamlParser}. Defaults to {@code true}.
   */
  default boolean appliesToStep() {
    return true;
  }

  /**
   * Parse this scope's key off a single step node onto {@code step}. The key has already been
   * recognised by {@link TitanYamlParser} as belonging to a scope (so it is not a descriptor key);
   * the value is {@code stepNode.get(key())}, which may be absent.
   */
  void parseStep(JsonNode stepNode, StepModel step, ParseContext ctx);

  /**
   * Parse this scope's key off a stage node and apply it: set stage-level data on {@code
   * ctx.stage()} and/or flatten a stage-level list onto every step in {@code steps}. Called once
   * per stage, <em>after</em> every step has been built — so the effective per-step value (step ++
   * stage-flatten) is available for {@link #validate}.
   */
  void parseStageAndFlatten(JsonNode stageNode, List<StepModel> steps, ParseContext ctx);

  /**
   * Validate the effective (post-flatten) state of {@code step}. A no-op by default. Called once
   * per step after {@link #parseStageAndFlatten} so a stage-flattened value is checked too.
   */
  default void validate(StepModel step, ParseContext ctx) {}
}

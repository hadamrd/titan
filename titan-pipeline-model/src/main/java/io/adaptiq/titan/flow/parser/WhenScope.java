package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.model.PreviousOutcome;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.model.WhenCondition;
import io.adaptiq.titan.flow.parser.grammar.GrammarKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The {@code when:} grammar scope (design/29 §3, design/42 §4.7, GH #240, GH #1093) — the optional
 * condition guarding whether a stage or an individual step runs.
 *
 * <p>{@code when:} applies at <strong>both</strong> stage and step level. At <em>stage</em> level
 * the value is a free-form CEL string ({@link #parseStageAndFlatten} sets {@code StageModel.when}).
 * At <em>step</em> level the value is a {@code oneOf[string, object]} (GH #1093):
 *
 * <ul>
 *   <li>a <strong>string</strong> → the legacy CEL expression, set on {@link StepModel#setWhen};
 *   <li>an <strong>object</strong> → the typed discriminated union {@link WhenCondition}, set on
 *       {@link StepModel#setWhenCondition} — exactly one of {@code branch} (glob), {@code previous}
 *       ({@code success|failure|always}) or {@code files_changed} (glob list).
 * </ul>
 *
 * <p>The object form is validated <strong>at parse time</strong> (GH #1093 acceptance criterion): a
 * malformed {@code when:} block — unknown key, zero or multiple discriminators, a bad {@code
 * previous} literal, a wrong value type — raises a located {@link PipelineParseException}, never a
 * runtime surprise. Runtime evaluation (skipping a step whose condition is false) lives in the
 * orchestrator's {@code StructuredWhenEvaluator}; this scope is the parse / model layer only.
 */
final class WhenScope implements StepScope {

  static final String KEY = "when";

  static final String BRANCH = "branch";
  static final String PREVIOUS = "previous";
  static final String FILES_CHANGED = "files_changed";

  /** The keys allowed inside the structured {@code when:} object form. */
  private static final Set<String> WHEN_KEYS = Set.of(BRANCH, PREVIOUS, FILES_CHANGED);

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public GrammarKey schema() {
    return GrammarKey.optional(
        KEY,
        "Condition expression — the stage runs only if it evaluates true.",
        GrammarKey.string());
  }

  @Override
  public GrammarKey stepSchema() {
    // GH #1093: the step-level `when:` is a oneOf[string, structured-object]. A string is the
    // legacy CEL expression; the object is the typed branch/previous/files_changed union.
    ObjectNode oneOf = NODES.objectNode();
    com.fasterxml.jackson.databind.node.ArrayNode branches = oneOf.putArray("oneOf");
    branches.add(NODES.objectNode().put("type", "string"));
    branches.add(GrammarKey.ref("whenCondition"));
    return GrammarKey.optional(
        KEY,
        "Condition guarding this step (GH #1093). Either a CEL string, or a structured block "
            + "with exactly one of: branch (glob), previous (success|failure|always), "
            + "files_changed (glob list). A false condition skips the step (status SKIPPED).",
        oneOf);
  }

  @Override
  public boolean appliesToStep() {
    return true;
  }

  @Override
  public void parseStep(JsonNode stepNode, StepModel step, ParseContext ctx) {
    JsonNode node = stepNode.get(KEY);
    if (node == null) {
      return; // genuinely absent — the step always runs.
    }
    String where = ctx.location() + " step " + step.getId() + " when";
    if (node.isNull()) {
      // design/48 D2/D3/D4: a present-and-null `when:` is a located error, not a silent no-op.
      throw TypedNodeReader.presentButNull(KEY, where);
    }
    if (node.isTextual()) {
      // Legacy CEL string form — unchanged behaviour.
      step.setWhen(node.textValue());
      return;
    }
    if (node.isObject()) {
      step.setWhenCondition(parseStructured(node, where));
      return;
    }
    // a number, a boolean, an array — neither oneOf branch.
    throw new PipelineParseException(
        where
            + ": 'when' must be a string (a CEL expression) or an object "
            + "{ branch | previous | files_changed }, got "
            + TypedNodeReader.describe(node));
  }

  /**
   * Parse the structured {@code when:} object into a typed {@link WhenCondition} (GH #1093),
   * enforcing the discriminated-union contract at parse time: unknown keys are rejected, exactly
   * one of {@code branch}/{@code previous}/{@code files_changed} must be present, and each value is
   * type-checked.
   */
  @NonNull
  private static WhenCondition parseStructured(@NonNull JsonNode node, @NonNull String where) {
    ParseSupport.rejectUnknownKeys(node, WHEN_KEYS, where);

    List<String> present = new ArrayList<>();
    for (String k : new String[] {BRANCH, PREVIOUS, FILES_CHANGED}) {
      if (node.has(k)) {
        present.add(k);
      }
    }
    if (present.isEmpty()) {
      throw new PipelineParseException(
          where
              + ": a structured 'when:' block must declare exactly one of "
              + WHEN_KEYS
              + " — found none");
    }
    if (present.size() > 1) {
      throw new PipelineParseException(
          where
              + ": a structured 'when:' block is a discriminated union — declare exactly one of "
              + WHEN_KEYS
              + ", found "
              + present);
    }

    String discriminator = present.get(0);
    switch (discriminator) {
      case BRANCH:
        return WhenCondition.ofBranch(requireGlobString(node.get(BRANCH), BRANCH, where));
      case PREVIOUS:
        return WhenCondition.ofPrevious(parsePrevious(node.get(PREVIOUS), where));
      case FILES_CHANGED:
        return WhenCondition.ofFilesChanged(parseGlobList(node.get(FILES_CHANGED), where));
      default:
        // unreachable — `present` is computed from WHEN_KEYS.
        throw new PipelineParseException(
            where + ": unhandled when discriminator '" + discriminator + "'");
    }
  }

  @NonNull
  private static String requireGlobString(
      @NonNull JsonNode v, @NonNull String field, @NonNull String where) {
    if (v.isNull()) {
      throw TypedNodeReader.presentButNull(field, where);
    }
    if (!v.isTextual() || v.textValue().isBlank()) {
      throw new PipelineParseException(
          where
              + ": 'when."
              + field
              + "' must be a non-blank glob string, got "
              + TypedNodeReader.describe(v));
    }
    return v.textValue();
  }

  @NonNull
  private static PreviousOutcome parsePrevious(@NonNull JsonNode v, @NonNull String where) {
    if (v.isNull()) {
      throw TypedNodeReader.presentButNull(PREVIOUS, where);
    }
    if (!v.isTextual()) {
      throw new PipelineParseException(
          where + ": 'when.previous' must be a string, got " + TypedNodeReader.describe(v));
    }
    PreviousOutcome outcome = PreviousOutcome.fromYaml(v.textValue());
    if (outcome == null) {
      throw new PipelineParseException(
          where
              + ": 'when.previous' must be one of success|failure|always, got '"
              + v.textValue()
              + "'");
    }
    return outcome;
  }

  @NonNull
  private static List<String> parseGlobList(@NonNull JsonNode v, @NonNull String where) {
    if (v.isNull()) {
      throw TypedNodeReader.presentButNull(FILES_CHANGED, where);
    }
    if (!v.isArray()) {
      throw new PipelineParseException(
          where
              + ": 'when.files_changed' must be an array of glob strings, got "
              + TypedNodeReader.describe(v));
    }
    if (v.isEmpty()) {
      throw new PipelineParseException(
          where + ": 'when.files_changed' must list at least one glob");
    }
    List<String> out = new ArrayList<>(v.size());
    int i = 0;
    for (JsonNode item : v) {
      if (!item.isTextual() || item.textValue().isBlank()) {
        throw new PipelineParseException(
            where
                + ": 'when.files_changed["
                + i
                + "]' must be a non-blank glob string, got "
                + TypedNodeReader.describe(item));
      }
      out.add(item.textValue());
      i++;
    }
    return out;
  }

  @Override
  public void parseStageAndFlatten(JsonNode stageNode, List<StepModel> steps, ParseContext ctx) {
    // Stage-level `when:` is the free-form CEL string only — it is NOT flattened onto individual
    // steps; a step's own `when:` is an independent per-step condition.
    ctx.stage().setWhen(TypedNodeReader.optString(stageNode, schema(), ctx.location()));
  }
}

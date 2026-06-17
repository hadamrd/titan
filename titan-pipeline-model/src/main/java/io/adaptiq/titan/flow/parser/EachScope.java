package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.parser.grammar.GrammarKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code each:} grammar scope (design/55) — declarative 1D fan-out of a stage. A stage carrying
 * {@code each:} expands at parse time into N cell stages, one per element of the {@code in:} list.
 * Step arguments referencing {@code ${each.<var>}} are substituted with the cell's value.
 *
 * <p>Sibling of {@link MatrixScope}: identical scope pattern (stage-only, parse-time expansion,
 * pending-spec stash drained by {@link TitanYamlParser}). The two scopes are <strong>mutually
 * exclusive on the same stage</strong> in v1 — see {@link #parseStageAndFlatten}.
 *
 * <p>{@code each:} is <strong>stage-only</strong> ({@link #appliesToStep()} returns {@code false})
 * — it changes the pipeline shape, not a single step's behaviour.
 *
 * <p>The {@code each} {@code $def} is declared in {@link
 * io.adaptiq.titan.flow.parser.grammar.TitanGrammar#eachDef()}; this scope only references it via
 * {@code $ref}.
 */
final class EachScope implements StepScope {

  static final String KEY = "each";

  /** Allowed keys inside the {@code each:} object. */
  private static final Set<String> EACH_KEYS = Set.of("var", "in", "maxParallel");

  /** Names a user-supplied {@code var:} may not take — reserved bindings. */
  private static final Set<String> RESERVED_VAR_NAMES = Set.of("each", "matrix", "env", "params");

  /**
   * Fail-fast default for an {@code each:} fan-out (#1213). {@code each:} exposes no {@code
   * fail_fast} key, so — matching GitHub Actions / GitLab CI / Buildkite, and {@link MatrixScope}'s
   * own default — a cell failure cancels the in-flight siblings. Stamped into the shared {@code
   * __matrix} metadata the orchestrator reads, so {@code each} and {@code matrix} share one
   * cancellation mechanism.
   */
  private static final boolean EACH_FAIL_FAST = true;

  /** Identifier shape for the {@code var:} name. */
  private static final Pattern VAR_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  /** Matches {@code ${each.<var>}} occurrences in a string argument value. */
  private static final Pattern EACH_REF = Pattern.compile("\\$\\{each\\.([A-Za-z0-9_]+)\\}");

  /** Per-parse stash of each specs awaiting engine-side expansion. */
  private static final ThreadLocal<Map<StageModel, EachSpec>> PENDING =
      ThreadLocal.withInitial(LinkedHashMap::new);

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public GrammarKey schema() {
    return GrammarKey.optional(
        KEY,
        "Stage-level 1D iteration (design/55). Fans the stage out into one stage per element "
            + "of 'in' at parse time; step arguments may reference the cell with "
            + "${each.<var>}. Mutually exclusive with 'matrix:' in v1.",
        GrammarKey.ref("each"));
  }

  @Override
  public boolean appliesToStep() {
    return false; // each is stage-structural; no step-level form.
  }

  @Override
  public void parseStep(JsonNode stepNode, StepModel step, ParseContext ctx) {
    // each is stage-only — nothing to do on a step.
  }

  @Override
  public void parseStageAndFlatten(JsonNode stageNode, List<StepModel> steps, ParseContext ctx) {
    JsonNode node = stageNode.get(KEY);
    if (node == null) {
      return;
    }
    // design/55 §3: each and matrix are mutually exclusive in v1. Report at parse, not at
    // expansion, so the engine never sees an ambiguous prototype.
    if (stageNode.has(MatrixScope.KEY)) {
      throw new PipelineParseException(
          ctx.location() + ": 'each' and 'matrix' are mutually exclusive on the same stage (v1)");
    }
    EachSpec spec = parseSpec(node, ctx.location() + " each");
    PENDING.get().put(ctx.stage(), spec);
  }

  /**
   * Return — and clear — the parser's stashed {@link EachSpec} for the just-built stage, or {@code
   * null} if the stage carried no {@code each:}. Engine-only; called from {@link
   * TitanYamlParser#parseStage} after the scope loop.
   */
  @Nullable
  static EachSpec pendingSpec(@NonNull StageModel stage) {
    return PENDING.get().remove(stage);
  }

  /** Reset the parser-local pending map — called at the top of every {@code parse()}. */
  static void resetPending() {
    PENDING.get().clear();
  }

  // ── expansion ─────────────────────────────────────────────────────────────

  @NonNull
  static List<StageModel> expand(
      @NonNull StageModel prototype, @NonNull EachSpec spec, @NonNull String location) {
    List<StageModel> out = new ArrayList<>(spec.values().size());
    String matrixGroup = prototype.getId();
    for (Object value : spec.values()) {
      out.add(cloneWithBinding(prototype, spec.var(), value, spec, matrixGroup));
    }
    return out;
  }

  // ── spec parsing ──────────────────────────────────────────────────────────

  @NonNull
  static EachSpec parseSpec(@NonNull JsonNode node, @NonNull String context) {
    if (node.isNull()) {
      throw TypedNodeReader.presentButNull(KEY, context);
    }
    if (!node.isObject()) {
      throw new PipelineParseException(
          context
              + ": 'each' must be an object { var, in, maxParallel? }, got "
              + TypedNodeReader.describe(node));
    }
    ParseSupport.rejectUnknownKeys(node, EACH_KEYS, context);

    JsonNode varNode = node.get("var");
    if (varNode == null) {
      throw new PipelineParseException(context + ": 'var' is required");
    }
    if (varNode.isNull()) {
      throw TypedNodeReader.presentButNull("var", context);
    }
    if (!varNode.isTextual() || varNode.asText().isBlank()) {
      throw new PipelineParseException(
          context + ": 'var' must be a non-blank string, got " + TypedNodeReader.describe(varNode));
    }
    String var = varNode.asText();
    if (!VAR_NAME.matcher(var).matches()) {
      throw new PipelineParseException(
          context + ": 'var' must match [A-Za-z_][A-Za-z0-9_]* (got '" + var + "')");
    }
    if (RESERVED_VAR_NAMES.contains(var)) {
      throw new PipelineParseException(
          context
              + ": 'var' is reserved (got '"
              + var
              + "'); reserved names: "
              + RESERVED_VAR_NAMES);
    }

    JsonNode inNode = node.get("in");
    if (inNode == null) {
      throw new PipelineParseException(context + ": 'in' is required");
    }
    if (inNode.isNull()) {
      throw TypedNodeReader.presentButNull("in", context);
    }
    if (!inNode.isArray() || inNode.isEmpty()) {
      throw new PipelineParseException(
          context
              + ": 'in' must be a non-empty list of scalar values, got "
              + TypedNodeReader.describe(inNode));
    }
    List<Object> values = new ArrayList<>(inNode.size());
    int idx = 0;
    for (JsonNode v : inNode) {
      if (!v.isValueNode() || v.isNull()) {
        throw new PipelineParseException(
            context + ": in[" + idx + "] must be a scalar, got " + TypedNodeReader.describe(v));
      }
      values.add(scalarValue(v));
      idx++;
    }

    Integer maxParallel = null;
    JsonNode mp = node.get("maxParallel");
    if (mp != null) {
      if (mp.isNull()) {
        throw TypedNodeReader.presentButNull("maxParallel", context);
      }
      if (!mp.isIntegralNumber()) {
        throw new PipelineParseException(
            context
                + ": 'maxParallel' must be an integer >= 1, got "
                + TypedNodeReader.describe(mp));
      }
      int v = mp.asInt();
      if (v < 1) {
        throw new PipelineParseException(context + ": 'maxParallel' must be >= 1 (got " + v + ")");
      }
      maxParallel = v;
    }

    return new EachSpec(var, values, maxParallel);
  }

  @NonNull
  private static Object scalarValue(@NonNull JsonNode v) {
    if (v.isBoolean()) {
      return v.booleanValue();
    }
    if (v.isIntegralNumber()) {
      return v.asLong();
    }
    if (v.isFloatingPointNumber()) {
      return v.asDouble();
    }
    return v.asText();
  }

  // ── cell cloning + substitution ───────────────────────────────────────────

  @NonNull
  private static StageModel cloneWithBinding(
      @NonNull StageModel prototype,
      @NonNull String var,
      @NonNull Object value,
      @NonNull EachSpec spec,
      @NonNull String matrixGroup) {
    StageModel out = new StageModel();
    out.setName(prototype.getName() + " [" + var + "=" + value + "]");
    out.setId(cellId(prototype.getId(), var, value));
    out.setAgentLabel(prototype.getAgentLabel());
    out.setImage(prototype.getImage());
    out.setWhen(prototype.getWhen());
    out.setEnv(prototype.getEnv() == null ? null : new LinkedHashMap<>(prototype.getEnv()));
    out.setDependsOn(new ArrayList<>(prototype.getDependsOn()));
    out.setParentStageIds(new ArrayList<>(prototype.getParentStageIds()));
    out.setParallel(prototype.isParallel());
    List<StepModel> cloned = new ArrayList<>(prototype.getSteps().size());
    int s = 0;
    for (StepModel proto : prototype.getSteps()) {
      cloned.add(cloneStep(proto, var, value, out.getId(), s++, spec, matrixGroup));
    }
    out.setSteps(cloned);
    return out;
  }

  @NonNull
  private static StepModel cloneStep(
      @NonNull StepModel proto,
      @NonNull String var,
      @NonNull Object value,
      @NonNull String stageId,
      int idx,
      @NonNull EachSpec spec,
      @NonNull String matrixGroup) {
    StepModel out = new StepModel();
    out.setId(stageId + "-s" + idx);
    out.setDescriptorId(proto.getDescriptorId());
    out.setRuntime(substitute(proto.getRuntime(), var, value));
    out.setBody(substitute(proto.getBody(), var, value));
    out.setImage(proto.getImage());
    out.setWhen(proto.getWhen());
    out.setWhenCondition(proto.getWhenCondition());
    out.setRetry(proto.getRetry());
    out.setTimeoutMillis(proto.getTimeoutMillis());
    out.setEnv(proto.getEnv() == null ? null : substituteMap(proto.getEnv(), var, value));
    out.setCredentials(new ArrayList<>(proto.getCredentials()));
    out.setSshAgent(new ArrayList<>(proto.getSshAgent()));
    out.setParentIds(new ArrayList<>(proto.getParentIds()));
    Map<String, Object> args = substituteArgs(proto.getArguments(), var, value);
    Map<String, Object> eachMeta = new LinkedHashMap<>();
    eachMeta.put("var", var);
    eachMeta.put("value", value);
    if (spec.maxParallel() != null) {
      eachMeta.put("maxParallel", spec.maxParallel());
    }
    args.put("__each", eachMeta);
    // #1213: stamp the SAME __matrix metadata MatrixScope writes so each: fan-outs share the
    // orchestrator's fail-fast sibling-cancellation mechanism (matrixGroup + failFast). maxParallel
    // is intentionally NOT mirrored here — each's throttle stays as-is (out of scope for #1213).
    Map<String, Object> matrixMeta = new LinkedHashMap<>();
    matrixMeta.put("matrixGroup", matrixGroup);
    matrixMeta.put("failFast", EACH_FAIL_FAST);
    args.put("__matrix", matrixMeta);
    out.setArguments(args);
    return out;
  }

  @NonNull
  private static Map<String, Object> substituteArgs(
      @NonNull Map<String, Object> src, @NonNull String var, @NonNull Object value) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : src.entrySet()) {
      out.put(e.getKey(), substituteValue(e.getValue(), var, value));
    }
    return out;
  }

  @Nullable
  private static Object substituteValue(
      @Nullable Object v, @NonNull String var, @NonNull Object value) {
    if (v instanceof String s) {
      return substitute(s, var, value);
    }
    if (v instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : m.entrySet()) {
        out.put(String.valueOf(e.getKey()), substituteValue(e.getValue(), var, value));
      }
      return out;
    }
    if (v instanceof List<?> list) {
      List<Object> out = new ArrayList<>(list.size());
      for (Object item : list) {
        out.add(substituteValue(item, var, value));
      }
      return out;
    }
    return v;
  }

  @NonNull
  private static Map<String, String> substituteMap(
      @NonNull Map<String, String> src, @NonNull String var, @NonNull Object value) {
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : src.entrySet()) {
      out.put(e.getKey(), substitute(e.getValue(), var, value));
    }
    return out;
  }

  @Nullable
  private static String substitute(
      @Nullable String input, @NonNull String var, @NonNull Object value) {
    if (input == null) {
      return null;
    }
    Matcher m = EACH_REF.matcher(input);
    if (!m.find()) {
      return input;
    }
    StringBuilder sb = new StringBuilder();
    m.reset();
    while (m.find()) {
      String ref = m.group(1);
      if (!ref.equals(var)) {
        throw new PipelineParseException(
            "each substitution: unknown binding '"
                + ref
                + "' referenced in '"
                + input
                + "' (only '${each."
                + var
                + "}' is declared)");
      }
      m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value)));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  @NonNull
  private static String cellId(
      @NonNull String stageId, @NonNull String var, @NonNull Object value) {
    String id = stageId + "-" + var + "-" + slug(String.valueOf(value));
    return id.length() > 64 ? id.substring(0, 64) : id;
  }

  @NonNull
  private static String slug(@NonNull String raw) {
    String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    s = s.replaceAll("(^-+)|(-+$)", "");
    return s.isEmpty() ? "x" : s;
  }

  // ── parsed spec record ────────────────────────────────────────────────────

  /** Parsed each declaration; immutable carrier. */
  record EachSpec(
      @NonNull String var, @NonNull List<Object> values, @Nullable Integer maxParallel) {}
}

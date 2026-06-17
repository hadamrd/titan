package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.parser.grammar.GrammarKey;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code matrix:} grammar scope (design/54, design/42 §4.7) — declarative Cartesian fan-out of
 * a stage. A stage carrying {@code matrix:} expands at parse time into N cell stages, one per point
 * of the product of the declared axes (optionally filtered by {@code exclude} and augmented by
 * {@code include}). Step arguments referencing {@code ${matrix.<axis>}} are substituted with the
 * cell's value for that axis.
 *
 * <p>{@code matrix:} is <strong>stage-only</strong> ({@link #appliesToStep()} returns {@code
 * false}) — it changes the pipeline shape, not a single step's behaviour. Unlike sugar scopes
 * ({@code retry}, {@code credentials}) the matrix is <em>structural</em>: it returns N stages, not
 * N copies of a list element. The {@link #parseStageAndFlatten} hook validates the matrix node and
 * stashes a {@link MatrixSpec} that the engine retrieves via {@link #pendingSpec(StageModel)} for
 * the actual one-to-many expansion in {@link TitanYamlParser}.
 *
 * <p>The spec is stashed in a parser-local {@link ThreadLocal} map keyed by {@link StageModel}
 * identity — the {@link StageModel} stays a clean domain object with no parser-only field. The
 * pending map is drained by the engine immediately after the scope loop (one stage parse → one
 * {@code pendingSpec} call), so it never outlives a single {@code parse()} invocation.
 *
 * <p>The {@code matrix} {@code $def} is declared in {@link
 * io.adaptiq.titan.flow.parser.grammar.TitanGrammar#matrixDef()}; this scope only references it via
 * {@code $ref}.
 */
final class MatrixScope implements StepScope {

  static final String KEY = "matrix";

  /** Allowed keys inside the {@code matrix:} object. */
  private static final Set<String> MATRIX_KEYS =
      Set.of("axes", "exclude", "include", "maxParallel", "fail_fast");

  /**
   * Hard cap on the number of cells a single {@code matrix:} block may expand into. This is a DoS
   * guard for an adversarial pipeline that declares (say) ten 5-value axes — 5<sup>10</sup> is ~10M
   * stages and would OOM the parser. The default is generous enough for realistic smoke / e2e
   * matrices (e.g. 5 OS × 5 jdk × 4 arch = 100) but bounds the blast radius of a typo or a
   * malicious pipeline. See #1092.
   */
  static final int DEFAULT_MAX_CELLS = 100;

  /** Matches {@code ${matrix.<axis>}} occurrences in a string argument value. */
  private static final Pattern MATRIX_REF = Pattern.compile("\\$\\{matrix\\.([A-Za-z0-9_]+)\\}");

  /** Per-parse stash of matrix specs awaiting engine-side expansion. */
  private static final ThreadLocal<Map<StageModel, MatrixSpec>> PENDING =
      ThreadLocal.withInitial(LinkedHashMap::new);

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public GrammarKey schema() {
    return GrammarKey.optional(
        KEY,
        "Stage-level matrix (design/54). Cartesian product of named axes, optionally "
            + "filtered by 'exclude' and augmented by 'include'. Fans the stage out into "
            + "one stage per cell at parse time; step arguments may reference the cell "
            + "with ${matrix.<axis>}.",
        GrammarKey.ref("matrix"));
  }

  @Override
  public boolean appliesToStep() {
    return false; // matrix is stage-structural; it has no step-level form.
  }

  @Override
  public void parseStep(JsonNode stepNode, StepModel step, ParseContext ctx) {
    // matrix is stage-only — nothing to do on a step.
  }

  @Override
  public void parseStageAndFlatten(JsonNode stageNode, List<StepModel> steps, ParseContext ctx) {
    JsonNode node = stageNode.get(KEY);
    if (node == null) {
      return;
    }
    MatrixSpec spec = parseSpec(node, ctx.location() + " matrix");
    PENDING.get().put(ctx.stage(), spec);
  }

  /**
   * Return — and clear — the parser's stashed {@link MatrixSpec} for the just-built stage, or
   * {@code null} if the stage carried no {@code matrix:}. Engine-only; called from {@link
   * TitanYamlParser#parseStage} after the scope loop.
   */
  @Nullable
  static MatrixSpec pendingSpec(@NonNull StageModel stage) {
    return PENDING.get().remove(stage);
  }

  /** Reset the parser-local pending map — called at the top of every {@code parse()}. */
  static void resetPending() {
    PENDING.get().clear();
  }

  // ── expansion ─────────────────────────────────────────────────────────────

  /**
   * Expand a prototype {@link StageModel} into one stage per matrix cell, applying {@code
   * ${matrix.<axis>}} substitution to every string step argument. The prototype is returned as the
   * first element after substitution-cloning when the matrix has at least one cell; the matrix is
   * required to have at least one cell (an empty matrix is a parse error in {@link
   * #parseSpec}/{@link #cells}).
   */
  @NonNull
  static List<StageModel> expand(
      @NonNull StageModel prototype, @NonNull MatrixSpec spec, @NonNull String location) {
    List<Map<String, Object>> cells = cells(spec, location);
    List<StageModel> out = new ArrayList<>(cells.size());
    String matrixGroup = prototype.getId();
    for (Map<String, Object> cell : cells) {
      out.add(cloneWithCell(prototype, cell, spec, matrixGroup));
    }
    return out;
  }

  // ── spec parsing ──────────────────────────────────────────────────────────

  @NonNull
  static MatrixSpec parseSpec(@NonNull JsonNode node, @NonNull String context) {
    if (node.isNull()) {
      throw TypedNodeReader.presentButNull(KEY, context);
    }
    if (!node.isObject()) {
      throw new PipelineParseException(
          context
              + ": 'matrix' must be an object { axes, exclude?, include?, maxParallel? }, got "
              + TypedNodeReader.describe(node));
    }
    ParseSupport.rejectUnknownKeys(node, MATRIX_KEYS, context);

    JsonNode axesNode = node.get("axes");
    if (axesNode == null) {
      throw new PipelineParseException(context + ": 'axes' is required");
    }
    if (axesNode.isNull()) {
      throw TypedNodeReader.presentButNull("axes", context);
    }
    if (!axesNode.isObject() || axesNode.isEmpty()) {
      throw new PipelineParseException(
          context + ": 'axes' must be a non-empty object of <name>: [values…]");
    }

    Map<String, List<Object>> axes = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> it = axesNode.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> e = it.next();
      String axisName = e.getKey();
      if (axisName.isBlank()) {
        throw new PipelineParseException(context + ": an axis name must be non-blank");
      }
      JsonNode values = e.getValue();
      if (values == null || values.isNull()) {
        throw TypedNodeReader.presentButNull("axes." + axisName, context);
      }
      if (!values.isArray() || values.isEmpty()) {
        throw new PipelineParseException(
            context
                + ": axis '"
                + axisName
                + "' must be a non-empty list of scalar values, got "
                + TypedNodeReader.describe(values));
      }
      List<Object> list = new ArrayList<>();
      int idx = 0;
      for (JsonNode v : values) {
        if (!v.isValueNode() || v.isNull()) {
          throw new PipelineParseException(
              context
                  + ": axes."
                  + axisName
                  + "["
                  + idx
                  + "] must be a scalar, got "
                  + TypedNodeReader.describe(v));
        }
        list.add(scalarValue(v));
        idx++;
      }
      axes.put(axisName, list);
    }

    List<Map<String, Object>> excludes = parseCellList(node.get("exclude"), "exclude", context);
    List<Map<String, Object>> includes = parseCellList(node.get("include"), "include", context);

    // exclude entries: must reference only declared axes (partial matches allowed).
    int eIdx = 0;
    for (Map<String, Object> ex : excludes) {
      for (String k : ex.keySet()) {
        if (!axes.containsKey(k)) {
          throw new PipelineParseException(
              context + ": exclude[" + eIdx + "] references unknown axis '" + k + "'");
        }
      }
      eIdx++;
    }
    // include entries: must name every declared axis (a full off-grid cell).
    int iIdx = 0;
    for (Map<String, Object> in : includes) {
      for (String k : in.keySet()) {
        if (!axes.containsKey(k)) {
          throw new PipelineParseException(
              context + ": include[" + iIdx + "] references unknown axis '" + k + "'");
        }
      }
      for (String k : axes.keySet()) {
        if (!in.containsKey(k)) {
          throw new PipelineParseException(
              context
                  + ": include["
                  + iIdx
                  + "] is missing axis '"
                  + k
                  + "' — an include must name every declared axis");
        }
      }
      iIdx++;
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

    boolean failFast = true;
    JsonNode ff = node.get("fail_fast");
    if (ff != null) {
      if (ff.isNull()) {
        throw TypedNodeReader.presentButNull("fail_fast", context);
      }
      if (!ff.isBoolean()) {
        throw new PipelineParseException(
            context + ": 'fail_fast' must be a boolean, got " + TypedNodeReader.describe(ff));
      }
      failFast = ff.booleanValue();
    }

    return new MatrixSpec(axes, excludes, includes, maxParallel, failFast);
  }

  @NonNull
  private static List<Map<String, Object>> parseCellList(
      @Nullable JsonNode node, @NonNull String field, @NonNull String context) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (node == null) {
      return out;
    }
    if (node.isNull()) {
      throw TypedNodeReader.presentButNull(field, context);
    }
    if (!node.isArray()) {
      throw new PipelineParseException(
          context
              + ": '"
              + field
              + "' must be an array of cell-pattern maps, got "
              + TypedNodeReader.describe(node));
    }
    int idx = 0;
    for (JsonNode entry : node) {
      if (!entry.isObject() || entry.isEmpty()) {
        throw new PipelineParseException(
            context + ": " + field + "[" + idx + "] must be a non-empty object");
      }
      Map<String, Object> cell = new LinkedHashMap<>();
      Iterator<Map.Entry<String, JsonNode>> fields = entry.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> e = fields.next();
        JsonNode v = e.getValue();
        if (v == null || !v.isValueNode() || v.isNull()) {
          throw new PipelineParseException(
              context
                  + ": "
                  + field
                  + "["
                  + idx
                  + "]."
                  + e.getKey()
                  + " must be a scalar, got "
                  + TypedNodeReader.describe(v));
        }
        cell.put(e.getKey(), scalarValue(v));
      }
      out.add(cell);
      idx++;
    }
    return out;
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

  // ── product enumeration ───────────────────────────────────────────────────

  @NonNull
  static List<Map<String, Object>> cells(@NonNull MatrixSpec spec, @NonNull String context) {
    // DoS guard (#1092): compute the upper bound on the Cartesian product BEFORE building it,
    // so a 10-axis adversarial pipeline doesn't allocate millions of maps before we reject it.
    long projected = 1L;
    for (List<Object> values : spec.axes().values()) {
      projected = Math.multiplyExact(projected, (long) values.size());
      if (projected > DEFAULT_MAX_CELLS) {
        throw new PipelineParseException(
            context
                + ": matrix would expand to "
                + projected
                + "+ cells, which exceeds the default cap of "
                + DEFAULT_MAX_CELLS
                + " (DoS guard). Reduce axis cardinality or split into separate stages.");
      }
    }
    List<Map<String, Object>> grid = new ArrayList<>();
    grid.add(new LinkedHashMap<>());
    for (Map.Entry<String, List<Object>> axis : spec.axes().entrySet()) {
      List<Map<String, Object>> next = new ArrayList<>(grid.size() * axis.getValue().size());
      for (Map<String, Object> partial : grid) {
        for (Object value : axis.getValue()) {
          Map<String, Object> extended = new LinkedHashMap<>(partial);
          extended.put(axis.getKey(), value);
          next.add(extended);
        }
      }
      grid = next;
    }
    // apply exclude
    List<Map<String, Object>> kept = new ArrayList<>(grid.size());
    for (Map<String, Object> cell : grid) {
      if (!isExcluded(cell, spec.excludes())) {
        kept.add(cell);
      }
    }
    // apply include — duplicates of a surviving cell are a parse error.
    for (Map<String, Object> inc : spec.includes()) {
      for (Map<String, Object> existing : kept) {
        if (existing.equals(inc)) {
          throw new PipelineParseException(
              context + ": include cell " + inc + " duplicates an existing cell");
        }
      }
      kept.add(new LinkedHashMap<>(inc));
    }
    if (kept.isEmpty()) {
      throw new PipelineParseException(
          context + ": matrix has zero cells (exclude removed every base cell, no include)");
    }
    // include can re-push total cell count past the cap.
    if (kept.size() > DEFAULT_MAX_CELLS) {
      throw new PipelineParseException(
          context
              + ": matrix would expand to "
              + kept.size()
              + " cells (after include), which exceeds the default cap of "
              + DEFAULT_MAX_CELLS
              + " (DoS guard).");
    }
    return kept;
  }

  private static boolean isExcluded(
      @NonNull Map<String, Object> cell, @NonNull List<Map<String, Object>> excludes) {
    for (Map<String, Object> ex : excludes) {
      boolean allMatch = true;
      for (Map.Entry<String, Object> e : ex.entrySet()) {
        Object cellValue = cell.get(e.getKey());
        if (cellValue == null || !cellValue.equals(e.getValue())) {
          allMatch = false;
          break;
        }
      }
      if (allMatch) {
        return true;
      }
    }
    return false;
  }

  // ── cell cloning + substitution ───────────────────────────────────────────

  @NonNull
  private static StageModel cloneWithCell(
      @NonNull StageModel prototype,
      @NonNull Map<String, Object> cell,
      @NonNull MatrixSpec spec,
      @NonNull String matrixGroup) {
    StageModel out = new StageModel();
    out.setName(prototype.getName() + " " + cellLabel(cell));
    out.setId(cellId(prototype.getId(), cell));
    out.setAgentLabel(prototype.getAgentLabel());
    out.setImage(prototype.getImage());
    out.setWhen(prototype.getWhen());
    // Per #1092: every cell exposes its axis bindings as MATRIX_<AXIS> env vars so the step's
    // shell can branch on them without templating. Pre-existing stage env values win on
    // collision — we never overwrite an explicit user env entry.
    Map<String, String> stageEnv =
        prototype.getEnv() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(prototype.getEnv());
    for (Map.Entry<String, Object> e : cell.entrySet()) {
      stageEnv.putIfAbsent(matrixEnvName(e.getKey()), String.valueOf(e.getValue()));
    }
    out.setEnv(stageEnv);
    out.setDependsOn(new ArrayList<>(prototype.getDependsOn()));
    out.setParentStageIds(new ArrayList<>(prototype.getParentStageIds()));
    out.setParallel(prototype.isParallel());
    List<StepModel> cloned = new ArrayList<>(prototype.getSteps().size());
    int s = 0;
    for (StepModel proto : prototype.getSteps()) {
      cloned.add(cloneStep(proto, cell, out.getId(), s++, spec, matrixGroup));
    }
    out.setSteps(cloned);
    return out;
  }

  @NonNull
  private static StepModel cloneStep(
      @NonNull StepModel proto,
      @NonNull Map<String, Object> cell,
      @NonNull String stageId,
      int idx,
      @NonNull MatrixSpec spec,
      @NonNull String matrixGroup) {
    StepModel out = new StepModel();
    out.setId(stageId + "-s" + idx);
    out.setDescriptorId(proto.getDescriptorId());
    out.setRuntime(substitute(proto.getRuntime(), cell));
    out.setBody(substitute(proto.getBody(), cell));
    out.setImage(proto.getImage());
    out.setWhen(proto.getWhen());
    out.setWhenCondition(proto.getWhenCondition());
    out.setRetry(proto.getRetry());
    out.setTimeoutMillis(proto.getTimeoutMillis());
    // step env = prototype env (post-substitution) + MATRIX_<AXIS> bindings; existing keys win.
    Map<String, String> stepEnv =
        proto.getEnv() == null ? new LinkedHashMap<>() : substituteMap(proto.getEnv(), cell);
    for (Map.Entry<String, Object> e : cell.entrySet()) {
      stepEnv.putIfAbsent(matrixEnvName(e.getKey()), String.valueOf(e.getValue()));
    }
    out.setEnv(stepEnv);
    out.setCredentials(new ArrayList<>(proto.getCredentials()));
    out.setSshAgent(new ArrayList<>(proto.getSshAgent()));
    out.setParentIds(new ArrayList<>(proto.getParentIds()));
    // Step arguments: deep-substitute string leaves; embed matrix metadata so the engine can
    // honour `maxParallel` at dispatch (read by the worker / dispatcher, opaque to the parser).
    Map<String, Object> args = substituteArgs(proto.getArguments(), cell);
    Map<String, Object> matrixMeta = new LinkedHashMap<>();
    matrixMeta.put("cell", new LinkedHashMap<>(cell));
    matrixMeta.put("matrixGroup", matrixGroup);
    matrixMeta.put("failFast", spec.failFast());
    if (spec.maxParallel() != null) {
      matrixMeta.put("maxParallel", spec.maxParallel());
    }
    args.put("__matrix", matrixMeta);
    out.setArguments(args);
    return out;
  }

  @SuppressWarnings("unchecked")
  @NonNull
  private static Map<String, Object> substituteArgs(
      @NonNull Map<String, Object> src, @NonNull Map<String, Object> cell) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : src.entrySet()) {
      out.put(e.getKey(), substituteValue(e.getValue(), cell));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  @Nullable
  private static Object substituteValue(@Nullable Object v, @NonNull Map<String, Object> cell) {
    if (v instanceof String s) {
      return substitute(s, cell);
    }
    if (v instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : m.entrySet()) {
        out.put(String.valueOf(e.getKey()), substituteValue(e.getValue(), cell));
      }
      return out;
    }
    if (v instanceof List<?> list) {
      List<Object> out = new ArrayList<>(list.size());
      for (Object item : list) {
        out.add(substituteValue(item, cell));
      }
      return out;
    }
    return v;
  }

  @NonNull
  private static Map<String, String> substituteMap(
      @NonNull Map<String, String> src, @NonNull Map<String, Object> cell) {
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : src.entrySet()) {
      out.put(e.getKey(), substitute(e.getValue(), cell));
    }
    return out;
  }

  @Nullable
  private static String substitute(@Nullable String input, @NonNull Map<String, Object> cell) {
    if (input == null) {
      return null;
    }
    Matcher m = MATRIX_REF.matcher(input);
    if (!m.find()) {
      return input;
    }
    StringBuilder sb = new StringBuilder();
    m.reset();
    while (m.find()) {
      String axis = m.group(1);
      Object value = cell.get(axis);
      if (value == null) {
        throw new PipelineParseException(
            "matrix substitution: unknown axis '"
                + axis
                + "' referenced in '"
                + input
                + "' (declared axes: "
                + cell.keySet()
                + ")");
      }
      m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value)));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  @NonNull
  private static String cellId(@NonNull String stageId, @NonNull Map<String, Object> cell) {
    StringBuilder sb = new StringBuilder(stageId);
    for (Map.Entry<String, Object> e : cell.entrySet()) {
      sb.append('-').append(e.getKey()).append('-').append(slug(String.valueOf(e.getValue())));
    }
    String id = sb.toString();
    return id.length() > 64 ? id.substring(0, 64) : id;
  }

  @NonNull
  private static String cellLabel(@NonNull Map<String, Object> cell) {
    StringBuilder sb = new StringBuilder("[");
    boolean first = true;
    for (Map.Entry<String, Object> e : cell.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append(e.getKey()).append('=').append(e.getValue());
    }
    return sb.append(']').toString();
  }

  /**
   * Compute the env var name a step sees for a given matrix axis: {@code MATRIX_<AXIS_UPPER>}. E.g.
   * axis {@code os} → {@code MATRIX_OS}; axis {@code nodeVersion} → {@code MATRIX_NODEVERSION}.
   * Non-alphanumerics collapse to underscores so e.g. {@code jdk.minor} → {@code MATRIX_JDK_MINOR}.
   */
  @NonNull
  static String matrixEnvName(@NonNull String axisName) {
    String upper = axisName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    upper = upper.replaceAll("(^_+)|(_+$)", "");
    return "MATRIX_" + (upper.isEmpty() ? "X" : upper);
  }

  @NonNull
  private static String slug(@NonNull String raw) {
    String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    s = s.replaceAll("(^-+)|(-+$)", "");
    return s.isEmpty() ? "x" : s;
  }

  // ── parsed spec record ────────────────────────────────────────────────────

  /**
   * Parsed matrix declaration; immutable carrier.
   *
   * @param failFast when {@code true} (the default), the first cell to reach terminal {@code
   *     FAILED} cancels every still-active sibling cell — each transitions to {@code ABORTED} (the
   *     failed cell stays {@code FAILED}, preserving the cause) and its descendant steps are
   *     SKIPPED. When {@code false}, every remaining cell runs to completion and the group reports
   *     overall failure only after all cells finish. Enforced by the orchestrator (#1213): {@code
   *     MatrixCoordinator.readMatrixMeta} reads this off the {@code __matrix.failFast} step
   *     metadata and {@code failFastAbortSet} selects the siblings {@code
   *     TitanOrchestrator.finishIfDone} aborts.
   */
  record MatrixSpec(
      @NonNull Map<String, List<Object>> axes,
      @NonNull List<Map<String, Object>> excludes,
      @NonNull List<Map<String, Object>> includes,
      @Nullable Integer maxParallel,
      boolean failFast) {}
}

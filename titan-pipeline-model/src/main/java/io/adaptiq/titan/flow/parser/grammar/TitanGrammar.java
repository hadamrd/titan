package io.adaptiq.titan.flow.parser.grammar;

import static io.adaptiq.titan.flow.parser.grammar.GrammarKey.any;
import static io.adaptiq.titan.flow.parser.grammar.GrammarKey.arrayOfRef;
import static io.adaptiq.titan.flow.parser.grammar.GrammarKey.boolWithDefault;
import static io.adaptiq.titan.flow.parser.grammar.GrammarKey.optional;
import static io.adaptiq.titan.flow.parser.grammar.GrammarKey.ref;
import static io.adaptiq.titan.flow.parser.grammar.GrammarKey.required;
import static io.adaptiq.titan.flow.parser.grammar.GrammarKey.string;
import static io.adaptiq.titan.flow.parser.grammar.GrammarKey.stringEnum;
import static io.adaptiq.titan.flow.parser.grammar.GrammarKey.stringWithExamples;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The single in-code declaration of the Titan YAML grammar (design/47 §2.2).
 *
 * <p>Before design/47 the grammar was declared twice: as hand literals in {@code TitanYamlParser}
 * and again as a hand-authored {@code titan-pipeline.schema.json}. This class is the one
 * declaration both project from: {@code TitanYamlParser} takes its key sets via {@link
 * #keyNames(List)}, and {@code TitanSchemaGenerator} emits the JSON Schema by walking these lists.
 *
 * <p>It declares every <strong>non-scope</strong> grammar context — {@link #ROOT}, {@link
 * #STAGE_SKELETON}, {@link #GATE}, {@link #PRECONDITION}, {@link #PARAMETER}, {@link #SCRIPT} — as
 * an ordered {@code List<GrammarKey>}, and the reusable {@code $defs} shapes the schema references.
 * The grammar <em>scopes</em> (design/42 §4.7) declare their own keys via {@link
 * io.adaptiq.titan.flow.parser.StepScope#schema()} — a scope is a single self-contained unit of
 * grammar — so the {@code stage} and {@code step} {@code $defs} are assembled by the generator from
 * {@code STAGE_SKELETON}/the {@code step} skeleton plus the scope keys.
 *
 * <p>Every description, example, default, pattern and enum here is ported <em>verbatim</em> from
 * the hand-authored schema — design/47 §2.2 forbids any quality regression.
 *
 * <p>The grammar is <strong>closed</strong> (design/42 §4.7): these lists are fixed, not {@code
 * ServiceLoader}-pluggable. A grammar keyword is global, so the grammar stays portable.
 */
public final class TitanGrammar {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private TitanGrammar() {}

  // ── schema document metadata (design/47 §4.2) ──

  /** The {@code $schema} dialect — draft 2020-12. */
  public static final String SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema";

  /** The schema {@code $id}. */
  public static final String SCHEMA_ID =
      "https://github.com/hadamrd/titan/blob/trunk/titan-pipeline-model/src/main/resources/io/adaptiq/titan/schemas/titan-pipeline.schema.json";

  /** The schema {@code title}. */
  public static final String SCHEMA_TITLE = "Titan Pipeline";

  /** The schema-document {@code description}. */
  public static final String SCHEMA_DESCRIPTION =
      "Titan YAML pipeline definition (design/29 §3). The pipeline body is accepted in "
          + "either shape: at the document root (the canonical form) or nested under a "
          + "top-level 'titan:' key (the legacy wrapper, kept for back-compat). This "
          + "schema is the editor/tooling contract; TitanYamlParser is the runtime "
          + "enforcer of the same contract.";

  // ── ROOT — the pipeline body (the `titan` $def's properties) ──

  /**
   * The root grammar context — the keys of the pipeline body (the {@code titan} {@code $def}). The
   * body is accepted at the document root or nested under {@code titan:} (design/29 §3); the key
   * set is identical either way.
   */
  public static final List<GrammarKey> ROOT =
      List.of(
          optional(
              "agent",
              "Default agent label for the whole pipeline. Optional. A stage with no "
                  + "'agent' of its own inherits this; if neither is set, the work runs "
                  + "on any available agent.",
              string()),
          optional(
              "failurePolicy",
              "What the engine does when a node fails. 'blockOnFailure' (default) skips every "
                  + "non-terminal node on the first failure; 'continueOnFailure' lets the DAG "
                  + "run past a failure (only the dependent subgraph is cut).",
              stringEnum("blockOnFailure", "continueOnFailure")),
          optional(
              "libraries",
              "Shared libraries to load for this pipeline. A map of "
                  + "<alias>: <coordinate-or-{url,credential}> (design/53). Each alias "
                  + "drives the `<alias>.<method>` dotted-step dispatch.",
              ref("librariesMap")),
          optional(
              "parameters", "Build parameters this pipeline declares.", arrayOfRef("parameter")),
          optional(
              "triggers",
              "Schedules that start a build automatically (design/50). Each entry is a "
                  + "trigger — either a 'cron:' schedule or a 'github:' webhook "
                  + "(issue #397). Exactly one discriminator key per entry.",
              arrayOfRef("trigger")),
          optional(
              "credentials",
              "Stored credentials bound into every step of every stage (design/42). "
                  + "Flattened onto each step at parse time; stage- and step-level "
                  + "'credentials' are appended after these, so precedence is "
                  + "step > stage > pipeline.",
              arrayOfRef("credentialBinding")),
          optional(
              "env",
              "Environment variables injected into every step of every stage (GH #239). "
                  + "Stage- and step-level 'env' override these "
                  + "(step > stage > pipeline precedence). Values must be strings.",
              envMapSchema()),
          optional(
              "timeout",
              "Pipeline-level execution deadline (issue #244). The outermost safety net: "
                  + "if no step- or stage-level 'timeout:' fires, the controller reaps the "
                  + "build at this deadline and marks it FAILED. Grammar: '30s', '5m', "
                  + "'2h', '1d', or a bare number of seconds.",
              string()),
          optional(
              "notify",
              "Lifecycle hooks fired by the orchestrator at the build's terminal state "
                  + "(#245), regardless of which step ran. Each entry is a webhook (today) "
                  + "or slack (future) sink; auth tokens MUST travel via 'credentialsId', "
                  + "never inline in 'url'.",
              arrayOfRef("notifyHook")),
          optional(
              "concurrency",
              "Per-job concurrency limit (#1101). Caps the number of RUNNING builds of this "
                  + "job; new builds either queue, cancel the oldest RUNNING build, or cancel "
                  + "other QUEUED builds, per 'on_overflow'. Absent: unlimited (legacy default).",
              ref("concurrency")),
          optional(
              "priority",
              "Per-job queue priority (#1100). Discriminated-union string — one of 'high' (10), "
                  + "'normal' (0, default), or 'low' (-10). Builds of higher-priority jobs are "
                  + "claimed from task_queue before lower-priority ones; same-priority builds "
                  + "preserve FIFO. Bare integers are rejected by design — use the named tier.",
              ref("priority")),
          optional(
              "buildRetention",
              "Per-job build-history retention override (#640). Caps this job's "
                  + "titan.builds rows at the last 'keepLast' entries; older builds (and "
                  + "their flow_nodes / artifact / test_result / task_queue / logs / "
                  + "task_archive rows) are dropped by the daily prune. When absent, the "
                  + "server-wide TITAN_JOB_BUILD_RETENTION default applies. 'keepLast: 0' "
                  + "is the per-job opt-out — keeps full history regardless of the global "
                  + "default.",
              ref("buildRetention")),
          optional(
              "include",
              "Pipeline fragments to inline before parsing (issue #1120). Each entry is either "
                  + "a repo-relative path string (sibling YAML fragment) or an object "
                  + "{ repo, ref, path[, credential] } for a cross-repo fragment. Includes are "
                  + "resolved depth-first with cycle detection; list keys (stages, parameters, "
                  + "triggers, notify, credentials) are concatenated with the main file's, map "
                  + "keys (libraries, env) are shallow-merged with the main file winning, and "
                  + "all other keys are overridden by the main file.",
              arrayOfRef("includeEntry")),
          required(
              "stages",
              "The pipeline DAG: stage, gate and precondition nodes. Order of this list "
                  + "does not imply order of execution — 'dependsOn' does. Sibling "
                  + "nodes with the same 'dependsOn' run in parallel.",
              minItemsArrayOfRef("node", 1)));

  // ── STAGE_SKELETON — the stage keys that are NOT scope keys ──

  /**
   * The stage skeleton: the stage keys that are <em>not</em> {@code StepScope} keys — the {@code
   * stage} discriminator, {@code agent}, and {@code steps}. The scope keys ({@code when}, {@code
   * dependsOn}, {@code image}, {@code credentials}, {@code sshAgent}, {@code retry}) are
   * contributed by the scopes' {@code schema()} (design/47 §3) and spliced in by the generator in
   * scope-registration order, after this skeleton.
   */
  public static final List<GrammarKey> STAGE_SKELETON =
      List.of(
          required("stage", "Stage name (also the basis of its node id).", string()),
          optional(
              "agent",
              "Agent label for this stage's steps. Optional — omit it to inherit "
                  + "'titan.agent'.",
              string()),
          optional("steps", "The steps this stage runs, in order.", arrayOfRef("step")));

  // ── STEP_SKELETON — the step keys that are NOT scope keys ──

  /**
   * The step skeleton: the step keys that are <em>not</em> {@code StepScope} keys and not the
   * single open descriptor key. Today this is empty — every non-descriptor step key ({@code image},
   * {@code credentials}, {@code sshAgent}, {@code retry}) is a scope key. Kept as an explicit
   * (empty) list so a future non-scope step key has a declared home.
   */
  public static final List<GrammarKey> STEP_SKELETON = List.of();

  // ── GATE ──

  /** The gate node grammar context (design/29 §3). */
  public static final List<GrammarKey> GATE =
      List.of(
          required("gate", "Gate name.", string()),
          optional(
              "requiresApproval",
              "Whether the gate needs a human approval to advance.",
              boolWithDefault(true)),
          optional("approvers", "Users or groups allowed to approve.", ref("stringOrList")),
          optional("dependsOn", "Node ids this gate waits for.", ref("stringOrList")));

  // ── PRECONDITION ──

  /** The precondition node grammar context (design/29 §3). */
  public static final List<GrammarKey> PRECONDITION =
      List.of(
          required("precondition", "Precondition name.", string()),
          required(
              "expression",
              "Expression that must evaluate true for downstream nodes to run.",
              string()),
          optional("dependsOn", "Node ids this precondition waits for.", ref("stringOrList")));

  // ── PARAMETER ──

  /** The build-parameter grammar context (design/29 §3). */
  public static final List<GrammarKey> PARAMETER =
      List.of(
          required("name", "Parameter name.", string()),
          optional("type", "Parameter type.", stringWithExamples("string", "boolean", "choice")),
          optional("default", "Default value when the build does not supply one.", any()),
          optional("description", "", string()),
          optional("required", "Whether a value must be supplied.", GrammarKey.bool()),
          optional("choices", "Allowed values for a choice parameter.", ref("stringOrList")));

  // ── SCRIPT — the `script:` step body ──

  /** The {@code script:} step-body grammar context (design/29 §3). */
  public static final List<GrammarKey> SCRIPT =
      List.of(
          required(
              "runtime",
              "The runtime that executes the body.",
              stringWithExamples("groovy", "bash", "python")),
          required("body", "The script source.", string()));

  // ── reusable $defs shapes (design/47 §2.2) ──

  /**
   * The {@code stringOrList} {@code $def}: a single string or a list of strings. A standalone
   * schema node, not a {@code GrammarKey} — it is a value shape, not a keyed context.
   */
  /**
   * The {@code librariesMap} {@code $def}: the {@code libraries:} value shape (design/53). An
   * object mapping {@code <alias>} to a coordinate, either as a bare string {@code <git-url>@<ref>}
   * (public repo) or an object {@code {url, credential}} (the worker resolves {@code credential}
   * via {@code SecretProvider} — design/40).
   */
  @NonNull
  public static ObjectNode librariesMapDef() {
    ObjectNode def = NODES.objectNode();
    def.put("description", "Map of <alias>: <coordinate-or-{url,credential}>.");
    def.put("type", "object");
    ObjectNode addl = NODES.objectNode();
    ArrayNode oneOf = addl.putArray("oneOf");
    oneOf.add(NODES.objectNode().put("type", "string"));
    ObjectNode objVal = NODES.objectNode().put("type", "object");
    objVal.put("additionalProperties", false);
    ObjectNode props = objVal.putObject("properties");
    props.putObject("url").put("type", "string");
    props.putObject("credential").put("type", "string");
    objVal.putArray("required").add("url");
    oneOf.add(objVal);
    def.set("additionalProperties", addl);
    return def;
  }

  @NonNull
  public static ObjectNode stringOrListDef() {
    ObjectNode def = NODES.objectNode();
    def.put("description", "A single string, or a list of strings.");
    ArrayNode oneOf = def.putArray("oneOf");
    oneOf.add(NODES.objectNode().put("type", "string"));
    ObjectNode arr = NODES.objectNode().put("type", "array");
    arr.set("items", NODES.objectNode().put("type", "string"));
    oneOf.add(arr);
    return def;
  }

  /**
   * The {@code retryPolicy} {@code $def}: the {@code retry:} scope's value shape — the complex
   * {@code oneOf} of an integer shorthand and the object form (design/44 §2). The {@code retry}
   * {@link io.adaptiq.titan.flow.parser.StepScope#schema()} {@code $ref}s this; it is declared
   * here, with the other {@code $defs}, because the generator emits all {@code $defs} together.
   */
  @NonNull
  public static ObjectNode retryPolicyDef() {
    ObjectNode def = NODES.objectNode();
    def.put(
        "description",
        "A declarative per-step retry policy (design/44 §2) — a Temporal-shaped policy "
            + "with bounded attempts, exponential backoff with a cap, and an optional "
            + "retryable-exit-code allowlist. Two forms: an integer shorthand "
            + "'retry: N' (N attempts, default backoff), or the object form.");
    ArrayNode oneOf = def.putArray("oneOf");

    ObjectNode shorthand = NODES.objectNode();
    shorthand.put("type", "integer");
    shorthand.put("minimum", 1);
    shorthand.put(
        "description",
        "Shorthand: total attempts including the first (1 = no retry), with the default "
            + "backoff (initial 10s, multiplier 2.0, max 5m).");
    oneOf.add(shorthand);

    ObjectNode objForm = NODES.objectNode();
    objForm.put("type", "object");
    objForm.put("additionalProperties", false);
    ObjectNode props = objForm.putObject("properties");

    ObjectNode maxAttempts = props.putObject("maxAttempts");
    maxAttempts.put("type", "integer");
    maxAttempts.put("minimum", 1);
    maxAttempts.put("default", 1);
    maxAttempts.put("description", "Total attempts including the first; 1 = no retry.");

    ObjectNode backoff = props.putObject("backoff");
    backoff.put("type", "object");
    backoff.put("additionalProperties", false);
    backoff.put(
        "description",
        "The exponential-backoff schedule. The delay before retry k (1-indexed) is "
            + "min(initial * multiplier^(k-1), max).");
    ObjectNode backoffProps = backoff.putObject("properties");
    ObjectNode initial = backoffProps.putObject("initial");
    initial.put("type", "string");
    initial.put("pattern", "^[0-9]+[smh]$");
    initial.put("default", "10s");
    initial.put("description", "Delay before the first retry — a duration suffixed s/m/h.");
    ObjectNode multiplier = backoffProps.putObject("multiplier");
    multiplier.put("type", "number");
    multiplier.put("minimum", 1.0);
    multiplier.put("default", 2.0);
    multiplier.put("description", "Exponential coefficient applied per attempt.");
    ObjectNode max = backoffProps.putObject("max");
    max.put("type", "string");
    max.put("pattern", "^[0-9]+[smh]$");
    max.put("default", "5m");
    max.put(
        "description",
        "Cap on the computed delay — a duration suffixed s/m/h; must be >= initial.");

    ObjectNode retryableExitCodes = props.putObject("retryableExitCodes");
    retryableExitCodes.put("type", "array");
    retryableExitCodes.set("items", NODES.objectNode().put("type", "integer"));
    retryableExitCodes.put(
        "description",
        "If set, retry only when the step's exit code is in this list; any other "
            + "non-zero exit fails fast. If absent, any non-zero exit is retryable.");

    oneOf.add(objForm);
    return def;
  }

  /**
   * The {@code matrix} {@code $def} (design/54): the value shape of the {@code matrix:} stage
   * scope. The {@code MatrixScope} {@code $ref}s this so the scope class stays a focused
   * parse/expand unit; the value shape — {@code axes}, {@code exclude}, {@code include}, {@code
   * maxParallel} — is declared here with the other reusable shapes.
   */
  @NonNull
  public static ObjectNode matrixDef() {
    ObjectNode def = NODES.objectNode();
    def.put("type", "object");
    def.put(
        "description",
        "Stage-level matrix (design/54). A Cartesian product of named axes, optionally "
            + "filtered by 'exclude' and augmented by 'include'. Fans the stage out into "
            + "one stage per cell at parse time; step arguments may reference the cell "
            + "with ${matrix.<axis>}.");
    def.putArray("required").add("axes");
    def.put("additionalProperties", false);
    ObjectNode props = def.putObject("properties");

    // axes: object mapping <axis name>: [scalar values]
    ObjectNode axes = props.putObject("axes");
    axes.put("type", "object");
    axes.put(
        "description",
        "Named axes of the product. Order is preserved. Each axis is a non-empty list of "
            + "scalar values (strings, numbers, booleans).");
    axes.put("minProperties", 1);
    ObjectNode axesValue = NODES.objectNode();
    axesValue.put("type", "array");
    axesValue.put("minItems", 1);
    ObjectNode axesItem = NODES.objectNode();
    ArrayNode axesItemOneOf = axesItem.putArray("oneOf");
    axesItemOneOf.add(NODES.objectNode().put("type", "string"));
    axesItemOneOf.add(NODES.objectNode().put("type", "number"));
    axesItemOneOf.add(NODES.objectNode().put("type", "boolean"));
    axesValue.set("items", axesItem);
    axes.set("additionalProperties", axesValue);

    // cell pattern: object whose values are scalars
    ObjectNode cellPattern = NODES.objectNode();
    cellPattern.put("type", "object");
    cellPattern.put("minProperties", 1);
    ObjectNode cellValue = NODES.objectNode();
    ArrayNode cellOneOf = cellValue.putArray("oneOf");
    cellOneOf.add(NODES.objectNode().put("type", "string"));
    cellOneOf.add(NODES.objectNode().put("type", "number"));
    cellOneOf.add(NODES.objectNode().put("type", "boolean"));
    cellPattern.set("additionalProperties", cellValue);

    ObjectNode exclude = props.putObject("exclude");
    exclude.put("type", "array");
    exclude.put(
        "description",
        "Cells (full or partial — every key matches) to drop from the Cartesian product.");
    exclude.set("items", cellPattern);

    ObjectNode include = props.putObject("include");
    include.put("type", "array");
    include.put(
        "description",
        "Off-grid cells to add to the matrix after 'exclude'. Each entry must name "
            + "every declared axis.");
    include.set("items", cellPattern.deepCopy());

    ObjectNode maxParallel = props.putObject("maxParallel");
    maxParallel.put("type", "integer");
    maxParallel.put("minimum", 1);
    maxParallel.put(
        "description",
        "Cap on how many cells run concurrently. Carried onto every generated cell stage; "
            + "engine-honoured at dispatch.");

    ObjectNode failFast = props.putObject("fail_fast");
    failFast.put("type", "boolean");
    failFast.put("default", true);
    failFast.put(
        "description",
        "When true (default), any cell failure aborts the fan-out group. When false, every "
            + "remaining cell runs to completion and the parent stage reports failure only "
            + "after all cells finish. (#1092)");
    return def;
  }

  /**
   * The {@code each} {@code $def} (design/55): the value shape of the {@code each:} stage scope.
   * The {@code EachScope} {@code $ref}s this; the value shape — {@code var}, {@code in}, {@code
   * maxParallel} — is declared here alongside the matrix shape.
   */
  @NonNull
  public static ObjectNode eachDef() {
    ObjectNode def = NODES.objectNode();
    def.put("type", "object");
    def.put(
        "description",
        "Stage-level 1D iteration (design/55). Fans the stage out into one stage per "
            + "element of 'in' at parse time; step arguments may reference the cell with "
            + "${each.<var>}. Mutually exclusive with 'matrix:' in v1.");
    ArrayNode req = def.putArray("required");
    req.add("var");
    req.add("in");
    def.put("additionalProperties", false);
    ObjectNode props = def.putObject("properties");

    ObjectNode var = props.putObject("var");
    var.put("type", "string");
    var.put("pattern", "^[A-Za-z_][A-Za-z0-9_]*$");
    var.put(
        "description",
        "The binding name. Substitution syntax is ${each.<var>}. Must be a valid identifier "
            + "and must not collide with a reserved name (each, matrix, env, params).");

    ObjectNode inArr = props.putObject("in");
    inArr.put("type", "array");
    inArr.put("minItems", 1);
    inArr.put("description", "Non-empty list of scalar values to iterate over.");
    ObjectNode item = NODES.objectNode();
    ArrayNode itemOneOf = item.putArray("oneOf");
    itemOneOf.add(NODES.objectNode().put("type", "string"));
    itemOneOf.add(NODES.objectNode().put("type", "number"));
    itemOneOf.add(NODES.objectNode().put("type", "boolean"));
    inArr.set("items", item);

    ObjectNode maxParallel = props.putObject("maxParallel");
    maxParallel.put("type", "integer");
    maxParallel.put("minimum", 1);
    maxParallel.put(
        "description",
        "Cap on how many cells run concurrently. Carried onto every generated cell stage; "
            + "engine-honoured at dispatch.");
    return def;
  }

  /**
   * The {@code use} {@code $def} (design/56): the value shape of the {@code use:} stage scope. The
   * {@code TemplateScope} {@code $ref}s this; the value shape — {@code from} (a relative local
   * path) and an optional {@code with} (a free-form keyword-args map) — is declared here alongside
   * the matrix and each shapes.
   */
  @NonNull
  public static ObjectNode useDef() {
    ObjectNode def = NODES.objectNode();
    def.put("type", "object");
    def.put(
        "description",
        "Stage-level local-template inlining (design/56). 'from' is a repo-relative path "
            + "to a template file; 'with' supplies keyword args. The template's step list "
            + "replaces the stage's steps at parse time, with ${param.<name>} substituted. "
            + "Mutually exclusive with 'steps:', 'matrix:' and 'each:' on the same stage.");
    def.putArray("required").add("from");
    def.put("additionalProperties", false);
    ObjectNode props = def.putObject("properties");

    ObjectNode from = props.putObject("from");
    from.put("type", "string");
    from.put(
        "description",
        "Relative local path to the template file, resolved against the pipeline file's "
            + "directory. Traversal within the repo root is allowed; escaping the repo "
            + "root is a parse error. Remote / git-ref'd reuse is the domain of "
            + "'libraries:' (design/53).");

    ObjectNode with = props.putObject("with");
    with.put("type", "object");
    with.put(
        "description",
        "Keyword args passed to the template — each key must name a 'params:' entry "
            + "declared by the template; values are typed-coerced where safe.");
    // free-form values — string, number, boolean — validated against params[].type at parse.
    ObjectNode withItem = NODES.objectNode();
    ArrayNode withOneOf = withItem.putArray("oneOf");
    withOneOf.add(NODES.objectNode().put("type", "string"));
    withOneOf.add(NODES.objectNode().put("type", "number"));
    withOneOf.add(NODES.objectNode().put("type", "boolean"));
    with.set("additionalProperties", withItem);

    return def;
  }

  /**
   * The {@code concurrency} {@code $def} (#1101): the value shape of the pipeline-root {@code
   * concurrency:} key. A {@code oneOf} — accept either a bare positive integer (short form,
   * equivalent to {@code {max: N}} with default {@code on_overflow: queue}) or an explicit object
   * carrying {@code max} (required, >= 1) and an optional {@code on_overflow} enum.
   */
  @NonNull
  public static ObjectNode concurrencyDef() {
    ObjectNode def = NODES.objectNode();
    def.put(
        "description",
        "Per-job concurrency limit (#1101). Caps the number of RUNNING builds of this job. "
            + "Either an integer (short form) or {max, on_overflow}.");
    ArrayNode oneOf = def.putArray("oneOf");

    // Short form: bare integer >= 1.
    ObjectNode shortForm = NODES.objectNode();
    shortForm.put("type", "integer");
    shortForm.put("minimum", 1);
    shortForm.put("description", "Short form: maximum RUNNING builds of this job, >= 1.");
    oneOf.add(shortForm);

    // Long form: object {max, on_overflow}.
    ObjectNode obj = NODES.objectNode();
    obj.put("type", "object");
    obj.putArray("required").add("max");
    obj.put("additionalProperties", false);
    ObjectNode props = obj.putObject("properties");
    ObjectNode max = props.putObject("max");
    max.put("type", "integer");
    max.put("minimum", 1);
    max.put("description", "Maximum RUNNING builds of this job, >= 1.");
    ObjectNode overflow = props.putObject("on_overflow");
    overflow.put("type", "string");
    ArrayNode overflowEnum = overflow.putArray("enum");
    overflowEnum.add("queue");
    overflowEnum.add("cancel_oldest");
    overflowEnum.add("cancel_pending");
    overflow.put(
        "description",
        "Behaviour when a new build arrives at the concurrency ceiling. 'queue' (default) "
            + "holds the new build until the cap clears; 'cancel_oldest' SIGTERMs the oldest "
            + "RUNNING build; 'cancel_pending' cancels other QUEUED builds of this job and "
            + "lets the new build through.");
    oneOf.add(obj);
    return def;
  }

  /**
   * The {@code priority} {@code $def} (#1100): the value shape of the pipeline-root {@code
   * priority:} key. A closed string enum — accept only {@code high} / {@code normal} / {@code low}.
   * Bare integers are rejected by design (principled-typed-design: named tiers, not a free-form
   * smallint surface the user must twiddle).
   */
  @NonNull
  public static ObjectNode priorityDef() {
    ObjectNode def = NODES.objectNode();
    def.put("type", "string");
    def.put(
        "description",
        "Per-job queue priority (#1100). One of: 'high' (weight 10), 'normal' (0, default), "
            + "'low' (-10). Higher-priority builds are claimed from task_queue first.");
    ArrayNode en = def.putArray("enum");
    en.add("high");
    en.add("normal");
    en.add("low");
    return def;
  }

  /**
   * The {@code buildRetention} {@code $def} (#640): the value shape of the pipeline-root {@code
   * buildRetention:} key. A closed object carrying a single required {@code keepLast} integer
   * (minimum {@code 0}) — the per-job override for the daily build-history prune.
   *
   * <p>{@code keepLast: 0} is the per-job opt-out — keeps full history for this job regardless of
   * the server-wide {@code TITAN_JOB_BUILD_RETENTION} default. A missing {@code buildRetention:}
   * block (the common case) means "fall back to the global default".
   */
  @NonNull
  public static ObjectNode buildRetentionDef() {
    ObjectNode def = NODES.objectNode();
    def.put("type", "object");
    def.put(
        "description",
        "Per-job build-retention override (#640). 'keepLast: N' caps this job's build "
            + "history at the last N rows; 'keepLast: 0' keeps full history for this job "
            + "regardless of the server-wide default.");
    def.putArray("required").add("keepLast");
    def.put("additionalProperties", false);
    ObjectNode props = def.putObject("properties");
    ObjectNode keepLast = props.putObject("keepLast");
    keepLast.put("type", "integer");
    keepLast.put("minimum", 0);
    keepLast.put(
        "description",
        "Maximum builds to retain for this job. 0 disables the prune for this job "
            + "(keeps full history).");
    return def;
  }

  /**
   * The {@code includeEntry} {@code $def} (#1120): one entry in the top-level {@code include:}
   * list. A {@code oneOf} — a bare relative path string OR an object {@code { repo, ref, path,
   * credential? }} for a cross-repo include.
   */
  @NonNull
  public static ObjectNode includeEntryDef() {
    ObjectNode def = NODES.objectNode();
    def.put(
        "description",
        "One include entry (#1120). Either a repo-relative path string to a sibling YAML "
            + "fragment, or an object { repo, ref, path[, credential] } for a cross-repo "
            + "fragment fetched via the controller's library fetcher.");
    ArrayNode oneOf = def.putArray("oneOf");

    ObjectNode pathForm = NODES.objectNode();
    pathForm.put("type", "string");
    pathForm.put(
        "description",
        "Local include: a repo-relative path to a sibling YAML fragment. URI schemes and "
            + "absolute paths are rejected (use the object form for cross-repo).");
    oneOf.add(pathForm);

    ObjectNode objForm = NODES.objectNode();
    objForm.put("type", "object");
    ArrayNode req = objForm.putArray("required");
    req.add("repo");
    req.add("ref");
    req.add("path");
    objForm.put("additionalProperties", false);
    ObjectNode props = objForm.putObject("properties");
    props
        .putObject("repo")
        .put("type", "string")
        .put("description", "Git URL of the source repo (https://, ssh://, file://, ...).");
    props
        .putObject("ref")
        .put("type", "string")
        .put("description", "Tag, branch, or full commit SHA to fetch at.");
    props
        .putObject("path")
        .put("type", "string")
        .put("description", "Path inside the source repo to the YAML fragment.");
    props
        .putObject("credential")
        .put("type", "string")
        .put(
            "description",
            "Optional secret-store id used to authenticate the fetch (design/40). "
                + "Plaintext secrets MUST NOT appear here.");
    oneOf.add(objForm);
    return def;
  }

  /**
   * The {@code credentialBinding} {@code $def}: one credential binding. A standalone schema — the
   * {@code credentials} scope {@code $ref}s an array of it.
   */
  @NonNull
  public static ObjectNode credentialBindingDef() {
    ObjectNode def = NODES.objectNode();
    def.put("type", "object");
    def.put(
        "description",
        "One credential binding (design/39 / design/32 D6): a credential-store "
            + "id bound into the step as masked, step-scoped environment. Not a "
            + "wrapping step — Titan has no blocks; a "
            + "binding is a declarative property of the step.");
    ArrayNode req = def.putArray("required");
    req.add("id");
    req.add("type");
    def.put("additionalProperties", false);
    ObjectNode props = def.putObject("properties");
    props.set(
        "id",
        NODES
            .objectNode()
            .put("type", "string")
            .put("description", "The credential-store id to resolve."));
    ObjectNode type = NODES.objectNode().put("type", "string");
    ArrayNode typeEnum = type.putArray("enum");
    typeEnum.add("usernamePassword");
    typeEnum.add("string");
    typeEnum.add("file");
    typeEnum.add("sshKey");
    type.put("description", "The binding type.");
    props.set("type", type);
    props.set(
        "usernameVariable",
        NODES
            .objectNode()
            .put("type", "string")
            .put("description", "Env var bound to the username (usernamePassword, sshKey)."));
    props.set(
        "passwordVariable",
        NODES
            .objectNode()
            .put("type", "string")
            .put("description", "Env var bound to the password (usernamePassword)."));
    props.set(
        "variable",
        NODES
            .objectNode()
            .put("type", "string")
            .put(
                "description",
                "Env var bound to the secret value (string) or the secret "
                    + "file's path (file)."));
    props.set(
        "keyFileVariable",
        NODES
            .objectNode()
            .put("type", "string")
            .put(
                "description",
                "Env var bound to the path of the materialised private-key " + "file (sshKey)."));
    props.set(
        "passphraseVariable",
        NODES
            .objectNode()
            .put("type", "string")
            .put("description", "Env var bound to the key passphrase (sshKey)."));
    return def;
  }

  /**
   * The {@code notifyHook} {@code $def} (#245): one entry in a {@code notify:} list. A small object
   * — discriminated by {@code type}, with {@code on} (event predicates), {@code url} (webhook
   * target) and an optional {@code credentialsId} (the secret-store id for auth-bearing hooks;
   * NEVER an inline literal — design/39). Today only {@code type: webhook} ships; {@code slack} is
   * reserved.
   */
  @NonNull
  public static ObjectNode notifyHookDef() {
    ObjectNode def = NODES.objectNode();
    def.put("type", "object");
    def.put(
        "description",
        "One lifecycle hook entry (#245). Fired by the orchestrator at the owning scope's "
            + "terminal state, regardless of which step ran.");
    def.putArray("required").add("type");
    def.put("additionalProperties", false);
    ObjectNode props = def.putObject("properties");

    ObjectNode type = props.putObject("type");
    type.put("type", "string");
    ArrayNode typeEnum = type.putArray("enum");
    typeEnum.add("webhook");
    typeEnum.add("slack");
    type.put(
        "description",
        "The hook kind. 'webhook' = plain HTTP POST (url required, credentialsId optional). "
            + "'slack' = Slack inbound-webhook (credentialsId REQUIRED — the stored secret is "
            + "the workspace-token-bearing URL; url MUST NOT be inline — CONSTITUTION §6).");

    ObjectNode on = props.putObject("on");
    on.put("type", "array");
    on.put("description", "Terminal-state predicates this hook fires on. Empty means 'always'.");
    ObjectNode onItem = NODES.objectNode();
    onItem.put("type", "string");
    ArrayNode onEnum = onItem.putArray("enum");
    onEnum.add("success");
    onEnum.add("failure");
    // #1102 — the fail→success transition. Independent of 'success': a hook declared
    // `on: [recovery]` fires ONLY on the transition out of a previous FAILED, not on every green
    // build. Used to page on-call when a job heals.
    onEnum.add("recovery");
    onEnum.add("always");
    on.set("items", onItem);

    ObjectNode url = props.putObject("url");
    url.put("type", "string");
    url.put("description", "The POST target URL. Required for 'type: webhook'.");

    ObjectNode credentialsId = props.putObject("credentialsId");
    credentialsId.put("type", "string");
    credentialsId.put(
        "description",
        "CredentialsService id resolved at dispatch time (design/39). Required for "
            + "'type: slack' (the stored secret is the workspace-token-bearing URL); optional "
            + "for 'type: webhook' (auth header). NEVER store the literal secret here — this "
            + "is an id reference (CONSTITUTION §6 — NO plaintext secret in config_json).");

    ObjectNode channel = props.putObject("channel");
    channel.put("type", "string");
    channel.put(
        "description",
        "Optional Slack channel override (e.g. '#deploys'). Plain display string, not a "
            + "secret. Only valid for 'type: slack'.");

    return def;
  }

  /**
   * The {@code envMap} value shape (GH #239, GH #1094): a JSON object whose values are strings,
   * used for the {@code env:} key at pipeline, stage, and step level. A value is either a literal
   * (which may contain anything, e.g. {@code PATH=/usr/bin:/bin}) OR a {@code secret:<id>}
   * reference the engine resolves at dispatch — the same rule {@code EnvValueRef.validate} enforces
   * in code.
   */
  @NonNull
  public static ObjectNode envMapSchema() {
    ObjectNode schema = NODES.objectNode();
    schema.put("type", "object");
    // oneOf: literal (must NOT start 'secret:') OR strict secret:<id> (one ':', no whitespace).
    ObjectNode value = NODES.objectNode().put("type", "string");
    ArrayNode oneOf = value.putArray("oneOf");
    oneOf.add(NODES.objectNode().set("not", NODES.objectNode().put("pattern", "^secret:")));
    oneOf.add(NODES.objectNode().put("pattern", "^secret:[^:\\s]+$"));
    schema.set("additionalProperties", value);
    return schema;
  }

  /**
   * A typed array of {@code $ref} items with a {@code minItems} floor — the {@code stages} key's
   * value shape. Kept here because {@link GrammarKey}'s fragment helpers cover the common
   * unbounded-array case; {@code minItems} is a one-off.
   */
  @NonNull
  private static ObjectNode minItemsArrayOfRef(@NonNull String def, int minItems) {
    ObjectNode node = NODES.objectNode().put("type", "array");
    node.put("minItems", minItems);
    node.set("items", ref(def));
    return node;
  }

  // ── projection helpers ──

  /**
   * Project the {@code Set} of key names out of an ordered grammar context — what {@code
   * TitanYamlParser} needs for its {@code rejectUnknownKeys} checks. Insertion order is preserved
   * (a {@link LinkedHashSet}); the parser only needs membership but a stable order keeps any
   * iteration deterministic.
   */
  @NonNull
  public static Set<String> keyNames(@NonNull List<GrammarKey> context) {
    Set<String> names = new LinkedHashSet<>();
    for (GrammarKey key : context) {
      names.add(key.name());
    }
    return Set.copyOf(names);
  }

  /** The required key names of a grammar context — what the generator's {@code required} needs. */
  @NonNull
  public static List<String> requiredNames(@NonNull List<GrammarKey> context) {
    return context.stream().filter(GrammarKey::required).map(GrammarKey::name).toList();
  }

  /**
   * Look up a {@link GrammarKey} by name in a grammar context — what the parser's typed reader
   * (design/48 D2) needs: it reads a key value through {@link GrammarKey#declaredType()}, so it
   * must reach the declaration, not just the name. A name not in the context is a programming error
   * in the parser (it would mean the parser reads a key the grammar does not declare).
   */
  @NonNull
  public static GrammarKey key(@NonNull List<GrammarKey> context, @NonNull String name) {
    for (GrammarKey key : context) {
      if (key.name().equals(name)) {
        return key;
      }
    }
    throw new IllegalArgumentException(
        "no grammar key '"
            + name
            + "' in this context — the parser must only read keys "
            + "the grammar declares (design/48 D2)");
  }
}

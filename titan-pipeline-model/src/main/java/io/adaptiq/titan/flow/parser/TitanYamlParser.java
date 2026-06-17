package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.model.CredentialBinding;
import io.adaptiq.titan.flow.model.GateModel;
import io.adaptiq.titan.flow.model.NotifyHook;
import io.adaptiq.titan.flow.model.ParameterModel;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.PreconditionModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.parser.grammar.TitanGrammar;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses a Titan YAML pipeline definition (design/29 §3) into a {@link PipelineModel}.
 *
 * <p>This is <em>deserialise + structural-validate</em> only — design/29 §0: the controller never
 * executes user code. The YAML is turned into a Jackson tree, every key is checked against the
 * known set for its context (unknown keys are rejected loudly), and the model is built. The DAG
 * itself (cycles, missing dependencies, duplicate ids) is checked separately by {@link
 * PipelineDagValidator} — call {@link #parseAndValidate(String)} to do both.
 *
 * <p><strong>A thin engine over {@link StepScope}s (design/42 §4.7).</strong> This class parses
 * only the pipeline/stage/step skeleton and the generic {@code descriptorId: args} body; every
 * grammar <em>scope</em> — {@code credentials}, {@code sshAgent}, {@code image}, {@code when},
 * {@code dependsOn} — is a focused {@link StepScope} in {@link #SCOPES}. Adding the next scope is
 * one new class plus one registration line; the engine here is untouched. {@code STAGE_KEYS} /
 * {@code STEP_KEYS} are the <em>computed</em> union of the registered scopes' {@code key()}s, not
 * hand-maintained literals.
 *
 * <p>The scope set is curated <em>core</em> (design/42 §4.7) — a fixed, explicitly-registered list,
 * not {@code ServiceLoader}-pluggable: a grammar keyword is global, so the grammar stays portable.
 *
 * <p>The parser carries <strong>zero per-step knowledge</strong> (design/42 §4.6): a bare scalar
 * step value always folds into the conventional {@code value} argument key, for any descriptor.
 * Which named argument that {@code value} is the shorthand for is a property of the step's {@code
 * StepDescriptor#scalarShorthandKey()}, consumed worker-side — never here.
 *
 * <p>The pipeline body is accepted in <strong>either</strong> shape (design/29 §3): at the
 * <em>document root</em> — the canonical form — or nested under a top-level {@code titan:} key —
 * the legacy wrapper, still accepted for back-compat. Both produce the identical model.
 *
 * <p>YAML is the only accepted form; code reuse is handled by {@code libraryCall} (design/53).
 */
public final class TitanYamlParser {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  /**
   * The curated, explicitly-registered core grammar scopes (design/42 §4.7). Adding a scope is one
   * line here — the engine below does not change.
   */
  private static final List<StepScope> SCOPES =
      List.of(
          // TemplateScope (design/56) runs FIRST so the inlined template step list is in place
          // before any flatten-onto-steps scope (credentials, sshAgent, image, retry, env)
          // applies — otherwise the stage-level flatten would target an empty `steps` and the
          // inlined template steps would never receive stage-level credentials etc.
          new TemplateScope(),
          new CredentialsScope(),
          new SshAgentScope(),
          new ImageScope(),
          new RetryScope(),
          new TimeoutScope(),
          new WhenScope(),
          new DependsOnScope(),
          new OnFailureScope(),
          new EnvScope(),
          new MatrixScope(),
          new EachScope(),
          new NotifyScope());

  // design/47 §4.1: the non-scope grammar contexts are projected from the single in-code
  // grammar declaration in TitanGrammar — no longer hand-maintained literals. The accepted key
  // sets are byte-identical to the former literals; only the source of truth moved.
  private static final Set<String> ROOT_KEYS = TitanGrammar.keyNames(TitanGrammar.ROOT);
  private static final Set<String> PARAMETER_KEYS = TitanGrammar.keyNames(TitanGrammar.PARAMETER);
  private static final Set<String> GATE_KEYS = TitanGrammar.keyNames(TitanGrammar.GATE);
  private static final Set<String> PRECONDITION_KEYS =
      TitanGrammar.keyNames(TitanGrammar.PRECONDITION);
  private static final Set<String> SCRIPT_KEYS = TitanGrammar.keyNames(TitanGrammar.SCRIPT);

  /**
   * The keys valid on a stage node — the {@code stage} discriminator plus {@code agent} plus the
   * computed union of every registered scope's {@code key()}. Not a hand-maintained literal.
   */
  private static final Set<String> STAGE_KEYS = computeStageKeys();

  /**
   * The keys valid on a step node <em>besides</em> its single descriptor key — the computed union
   * of the scope keys that {@link StepScope#appliesToStep()}. Not a hand-maintained literal.
   */
  private static final Set<String> STEP_KEYS = computeStepKeys();

  /**
   * The registered grammar scopes, in registration order — package-private so {@code
   * TitanSchemaGenerator} (design/47 §4.2) can project the {@code stage}/{@code step} {@code $defs}
   * from the same scope list the parser enforces. The list is immutable.
   */
  static List<StepScope> scopes() {
    return SCOPES;
  }

  /**
   * The computed step-scope key set — visible to scopes that need to recognise non-descriptor
   * sibling keys on a step node ({@link TemplateScope} synthesises {@link StepModel}s out of a
   * template's {@code steps:} block and applies the same descriptor-vs-scope-key recognition the
   * parser uses).
   */
  static Set<String> stepScopeKeys() {
    return STEP_KEYS;
  }

  /**
   * The parser's <em>effective</em> key sets, one per closed grammar context — visible for testing
   * (design/47 §5). {@code GrammarSchemaContractTest} compares these against the key sets the
   * generated JSON Schema declares: the two must be independently-derived projections of the one
   * grammar, and equal. Returns the parser-side sets exactly as {@code rejectUnknownKeys} uses them
   * — {@code STAGE_KEYS}/{@code STEP_KEYS} are the computed scope unions, not literals.
   */
  // visible for testing
  static Map<String, Set<String>> grammarKeySetsForTest() {
    Map<String, Set<String>> sets = new LinkedHashMap<>();
    sets.put("root", ROOT_KEYS);
    sets.put("stage", STAGE_KEYS);
    sets.put("gate", GATE_KEYS);
    sets.put("precondition", PRECONDITION_KEYS);
    sets.put("parameter", PARAMETER_KEYS);
    sets.put("trigger", TriggerModelParser.TRIGGER_KEYS);
    sets.put("githubTrigger", TriggerModelParser.GITHUB_TRIGGER_KEYS);
    sets.put("gitlabTrigger", TriggerModelParser.GITLAB_TRIGGER_KEYS);
    sets.put("bitbucketTrigger", TriggerModelParser.BITBUCKET_TRIGGER_KEYS);
    sets.put("script", SCRIPT_KEYS);
    sets.put("step", STEP_KEYS);
    return sets;
  }

  private static Set<String> computeStageKeys() {
    Set<String> keys = new LinkedHashSet<>();
    keys.add("stage");
    keys.add("agent");
    keys.add("steps");
    for (StepScope scope : SCOPES) {
      keys.add(scope.key());
    }
    return Set.copyOf(keys);
  }

  private static Set<String> computeStepKeys() {
    Set<String> keys = new LinkedHashSet<>();
    for (StepScope scope : SCOPES) {
      if (scope.appliesToStep()) {
        keys.add(scope.key());
      }
    }
    return Set.copyOf(keys);
  }

  /**
   * Per-thread include resolver (#1120). The parser is a static facade for back-compat; the
   * resolver is injected via {@link #parse(String, Path)} / {@link #parse(String, IncludeResolver)}
   * and stashed here for the duration of one parse call.
   */
  private static final ThreadLocal<IncludeResolver> INCLUDE_RESOLVER = new ThreadLocal<>();

  private TitanYamlParser() {}

  /**
   * Tolerantly extract just the top-level {@code notify:} block from a (possibly malformed) Titan
   * pipeline YAML. Issue #360: when bake itself crashes (bad YAML, broken DAG, unknown step) the
   * full {@link PipelineModel} is never built, but the build's declared notify hooks should still
   * fire with a {@code BAKE_FAILURE} synthetic event. This is a <em>best-effort</em> pre-bake read
   * — any failure (invalid YAML, malformed {@code notify:} block, present-but-null, anything) is
   * swallowed and the result is an empty list. The dispatcher then has nothing to send and the
   * caller proceeds with the failure-handling path unchanged.
   *
   * <p>The pipeline body is accepted in either shape — root-level or under {@code titan:} —
   * mirroring {@link #parseInternal}. {@code notify:} on a malformed-but-parseable YAML document
   * (e.g. a top-level {@code pipeline:} wrapper — the build #46 class of failure) will return empty
   * unless the {@code notify:} key happens to live at one of the two recognised positions.
   */
  @NonNull
  public static List<NotifyHook> parseNotifyHooksTolerant(@NonNull String yaml) {
    try {
      if (yaml.isBlank()) {
        return List.of();
      }
      JsonNode root = YAML.readTree(yaml);
      if (root == null || !root.isObject()) {
        return List.of();
      }
      JsonNode body;
      if (root.has("titan") && root.get("titan").isObject()) {
        body = root.get("titan");
      } else {
        body = root;
      }
      JsonNode notifyNode = body.get("notify");
      if (notifyNode == null) {
        return List.of();
      }
      return NotifyScope.parseList(notifyNode, "tolerant-prebake notify");
    } catch (RuntimeException | JacksonException e) {
      // Best-effort: any parse trouble on the notify block falls back to "no hooks". The build's
      // bake failure is already being recorded elsewhere — losing the notify dispatch on a
      // YAML that's so malformed we can't even find `notify:` is acceptable (#360).
      return List.of();
    }
  }

  /** Parse <em>and</em> DAG-validate — the bake-time entry point. */
  @NonNull
  public static PipelineModel parseAndValidate(@NonNull String yaml) {
    PipelineModel model = parse(yaml);
    PipelineDagValidator.validate(model);
    return model;
  }

  /**
   * Parse <em>and</em> DAG-validate with an explicit base directory for {@code use:} template
   * resolution (design/56). Templates referenced via {@code use: { from: <relative-path> }} are
   * resolved against {@code baseDir} — typically the pipeline file's directory.
   */
  @NonNull
  public static PipelineModel parseAndValidate(@NonNull String yaml, @NonNull Path baseDir) {
    PipelineModel model = parse(yaml, baseDir);
    PipelineDagValidator.validate(model);
    return model;
  }

  /**
   * Parse YAML into a {@link PipelineModel} with an explicit base directory for {@code use:}
   * template resolution (design/56). For tests/embeddings without a real filesystem, see {@link
   * #parse(String, TemplateResolver)}.
   */
  @NonNull
  public static PipelineModel parse(@NonNull String yaml, @NonNull Path baseDir) {
    INCLUDE_RESOLVER.set(new IncludeResolver.LocalRelativeIncludeResolver(baseDir));
    try {
      return parse(yaml, new TemplateResolver.LocalRelativeTemplateResolver(baseDir));
    } finally {
      INCLUDE_RESOLVER.remove();
    }
  }

  /** Parse YAML with an explicit {@link IncludeResolver} — test seam for #1120 fixtures. */
  @NonNull
  public static PipelineModel parse(
      @NonNull String yaml, @NonNull IncludeResolver includeResolver) {
    INCLUDE_RESOLVER.set(includeResolver);
    try {
      return parseInternal(yaml);
    } finally {
      INCLUDE_RESOLVER.remove();
    }
  }

  /**
   * Parse YAML with a caller-supplied {@link TemplateResolver} — test-only entry point that lets a
   * fixture stub the filesystem. Production callers should use {@link #parse(String, Path)}.
   */
  @NonNull
  public static PipelineModel parse(@NonNull String yaml, @NonNull TemplateResolver resolver) {
    TemplateScope.setResolver(resolver);
    try {
      return parseInternal(yaml);
    } finally {
      TemplateScope.clearResolver();
    }
  }

  /** Parse YAML into a {@link PipelineModel} without DAG validation. */
  @NonNull
  public static PipelineModel parse(@NonNull String yaml) {
    // Legacy entry point — no baseDir given. Use the current working directory as the resolver
    // root so `use:` references are still resolvable in single-file test fixtures; tests that
    // need a stubbed filesystem call parse(yaml, TemplateResolver) directly.
    TemplateScope.setResolver(new TemplateResolver.LocalRelativeTemplateResolver(Path.of(".")));
    try {
      return parseInternal(yaml);
    } finally {
      TemplateScope.clearResolver();
    }
  }

  @NonNull
  private static PipelineModel parseInternal(@NonNull String yaml) {
    if (yaml.isBlank()) {
      throw new PipelineParseException("pipeline definition is empty");
    }
    JsonNode root;
    try {
      root = YAML.readTree(yaml);
    } catch (JacksonException e) {
      throw new PipelineParseException("invalid YAML: " + e.getOriginalMessage(), e);
    }
    if (root == null || !root.isObject()) {
      throw new PipelineParseException("pipeline definition must be a YAML object (design/29 §3)");
    }
    // design/54: the MatrixScope stashes parsed matrix specs in a ThreadLocal keyed by built
    // StageModel — the engine drains it just below the scope loop. Clear any leftover from a
    // prior parse() that bailed mid-flight, so this parse() starts on a fresh slate.
    MatrixScope.resetPending();
    EachScope.resetPending();
    // design/29 §3: the pipeline body lives either at the document root (the canonical form)
    // or nested under a `titan:` key (the legacy wrapper, kept for back-compat). Detection is
    // unambiguous — `titan` is not a valid root key, so a `titan` field holding an object can
    // only be the wrapper; anything else is the root form.
    JsonNode titan;
    String context;
    if (root.has("titan") && root.get("titan").isObject()) {
      titan = root.get("titan");
      context = "titan";
      // design/47 (crack hunt): the legacy wrapper form is *exactly* a single top-level
      // `titan:` key. Before this guard the parser projected only `root.get("titan")` and
      // never looked at the document root's other fields, so a sibling like
      // `{ titan: {...}, somethingElse: 1 }` was silently accepted while the generated
      // schema's wrapper branch (`additionalProperties:false`, only `titan` allowed)
      // rejected it — a parser/schema divergence. The wrapper has no other root keys
      // (design/29 §3); reject any sibling so the parser matches the schema.
      ParseSupport.rejectUnknownKeys(root, Set.of("titan"), "document root");
    } else if (root.has("titan")) {
      // `titan:` present but not an object — a malformed legacy wrapper.
      throw new PipelineParseException("'titan' must be an object");
    } else {
      titan = root;
      context = "pipeline";
    }

    // issue #1120 — include directive. If the body declares `include:`, resolve+inline the
    // included fragments before the structural-key check below. The processor strips the
    // `include:` key on the way out and returns a merged body the rest of this method consumes
    // unchanged. If no resolver is configured (legacy single-arg parse()), a present-and-non-
    // empty include is a parse error — the caller must use parse(yaml, baseDir) or supply an
    // IncludeResolver so the engine knows how to read the included files.
    if (titan.isObject() && titan.has("include") && !titan.get("include").isNull()) {
      IncludeResolver resolver = INCLUDE_RESOLVER.get();
      if (resolver == null) {
        throw new PipelineParseException(
            context
                + ": 'include:' is declared but the parser was called without a base directory "
                + "or IncludeResolver — use TitanYamlParser.parse(yaml, baseDir) so the engine "
                + "can read the included fragments (#1120)");
      }
      titan = IncludeProcessor.inline((ObjectNode) titan, resolver, "<main-pipeline>");
    }
    ParseSupport.rejectUnknownKeys(titan, ROOT_KEYS, context);

    PipelineModel model = new PipelineModel();
    // design/48 D2: every grammar key is read through the TitanGrammar-typed reader — a
    // wrong-typed value (agent: [a,b], failurePolicy: [x]) or a present-and-null value is now
    // a located error, not a silent coercion to "" / key-absent.
    model.setAgent(
        TypedNodeReader.optString(titan, TitanGrammar.key(TitanGrammar.ROOT, "agent"), context));
    String failurePolicy =
        TypedNodeReader.optString(
            titan, TitanGrammar.key(TitanGrammar.ROOT, "failurePolicy"), context);
    if (failurePolicy != null) {
      // issue #392: typed enum, not free-form string — unknown values are a located parse error,
      // not a silent no-op at runtime in the orchestrator.
      model.setFailurePolicy(
          io.adaptiq.titan.flow.model.FailurePolicy.fromYaml(
              failurePolicy, context + " failurePolicy"));
    }
    LibrariesScope.applyTo(titan, model, context);
    parseParameters(titan, model, context);
    TriggerModelParser.parse(titan, model, context);

    // Pipeline-level `credentials:` (design/42) is parser sugar: it is flattened onto every
    // step of every stage, exactly as a stage-level list already is. The pipeline list is
    // prepended to the per-step set, giving step > stage > pipeline precedence (design/42 §3).
    List<CredentialBinding> pipelineCreds =
        CredentialsScope.parse(titan.get("credentials"), context);

    // Pipeline-level `env:` (GH #239) — stored on the model; the merge (pipeline <- stage <-
    // step) happens at dispatch time via MergedEnv, not flattened at parse time.
    model.setEnv(EnvScope.parse(titan.get("env"), context + " env"));

    // Pipeline-level `timeout:` (issue #244) — the outermost safety net. Distinct from
    // step/stage timeouts: this caps total wall-clock pipeline runtime. Parsed via the same
    // TimeoutScope.parseMillis grammar so '30s' / '5m' / '2h' / '1d' / bare-seconds all work.
    JsonNode rootTimeout = titan.get("timeout");
    if (rootTimeout != null && !rootTimeout.isNull()) {
      model.setTimeoutMillis(TimeoutScope.parseMillis(rootTimeout, context + " timeout"));
    }

    // Pipeline-level `notify:` (#245) — declarative lifecycle hooks fired by the orchestrator at
    // the build's terminal state. Parsed via NotifyScope.parseList so the grammar (type/on/url/
    // credentialsId) lives in one place; the scope itself handles the stage-level form.
    model.setNotify(NotifyScope.parseList(titan.get("notify"), context + " notify"));

    // Pipeline-level `buildRetention:` (#640) — per-job override for the daily build-history
    // prune. Schema: { keepLast: integer >= 0 }. The closed-object/required-key shape is
    // enforced by the typed-node reader + an explicit keepLast read; the daily pruner reads the
    // resulting integer off the parsed model and falls back to the server-wide default when this
    // is absent.
    JsonNode buildRetention =
        TypedNodeReader.optionalNode(
            titan, TitanGrammar.key(TitanGrammar.ROOT, "buildRetention"), context);
    if (buildRetention != null) {
      ParseSupport.rejectUnknownKeys(
          buildRetention, Set.of("keepLast"), context + " buildRetention");
      JsonNode keepLast = buildRetention.get("keepLast");
      if (keepLast == null) {
        throw new PipelineParseException(
            context + " buildRetention: missing required key 'keepLast'");
      }
      if (keepLast.isNull()) {
        throw TypedNodeReader.presentButNull("keepLast", context + " buildRetention");
      }
      if (!keepLast.isIntegralNumber()) {
        throw new PipelineParseException(
            context
                + " buildRetention: 'keepLast' must be an integer >= 0, got "
                + TypedNodeReader.describe(keepLast));
      }
      int value = keepLast.intValue();
      if (value < 0) {
        throw new PipelineParseException(
            context + " buildRetention: 'keepLast' must be >= 0, got " + value);
      }
      model.setBuildRetentionKeepLast(value);
    }

    // Pipeline-level `concurrency:` (#1101) — see ConcurrencyScope.
    model.setConcurrency(ConcurrencyScope.parse(titan.get("concurrency"), context));

    // Pipeline-level `priority:` (#1100) — see PriorityScope. Discriminated-union string
    // (high|normal|low); maps to an integer weight written into task_queue.priority.
    model.setPriority(PriorityScope.parse(titan.get("priority"), context));
    JsonNode stages = titan.get("stages");
    if (stages == null || !stages.isArray() || stages.isEmpty()) {
      throw new PipelineParseException("'" + context + ".stages' must be a non-empty array");
    }
    // design/54 + design/55 + #398: matrix/each prototypes expand into N cell stages whose names
    // differ from the prototype's. We track prototypeName → [cellNames] as each stage parses, then
    // — after every stage is in place — rewrite downstream `dependsOn` so a reference to the
    // prototype name resolves to all its cells (fan-in semantics). A literal cell name still
    // passes through unchanged.
    PrototypeExpansion expansion = new PrototypeExpansion();
    int index = 0;
    for (JsonNode entry : stages) {
      parseNode(entry, index++, model, pipelineCreds, expansion);
    }
    expansion.rewrite(model);
    // design/68 #947: validate onFailure target ids while the prototype map is still in scope.
    expansion.validateOnFailureTargets(model);
    return model;
  }

  /** Parse the optional {@code parameters:} block — the build parameters the pipeline declares. */
  private static void parseParameters(
      @NonNull JsonNode titan, @NonNull PipelineModel model, @NonNull String context) {
    // design/48 D2: `parameters` is a grammar key declared `array` — read it through the typed
    // reader so `parameters: null` is a present-and-null error and `parameters: {a:1}` a type
    // error, not a silent skip.
    JsonNode params =
        TypedNodeReader.optionalNode(
            titan, TitanGrammar.key(TitanGrammar.ROOT, "parameters"), context);
    if (params == null) {
      return;
    }
    int index = 0;
    for (JsonNode p : params) {
      if (!p.isObject()) {
        throw new PipelineParseException("parameters[" + index + "] must be an object");
      }
      ParseSupport.rejectUnknownKeys(p, PARAMETER_KEYS, "parameters[" + index + "]");
      ParameterModel pm = new ParameterModel();
      String name =
          TypedNodeReader.requireString(
              p, TitanGrammar.key(TitanGrammar.PARAMETER, "name"), "parameters[" + index + "]");
      pm.setName(name);
      String paramCtx = "parameter '" + name + "'";
      String type =
          TypedNodeReader.optString(p, TitanGrammar.key(TitanGrammar.PARAMETER, "type"), paramCtx);
      if (type != null) {
        pm.setType(type);
      }
      pm.setDescription(
          TypedNodeReader.optString(
              p, TitanGrammar.key(TitanGrammar.PARAMETER, "description"), paramCtx));
      Boolean required =
          TypedNodeReader.optBoolean(
              p, TitanGrammar.key(TitanGrammar.PARAMETER, "required"), paramCtx);
      if (required != null) {
        pm.setRequired(required);
      }
      // design/48 D2: parameters[].default is genuinely any-typed — no type check, but a
      // present-and-null `default: ~` stays a no-op (an absent default), unchanged.
      JsonNode def = p.get("default");
      if (def != null && !def.isNull()) {
        pm.setDefaultValue(YAML.convertValue(def, Object.class));
      }
      pm.setChoices(
          TypedNodeReader.stringList(
              p, TitanGrammar.key(TitanGrammar.PARAMETER, "choices"), paramCtx));
      model.getParameters().add(pm);
      index++;
    }
  }

  /** Dispatch one {@code stages[]} entry to the right node parser by its discriminator key. */
  private static void parseNode(
      @NonNull JsonNode entry,
      int index,
      @NonNull PipelineModel model,
      @NonNull List<CredentialBinding> pipelineCreds,
      @NonNull PrototypeExpansion expansion) {
    if (!entry.isObject()) {
      throw new PipelineParseException("stages[" + index + "] must be an object");
    }
    boolean isStage = entry.has("stage");
    boolean isGate = entry.has("gate");
    boolean isPrecondition = entry.has("precondition");
    int discriminators = (isStage ? 1 : 0) + (isGate ? 1 : 0) + (isPrecondition ? 1 : 0);
    if (discriminators != 1) {
      throw new PipelineParseException(
          "stages["
              + index
              + "] must have exactly one of 'stage:', 'gate:' or "
              + "'precondition:' as its node-kind key");
    }
    if (isStage) {
      // design/54: parseStage may fan-out into N cell stages when the stage carries `matrix:`.
      // The engine appends every returned stage to the pipeline DAG in order.
      model
          .getStages()
          .addAll(
              parseStage(
                  entry,
                  index,
                  pipelineCreds,
                  model.getLibraryAliases(),
                  model.getLibraryAliasCredentials(),
                  expansion));
    } else if (isGate) {
      model.getGates().add(parseGate(entry, index));
    } else {
      model.getPreconditions().add(parsePrecondition(entry, index));
    }
  }

  /**
   * Parse a stage node. The engine builds the stage skeleton and every step's generic body, then
   * loops the registered {@link StepScope}s: {@code parseStageAndFlatten} per scope, then {@code
   * validate} per scope per step. No per-scope code lives here.
   */
  private static List<StageModel> parseStage(
      @NonNull JsonNode entry,
      int index,
      @NonNull List<CredentialBinding> pipelineCreds,
      @NonNull Map<String, String> libraryAliases,
      @NonNull Map<String, String> libraryAliasCredentials,
      @NonNull PrototypeExpansion expansion) {
    // Titan has no `parallel:` keyword — concurrency is the DAG itself. Give a pointed error
    // rather than the generic unknown-key one (design/29 §3).
    if (entry.has("parallel")) {
      throw new PipelineParseException(
          "stages["
              + index
              + "]: 'parallel' is not a Titan stage key — express "
              + "concurrency by giving sibling stages the same 'dependsOn' "
              + "(design/29 §3); the DAG is the parallelism");
    }
    ParseSupport.rejectUnknownKeys(entry, STAGE_KEYS, "stages[" + index + "]");
    StageModel stage = new StageModel();
    // design/48 D2: `stage`/`agent`/`steps` are stage-skeleton grammar keys — typed-read.
    String name =
        TypedNodeReader.requireString(
            entry, TitanGrammar.key(TitanGrammar.STAGE_SKELETON, "stage"), "stages[" + index + "]");
    stage.setName(name);
    stage.setId(slug(name));
    stage.setAgentLabel(
        TypedNodeReader.optString(
            entry, TitanGrammar.key(TitanGrammar.STAGE_SKELETON, "agent"), "stage '" + name + "'"));

    ParseContext stageCtx = ParseContext.forStage("stage '" + name + "'", stage);

    JsonNode steps =
        TypedNodeReader.optionalNode(
            entry, TitanGrammar.key(TitanGrammar.STAGE_SKELETON, "steps"), "stage '" + name + "'");
    if (steps != null) {
      int s = 0;
      for (JsonNode stepNode : steps) {
        stage
            .getSteps()
            .add(
                parseStep(
                    stepNode, stage.getId(), s++, name, libraryAliases, libraryAliasCredentials));
      }
    }

    // Apply every scope: stage-level parse + flatten onto the steps, then per-step validation
    // of the effective (post-flatten) state. `when`/`dependsOn`/`image` set stage data; the
    // sugar scopes (`credentials`/`sshAgent`) flatten onto every step.
    for (StepScope scope : SCOPES) {
      scope.parseStageAndFlatten(entry, stage.getSteps(), stageCtx);
    }
    // Pipeline-level `credentials:` is prepended after the stage flatten, giving the full
    // pipeline ++ stage ++ step order (design/42 §3) — engine concern, not a scope's.
    if (!pipelineCreds.isEmpty()) {
      for (StepModel step : stage.getSteps()) {
        List<CredentialBinding> merged = new ArrayList<>(pipelineCreds);
        merged.addAll(step.getCredentials());
        step.setCredentials(merged);
      }
    }
    for (StepModel step : stage.getSteps()) {
      for (StepScope scope : SCOPES) {
        scope.validate(step, stageCtx);
      }
    }
    // design/54 matrix fan-out — engine seam, one-to-many. If the stage declared `matrix:`,
    // MatrixScope stashed the parsed spec during its parseStageAndFlatten; we drain that here
    // and expand the prototype stage into one StageModel per cell. No matrix → singleton list.
    MatrixScope.MatrixSpec matrixSpec = MatrixScope.pendingSpec(stage);
    EachScope.EachSpec eachSpec = EachScope.pendingSpec(stage);
    if (matrixSpec != null) {
      // EachScope rejects matrix+each on the same stage at parse-time; defence-in-depth here.
      if (eachSpec != null) {
        throw new PipelineParseException(
            "stage '" + name + "': 'each' and 'matrix' are mutually exclusive on the same stage");
      }
      List<StageModel> cells = MatrixScope.expand(stage, matrixSpec, "stage '" + name + "' matrix");
      // #398: remember prototypeName → cell names so a downstream `dependsOn: [<prototype>]`
      // resolves to all cells at bake time, not the now-vanished prototype.
      expansion.record(name, cells);
      return cells;
    }
    if (eachSpec != null) {
      List<StageModel> cells = EachScope.expand(stage, eachSpec, "stage '" + name + "' each");
      expansion.record(name, cells);
      return cells;
    }
    return List.of(stage);
  }

  /**
   * Parse a single step node into a {@link StepModel}: the generic {@code descriptorId: args} body
   * plus the step-level scope keys. A step is exactly one descriptor key — {@code sh:}, {@code
   * script:}, … — optionally with sibling scope keys ({@code image}, {@code credentials}, {@code
   * sshAgent}). The engine carries no per-step knowledge: a bare scalar always folds into the
   * conventional {@code value} key (design/42 §4.6).
   */
  private static StepModel parseStep(
      @NonNull JsonNode stepNode,
      @NonNull String stageId,
      int s,
      @NonNull String stageName,
      @NonNull Map<String, String> libraryAliases,
      @NonNull Map<String, String> libraryAliasCredentials) {
    if (!stepNode.isObject() || stepNode.isEmpty()) {
      throw new PipelineParseException(
          "stage '"
              + stageName
              + "' step "
              + s
              + ": each step must be a map with one descriptor key"
              + " (optionally plus 'image')");
    }
    String descriptor = null;
    JsonNode value = null;
    Iterator<Map.Entry<String, JsonNode>> fields = stepNode.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> e = fields.next();
      if (STEP_KEYS.contains(e.getKey())) {
        // A step-level scope key — parsed by its StepScope below, not a descriptor key.
        continue;
      }
      if (descriptor != null) {
        throw new PipelineParseException(
            "stage '"
                + stageName
                + "' step "
                + s
                + ": a step has exactly one"
                + " descriptor key (found '"
                + descriptor
                + "' and '"
                + e.getKey()
                + "') — concurrency is the DAG, not multiple step keys");
      }
      descriptor = e.getKey();
      value = e.getValue();
    }
    if (descriptor == null) {
      throw new PipelineParseException(
          "stage '"
              + stageName
              + "' step "
              + s
              + ": no descriptor key (only scope keys: "
              + "'image'/'credentials'/'sshAgent'/'retry')");
    }

    // #706: collapse descriptor aliases (e.g. `wait` → `sleep`) before the model is built so
    // the rest of the engine — scope dispatch, worker handler lookup — only sees the canonical
    // key. Alias resolution happens after descriptor identification so duplicate-descriptor and
    // missing-descriptor errors above still cite what the user actually wrote.
    StepModel step = new StepModel();
    step.setId(stageId + "-s" + s);
    step.setDescriptorId(DescriptorAliases.canonicalize(descriptor));

    String stepLocation = "stage '" + stageName + "' step " + s;
    ParseContext stepCtx = ParseContext.forStep(stepLocation);
    for (StepScope scope : SCOPES) {
      if (scope.appliesToStep()) {
        scope.parseStep(stepNode, step, stepCtx);
      }
    }

    if ("script".equals(descriptor)) {
      // `script` is a structural step body (runtime + source text), not an arguments map —
      // a distinct shape design/29 §3 defines, kept in the engine. It is not a scope.
      if (!value.isObject()) {
        throw new PipelineParseException(
            stepLocation + ": a 'script' step must be an object with 'runtime' and 'body'");
      }
      ParseSupport.rejectUnknownKeys(
          value, SCRIPT_KEYS, "stage '" + stageName + "' script step " + s);
      // design/48 D2: `runtime`/`body` are SCRIPT grammar keys — typed-read so a non-string
      // or present-and-null value is a located type error, not a coercion.
      String scriptCtx = "script step " + s;
      step.setRuntime(
          TypedNodeReader.requireString(
              value, TitanGrammar.key(TitanGrammar.SCRIPT, "runtime"), scriptCtx));
      step.setBody(
          TypedNodeReader.requireString(
              value, TitanGrammar.key(TitanGrammar.SCRIPT, "body"), scriptCtx));
    } else if (value.isObject()) {
      step.setArguments(toArgMap(value));
    } else if (value.isNull()) {
      // a no-argument step, e.g. `- checkout:` — empty argument map.
      step.setArguments(new LinkedHashMap<>());
    } else {
      // A bare scalar step value, e.g. `sh: mvn clean package`. The generic rule (design/42
      // §4.6): a scalar always folds into the conventional `value` argument key, for ANY
      // descriptor — the parser has no per-step knowledge. The descriptor's
      // `scalarShorthandKey()` resolves `value` to its named argument worker-side.
      Map<String, Object> args = new LinkedHashMap<>();
      args.put("value", value.asText());
      step.setArguments(args);
    }

    // design/53 dotted-step dispatch. If the step type is `<alias>.<method>` and `<alias>`
    // appears in pipeline.libraries (object form), rewrite the descriptor to `libraryCall`
    // and wrap the user args. Only the dotted form triggers dispatch — a bare alias never
    // shadows a built-in step type.
    applyLibraryAliasDispatch(step, libraryAliases, libraryAliasCredentials);
    return step;
  }

  /**
   * If the step's descriptor is {@code <alias>.<method>} with {@code <alias>} declared in the
   * pipeline's {@code libraries:} map, rewrite to a {@code libraryCall} step (design/53). No-op
   * otherwise.
   */
  private static void applyLibraryAliasDispatch(
      @NonNull StepModel step,
      @NonNull Map<String, String> libraryAliases,
      @NonNull Map<String, String> libraryAliasCredentials) {
    if (libraryAliases.isEmpty()) {
      return;
    }
    String descriptor = step.getDescriptorId();
    int dot = descriptor.indexOf('.');
    if (dot <= 0 || dot == descriptor.length() - 1) {
      return;
    }
    String alias = descriptor.substring(0, dot);
    String method = descriptor.substring(dot + 1);
    if (method.indexOf('.') >= 0) {
      return; // multi-dotted descriptors are not in design/53 — leave to fail loud worker-side.
    }
    String coordinate = libraryAliases.get(alias);
    if (coordinate == null) {
      return;
    }
    Map<String, Object> wrapped = new LinkedHashMap<>();
    wrapped.put("library", coordinate);
    wrapped.put("file", alias);
    wrapped.put("method", method);
    String credentialName = libraryAliasCredentials.get(alias);
    if (credentialName != null && !credentialName.isBlank()) {
      wrapped.put("libraryCredential", credentialName);
    }
    wrapped.put(
        "args",
        step.getArguments() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(step.getArguments()));
    step.setDescriptorId("libraryCall");
    step.setArguments(wrapped);
  }

  private static GateModel parseGate(@NonNull JsonNode entry, int index) {
    ParseSupport.rejectUnknownKeys(entry, GATE_KEYS, "stages[" + index + "]");
    GateModel gate = new GateModel();
    // design/48 D2/D4: `gate`/`requiresApproval`/`approvers`/`dependsOn` are GATE grammar keys.
    // `requiresApproval: "yes"` / `: 1` is now a located type error, never a coercion — this
    // flips a deployment approval gate, so a fat-fingered value must fail loudly (design/48).
    String name =
        TypedNodeReader.requireString(
            entry, TitanGrammar.key(TitanGrammar.GATE, "gate"), "stages[" + index + "]");
    gate.setName(name);
    gate.setId(slug(name));
    String gateCtx = "gate '" + name + "'";
    Boolean requiresApproval =
        TypedNodeReader.optBoolean(
            entry, TitanGrammar.key(TitanGrammar.GATE, "requiresApproval"), gateCtx);
    if (requiresApproval != null) {
      gate.setRequiresApproval(requiresApproval);
    }
    for (String approver :
        TypedNodeReader.stringList(
            entry, TitanGrammar.key(TitanGrammar.GATE, "approvers"), gateCtx)) {
      gate.getApprovers().add(approver);
    }
    gate.setDependsOn(
        TypedNodeReader.stringList(
            entry, TitanGrammar.key(TitanGrammar.GATE, "dependsOn"), gateCtx));
    return gate;
  }

  private static PreconditionModel parsePrecondition(@NonNull JsonNode entry, int index) {
    ParseSupport.rejectUnknownKeys(entry, PRECONDITION_KEYS, "stages[" + index + "]");
    PreconditionModel pre = new PreconditionModel();
    // design/48 D2: `precondition`/`expression`/`dependsOn` are PRECONDITION grammar keys.
    String name =
        TypedNodeReader.requireString(
            entry,
            TitanGrammar.key(TitanGrammar.PRECONDITION, "precondition"),
            "stages[" + index + "]");
    pre.setName(name);
    pre.setId(slug(name));
    String preCtx = "precondition '" + name + "'";
    pre.setExpression(
        TypedNodeReader.requireString(
            entry, TitanGrammar.key(TitanGrammar.PRECONDITION, "expression"), preCtx));
    pre.setDependsOn(
        TypedNodeReader.stringList(
            entry, TitanGrammar.key(TitanGrammar.PRECONDITION, "dependsOn"), preCtx));
    return pre;
  }

  // ---- helpers ------------------------------------------------------------

  @NonNull
  private static Map<String, Object> toArgMap(@NonNull JsonNode obj) {
    Map<String, Object> args = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> e = it.next();
      args.put(e.getKey(), YAML.convertValue(e.getValue(), Object.class));
    }
    return args;
  }

  /** Derive a stable, DB-safe node id from a node name: lowercase, non-alphanumeric → '-'. */
  @NonNull
  static String slug(@NonNull String name) {
    String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    s = s.replaceAll("(^-+)|(-+$)", "");
    if (s.isEmpty()) {
      throw new PipelineParseException("node name '" + name + "' yields an empty id");
    }
    return s.length() > 64 ? s.substring(0, 64) : s;
  }
}

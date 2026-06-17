package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.parser.grammar.GrammarKey;
import io.adaptiq.titan.flow.parser.grammar.TitanGrammar;
import io.adaptiq.titan.flow.parser.grammar.TriggerGrammar;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Projects the single in-code grammar declaration ({@link TitanGrammar} plus {@link
 * TitanYamlParser}'s registered {@link StepScope}s) into the editor/tooling JSON Schema —
 * draft-2020-12, the {@code titan-pipeline.schema.json} resource (design/47 §4.2).
 *
 * <p>Before design/47 that schema was hand-authored and drifted from the parser. It is now a
 * <em>generated</em> projection: this class walks {@code TitanGrammar} for the non-scope contexts
 * and {@code TitanYamlParser.scopes()} for the scope keys, and emits a schema semantically
 * identical to the former hand-authored file — same {@code $schema}, {@code $id}, the {@code oneOf}
 * root/legacy-wrapper root form, the {@code $defs}.
 *
 * <p>{@link #main(String[])} writes the committed resource; {@link #generate()} returns the schema
 * in memory, which {@code TitanSchemaGenerationTest} uses as the drift guard. The {@code step}
 * {@code $def} stays <strong>open</strong> ({@code additionalProperties} is not {@code false}) —
 * the {@code StepHandler} SPI is extensible (design/42); the curated {@code sh}/{@code echo}/{@code
 * checkout}/{@code script} hint list is kept but the descriptor set is not generated.
 */
public final class TitanSchemaGenerator {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  /**
   * The committed schema resource, relative to the repository root. Moved here from {@code
   * titan-plugin/src/main/resources/} when Phase 3 (#340) deleted titan-plugin.
   */
  private static final String SCHEMA_RESOURCE_PATH =
      "titan-pipeline-model/src/main/resources/io/adaptiq/titan/schemas/titan-pipeline.schema.json";

  private TitanSchemaGenerator() {}

  /**
   * Build the full draft-2020-12 schema as a Jackson tree. Pure — no I/O; the drift-guard test
   * compares this against the committed resource.
   */
  @NonNull
  public static ObjectNode generate() {
    ObjectNode schema = NODES.objectNode();
    schema.put("$schema", TitanGrammar.SCHEMA_DIALECT);
    schema.put("$id", TitanGrammar.SCHEMA_ID);
    schema.put("title", TitanGrammar.SCHEMA_TITLE);
    schema.put("description", TitanGrammar.SCHEMA_DESCRIPTION);
    schema.set("oneOf", rootOneOf());
    schema.set("$defs", defs());
    return schema;
  }

  /**
   * The root {@code oneOf}: the canonical document-root form and the legacy {@code titan:} wrapper.
   */
  @NonNull
  private static ArrayNode rootOneOf() {
    ArrayNode oneOf = NODES.arrayNode();

    ObjectNode canonical = NODES.objectNode();
    canonical.put("description", "Canonical form — the pipeline body at the document root.");
    canonical.put("$ref", "#/$defs/titan");
    oneOf.add(canonical);

    ObjectNode wrapper = NODES.objectNode();
    wrapper.put("description", "Legacy wrapper form — the pipeline body nested under 'titan:'.");
    wrapper.put("type", "object");
    wrapper.putArray("required").add("titan");
    wrapper.put("additionalProperties", false);
    wrapper.putObject("properties").set("titan", refNode("titan"));
    oneOf.add(wrapper);

    return oneOf;
  }

  /** The {@code $defs} block — every reusable shape, in the hand-authored schema's order. */
  @NonNull
  private static ObjectNode defs() {
    ObjectNode defs = NODES.objectNode();
    defs.set("titan", titanDef());
    defs.set("stringOrList", TitanGrammar.stringOrListDef());
    defs.set("librariesMap", TitanGrammar.librariesMapDef());
    defs.set("parameter", objectDef("parameter", null, TitanGrammar.PARAMETER));
    defs.set("trigger", triggerDef());
    defs.set(
        "githubTrigger",
        objectDef(
            "githubTrigger",
            "A GitHub-webhook trigger value (issue #397) — the inner object under a "
                + "`github:` discriminator on a trigger entry.",
            TriggerGrammar.GITHUB_TRIGGER));
    defs.set(
        "gitlabTrigger",
        objectDef(
            "gitlabTrigger",
            "A GitLab-webhook trigger value (issue #1078) — the inner object under a "
                + "`gitlab:` discriminator on a trigger entry.",
            TriggerGrammar.GITLAB_TRIGGER));
    defs.set(
        "bitbucketTrigger",
        objectDef(
            "bitbucketTrigger",
            "A Bitbucket-webhook trigger value (issue #1079) — the inner object under a "
                + "`bitbucket:` discriminator on a trigger entry.",
            TriggerGrammar.BITBUCKET_TRIGGER));
    defs.set("node", nodeDef());
    defs.set("stage", stageDef());
    defs.set(
        "gate",
        objectDef(
            "gate",
            "A gate: a pause point in the DAG, typically a human approval.",
            TitanGrammar.GATE));
    defs.set(
        "precondition",
        objectDef(
            "precondition",
            "A precondition: an expression that must hold for the DAG to proceed past it.",
            TitanGrammar.PRECONDITION));
    defs.set("step", stepDef());
    defs.set("scriptStep", scriptStepDef());
    defs.set("credentialBinding", TitanGrammar.credentialBindingDef());
    defs.set("retryPolicy", TitanGrammar.retryPolicyDef());
    defs.set("whenCondition", io.adaptiq.titan.flow.parser.grammar.WhenGrammar.whenConditionDef());
    defs.set("matrix", TitanGrammar.matrixDef());
    defs.set("each", TitanGrammar.eachDef());
    defs.set("use", TitanGrammar.useDef());
    defs.set("includeEntry", TitanGrammar.includeEntryDef());
    defs.set("notifyHook", TitanGrammar.notifyHookDef());
    defs.set("buildRetention", TitanGrammar.buildRetentionDef());
    defs.set("concurrency", TitanGrammar.concurrencyDef());
    defs.set("priority", TitanGrammar.priorityDef());
    return defs;
  }

  /** The {@code titan} {@code $def} — the pipeline body, from {@link TitanGrammar#ROOT}. */
  @NonNull
  private static ObjectNode titanDef() {
    ObjectNode def = NODES.objectNode();
    def.put("type", "object");
    def.put("description", "The pipeline definition.");
    addRequired(def, TitanGrammar.ROOT);
    def.put("additionalProperties", false);
    def.set("properties", propertiesOf(TitanGrammar.ROOT));
    return def;
  }

  /**
   * A plain object {@code $def} from a single grammar context: {@code type}, optional {@code
   * description}, {@code required}, {@code additionalProperties: false}, {@code properties}.
   */
  @NonNull
  private static ObjectNode objectDef(
      @NonNull String name, String description, @NonNull List<GrammarKey> context) {
    ObjectNode def = NODES.objectNode();
    def.put("type", "object");
    if (description != null) {
      def.put("description", description);
    }
    addRequired(def, context);
    def.put("additionalProperties", false);
    def.set("properties", propertiesOf(context));
    return def;
  }

  /**
   * The {@code trigger} {@code $def} (issues #397, #1078) — a closed object that carries the
   * discriminator keys ({@code cron}, {@code github}, {@code gitlab}), with a {@code oneOf}
   * enforcing exactly one of them.
   *
   * <p>Mirrors the parser's "exactly one of 'cron', 'github' or 'gitlab'" check, so editor
   * validation and parser validation agree on the same shape.
   */
  @NonNull
  private static ObjectNode triggerDef() {
    ObjectNode def = NODES.objectNode();
    def.put("type", "object");
    def.put(
        "description",
        "A build trigger — exactly one of a 'cron' schedule (design/50), a 'github' "
            + "webhook (issue #397), a 'gitlab' webhook (issue #1078) or a 'bitbucket' "
            + "webhook (issue #1079).");
    def.put("additionalProperties", false);
    def.set("properties", propertiesOf(TriggerGrammar.TRIGGER));
    ArrayNode oneOf = def.putArray("oneOf");
    oneOf.add(discriminatorOnly("cron", "github", "gitlab", "bitbucket"));
    oneOf.add(discriminatorOnly("github", "cron", "gitlab", "bitbucket"));
    oneOf.add(discriminatorOnly("gitlab", "cron", "github", "bitbucket"));
    oneOf.add(discriminatorOnly("bitbucket", "cron", "github", "gitlab"));
    return def;
  }

  /**
   * A {@code oneOf} branch declaring a single discriminator as required while forbidding all other
   * listed discriminators. Pure helper.
   */
  @NonNull
  private static ObjectNode discriminatorOnly(@NonNull String required, @NonNull String... others) {
    ObjectNode branch = NODES.objectNode();
    branch.putArray("required").add(required);
    ArrayNode allOf = branch.putArray("allOf");
    for (String other : others) {
      ObjectNode not = NODES.objectNode();
      not.putObject("not").putArray("required").add(other);
      allOf.add(not);
    }
    return branch;
  }

  /** The {@code node} {@code $def} — the {@code oneOf} of stage / gate / precondition. */
  @NonNull
  private static ObjectNode nodeDef() {
    ObjectNode def = NODES.objectNode();
    def.put("description", "One DAG node: exactly one of a stage, a gate, or a precondition.");
    ArrayNode oneOf = def.putArray("oneOf");
    oneOf.add(refNode("stage"));
    oneOf.add(refNode("gate"));
    oneOf.add(refNode("precondition"));
    return def;
  }

  /**
   * The {@code stage} {@code $def} — the {@link TitanGrammar#STAGE_SKELETON} skeleton plus every
   * registered scope's stage-context {@link StepScope#schema()}, spliced in scope-registration
   * order (design/47 §4.2). All six scopes apply to a stage.
   */
  @NonNull
  private static ObjectNode stageDef() {
    ObjectNode def = NODES.objectNode();
    def.put("type", "object");
    def.put("description", "A stage: a named group of steps.");
    addRequired(def, TitanGrammar.STAGE_SKELETON);
    def.put("additionalProperties", false);
    ObjectNode props = def.putObject("properties");
    for (GrammarKey key : TitanGrammar.STAGE_SKELETON) {
      props.set(key.name(), key.toPropertyNode());
    }
    for (StepScope scope : TitanYamlParser.scopes()) {
      GrammarKey key = scope.schema();
      props.set(key.name(), key.toPropertyNode());
    }
    return def;
  }

  /**
   * The {@code step} {@code $def} — the {@link TitanGrammar#STEP_SKELETON} skeleton plus the
   * step-applicable scopes' {@link StepScope#stepSchema()}, then the curated open descriptor hints.
   * Stays <strong>open</strong>: no {@code additionalProperties: false} (design/42 — the descriptor
   * set is extensible).
   */
  @NonNull
  private static ObjectNode stepDef() {
    ObjectNode def = NODES.objectNode();
    def.put("type", "object");
    def.put(
        "description",
        "A step: exactly one descriptor key (sh, script, checkout, …) — plus an optional "
            + "'image' for the container it runs in. The descriptor set is open (the "
            + "StepHandler SPI is extensible); a few common ones are listed here for "
            + "assistance.");
    def.put("minProperties", 1);
    ObjectNode props = def.putObject("properties");
    for (GrammarKey key : TitanGrammar.STEP_SKELETON) {
      props.set(key.name(), key.toPropertyNode());
    }
    for (StepScope scope : TitanYamlParser.scopes()) {
      if (scope.appliesToStep()) {
        GrammarKey key = scope.stepSchema();
        props.set(key.name(), key.toPropertyNode());
      }
    }
    // The curated descriptor hints — design/47 §4.2: the descriptor set is open, so this is a
    // fixed assistance list, not a generated catalogue.
    ObjectNode sh = NODES.objectNode();
    sh.put("description", "Run a shell command on the agent.");
    ArrayNode shOneOf = sh.putArray("oneOf");
    shOneOf.add(NODES.objectNode().put("type", "string"));
    shOneOf.add(NODES.objectNode().put("type", "object"));
    shOneOf.add(NODES.objectNode().put("type", "null"));
    props.set("sh", sh);

    props.set(
        "echo",
        NODES
            .objectNode()
            .put("type", "string")
            .put("description", "Print a message to the build log."));

    ObjectNode checkout = NODES.objectNode();
    checkout.put("description", "Check out source for the build.");
    ArrayNode checkoutOneOf = checkout.putArray("oneOf");
    checkoutOneOf.add(NODES.objectNode().put("type", "object"));
    checkoutOneOf.add(NODES.objectNode().put("type", "null"));
    props.set("checkout", checkout);

    props.set("script", refNode("scriptStep"));
    return def;
  }

  /** The {@code scriptStep} {@code $def} — from {@link TitanGrammar#SCRIPT}. */
  @NonNull
  private static ObjectNode scriptStepDef() {
    ObjectNode def = NODES.objectNode();
    def.put("type", "object");
    def.put("description", "A 'script' step: a body of code run by a named runtime on the agent.");
    addRequired(def, TitanGrammar.SCRIPT);
    def.put("additionalProperties", false);
    def.set("properties", propertiesOf(TitanGrammar.SCRIPT));
    return def;
  }

  // ── helpers ──

  @NonNull
  private static ObjectNode propertiesOf(@NonNull List<GrammarKey> context) {
    ObjectNode props = NODES.objectNode();
    for (GrammarKey key : context) {
      props.set(key.name(), key.toPropertyNode());
    }
    return props;
  }

  private static void addRequired(@NonNull ObjectNode def, @NonNull List<GrammarKey> context) {
    List<String> required = TitanGrammar.requiredNames(context);
    if (!required.isEmpty()) {
      ArrayNode req = def.putArray("required");
      required.forEach(req::add);
    }
  }

  @NonNull
  private static ObjectNode refNode(@NonNull String def) {
    return NODES.objectNode().put("$ref", "#/$defs/" + def);
  }

  /**
   * Regenerate the committed {@code titan-pipeline.schema.json} (design/47 §5). The single optional
   * argument is the repository root; it defaults to the current working directory.
   */
  public static void main(String[] args) throws IOException {
    Path root = Path.of(args.length > 0 ? args[0] : ".");
    Path target = root.resolve(SCHEMA_RESOURCE_PATH);
    ObjectMapper mapper = new ObjectMapper();
    mapper.enable(SerializationFeature.INDENT_OUTPUT);
    String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(generate());
    Files.writeString(target, json + System.lineSeparator(), StandardCharsets.UTF_8);
    System.out.println("Wrote " + target.toAbsolutePath());
  }

  /** Read the committed schema resource as a parsed tree — used by the drift-guard test. */
  @NonNull
  public static JsonNode committedSchema(@NonNull Path repoRoot) throws IOException {
    Path target = repoRoot.resolve(SCHEMA_RESOURCE_PATH);
    return new ObjectMapper().readTree(Files.readString(target, StandardCharsets.UTF_8));
  }
}

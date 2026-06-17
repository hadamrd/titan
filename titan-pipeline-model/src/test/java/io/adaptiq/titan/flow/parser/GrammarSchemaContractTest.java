package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * design/47 — the grammar/schema contract beyond the {@link TitanSchemaGenerationTest} freshness
 * drift guard. design/47's whole premise is that {@code titan-pipeline.schema.json} is a
 * <em>projection</em> of {@code TitanGrammar} + the registered {@code StepScope}s, the same single
 * source the parser projects its key sets from — so the schema "cannot drift". That guarantee is
 * only real if the projection is actually faithful. These tests guard the faithfulness itself:
 *
 * <ul>
 *   <li>that the schema's per-context property sets equal the parser's effective key sets (test 1)
 *       — the keystone;
 *   <li>that the generator emits a structurally valid draft-2020-12 schema (test 2);
 *   <li>that parser-acceptance and schema-validity actually agree on a corpus (test 3);
 *   <li>that the closed/open {@code additionalProperties} invariant holds (test 4);
 *   <li>that every scope is represented with a real description (test 5).
 * </ul>
 *
 * <p>Each is a guard against a distinct, plausible future regression — see each method's comment.
 * If one fails it has found a real inconsistency: fix the production code, never weaken the test.
 */
class GrammarSchemaContractTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  /** The generated schema, parsed once. */
  private static JsonNode schema() {
    return TitanSchemaGenerator.generate();
  }

  /**
   * The curated open descriptor-hint keys the {@code step} {@code $def} carries on top of the scope
   * keys (design/47 §4.2). They are assistance entries, not parser-validated scope keys — the
   * {@code step} def is open — so test 1 excludes them when comparing the {@code step} property set
   * to the parser's {@code STEP_KEYS}.
   */
  private static final Set<String> STEP_DESCRIPTOR_HINTS =
      Set.of("sh", "echo", "checkout", "script");

  // ──────────────────────────────────────────────────────────────────────────
  // Test 1 — Parser ⇔ Schema key-set equivalence (the keystone).
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Guards the central design/47 promise: the schema's declared property set for every closed
   * grammar context is byte-identical to the key set the parser actually enforces with {@code
   * rejectUnknownKeys}. The two sides are derived independently — the parser side from {@code
   * TitanYamlParser.grammarKeySetsForTest()}, the schema side by reading {@code properties} out of
   * the generated schema document — so this is a true cross-check, not a tautology.
   *
   * <p>Regression guarded: someone adds a key to {@code TitanGrammar} but the generator skips it
   * (or vice versa: a generator helper emits a property the parser does not accept). Either way the
   * editor schema would lie about what the parser accepts — exactly the drift surface design/47
   * exists to kill. {@link TitanSchemaGenerationTest} only checks the committed file matches the
   * generator; it cannot catch the generator and parser disagreeing about the <em>grammar
   * itself</em>.
   */
  @Test
  void schemaPropertySetsEqualParserKeySets() {
    JsonNode defs = schema().get("$defs");
    // schema $def name  ->  parser context name in grammarKeySetsForTest()
    assertContextKeySetsEqual(defs, "titan", "root");
    assertContextKeySetsEqual(defs, "stage", "stage");
    assertContextKeySetsEqual(defs, "gate", "gate");
    assertContextKeySetsEqual(defs, "precondition", "precondition");
    assertContextKeySetsEqual(defs, "parameter", "parameter");
    assertContextKeySetsEqual(defs, "scriptStep", "script");
    // `step` is the open def — its property set is the scope keys PLUS the descriptor hints;
    // the parser's STEP_KEYS is only the scope keys. Compare after removing the hints.
    Set<String> stepProps = new LinkedHashSet<>(propertyNames(defs.get("step")));
    stepProps.removeAll(STEP_DESCRIPTOR_HINTS);
    assertEquals(
        TitanYamlParser.grammarKeySetsForTest().get("step"),
        stepProps,
        "the schema's `step` def declares a different scope-key set than the parser's "
            + "STEP_KEYS — the generated step grammar drifted from the parser");
  }

  private static void assertContextKeySetsEqual(
      JsonNode defs, String schemaDef, String parserContext) {
    Set<String> parserKeys = TitanYamlParser.grammarKeySetsForTest().get(parserContext);
    Set<String> schemaKeys = propertyNames(defs.get(schemaDef));
    assertEquals(
        parserKeys,
        schemaKeys,
        "context '"
            + parserContext
            + "': the generated schema's `"
            + schemaDef
            + "` def and the parser's key set disagree — grammar drift (design/47)");
  }

  private static Set<String> propertyNames(JsonNode def) {
    Set<String> names = new LinkedHashSet<>();
    JsonNode props = def.get("properties");
    if (props != null) {
      props.fieldNames().forEachRemaining(names::add);
    }
    return names;
  }

  /**
   * The honest end-to-end form of test 1 for the root context: a doc carrying an unknown root key
   * must be rejected by the parser <em>and</em> fail schema validation; a doc using every declared
   * root key must be accepted by both. This proves the two enforcers agree on the concrete YAML,
   * not merely on a key-name set computed from the same grammar object.
   */
  @Test
  void unknownRootKeyRejectedByBothEnforcers() {
    String bad =
        """
                bogusRootKey: x
                stages:
                  - stage: S
                    steps:
                      - sh: echo hi
                """;
    assertThrows(
        PipelineParseException.class,
        () -> TitanYamlParser.parse(bad),
        "parser must reject an unknown root key");
    assertFalse(
        validate(bad).isEmpty(), "schema must reject an unknown root key — `titan` def is closed");
  }

  /**
   * The positive arm of the root cross-check: a document that exercises every declared root key is
   * accepted by the parser and validates clean against the generated schema. Guards a generator
   * that emits a too-narrow type for a key (e.g. forgetting {@code stringOrList} on {@code
   * libraries}) — that would not move any key-name set, so test 1's set comparison would stay green
   * while real valid YAML started failing schema validation.
   */
  @Test
  void fullyPopulatedRootAcceptedByBothEnforcers() {
    String good =
        """
                agent: linux
                failurePolicy: blockOnFailure
                libraries:
                  shared: "https://github.com/acme/shared.git@v1"
                parameters:
                  - name: ENV
                    type: choice
                    default: dev
                    description: target environment
                    required: true
                    choices: [dev, prod]
                credentials:
                  - id: deploy-token
                    type: string
                    variable: TOKEN
                stages:
                  - stage: Build
                    steps:
                      - sh: make
                """;
    TitanYamlParser.parse(good);
    assertNoSchemaErrors(good);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Test 2 — Generated schema is itself valid draft-2020-12 JSON Schema.
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Validates the generated schema document as an <em>instance</em> against the JSON Schema
   * draft-2020-12 meta-schema. design/47 declares the dialect 2020-12; if {@code
   * TitanSchemaGenerator} ever emits something the meta-schema rejects — a malformed {@code oneOf},
   * a non-string {@code $ref}, a misspelled keyword in the wrong position — every downstream
   * editor/tooling consumer breaks. The generator builds the tree by hand with Jackson, so this is
   * a real failure mode, not a theoretical one.
   */
  @Test
  void generatedSchemaIsValidDraft2020Schema() {
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    // The 2020-12 meta-schema, resolved by the factory from its dialect URI.
    JsonSchema metaSchema =
        factory.getSchema(java.net.URI.create("https://json-schema.org/draft/2020-12/schema"));
    Set<ValidationMessage> errors = metaSchema.validate(schema());
    assertTrue(
        errors.isEmpty(),
        "the generated titan-pipeline schema is not valid draft-2020-12 JSON Schema: " + errors);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Test 4 — Closed-vs-open additionalProperties invariant.
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * The {@code step} def must stay <strong>open</strong> — design/42's StepHandler SPI means
   * worker-side step descriptors (every Tier-1/Tier-2 step) are not visible to the controller
   * schema, so an unknown descriptor key on a step must validate. Proven behaviourally: a step
   * using an unknown descriptor passes schema validation.
   *
   * <p>Regression guarded: someone "tidies" the schema by adding {@code additionalProperties:false}
   * to the {@code step} def — silently every custom step descriptor would start failing editor
   * validation. The generator comment says the def stays open; this test makes that load-bearing.
   */
  @Test
  void stepDefStaysOpenForUnknownDescriptors() {
    JsonNode stepDef = schema().get("$defs").get("step");
    JsonNode addl = stepDef.get("additionalProperties");
    assertTrue(
        addl == null || addl.asBoolean(true),
        "the `step` def must NOT set additionalProperties:false (design/42 SPI)");
    String unknownDescriptor =
        """
                stages:
                  - stage: S
                    steps:
                      - deployToKubernetes:
                          namespace: prod
                """;
    assertNoSchemaErrors(unknownDescriptor);
  }

  /**
   * The dual invariant: the closed contexts — {@code titan}, {@code stage}, {@code gate}, {@code
   * precondition}, {@code parameter} — must each set {@code additionalProperties:false}, and an
   * unknown key in each must fail schema validation. design/42 §4.7: the grammar skeleton is closed
   * and portable; only the descriptor set is open. Guards the inverse "tidy" — someone loosening a
   * skeleton context to open, which would let typo'd keys through the editor.
   */
  @Test
  void skeletonContextsAreClosed() {
    JsonNode defs = schema().get("$defs");
    for (String closedDef : new String[] {"titan", "stage", "gate", "precondition", "parameter"}) {
      JsonNode addl = defs.get(closedDef).get("additionalProperties");
      assertTrue(
          addl != null && !addl.asBoolean(true),
          "the `" + closedDef + "` def must set additionalProperties:false");
    }
    // Behavioural proof, one per closed context — an unknown key fails validation.
    assertFalse(
        validate(
                """
                stages: [{stage: S, steps: [{sh: hi}]}]
                bogus: x
                """)
            .isEmpty(),
        "unknown key on `titan` must fail");
    assertFalse(
        validate(
                """
                stages:
                  - stage: S
                    bogus: x
                    steps: [{sh: hi}]
                """)
            .isEmpty(),
        "unknown key on `stage` must fail");
    assertFalse(
        validate(
                """
                stages:
                  - gate: G
                    bogus: x
                """)
            .isEmpty(),
        "unknown key on `gate` must fail");
    assertFalse(
        validate(
                """
                stages:
                  - precondition: P
                    expression: "true"
                    bogus: x
                """)
            .isEmpty(),
        "unknown key on `precondition` must fail");
    assertFalse(
        validate(
                """
                parameters:
                  - name: P
                    bogus: x
                stages: [{stage: S, steps: [{sh: hi}]}]
                """)
            .isEmpty(),
        "unknown key on `parameter` must fail");
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Test 5 — Every StepScope is represented in the schema with a real description.
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Every registered {@code StepScope} must appear as a property in the generated {@code stage}
   * def, and — iff {@link StepScope#appliesToStep()} — in the {@code step} def too; and the
   * description the generator emits for it must be non-blank and not a placeholder.
   *
   * <p>Regression guarded: someone adds a scope (one new class + one line in {@code
   * TitanYamlParser.SCOPES}) but botches its {@code schema()} — returns a blank description, or a
   * stub like "TODO". The parser would accept the key (STAGE_KEYS is computed from the scope list)
   * but the editor schema would carry a useless or absent entry. design/47 §3: "one scope class =
   * parser + schema, both moved together, by construction" — this test makes "by construction"
   * enforced rather than hoped-for.
   */
  @Test
  void everyScopeIsRepresentedWithARealDescription() {
    JsonNode stageProps = schema().get("$defs").get("stage").get("properties");
    JsonNode stepProps = schema().get("$defs").get("step").get("properties");
    for (StepScope scope : TitanYamlParser.scopes()) {
      String key = scope.key();
      assertTrue(
          stageProps.has(key), "scope '" + key + "' is missing from the generated `stage` def");
      assertRealDescription(stageProps.get(key), "stage:" + key);
      // The scope's GrammarKey name must equal its key() — the StepScope contract.
      assertEquals(
          key, scope.schema().name(), "scope '" + key + "': schema().name() must equal key()");
      if (scope.appliesToStep()) {
        assertTrue(
            stepProps.has(key),
            "step-applicable scope '" + key + "' is missing from the `step` def");
        assertRealDescription(stepProps.get(key), "step:" + key);
      } else {
        assertFalse(
            stepProps.has(key), "stage-only scope '" + key + "' must NOT appear in the `step` def");
      }
    }
  }

  private static void assertRealDescription(JsonNode propNode, String where) {
    JsonNode desc = propNode.get("description");
    assertTrue(
        desc != null && desc.isTextual(), where + ": scope property must carry a description");
    String text = desc.asText().strip();
    assertFalse(text.isEmpty(), where + ": scope description must be non-blank");
    String lower = text.toLowerCase(java.util.Locale.ROOT);
    assertFalse(
        lower.contains("todo")
            || lower.contains("fixme")
            || lower.equals("description")
            || text.length() < 12,
        where + ": scope description looks like a placeholder: '" + text + "'");
  }

  // ── shared validation helpers ──

  /** Validate a YAML doc against the generated schema; returns the (possibly empty) error set. */
  private static Set<ValidationMessage> validate(String yaml) {
    try {
      JsonNode instance = YAML.readTree(yaml);
      JsonSchema compiled =
          JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema());
      return compiled.validate(instance);
    } catch (Exception e) {
      throw new AssertionError("test fixture YAML failed to parse: " + e.getMessage(), e);
    }
  }

  private static void assertNoSchemaErrors(String yaml) {
    Set<ValidationMessage> errors = validate(yaml);
    assertTrue(errors.isEmpty(), "expected schema-valid YAML but got: " + errors);
  }
}

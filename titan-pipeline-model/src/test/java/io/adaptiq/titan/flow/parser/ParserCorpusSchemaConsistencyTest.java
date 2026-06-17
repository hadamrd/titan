package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * design/47 — the <strong>independent oracle</strong> cross-check.
 *
 * <p>The weakness of {@code GrammarSchemaContractTest.schemaPropertySetsEqualParserKeySets} is that
 * both sides it compares are projected from the same {@code TitanGrammar} object — if the grammar
 * is wrong, both projections are wrong identically and the test still passes (thin-air vs
 * thin-air). This test removes that weakness: its corpus is a set of <em>golden</em> Titan YAML
 * documents <strong>hand-authored by judgment</strong> — drawn verbatim from the YAML examples in
 * design/29, design/41, design/42 and design/44, and from realistic pipelines a user would write —
 * NOT generated, NOT derived from {@code TitanGrammar}.
 *
 * <p>Because the goldens are authored truth <em>independent of the single source</em>, agreement
 * here is a genuine cross-check: if {@code TitanGrammar} (and therefore both the parser and the
 * generated schema) drifted away from what design/29 actually specifies, a golden would catch it —
 * the schema-property-set test could not, because it never looks outside the grammar object.
 *
 * <p>Each golden is decided unambiguously valid or invalid by a human reading the design docs. For
 * a valid golden: the parser must accept it AND the generated schema must validate it clean. For an
 * invalid golden (each fails for a <em>structural</em> reason a JSON Schema can see — an unknown
 * key, a missing required key): both enforcers must reject it.
 */
class ParserCorpusSchemaConsistencyTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  /** The generated schema, compiled once. */
  private static final JsonSchema SCHEMA =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
          .getSchema(TitanSchemaGenerator.generate());

  private static final String GOLDEN_ROOT = "/io/adaptiq/titan/flow/corpus/golden/";

  /** Hand-authored documents that are unambiguously valid Titan (design docs + realistic). */
  private static final List<String> VALID_GOLDENS =
      List.of(
          "valid-design29-canonical.yml",
          "valid-design29-legacy-wrapper.yml",
          "valid-design41-sshagent.yml",
          "valid-design42-credential-scoping.yml",
          "valid-design44-retry.yml",
          "valid-realistic-parameters.yml",
          "valid-realistic-bare-checkout.yml");

  /** Hand-authored documents that are unambiguously invalid for a structural reason. */
  private static final List<String> INVALID_GOLDENS =
      List.of(
          "invalid-typo-stagess.yml",
          "invalid-gate-typo-key.yml",
          "invalid-precondition-no-expression.yml",
          "invalid-script-missing-body.yml",
          "invalid-credential-binding-unknown-key.yml");

  static Stream<Arguments> validGoldens() {
    return VALID_GOLDENS.stream().map(Arguments::of);
  }

  static Stream<Arguments> invalidGoldens() {
    return INVALID_GOLDENS.stream().map(Arguments::of);
  }

  /**
   * Hunts: a grammar that drifted from the design spec in the <em>permissive</em> direction. A
   * golden authored as valid-per-design that the schema rejects means the generated schema (and
   * thus {@code TitanGrammar}) is stricter than design/29 actually says — the editor would red-flag
   * a legitimate pipeline.
   */
  @ParameterizedTest(name = "golden valid: {0}")
  @MethodSource("validGoldens")
  void parserAcceptsAndSchemaValidatesGoldenValid(String golden) {
    String yaml = load(golden);
    TitanYamlParser.parse(yaml); // must not throw
    Set<ValidationMessage> errors = validate(yaml);
    assertTrue(
        errors.isEmpty(),
        golden
            + ": a design-doc-authored VALID pipeline — parser accepts it but the "
            + "generated schema rejects it. The schema/grammar drifted stricter than "
            + "the design spec. Schema errors: "
            + errors);
  }

  /**
   * Hunts: a grammar that drifted in the <em>restrictive</em> direction has a dual failure — a
   * structurally-invalid golden that one enforcer waves through. Both the parser and the schema
   * must reject every invalid golden; a divergence is a real editor-contract gap.
   */
  @ParameterizedTest(name = "golden invalid: {0}")
  @MethodSource("invalidGoldens")
  void parserRejectsAndSchemaRejectsGoldenInvalid(String golden) {
    String yaml = load(golden);
    boolean parserRejected;
    try {
      TitanYamlParser.parse(yaml);
      parserRejected = false;
    } catch (PipelineParseException e) {
      parserRejected = true;
    }
    assertTrue(
        parserRejected,
        golden + ": a hand-authored structurally-invalid pipeline the parser accepted");
    assertFalse(
        validate(yaml).isEmpty(),
        golden
            + ": a hand-authored structurally-invalid pipeline the schema accepted — "
            + "the editor would green-light YAML the engine then rejects");
  }

  // ── helpers ──

  private static Set<ValidationMessage> validate(String yaml) {
    try {
      JsonNode instance = YAML.readTree(yaml);
      return SCHEMA.validate(instance);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String load(String golden) {
    String resource = GOLDEN_ROOT + golden;
    try (InputStream in = ParserCorpusSchemaConsistencyTest.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("missing golden resource: " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

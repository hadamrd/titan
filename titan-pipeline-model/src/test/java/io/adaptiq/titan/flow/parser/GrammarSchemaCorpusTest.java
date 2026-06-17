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
 * design/47 — the fixture-corpus cross-check: for a corpus of Titan YAML documents, the parser's
 * accept/reject verdict and the generated schema's valid/invalid verdict must
 * <strong>agree</strong>.
 *
 * <p>This is the strongest possible form of the design/47 guarantee. {@link
 * TitanSchemaGenerationTest} proves the committed file matches the generator; {@link
 * GrammarSchemaContractTest} proves the key-name sets line up. This test proves the two enforcers
 * behave identically on <em>concrete documents</em> — the property a pipeline author actually
 * depends on when the editor says "valid".
 *
 * <p>Scope honesty (design/47, the corpus note): JSON Schema can only see <em>structural</em> shape
 * — known/unknown keys, required keys, node-kind/type. Semantic checks the parser also does — DAG
 * cycles, dangling {@code dependsOn} targets, the {@code sshAgent}-on-non-{@code sh} restriction,
 * the credential-type allowlist — are out of a schema's reach <em>by design</em>. The corpus
 * therefore contains only structurally-decidable documents: every {@code invalid/} fixture fails
 * for a reason a schema can see. Semantic-only failures are deliberately excluded; they are
 * exercised by {@code PipelineDagValidatorTest}, {@code SshAgentParsingTest} et al.
 */
class GrammarSchemaCorpusTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  /** The generated schema, compiled once for the whole corpus run. */
  private static final JsonSchema SCHEMA =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
          .getSchema(TitanSchemaGenerator.generate());

  /** Corpus resources, relative to this package's {@code corpus/} resource directory. */
  private static final String CORPUS_ROOT = "/io/adaptiq/titan/flow/corpus/";

  private static final List<String> VALID_FIXTURES =
      List.of(
          "valid/01-root-form-minimal.yml",
          "valid/02-legacy-titan-wrapper.yml",
          "valid/03-all-node-kinds.yml",
          "valid/04-every-scope-shorthand.yml",
          "valid/05-every-scope-object-form.yml",
          "valid/06-script-step-and-parameters.yml",
          // timeout is a registered TimeoutScope key in STAGE_KEYS — a bare-integer (seconds)
          // stage-level timeout is legal; reclassified from invalid/02-unknown-stage-key.yml.
          "valid/07-stage-level-timeout.yml",
          // step-level when: — per-step conditional execution (GH #240).
          "valid/08-step-level-when.yml",
          // env: injection at pipeline, stage, and step scope (GH #239).
          "valid/09-env-scoping.yml");

  private static final List<String> INVALID_FIXTURES =
      List.of(
          "invalid/01-unknown-root-key.yml",
          // invalid/02 was invalid/02-unknown-stage-key.yml — reclassified to valid/07:
          // `timeout` is registered by TimeoutScope and IS a valid stage key.
          "invalid/03-missing-required-stages.yml",
          "invalid/04-missing-required-expression.yml",
          "invalid/05-wrong-type-stages.yml",
          "invalid/06-unknown-credential-binding-key.yml",
          "invalid/07-unknown-script-key.yml");

  static Stream<Arguments> validFixtures() {
    return VALID_FIXTURES.stream().map(Arguments::of);
  }

  static Stream<Arguments> invalidFixtures() {
    return INVALID_FIXTURES.stream().map(Arguments::of);
  }

  /**
   * Every {@code valid/} fixture must be accepted by the parser <em>and</em> validate clean against
   * the generated schema. A divergence here means the schema is stricter than the parser — the
   * editor would red-flag a pipeline the engine happily bakes, eroding trust in the tooling
   * contract design/47 exists to make trustworthy.
   */
  @ParameterizedTest(name = "valid: {0}")
  @MethodSource("validFixtures")
  void parserAcceptsAndSchemaValidatesEveryValidFixture(String fixture) {
    String yaml = load(fixture);
    // Parser side — must not throw.
    TitanYamlParser.parse(yaml);
    // Schema side — must produce zero validation errors.
    Set<ValidationMessage> errors = validate(yaml);
    assertTrue(
        errors.isEmpty(),
        fixture
            + ": parser accepts it but the generated schema rejects it — the schema "
            + "is stricter than the parser. Schema errors: "
            + errors);
  }

  /**
   * Every {@code invalid/} fixture fails for a structural reason — and so must be rejected by the
   * parser <em>and</em> by the generated schema. A divergence here means the schema is more
   * permissive than the parser — the editor would green-light YAML the engine then rejects at bake
   * time, the worst failure mode for an editor contract.
   */
  @ParameterizedTest(name = "invalid: {0}")
  @MethodSource("invalidFixtures")
  void parserRejectsAndSchemaRejectsEveryInvalidFixture(String fixture) {
    String yaml = load(fixture);
    // Parser side — must throw.
    boolean parserRejected;
    try {
      TitanYamlParser.parse(yaml);
      parserRejected = false;
    } catch (PipelineParseException e) {
      parserRejected = true;
    }
    assertTrue(
        parserRejected,
        fixture + ": expected the parser to reject this structurally-invalid fixture");
    // Schema side — must produce at least one validation error.
    assertFalse(
        validate(yaml).isEmpty(),
        fixture
            + ": the parser rejects it but the generated schema accepts it — the "
            + "schema is more permissive than the parser (a structural gap)");
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

  private static String load(String fixture) {
    String resource = CORPUS_ROOT + fixture;
    try (InputStream in = GrammarSchemaCorpusTest.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("missing corpus resource: " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

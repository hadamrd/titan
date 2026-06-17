package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PipelineModel;
import org.junit.jupiter.api.Test;

/**
 * design/48 — the focused parser-strictness regression guard. Independent of the {@code
 * GrammarSchemaDivergenceTest} parser/schema divergence harness, this class pins the design/48
 * contract directly on {@link TitanYamlParser}:
 *
 * <ul>
 *   <li>a grammar key that is <em>present-and-null</em> is a located error (D3);
 *   <li>a grammar key whose value is the <em>wrong JSON type</em> is a located, value-echoing error
 *       — never a coercion (D4) — covered on a string key, a boolean key, a string-or-list key, the
 *       integer-or-object {@code retry} key, and a nested {@code backoff} string field;
 *   <li>and the boundary strictness must <strong>not</strong> over-reach: a genuinely
 *       <em>absent</em> key still parses, {@code parameters[].default} stays any-typed, and a
 *       no-argument step ({@code - checkout:} with a null value) still parses — step descriptor
 *       values are out of design/48's scope (D5).
 * </ul>
 *
 * <p>Each test names the design/48 clause it guards. A test that only checked "it threw" would be
 * too weak; the type-mismatch tests assert the message names the key, the expected type and echoes
 * the offending value, proving the message teaches.
 */
class ParserStrictnessTest {

  // ════════════════════════════════════════════════════════════════════════
  // D3 — present-and-null on a grammar key is a located error.
  // ════════════════════════════════════════════════════════════════════════

  /**
   * design/48 D3: a grammar key that is <em>present</em> must carry a value of its declared type;
   * an explicit {@code null} is never that type. Before design/48 the parser collapsed {@code
   * agent: null} to "key absent" via {@code optText}. The message must name the key and explain the
   * fix (omit it, or give it a value).
   */
  @Test
  void presentAndNullGrammarKeyIsRejectedWithLocatedMessage() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    "agent: null\nstages:\n  - stage: S\n    steps:\n      - sh: hi\n"));
    assertTrue(e.getMessage().contains("'agent'"), e.getMessage());
    assertTrue(e.getMessage().contains("present but null"), e.getMessage());
  }

  // ════════════════════════════════════════════════════════════════════════
  // D4 — type mismatch is a located, value-echoing error, never a coercion.
  // ════════════════════════════════════════════════════════════════════════

  /**
   * design/48 D4: a key declared {@code string} ({@code agent}) rejects an array value rather than
   * coercing it via {@code asText()} to {@code ""}. The message names the key, the expected type
   * and echoes the offending shape.
   */
  @Test
  void stringKeyRejectsAnArrayWithTypeNamingMessage() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    "agent: [a, b]\nstages:\n  - stage: S\n    steps:\n      - sh: hi\n"));
    assertTrue(e.getMessage().contains("'agent'"), e.getMessage());
    assertTrue(e.getMessage().contains("a string"), e.getMessage());
    assertTrue(e.getMessage().contains("an array"), e.getMessage());
  }

  /**
   * design/48 D4: a key declared {@code boolean} ({@code requiresApproval}) rejects a string rather
   * than coercing it via {@code JsonNode.asBoolean()}. A fat-fingered {@code "yes"} must fail
   * loudly — it flips a deployment approval gate. The message echoes the offending string.
   */
  @Test
  void booleanKeyRejectsAStringWithValueEchoingMessage() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () -> TitanYamlParser.parse("stages:\n  - gate: G\n    requiresApproval: \"yes\"\n"));
    assertTrue(e.getMessage().contains("'requiresApproval'"), e.getMessage());
    assertTrue(e.getMessage().contains("boolean (true or false)"), e.getMessage());
    assertTrue(e.getMessage().contains("the string \"yes\""), e.getMessage());
  }

  /**
   * design/48 D4: a key declared {@code stringOrList} ({@code dependsOn}) rejects a number rather
   * than coercing it to text via {@code stringList}. The message names the key, the string-or-list
   * type and echoes the offending number.
   */
  @Test
  void stringOrListKeyRejectsANumberWithValueEchoingMessage() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    "stages:\n  - stage: S\n    dependsOn: 5\n    steps:\n      - sh: hi\n"));
    assertTrue(e.getMessage().contains("'dependsOn'"), e.getMessage());
    assertTrue(e.getMessage().contains("a string or a list of strings"), e.getMessage());
    assertTrue(e.getMessage().contains("the number 5"), e.getMessage());
  }

  /**
   * design/48 D4: the {@code retry} key is declared the {@code retryPolicy} {@code oneOf} — an
   * integer shorthand or an object, never a string. A string value must be a located error, not a
   * coercion. The message names {@code retry} and explains the two valid forms.
   */
  @Test
  void integerOrObjectRetryKeyRejectsAStringWithLocatedMessage() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    "stages:\n  - stage: S\n    steps:\n      - sh: hi\n        retry: soon\n"));
    assertTrue(e.getMessage().contains("'retry'"), e.getMessage());
    assertTrue(e.getMessage().contains("integer"), e.getMessage());
    assertTrue(e.getMessage().contains("object"), e.getMessage());
    assertTrue(e.getMessage().contains("the string \"soon\""), e.getMessage());
  }

  /**
   * design/48 §2: the strictness reaches the nested {@code backoff} object — its {@code initial}
   * field is declared a duration string. A wrong-typed (number) {@code initial} must fail with a
   * located, type-naming message before the {@code s/m/h} duration grammar is even consulted.
   */
  @Test
  void nestedBackoffStringFieldRejectsAWrongType() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    "stages:\n  - stage: S\n    steps:\n      - sh: hi\n        retry:\n"
                        + "          maxAttempts: 2\n          backoff:\n"
                        + "            initial: 10\n"));
    assertTrue(e.getMessage().contains("'initial'"), e.getMessage());
    assertTrue(e.getMessage().contains("string"), e.getMessage());
    assertTrue(e.getMessage().contains("the number 10"), e.getMessage());
  }

  // ════════════════════════════════════════════════════════════════════════
  // The boundary — strictness must NOT over-reach.
  // ════════════════════════════════════════════════════════════════════════

  /**
   * design/48 D3: "absent" still means absent. A genuinely missing optional grammar key ({@code
   * agent} omitted entirely) is fine — design/48 rejects present-and-null, never an absent key.
   * This guards against the strictness over-reaching into required-everywhere.
   */
  @Test
  void genuinelyAbsentOptionalKeyStillParses() {
    PipelineModel model =
        assertDoesNotThrow(
            () -> TitanYamlParser.parse("stages:\n  - stage: S\n    steps:\n      - sh: hi\n"));
    assertEquals(1, model.getStages().size());
  }

  /**
   * design/48 D2: {@code parameters[].default} is declared {@link
   * io.adaptiq.titan.flow.parser.grammar.GrammarType#ANY any} — it is genuinely any-typed. The
   * typed reader applies no type check to it: a string, a number, a boolean, a list and an object
   * are all accepted. Strictness must not narrow a deliberately-untyped key.
   */
  @Test
  void parameterDefaultStaysAnyTyped() {
    for (String def : new String[] {"a-string", "42", "true", "[a, b]", "{k: v}"}) {
      assertDoesNotThrow(
          () ->
              TitanYamlParser.parse(
                  "parameters:\n  - name: P\n    default: "
                      + def
                      + "\n"
                      + "stages:\n  - stage: S\n    steps:\n      - sh: hi\n"),
          "parameters[].default must accept any-typed value: " + def);
    }
  }

  /**
   * design/48 D5: step <em>descriptor</em> argument values are out of scope — the parser carries no
   * per-step knowledge, argument typing is the worker-side {@code StepDescriptor} contract. A
   * no-argument step ({@code - checkout:} with a null value) must still parse: the {@code null} is
   * a descriptor value, not a grammar-key value, so D3's present-and-null rule does not apply.
   */
  @Test
  void noArgumentStepWithNullDescriptorValueStillParses() {
    PipelineModel model =
        assertDoesNotThrow(
            () -> TitanYamlParser.parse("stages:\n  - stage: S\n    steps:\n      - checkout:\n"));
    assertEquals(1, model.getStages().size());
  }
}

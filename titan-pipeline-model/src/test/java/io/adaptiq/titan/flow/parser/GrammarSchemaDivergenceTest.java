package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * design/47 — the <strong>crack hunter</strong>. An adversarial corpus whose explicit mission is to
 * FIND documents where {@code TitanYamlParser} and the generated JSON Schema DISAGREE.
 *
 * <p>design/47's whole claim is that the parser and the schema are "faithful projections of one
 * source". This test attacks that claim. For every adversarial case it runs the parser ({@code
 * PipelineParseException} → "rejected") AND validates the document against the generated schema
 * (networknt 2020-12 validator → "rejected"/"accepted"), and asserts the two verdicts
 * <em>match</em>.
 *
 * <p>Most cases are {@link #agreeingCases()} — the parser and schema must reach the same verdict; a
 * divergence there is a real bug. A few cases are {@link #knownGapCases()} — documents where the
 * two enforcers genuinely <em>cannot</em> agree because of a limit of JSON Schema (or a deliberate
 * parser-leniency choice that design/47 puts out of scope). Each known-gap carries a precise
 * comment explaining the gap; for those the test asserts only the parser side and records the
 * schema verdict for visibility. A known-gap is never deleted and never weakens the suite.
 *
 * <p>One real parser bug was found and fixed by this pass — the legacy-wrapper sibling case (a
 * top-level key alongside {@code titan:} was silently ignored). {@link #wrapperSiblingIsRejected()}
 * is its focused regression test.
 */
class GrammarSchemaDivergenceTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private static final JsonSchema SCHEMA =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
          .getSchema(TitanSchemaGenerator.generate());

  private enum Verdict {
    ACCEPTED,
    REJECTED
  }

  /**
   * One adversarial case: a name, the YAML, whether the parser is expected to accept it, and — for
   * a {@code REJECTED} case — substrings the {@code PipelineParseException} message must contain.
   * design/48 promises located, value-echoing messages; an empty fragment list means the message is
   * not asserted (legacy cases / accepted cases).
   */
  private record Case(String name, String yaml, Verdict expectedParser, List<String> msgParts) {
    @Override
    public String toString() {
      return name;
    }
  }

  private static Case rejected(String name, String yaml) {
    return new Case(name, yaml, Verdict.REJECTED, List.of());
  }

  /**
   * A {@code REJECTED} case that additionally asserts the parser's error message — design/48's
   * promise of a located, type-naming, value-echoing message. Used by the design/48-promoted cases
   * so a relabel is not enough: the message must teach.
   */
  private static Case rejectedWithMessage(String name, String yaml, String... msgParts) {
    return new Case(name, yaml, Verdict.REJECTED, List.of(msgParts));
  }

  private static Case accepted(String name, String yaml) {
    return new Case(name, yaml, Verdict.ACCEPTED, List.of());
  }

  // ════════════════════════════════════════════════════════════════════════
  // The agreeing corpus — parser verdict and schema verdict MUST be identical.
  // Any divergence on one of these is a real parser/schema-faithfulness bug.
  // ════════════════════════════════════════════════════════════════════════

  static Stream<Arguments> agreeingCases() {
    return AGREEING.stream().map(Arguments::of);
  }

  private static final List<Case> AGREEING =
      List.of(

          // ── Wrong value type for every key. The schema types each key; the parser
          // structurally checks each. Both must reject a value of the wrong JSON type. ──
          rejected("stages-as-object", "stages: {a: 1}\n"),
          rejected("stages-as-string", "stages: nope\n"),
          rejected(
              "parameters-as-object",
              "parameters: {a: 1}\nstages:\n  - stage: S\n    steps:\n      - sh: hi\n"),
          rejected("steps-as-string", "stages:\n  - stage: S\n    steps: nope\n"),
          rejected(
              "retry-as-string",
              "stages:\n  - stage: S\n    steps:\n      - sh: hi\n        retry: soon\n"),

          // ── design/48-promoted: present-and-null on a grammar key (design/48 D3). Once a
          // documented parser-leniency known-gap (the parser collapsed an explicit `null` to
          // key-absent); design/48 makes a present-and-null grammar key a located error, so the
          // parser and schema now AGREE — both REJECT. Each asserts the located message names the
          // key and says "present but null". ──
          rejectedWithMessage(
              "agent-explicit-null",
              "agent: null\nstages:\n  - stage: S\n    steps:\n      - sh: hi\n",
              "'agent'",
              "present but null"),
          rejectedWithMessage(
              "when-explicit-null",
              "stages:\n  - stage: S\n    when: null\n    steps:\n      - sh: hi\n",
              "'when'",
              "present but null"),
          rejectedWithMessage(
              "retry-explicit-null",
              "stages:\n  - stage: S\n    steps:\n      - sh: hi\n        retry: null\n",
              "'retry'",
              "present but null"),
          rejectedWithMessage(
              "credentials-explicit-null",
              "credentials: null\nstages:\n  - stage: S\n    steps:\n      - sh: hi\n",
              "'credentials'",
              "present but null"),

          // ── design/48-promoted: scalar coercion is now a type error (design/48 D4). Once a
          // documented parser-leniency known-gap (the parser coerced via asBoolean()/asText());
          // design/48 type-checks every grammar key, so the parser and schema now AGREE — both
          // REJECT. Each asserts the message names the key, the expected type, and echoes the
          // offending value. ──
          rejectedWithMessage(
              "requiresApproval-as-string",
              "stages:\n  - gate: G\n    requiresApproval: \"yes\"\n",
              "'requiresApproval'",
              "boolean (true or false)",
              "the string \"yes\""),
          rejectedWithMessage(
              "requiresApproval-as-int",
              "stages:\n  - gate: G\n    requiresApproval: 1\n",
              "'requiresApproval'",
              "boolean (true or false)",
              "the number 1"),
          rejectedWithMessage(
              "dependsOn-as-number",
              "stages:\n  - stage: S\n    dependsOn: 5\n    steps:\n      - sh: hi\n",
              "'dependsOn'",
              "a string or a list of strings",
              "the number 5"),
          rejectedWithMessage(
              "agent-as-list",
              "agent: [a, b]\nstages:\n  - stage: S\n    steps:\n      - sh: hi\n",
              "'agent'",
              "a string",
              "an array"),
          rejectedWithMessage(
              "failurePolicy-as-list",
              "failurePolicy: [a]\nstages:\n  - stage: S\n    steps:\n      - sh: hi\n",
              "'failurePolicy'",
              "a string",
              "an array"),

          // ── oneOf root ambiguity / malformed wrapper. ──
          rejected("titan-not-object", "titan: 5\n"),
          rejected("root-is-a-list", "- stage: S\n"),
          rejected("root-is-a-scalar", "just a string\n"),
          // A doc matching NEITHER root branch: not a valid canonical body (no stages) and not
          // a clean wrapper (titan present but body invalid).
          rejected("matches-neither-root-branch", "titan:\n  notAKey: 1\n"),

          // ── retryPolicy boundaries (design/44 §2). ──
          rejected(
              "retry-0", "stages:\n  - stage: S\n    steps:\n      - sh: hi\n        retry: 0\n"),
          accepted(
              "retry-1-is-the-floor",
              "stages:\n  - stage: S\n    steps:\n      - sh: hi\n        retry: 1\n"),
          rejected(
              "retry-negative",
              "stages:\n  - stage: S\n    steps:\n      - sh: hi\n        retry: -1\n"),
          rejected(
              "retry-fractional",
              "stages:\n  - stage: S\n    steps:\n      - sh: hi\n        retry: 1.5\n"),
          accepted(
              "retry-empty-object-is-defaults",
              "stages:\n  - stage: S\n    steps:\n      - sh: hi\n        retry: {}\n"),
          rejected(
              "retry-maxAttempts-0",
              "stages:\n  - stage: S\n    steps:\n      - sh: hi\n"
                  + "        retry:\n          maxAttempts: 0\n"),
          rejected(
              "retry-maxAttempts-as-string",
              "stages:\n  - stage: S\n    steps:\n      - sh: hi\n"
                  + "        retry:\n          maxAttempts: \"3\"\n"),
          accepted(
              "backoff-multiplier-exactly-1",
              "stages:\n  - stage: S\n    steps:\n      - sh: hi\n        retry:\n"
                  + "          maxAttempts: 2\n          backoff:\n"
                  + "            multiplier: 1.0\n"),
          rejected(
              "backoff-multiplier-below-1",
              "stages:\n  - stage: S\n    steps:\n      - sh: hi\n        retry:\n"
                  + "          maxAttempts: 2\n          backoff:\n"
                  + "            multiplier: 0.5\n"),
          rejected(
              "retryableExitCodes-as-string",
              "stages:\n  - stage: S\n    steps:\n      - sh: hi\n        retry:\n"
                  + "          maxAttempts: 2\n          retryableExitCodes: nope\n"),

          // ── Duration pattern edges. Schema pattern is ^[0-9]+[smh]$; the parser's
          // parseDurationMillis must agree at each edge. ──
          rejected("duration-no-unit-suffix", backoffInitial("\"10\"")),
          rejected("duration-bad-unit", backoffInitial("\"10x\"")),
          rejected("duration-non-numeric", backoffInitial("\"abc\"")),
          rejected("duration-negative", backoffInitial("\"-5s\"")),
          rejected("duration-empty-string", backoffInitial("\"\"")),
          accepted("duration-valid-seconds", backoffInitial("\"30s\"")),
          // A valid `2h` initial — note `max` must be raised too, else the default `max: 5m`
          // is below 2h and validatePolicy rejects it (the max>=initial rule, a known-gap the
          // schema cannot see). This case isolates the duration *pattern*, not the cross-field
          // rule, so it supplies an explicit large `max`.
          accepted(
              "duration-valid-hours",
              "stages:\n  - stage: S\n    steps:\n      - sh: hi\n        retry:\n"
                  + "          maxAttempts: 2\n          backoff:\n"
                  + "            initial: \"2h\"\n            max: \"3h\"\n"),

          // ── Cardinality. ──
          rejected("stages-empty-array", "stages: []\n"),
          rejected("stages-missing", "agent: linux\n"),
          rejected("step-bare-string", "stages:\n  - stage: S\n    steps:\n      - hello\n"),
          rejected("step-null-value", "stages:\n  - stage: S\n    steps:\n      - null\n"),
          rejected("step-empty-object", "stages:\n  - stage: S\n    steps:\n      - {}\n"),
          rejected("node-no-discriminator", "stages:\n  - steps: []\n"),
          rejected("node-both-stage-and-gate", "stages:\n  - stage: S\n    gate: G\n"),
          rejected(
              "node-both-stage-and-precondition",
              "stages:\n  - stage: S\n    precondition: P\n    expression: \"true\"\n"),

          // ── Unknown keys at every nesting depth. ──
          rejected(
              "unknown-key-in-backoff",
              "stages:\n  - stage: S\n    steps:\n      - sh: hi\n        retry:\n"
                  + "          maxAttempts: 2\n          backoff:\n"
                  + "            initial: 5s\n            junk: 1\n"),
          rejected(
              "unknown-key-in-retry-object",
              "stages:\n  - stage: S\n    steps:\n      - sh: hi\n        retry:\n"
                  + "          maxAttempts: 2\n          junk: 1\n"),
          rejected(
              "unknown-key-in-credentialBinding",
              "stages:\n  - stage: S\n    credentials:\n      - id: x\n        type: string\n"
                  + "        variable: V\n        junk: 1\n    steps:\n      - sh: hi\n"),
          rejected(
              "unknown-key-in-parameter",
              "parameters:\n  - name: P\n    junk: 1\nstages:\n  - stage: S\n"
                  + "    steps:\n      - sh: hi\n"),
          rejected(
              "unknown-key-in-script-step",
              "stages:\n  - stage: S\n    steps:\n      - script:\n"
                  + "          runtime: bash\n          body: echo\n          junk: 1\n"),
          rejected("unknown-key-on-gate", "stages:\n  - gate: G\n    junk: 1\n"),
          rejected(
              "unknown-key-on-precondition",
              "stages:\n  - precondition: P\n    expression: \"true\"\n    junk: 1\n"),

          // ── credentialBinding required keys + type enum. ──
          rejected(
              "credentialBinding-missing-id",
              "stages:\n  - stage: S\n    credentials:\n      - type: string\n"
                  + "        variable: V\n    steps:\n      - sh: hi\n"),
          rejected(
              "credentialBinding-missing-type",
              "stages:\n  - stage: S\n    credentials:\n      - id: x\n        variable: V\n"
                  + "    steps:\n      - sh: hi\n"),
          rejected(
              "credentialBinding-type-outside-enum",
              "stages:\n  - stage: S\n    credentials:\n      - id: x\n        type: oauth\n"
                  + "        variable: V\n    steps:\n      - sh: hi\n"),

          // ── A script step that is not an object. ──
          rejected(
              "script-step-as-string",
              "stages:\n  - stage: S\n    steps:\n      - script: just text\n"),
          rejected(
              "script-missing-runtime",
              "stages:\n  - stage: S\n    steps:\n      - script:\n          body: echo\n"),

          // ── Null values that the schema and parser BOTH reject (a NullNode for stages
          // is not absent — the parser sees a non-array). ──
          rejected("stages-null", "stages: null\n"));

  /**
   * The keystone of this suite: for every adversarial agreeing case the parser's accept/reject
   * verdict and the generated schema's valid/invalid verdict are <strong>identical</strong>. A
   * failure is a genuine grammar/schema-faithfulness crack — see the case's group comment for what
   * it hunts. Fix the production code; never weaken this assertion.
   */
  @ParameterizedTest(name = "agree: {0}")
  @MethodSource("agreeingCases")
  void parserAndSchemaAgree(Case c) {
    Verdict parser = parserVerdict(c.yaml());
    Verdict schema = schemaVerdict(c.yaml());
    assertEquals(
        parser,
        schema,
        c.name()
            + ": parser and schema DIVERGE — parser="
            + parser
            + ", schema="
            + schema
            + ". design/47 claims they are faithful projections of one grammar; this "
            + "document breaks that claim. Either fix the production code or, if it is "
            + "an inherent JSON-Schema limit, reclassify the case as a known-gap.");
    assertEquals(
        c.expectedParser(), parser, c.name() + ": parser verdict is not what the case expects");
    // design/48: a promoted case is not just relabelled — its parser message must teach. For
    // a case with declared message fragments, assert the located, value-echoing message
    // contains each (the key name, the expected type, the offending value).
    if (!c.msgParts().isEmpty()) {
      String msg = parserMessage(c.yaml());
      for (String part : c.msgParts()) {
        assertTrue(
            msg.contains(part),
            c.name()
                + ": parser message must contain \""
                + part
                + "\" (design/48 promises a located, value-echoing message) — got: "
                + msg);
      }
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // The known-gap corpus — documents where the two enforcers CANNOT agree.
  // Each carries a precise reason. The test asserts the PARSER side only and
  // records (does not assert) the schema verdict. Never deleted, never weakened.
  // ════════════════════════════════════════════════════════════════════════

  /** A documented gap: case, expected parser verdict, the reason the enforcers diverge. */
  private record Gap(Case c, Verdict expectedSchema, String reason) {
    @Override
    public String toString() {
      return c.name();
    }
  }

  static Stream<Arguments> knownGapCases() {
    return KNOWN_GAPS.stream().map(Arguments::of);
  }

  private static final List<Gap> KNOWN_GAPS =
      List.of(

          // ── GAP 1 — JSON Schema cannot express a cross-field comparison.
          // design/44 §2 requires backoff.max >= backoff.initial. The parser's validatePolicy
          // checks it; draft-2020-12 has no construct to compare two sibling property values,
          // so the schema accepts an inverted pair. Real semantic rule, no schema expression.
          new Gap(
              rejected(
                  "backoff-max-below-initial",
                  "stages:\n  - stage: S\n    steps:\n      - sh: hi\n        retry:\n"
                      + "          maxAttempts: 2\n          backoff:\n"
                      + "            initial: 5m\n            max: 10s\n"),
              Verdict.ACCEPTED,
              "JSON Schema cannot compare two sibling field values (backoff.max >= "
                  + "backoff.initial); the parser enforces it semantically."),

          // ── GAP 2 — "a step has exactly one descriptor key" is inexpressible.
          // The `step` $def is deliberately OPEN (design/42 — the StepHandler SPI means
          // worker-side descriptors are not in the controller schema). An open object cannot
          // constrain its non-listed property count to exactly one; the parser counts them.
          new Gap(
              rejected(
                  "step-with-two-descriptor-keys",
                  "stages:\n  - stage: S\n    steps:\n      - sh: hi\n        echo: yo\n"),
              Verdict.ACCEPTED,
              "the `step` $def is open (extensible StepHandler SPI); JSON Schema cannot "
                  + "require exactly one unknown descriptor key. The parser counts "
                  + "descriptor keys and rejects two."),
          new Gap(
              rejected(
                  "step-with-zero-descriptor-keys",
                  "stages:\n  - stage: S\n    steps:\n      - image: foo:1\n"),
              Verdict.ACCEPTED,
              "the `step` $def is open with minProperties:1; a step carrying only a scope "
                  + "key (image) satisfies minProperties but has no descriptor. JSON "
                  + "Schema cannot require >=1 key OUTSIDE the known scope set. The "
                  + "parser rejects a step with no descriptor key."));

  // design/48 closed the two former parser-leniency known-gap families — null-as-absent (GAP 3)
  // and Jackson scalar coercion (GAP 4). All nine cases now have the parser and schema AGREEING
  // on REJECT and have been PROMOTED into the AGREEING corpus above (each with a parser-message
  // assertion). Only the three genuine JSON-Schema expressiveness gaps remain here; {@link
  // #knownGapSetIsExactlyTheThreeSchemaExpressivenessGaps()} pins that count so a future
  // accidental gap cannot hide.

  /**
   * For each known-gap document the test asserts only the side that <em>can</em> be asserted — the
   * parser verdict — and additionally confirms the schema verdict still matches the recorded gap
   * (so a future change that <em>closes</em> the gap, e.g. the parser is hardened, surfaces here as
   * a failure and the case can be promoted into the agreeing corpus). The gap's reason string
   * documents exactly why the two enforcers cannot agree today.
   */
  @ParameterizedTest(name = "known-gap: {0}")
  @MethodSource("knownGapCases")
  void knownGapBehavesAsDocumented(Gap gap) {
    Verdict parser = parserVerdict(gap.c().yaml());
    assertEquals(
        gap.c().expectedParser(),
        parser,
        gap.c().name() + ": parser verdict changed — " + gap.reason());
    Verdict schema = schemaVerdict(gap.c().yaml());
    assertEquals(
        gap.expectedSchema(),
        schema,
        gap.c().name()
            + ": the documented known-gap has CHANGED. Recorded gap: "
            + gap.reason()
            + " — if the parser/schema were brought into agreement, "
            + "promote this case into AGREEING; if it diverged differently, "
            + "re-document it. Do not silently weaken.");
  }

  // ════════════════════════════════════════════════════════════════════════
  // The fixed-bug regression test — the legacy-wrapper sibling crack.
  // ════════════════════════════════════════════════════════════════════════

  /**
   * Regression for the one real parser bug this adversarial pass found and fixed.
   *
   * <p>Before the fix, {@code TitanYamlParser} detected the legacy wrapper by {@code
   * root.get("titan")} and then projected <em>only that subtree</em> — it never inspected the
   * document root's other fields. So {@code { titan: {<valid pipeline>}, somethingElse: 1 }} was
   * silently accepted: {@code somethingElse} was dropped on the floor. The generated schema's
   * wrapper branch is {@code additionalProperties:false} with only {@code titan} allowed, so the
   * schema correctly rejected it — a parser/schema divergence, and the parser was the wrong side (a
   * typo'd sibling key would vanish without a word). The fix adds a {@code rejectUnknownKeys} on
   * the document root in the wrapper branch, so the wrapper is now exactly one {@code titan:} key
   * as design/29 §3 specifies.
   */
  @Test
  void wrapperSiblingIsRejected() {
    String withSibling =
        """
                titan:
                  stages:
                    - stage: Build
                      steps:
                        - sh: make
                somethingElse: 1
                """;
    assertEquals(
        Verdict.REJECTED,
        parserVerdict(withSibling),
        "the parser must reject a top-level key alongside the legacy `titan:` wrapper");
    assertEquals(
        Verdict.REJECTED,
        schemaVerdict(withSibling),
        "the schema rejects the wrapper sibling — the two now agree");
  }

  /**
   * The companion positive case: the legacy wrapper with <em>exactly</em> one {@code titan:} key
   * still parses (the fix must not break the back-compat wrapper form).
   */
  @Test
  void cleanWrapperStillParses() {
    String clean =
        """
                titan:
                  agent: linux
                  stages:
                    - stage: Build
                      steps:
                        - sh: make
                """;
    assertEquals(Verdict.ACCEPTED, parserVerdict(clean));
    assertEquals(Verdict.ACCEPTED, schemaVerdict(clean));
  }

  // ── helpers ──

  /** A backoff-only retry doc with a given raw `initial:` value — for the duration-edge cases. */
  private static String backoffInitial(String rawInitial) {
    return "stages:\n  - stage: S\n    steps:\n      - sh: hi\n        retry:\n"
        + "          maxAttempts: 2\n          backoff:\n"
        + "            initial: "
        + rawInitial
        + "\n";
  }

  private static Verdict parserVerdict(String yaml) {
    try {
      TitanYamlParser.parse(yaml);
      return Verdict.ACCEPTED;
    } catch (PipelineParseException e) {
      return Verdict.REJECTED;
    }
  }

  /** The {@link PipelineParseException} message for a YAML the parser is expected to reject. */
  private static String parserMessage(String yaml) {
    try {
      TitanYamlParser.parse(yaml);
      throw new AssertionError("expected the parser to reject this document: " + yaml);
    } catch (PipelineParseException e) {
      return e.getMessage();
    }
  }

  private static Verdict schemaVerdict(String yaml) {
    try {
      JsonNode instance = YAML.readTree(yaml);
      Set<ValidationMessage> errors = SCHEMA.validate(instance);
      return errors.isEmpty() ? Verdict.ACCEPTED : Verdict.REJECTED;
    } catch (Exception e) {
      throw new AssertionError("adversarial fixture YAML failed to parse: " + yaml, e);
    }
  }

  /**
   * A sanity guard on the corpus itself: no case name is duplicated across the agreeing and
   * known-gap lists, and neither list is empty. A typo'd duplicate would silently shadow a case in
   * the JUnit report.
   */
  @Test
  void corpusIsWellFormed() {
    assertFalse(AGREEING.isEmpty(), "the agreeing corpus must not be empty");
    assertFalse(KNOWN_GAPS.isEmpty(), "the known-gap corpus must not be empty");
    Set<String> names = new java.util.HashSet<>();
    for (Case c : AGREEING) {
      assertTrue(names.add(c.name()), "duplicate case name: " + c.name());
    }
    for (Gap g : KNOWN_GAPS) {
      assertTrue(names.add(g.c().name()), "duplicate case name: " + g.c().name());
    }
  }

  /**
   * design/48 done-when guard: after the nine parser-leniency gaps were promoted, the known-gap
   * corpus must contain <em>exactly</em> the three genuine JSON-Schema expressiveness limits and
   * nothing else. Pinning the count (and the names) means a future accidental parser-leniency gap
   * cannot quietly slip back into the known-gap list — it would have to bump this number, which is
   * a deliberate, reviewed act. The three survivors are the cross-field {@code backoff.max >=
   * backoff.initial} rule and the two open-{@code step}-def cardinality limits, which are schema
   * limitations, not parser leniency (design/48 non-goals).
   */
  @Test
  void knownGapSetIsExactlyTheThreeSchemaExpressivenessGaps() {
    assertEquals(
        3,
        KNOWN_GAPS.size(),
        "the known-gap corpus must hold exactly the 3 JSON-Schema expressiveness gaps — "
            + "design/48 promoted the 9 parser-leniency gaps into AGREEING. A different "
            + "count means a gap was added or a promoted case regressed.");
    Set<String> gapNames = new java.util.HashSet<>();
    for (Gap g : KNOWN_GAPS) {
      gapNames.add(g.c().name());
    }
    assertEquals(
        Set.of(
            "backoff-max-below-initial",
            "step-with-two-descriptor-keys",
            "step-with-zero-descriptor-keys"),
        gapNames,
        "the surviving known-gaps must be exactly the three schema-expressiveness limits");
  }
}

package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.ConcurrencyConfig;
import io.adaptiq.titan.flow.model.PipelineModel;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@code concurrency:} pipeline-root scope (issue #1101).
 *
 * <p>Covers: absent → null (legacy unlimited); short int form; full object form with each {@code
 * on_overflow}; unknown overflow rejected; non-positive max rejected; present-but-null rejected;
 * unknown sub-key rejected; tolerant pre-bake scanner round-trips and swallows malformed YAML.
 */
class ConcurrencyScopeTest {

  private static PipelineModel parse(String body) {
    return TitanYamlParser.parse(
        body + "stages:\n  - stage: build\n    steps:\n      - sh: echo hi\n");
  }

  @Test
  void absentConcurrencyLeavesConfigNull() {
    assertNull(parse("").getConcurrency(), "absent concurrency → null (legacy unlimited)");
  }

  @Test
  void shortIntFormParsesAsMaxWithQueueDefault() {
    ConcurrencyConfig cfg = parse("concurrency: 3\n").getConcurrency();
    assertNotNull(cfg);
    assertEquals(3, cfg.getMax());
    assertEquals(ConcurrencyConfig.OnOverflow.QUEUE, cfg.getOnOverflow());
  }

  @Test
  void fullObjectFormWithQueueOverflow() {
    ConcurrencyConfig cfg =
        parse("concurrency:\n  max: 2\n  on_overflow: queue\n").getConcurrency();
    assertNotNull(cfg);
    assertEquals(2, cfg.getMax());
    assertEquals(ConcurrencyConfig.OnOverflow.QUEUE, cfg.getOnOverflow());
  }

  @Test
  void fullObjectFormWithCancelOldestOverflow() {
    ConcurrencyConfig cfg =
        parse("concurrency:\n  max: 1\n  on_overflow: cancel_oldest\n").getConcurrency();
    assertEquals(ConcurrencyConfig.OnOverflow.CANCEL_OLDEST, cfg.getOnOverflow());
  }

  @Test
  void fullObjectFormWithCancelPendingOverflow() {
    ConcurrencyConfig cfg =
        parse("concurrency:\n  max: 4\n  on_overflow: cancel_pending\n").getConcurrency();
    assertEquals(ConcurrencyConfig.OnOverflow.CANCEL_PENDING, cfg.getOnOverflow());
  }

  @Test
  void concurrencyInsideLegacyTitanWrapperIsAccepted() {
    PipelineModel model =
        TitanYamlParser.parse(
            "titan:\n"
                + "  concurrency:\n"
                + "    max: 7\n"
                + "  stages:\n"
                + "    - stage: build\n"
                + "      steps:\n"
                + "        - sh: echo hi\n");
    assertEquals(7, model.getConcurrency().getMax());
  }

  @Test
  void unknownOverflowValueIsRejected() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () -> parse("concurrency:\n  max: 1\n  on_overflow: cancel-everything\n"));
    assertTrue(
        ex.getMessage().contains("on_overflow"),
        "error should name on_overflow: " + ex.getMessage());
  }

  @Test
  void nonPositiveMaxIsRejected() {
    PipelineParseException ex =
        assertThrows(PipelineParseException.class, () -> parse("concurrency:\n  max: 0\n"));
    assertTrue(ex.getMessage().contains("max"), ex.getMessage());
  }

  @Test
  void shortFormZeroIsRejected() {
    assertThrows(PipelineParseException.class, () -> parse("concurrency: 0\n"));
  }

  @Test
  void presentButNullIsRejected() {
    PipelineParseException ex =
        assertThrows(PipelineParseException.class, () -> parse("concurrency: ~\n"));
    assertTrue(ex.getMessage().contains("concurrency"), ex.getMessage());
  }

  @Test
  void missingMaxKeyIsRejected() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class, () -> parse("concurrency:\n  on_overflow: queue\n"));
    assertTrue(ex.getMessage().contains("max"), ex.getMessage());
  }

  @Test
  void unknownSubKeyIsRejected() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class, () -> parse("concurrency:\n  max: 1\n  group: foo\n"));
    assertTrue(ex.getMessage().contains("group"), ex.getMessage());
  }

  @Test
  void overflowAsListIsRejected() {
    assertThrows(
        PipelineParseException.class,
        () -> parse("concurrency:\n  max: 1\n  on_overflow: [queue]\n"));
  }

  @Test
  void tolerantParseRoundTripsHappyPath() {
    ConcurrencyConfig cfg =
        ConcurrencyScope.parseTolerant(
            "concurrency:\n"
                + "  max: 2\n"
                + "  on_overflow: cancel_oldest\n"
                + "stages:\n"
                + "  - stage: s\n"
                + "    steps:\n"
                + "      - sh: echo\n");
    assertNotNull(cfg);
    assertEquals(2, cfg.getMax());
    assertEquals(ConcurrencyConfig.OnOverflow.CANCEL_OLDEST, cfg.getOnOverflow());
  }

  @Test
  void tolerantParseReturnsNullOnAbsentBlock() {
    assertNull(
        ConcurrencyScope.parseTolerant("stages:\n  - stage: s\n    steps:\n      - sh: echo\n"));
  }

  @Test
  void tolerantParseReturnsNullOnMalformedYaml() {
    // Adversarial: a wholly invalid YAML must NOT crash the runtime gate — just return null
    // so the gate defaults to legacy unlimited. The bake-time parser still reports the error.
    assertNull(ConcurrencyScope.parseTolerant("not: [valid: yaml: at: all"));
  }

  @Test
  void tolerantParseReturnsNullOnMalformedConcurrencyBlock() {
    // The concurrency block itself is wrong (max < 1) but the rest of the YAML is valid.
    // Tolerant scanner swallows the error and falls back to legacy unlimited; the bake-time
    // parser will still hard-fail on this pipeline at the real parse() callsite.
    assertNull(
        ConcurrencyScope.parseTolerant(
            "concurrency:\n  max: -5\nstages:\n  - stage: s\n    steps:\n      - sh: x\n"));
  }

  @Test
  void onOverflowFromYamlAcceptsDashesAndMixedCase() {
    // sad-path resilience: GHA-style hyphenated value should map onto the underscored enum.
    assertEquals(
        ConcurrencyConfig.OnOverflow.CANCEL_OLDEST,
        ConcurrencyConfig.OnOverflow.fromYaml("Cancel-Oldest", "test"));
  }

  @Test
  void concurrencyConfigConstructorRejectsZero() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConcurrencyConfig(0, ConcurrencyConfig.OnOverflow.QUEUE));
  }
}

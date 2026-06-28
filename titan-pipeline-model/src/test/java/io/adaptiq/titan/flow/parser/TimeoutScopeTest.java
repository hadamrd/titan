package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.StepModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for the {@code timeout:} scope ({@link TimeoutScope}) — the richest value grammar
 * of any scope (duration suffixes {@code s/m/h/d}, bare-seconds), the stage→step flatten rule ("a
 * step's own {@code timeout:} wins"), and the four {@link PipelineParseException} error paths.
 *
 * <p>Previously this behaviour was only exercised incidentally by ~12 corpus tests; the unit
 * conversions, flatten-precedence, and located validation failures had no dedicated guard. Mirrors
 * the style of the sibling {@link RetryParsingTest} (#13).
 */
class TimeoutScopeTest {

  private static StepModel firstStep(String yaml) {
    return TitanYamlParser.parse(yaml).getStages().get(0).getSteps().get(0);
  }

  private static StepModel stepWithTimeout(String value) {
    return firstStep(
        """
            stages:
              - stage: test
                steps:
                  - sh: a.sh
                    timeout: %s
            """
            .formatted(value));
  }

  // ── happy path: every duration suffix + bare-seconds ──────────────────────

  @Test
  void secondsSuffixParsesToMillis() {
    assertEquals(30_000L, stepWithTimeout("30s").getTimeoutMillis(), "30s -> 30000ms");
  }

  @Test
  void minutesSuffixParsesToMillis() {
    assertEquals(300_000L, stepWithTimeout("5m").getTimeoutMillis(), "5m -> 300000ms");
  }

  @Test
  void hoursSuffixParsesToMillis() {
    assertEquals(7_200_000L, stepWithTimeout("2h").getTimeoutMillis(), "2h -> 7200000ms");
  }

  @Test
  void daysSuffixParsesToMillis() {
    assertEquals(86_400_000L, stepWithTimeout("1d").getTimeoutMillis(), "1d -> 86400000ms");
  }

  @Test
  void bareNumberIsInterpretedAsSeconds() {
    assertEquals(45_000L, stepWithTimeout("45").getTimeoutMillis(), "bare 45 -> 45000ms");
  }

  // ── stage-level timeout flattens onto steps; a step's own timeout wins ─────

  @Test
  void stageLevelTimeoutFlattensOntoEveryStep() {
    List<StepModel> steps =
        TitanYamlParser.parse(
                """
                stages:
                  - stage: test
                    timeout: 5m
                    steps:
                      - sh: a.sh
                      - sh: b.sh
                """)
            .getStages()
            .get(0)
            .getSteps();
    assertEquals(2, steps.size());
    for (StepModel step : steps) {
      assertEquals(300_000L, step.getTimeoutMillis(), "every step inherits the stage timeout (5m)");
    }
  }

  @Test
  void aStepsOwnTimeoutWinsOverTheStageTimeout() {
    List<StepModel> steps =
        TitanYamlParser.parse(
                """
                stages:
                  - stage: test
                    timeout: 5m
                    steps:
                      - sh: a.sh
                        timeout: 30s
                      - sh: b.sh
                """)
            .getStages()
            .get(0)
            .getSteps();
    assertEquals(30_000L, steps.get(0).getTimeoutMillis(), "step 0 keeps its own 30s timeout");
    assertEquals(300_000L, steps.get(1).getTimeoutMillis(), "step 1 inherits the stage's 5m");
  }

  // ── absent timeout — null, no behaviour change ────────────────────────────

  @Test
  void aStepWithNoTimeoutHasANullTimeout() {
    assertNull(
        firstStep(
                """
                stages:
                  - stage: build
                    steps:
                      - sh: mvn package
                """)
            .getTimeoutMillis(),
        "no timeout: key -> getTimeoutMillis() is null");
  }

  // ── sad path: all four PipelineParseException branches ────────────────────

  @Test
  void anEmptyTimeoutIsRejected() {
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> stepWithTimeout("\"\""));
    assertTrue(e.getMessage().contains("empty"), e.getMessage());
    assertTrue(e.getMessage().contains("timeout"), e.getMessage());
  }

  @Test
  void aNonNumericTimeoutIsRejected() {
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> stepWithTimeout("abc"));
    assertTrue(e.getMessage().contains("not a number"), e.getMessage());
    assertTrue(
        e.getMessage().contains("abc"), "message echoes the offending value: " + e.getMessage());
  }

  @Test
  void aZeroTimeoutIsRejected() {
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> stepWithTimeout("0s"));
    assertTrue(e.getMessage().contains("positive"), e.getMessage());
    assertTrue(
        e.getMessage().contains("0s"), "message echoes the offending value: " + e.getMessage());
  }

  @Test
  void aNegativeTimeoutIsRejected() {
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> stepWithTimeout("-5m"));
    assertTrue(e.getMessage().contains("positive"), e.getMessage());
    assertTrue(
        e.getMessage().contains("-5m"), "message echoes the offending value: " + e.getMessage());
  }

  @Test
  void anUnknownUnitIsRejected() {
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> stepWithTimeout("10w"));
    assertTrue(e.getMessage().contains("unknown timeout unit"), e.getMessage());
    assertTrue(e.getMessage().contains("w"), "message names the offending unit: " + e.getMessage());
  }
}

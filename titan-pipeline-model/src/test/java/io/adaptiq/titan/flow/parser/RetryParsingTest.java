package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.flow.model.RetryPolicy;
import io.adaptiq.titan.flow.model.StepModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests {@code retry:} parsing on a step and a stage (design/44 §1, §2, build step 44-T A).
 *
 * <p>{@code retry:} is a declarative per-step {@link io.adaptiq.titan.flow.model.RetryPolicy}
 * carried on a {@link StepModel} — Titan has no CPS and no blocks, so it is a scope, not a wrapping
 * construct (design/44 §1). These tests cover the two surface forms (scalar shorthand and the
 * object form), duration-suffix parsing, the stage-flatten rule, the located validation failures,
 * the absent-policy case, and a Jackson round-trip through {@code pipeline_model_json}.
 */
class RetryParsingTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static StepModel firstStep(String yaml) {
    return TitanYamlParser.parse(yaml).getStages().get(0).getSteps().get(0);
  }

  // ── scalar shorthand ──────────────────────────────────────────────────────

  @Test
  void scalarShorthandSetsMaxAttemptsWithDefaultBackoff() {
    StepModel step =
        firstStep(
            """
                stages:
                  - stage: test
                    steps:
                      - sh: ./flaky.sh
                        retry: 3
                """);
    RetryPolicy policy = step.getRetry();
    assertNotNull(policy, "retry: 3 yields a RetryPolicy");
    assertEquals(3, policy.getMaxAttempts());
  }

  @Test
  void scalarShorthandUsesTheDefaultBackoffSchedule() {
    RetryPolicy.Backoff backoff =
        firstStep(
                """
                stages:
                  - stage: test
                    steps:
                      - sh: ./flaky.sh
                        retry: 3
                """)
            .getRetry()
            .getBackoff();
    assertEquals(10_000L, backoff.getInitialMillis(), "default initial is 10s");
    assertEquals(2.0, backoff.getMultiplier(), "default multiplier is 2.0");
    assertEquals(300_000L, backoff.getMaxMillis(), "default max is 5m");
  }

  @Test
  void scalarShorthandLeavesRetryableExitCodesEmpty() {
    assertTrue(
        firstStep(
                """
                stages:
                  - stage: test
                    steps:
                      - sh: ./flaky.sh
                        retry: 3
                """)
            .getRetry()
            .getRetryableExitCodes()
            .isEmpty());
  }

  // ── object form ───────────────────────────────────────────────────────────

  @Test
  void objectFormParsesMaxAttempts() {
    assertEquals(
        4,
        firstStep(
                """
                stages:
                  - stage: deploy
                    steps:
                      - sh: ./deploy.sh
                        retry:
                          maxAttempts: 4
                """)
            .getRetry()
            .getMaxAttempts());
  }

  @Test
  void objectFormParsesBackoffDurationSuffixes() {
    RetryPolicy.Backoff backoff =
        firstStep(
                """
                stages:
                  - stage: deploy
                    steps:
                      - sh: ./deploy.sh
                        retry:
                          maxAttempts: 4
                          backoff: { initial: 15s, multiplier: 3.0, max: 2h }
                """)
            .getRetry()
            .getBackoff();
    assertEquals(15_000L, backoff.getInitialMillis(), "15s -> 15000ms");
    assertEquals(3.0, backoff.getMultiplier());
    assertEquals(7_200_000L, backoff.getMaxMillis(), "2h -> 7200000ms");
  }

  @Test
  void objectFormParsesMinuteDurationSuffix() {
    assertEquals(
        300_000L,
        firstStep(
                """
                stages:
                  - stage: deploy
                    steps:
                      - sh: ./deploy.sh
                        retry:
                          maxAttempts: 2
                          backoff: { initial: 5m, max: 5m }
                """)
            .getRetry()
            .getBackoff()
            .getInitialMillis(),
        "5m -> 300000ms");
  }

  @Test
  void objectFormParsesRetryableExitCodes() {
    assertEquals(
        List.of(2, 75),
        firstStep(
                """
                stages:
                  - stage: deploy
                    steps:
                      - sh: ./deploy.sh
                        retry:
                          maxAttempts: 4
                          retryableExitCodes: [2, 75]
                """)
            .getRetry()
            .getRetryableExitCodes());
  }

  // ── stage-level retry flattens onto steps ─────────────────────────────────

  @Test
  void stageLevelRetryFlattensOntoEveryStep() {
    List<StepModel> steps =
        TitanYamlParser.parse(
                """
                stages:
                  - stage: test
                    retry: 2
                    steps:
                      - sh: a.sh
                      - sh: b.sh
                """)
            .getStages()
            .get(0)
            .getSteps();
    assertEquals(2, steps.size());
    for (StepModel step : steps) {
      assertNotNull(step.getRetry(), "every step inherits the stage retry");
      assertEquals(2, step.getRetry().getMaxAttempts());
    }
  }

  @Test
  void aStepsOwnRetryWinsOverTheStageRetry() {
    StepModel step =
        firstStep(
            """
                stages:
                  - stage: test
                    retry: 2
                    steps:
                      - sh: a.sh
                        retry: 5
                """);
    assertEquals(5, step.getRetry().getMaxAttempts(), "the step's own retry wins");
  }

  @Test
  void aStepWithoutItsOwnRetryInheritsTheStageRetry() {
    List<StepModel> steps =
        TitanYamlParser.parse(
                """
                stages:
                  - stage: test
                    retry: 2
                    steps:
                      - sh: a.sh
                        retry: 5
                      - sh: b.sh
                """)
            .getStages()
            .get(0)
            .getSteps();
    assertEquals(5, steps.get(0).getRetry().getMaxAttempts(), "step 0 keeps its own policy");
    assertEquals(2, steps.get(1).getRetry().getMaxAttempts(), "step 1 inherits the stage's");
  }

  // ── validation failures — located PipelineParseException ──────────────────

  @Test
  void maxAttemptsBelowOneIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                        stages:
                          - stage: test
                            steps:
                              - sh: a.sh
                                retry:
                                  maxAttempts: 0
                        """));
    assertTrue(e.getMessage().contains("maxAttempts"), e.getMessage());
  }

  @Test
  void multiplierBelowOneIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                        stages:
                          - stage: test
                            steps:
                              - sh: a.sh
                                retry:
                                  maxAttempts: 3
                                  backoff: { multiplier: 0.5 }
                        """));
    assertTrue(e.getMessage().contains("multiplier"), e.getMessage());
  }

  @Test
  void aNegativeBackoffDurationIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                        stages:
                          - stage: test
                            steps:
                              - sh: a.sh
                                retry:
                                  maxAttempts: 3
                                  backoff: { initial: -5s, max: 5m }
                        """));
    // the duration parser rejects a negative value before the policy validator runs
    assertTrue(
        e.getMessage().toLowerCase().contains("duration") || e.getMessage().contains("negative"),
        e.getMessage());
  }

  @Test
  void backoffMaxBelowInitialIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                        stages:
                          - stage: test
                            steps:
                              - sh: a.sh
                                retry:
                                  maxAttempts: 3
                                  backoff: { initial: 5m, max: 10s }
                        """));
    assertTrue(e.getMessage().contains("max"), e.getMessage());
  }

  // ── absent policy — no behaviour change ───────────────────────────────────

  @Test
  void aStepWithNoRetryHasANullPolicy() {
    assertNull(
        firstStep(
                """
                stages:
                  - stage: build
                    steps:
                      - sh: mvn package
                """)
            .getRetry(),
        "no retry: key -> getRetry() is null");
  }

  // ── Jackson round-trip ────────────────────────────────────────────────────

  @Test
  void aRetryPolicyRoundTripsThroughPipelineModelJson() throws Exception {
    String yaml =
        """
                stages:
                  - stage: deploy
                    steps:
                      - sh: ./deploy.sh
                        retry:
                          maxAttempts: 4
                          backoff: { initial: 15s, multiplier: 2.5, max: 2h }
                          retryableExitCodes: [2, 75]
                """;
    StepModel original = firstStep(yaml);
    String json = JSON.writeValueAsString(original);
    StepModel reloaded = JSON.readValue(json, StepModel.class);

    RetryPolicy policy = reloaded.getRetry();
    assertNotNull(policy, "the retry policy survives the JSON round-trip");
    assertEquals(4, policy.getMaxAttempts());
    assertEquals(15_000L, policy.getBackoff().getInitialMillis());
    assertEquals(2.5, policy.getBackoff().getMultiplier());
    assertEquals(7_200_000L, policy.getBackoff().getMaxMillis());
    assertEquals(List.of(2, 75), policy.getRetryableExitCodes());
  }
}

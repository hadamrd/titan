package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PreviousOutcome;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.model.WhenCondition;
import io.adaptiq.titan.flow.model.WhenKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests {@code when:} parsing at stage level and — since GH #240 — at step level too.
 *
 * <p>{@code when:} is a {@link StepScope} whose {@link WhenScope#appliesToStep()} returns {@code
 * true} (GH #240). At stage level it sets {@code StageModel.when}; at step level it sets {@code
 * StepModel.when}. The two conditions are independent — a step-level {@code when:} never inherits
 * the stage-level one and the stage's own guard is unaffected by step-level guards.
 */
class WhenParsingTest {

  private static StageModel firstStage(String yaml) {
    return TitanYamlParser.parse(yaml).getStages().get(0);
  }

  private static StepModel firstStep(String yaml) {
    return firstStage(yaml).getSteps().get(0);
  }

  // ── stage-level when: (existing behaviour, must stay intact) ─────────────

  @Test
  void stageLevelWhenIsSetOnTheStageModel() {
    StageModel stage =
        firstStage(
            """
            stages:
              - stage: Build
                when: "${params.RUN_BUILD}"
                steps:
                  - sh: mvn package
            """);
    assertEquals("${params.RUN_BUILD}", stage.getWhen());
  }

  @Test
  void stageLevelWhenAbsentLeavesStageWhenNull() {
    assertNull(
        firstStage(
                """
                stages:
                  - stage: Build
                    steps:
                      - sh: mvn package
                """)
            .getWhen());
  }

  // ── step-level when: (GH #240 — the new behaviour) ───────────────────────

  @Test
  void stepLevelWhenIsSetOnTheStepModel() {
    StepModel step =
        firstStep(
            """
            stages:
              - stage: Deploy
                steps:
                  - sh: ./deploy.sh
                    when: "${params.BRANCH == 'main'}"
            """);
    assertEquals("${params.BRANCH == 'main'}", step.getWhen());
  }

  @Test
  void stepWithoutWhenHasNullWhen() {
    assertNull(
        firstStep(
                """
                stages:
                  - stage: Deploy
                    steps:
                      - sh: ./deploy.sh
                """)
            .getWhen());
  }

  @Test
  void stepLevelWhenDoesNotAffectOtherStepsInTheSameStage() {
    List<StepModel> steps =
        TitanYamlParser.parse(
                """
                stages:
                  - stage: Build
                    steps:
                      - sh: ./compile.sh
                        when: "${params.COMPILE}"
                      - sh: ./test.sh
                """)
            .getStages()
            .get(0)
            .getSteps();
    assertEquals("${params.COMPILE}", steps.get(0).getWhen(), "first step has its own when");
    assertNull(steps.get(1).getWhen(), "second step has no when — null, not inherited");
  }

  @Test
  void stageLevelWhenAndStepLevelWhenCoexistIndependently() {
    StageModel stage =
        firstStage(
            """
            stages:
              - stage: Build
                when: "${params.RUN_BUILD}"
                steps:
                  - sh: ./compile.sh
                    when: "${params.SKIP_COMPILE == false}"
                  - sh: ./test.sh
            """);
    assertEquals("${params.RUN_BUILD}", stage.getWhen(), "stage-level when is set");
    assertEquals(
        "${params.SKIP_COMPILE == false}",
        stage.getSteps().get(0).getWhen(),
        "step 0 has its own when");
    assertNull(stage.getSteps().get(1).getWhen(), "step 1 has no when");
  }

  @Test
  void stageLevelWhenIsNotFlattenedOntoSteps() {
    // The stage-level when: guards the whole stage; it must NOT be copied down to
    // individual steps — each step's when: is purely its own.
    List<StepModel> steps =
        TitanYamlParser.parse(
                """
                stages:
                  - stage: Deploy
                    when: "${params.DEPLOY}"
                    steps:
                      - sh: a.sh
                      - sh: b.sh
                """)
            .getStages()
            .get(0)
            .getSteps();
    assertNull(steps.get(0).getWhen(), "stage when must not be flattened onto step 0");
    assertNull(steps.get(1).getWhen(), "stage when must not be flattened onto step 1");
  }

  // ── type strictness (design/48 D2/D3) ────────────────────────────────────

  @Test
  void stepLevelWhenNullIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                    stages:
                      - stage: S
                        steps:
                          - sh: echo hi
                            when: ~
                    """));
    assertTrue(e.getMessage().contains("when"), e.getMessage());
  }

  @Test
  void stageLevelWhenNullIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                    stages:
                      - stage: S
                        when: ~
                        steps:
                          - sh: echo hi
                    """));
    assertTrue(e.getMessage().contains("when"), e.getMessage());
  }

  // ── structured when: discriminated union (GH #1093) ──────────────────────

  private static WhenCondition firstStepCondition(String yaml) {
    return firstStep(yaml).getWhenCondition();
  }

  @Test
  void structuredWhenBranchParsesToTypedCondition() {
    WhenCondition c =
        firstStepCondition(
            """
            stages:
              - stage: Deploy
                steps:
                  - sh: ./deploy.sh
                    when:
                      branch: "release/*"
            """);
    assertEquals(WhenKind.BRANCH, c.getKind());
    assertEquals("release/*", c.getBranch());
    assertNull(c.getPrevious());
    assertNull(c.getFilesChanged());
    // The legacy string `when` is left null when the structured form is used.
    assertNull(
        firstStep(
                """
        stages:
          - stage: Deploy
            steps:
              - sh: ./deploy.sh
                when:
                  branch: main
        """)
            .getWhen());
  }

  @Test
  void structuredWhenPreviousParsesEnumCaseInsensitively() {
    WhenCondition c =
        firstStepCondition(
            """
            stages:
              - stage: Deploy
                steps:
                  - sh: ./notify.sh
                    when:
                      previous: FAILURE
            """);
    assertEquals(WhenKind.PREVIOUS, c.getKind());
    assertEquals(PreviousOutcome.FAILURE, c.getPrevious());
  }

  @Test
  void structuredWhenFilesChangedParsesGlobList() {
    WhenCondition c =
        firstStepCondition(
            """
            stages:
              - stage: Build
                steps:
                  - sh: ./build-docs.sh
                    when:
                      files_changed:
                        - "docs/**"
                        - "*.md"
            """);
    assertEquals(WhenKind.FILES_CHANGED, c.getKind());
    assertEquals(List.of("docs/**", "*.md"), c.getFilesChanged());
  }

  @Test
  void structuredWhenWithNoDiscriminatorIsRejectedAtParse() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                    stages:
                      - stage: S
                        steps:
                          - sh: echo hi
                            when: {}
                    """));
    assertTrue(e.getMessage().contains("exactly one"), e.getMessage());
  }

  @Test
  void structuredWhenWithTwoDiscriminatorsIsRejectedAtParse() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                    stages:
                      - stage: S
                        steps:
                          - sh: echo hi
                            when:
                              branch: main
                              previous: success
                    """));
    assertTrue(e.getMessage().contains("discriminated union"), e.getMessage());
  }

  @Test
  void structuredWhenWithUnknownKeyIsRejectedAtParse() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                    stages:
                      - stage: S
                        steps:
                          - sh: echo hi
                            when:
                              bogus: nope
                    """));
    assertTrue(e.getMessage().contains("unknown key"), e.getMessage());
  }

  @Test
  void structuredWhenWithBadPreviousLiteralIsRejectedAtParse() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                    stages:
                      - stage: S
                        steps:
                          - sh: echo hi
                            when:
                              previous: maybe
                    """));
    assertTrue(e.getMessage().contains("success|failure|always"), e.getMessage());
  }

  @Test
  void structuredWhenFilesChangedMustBeAnArray() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                    stages:
                      - stage: S
                        steps:
                          - sh: echo hi
                            when:
                              files_changed: "docs/**"
                    """));
    assertTrue(e.getMessage().contains("array"), e.getMessage());
  }

  @Test
  void structuredWhenFilesChangedRejectsEmptyList() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                    stages:
                      - stage: S
                        steps:
                          - sh: echo hi
                            when:
                              files_changed: []
                    """));
    assertTrue(e.getMessage().contains("at least one"), e.getMessage());
  }

  @Test
  void structuredWhenBranchRejectsNonStringValue() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                    stages:
                      - stage: S
                        steps:
                          - sh: echo hi
                            when:
                              branch: 42
                    """));
    assertTrue(e.getMessage().contains("glob string"), e.getMessage());
  }
}

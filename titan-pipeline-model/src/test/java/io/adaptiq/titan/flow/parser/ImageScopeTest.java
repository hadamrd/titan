package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests {@code image:} parsing on a step and a stage (design/31 §6G, design/42 §4.7, design/48
 * D2/D3/D4).
 *
 * <p>{@code image:} is the typed-read string scope a step or stage runs in. Unlike {@code retry:} /
 * {@code timeout:} it does <em>not</em> flatten: a stage-level {@code image:} lands on {@link
 * StageModel#getImage()} as an inherited-at-runtime property and is <em>not</em> copied onto each
 * {@link StepModel} at parse time. A step-level {@code image:} sets {@link StepModel#getImage()}
 * and overrides the stage. Because {@code image} is declared a string (design/48 D2), {@code image:
 * null} is a present-and-null error and {@code image: [a, b]} a located type error — never a silent
 * coercion to {@code ""}.
 */
class ImageScopeTest {

  private static StepModel firstStep(String yaml) {
    return firstStage(yaml).getSteps().get(0);
  }

  private static StageModel firstStage(String yaml) {
    return TitanYamlParser.parse(yaml).getStages().get(0);
  }

  // ── step-level ────────────────────────────────────────────────────────────

  @Test
  void stepLevelImageSetsTheStepImage() {
    StepModel step =
        firstStep(
            """
                stages:
                  - stage: build
                    steps:
                      - sh: ./build.sh
                        image: alpine:3.20
                """);
    assertEquals("alpine:3.20", step.getImage(), "step image: lands on StepModel.getImage()");
  }

  // ── stage-level inheritance — no flatten ──────────────────────────────────

  @Test
  void stageLevelImageSetsTheStageImage() {
    StageModel stage =
        firstStage(
            """
                stages:
                  - stage: build
                    image: ubuntu:24.04
                    steps:
                      - sh: a.sh
                      - sh: b.sh
                """);
    assertEquals("ubuntu:24.04", stage.getImage(), "stage image: lands on StageModel.getImage()");
  }

  @Test
  void stageLevelImageIsNotFlattenedOntoEachStep() {
    // Contrast with retry:/timeout: which DO copy onto each StepModel at parse time. image:
    // is inherited at runtime, so the step's own getImage() stays null at parse time.
    List<StepModel> steps =
        firstStage(
                """
                stages:
                  - stage: build
                    image: ubuntu:24.04
                    steps:
                      - sh: a.sh
                      - sh: b.sh
                """)
            .getSteps();
    assertEquals(2, steps.size());
    for (StepModel step : steps) {
      assertNull(
          step.getImage(),
          "stage image: must NOT be copied onto the step at parse time (no flatten)");
    }
  }

  // ── step overrides stage ──────────────────────────────────────────────────

  @Test
  void aStepsOwnImageWinsOverTheStageImage() {
    StepModel step =
        firstStep(
            """
                stages:
                  - stage: build
                    image: a
                    steps:
                      - sh: ./build.sh
                        image: b
                """);
    assertEquals("b", step.getImage(), "the step's own image: wins over the stage image:");
  }

  // ── absent ────────────────────────────────────────────────────────────────

  @Test
  void aStepWithNoImageHasANullImage() {
    assertNull(
        firstStep(
                """
                stages:
                  - stage: build
                    steps:
                      - sh: mvn package
                """)
            .getImage(),
        "no image: key -> getImage() is null");
  }

  @Test
  void aStageWithNoImageHasANullImage() {
    assertNull(
        firstStage(
                """
                stages:
                  - stage: build
                    steps:
                      - sh: mvn package
                """)
            .getImage(),
        "no stage image: key -> getImage() is null");
  }

  // ── sad path — typed read, not coercion (design/48 D3/D4) ─────────────────

  @Test
  void stepImageNullIsRejectedAsPresentButNull() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                        stages:
                          - stage: build
                            steps:
                              - sh: ./build.sh
                                image: null
                        """));
    // present-and-null is an explanatory error, not a coercion to "".
    assertTrue(e.getMessage().contains("present but null"), e.getMessage());
    assertTrue(e.getMessage().contains("'image'"), e.getMessage());
  }

  @Test
  void stageImageNullIsRejectedAsPresentButNull() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                        stages:
                          - stage: build
                            image: null
                            steps:
                              - sh: ./build.sh
                        """));
    assertTrue(e.getMessage().contains("present but null"), e.getMessage());
    assertTrue(e.getMessage().contains("'image'"), e.getMessage());
  }

  @Test
  void stepImageAsAListIsRejectedWithALocatedTypeError() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                        stages:
                          - stage: build
                            steps:
                              - sh: ./build.sh
                                image: [a, b]
                        """));
    // a located type error that names the key and the expected type — not coerced to "".
    assertTrue(e.getMessage().contains("'image'"), e.getMessage());
    assertTrue(e.getMessage().contains("must be a string"), e.getMessage());
    assertTrue(e.getMessage().contains("an array"), e.getMessage());
  }

  @Test
  void stageImageAsAListIsRejectedWithALocatedTypeError() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                        stages:
                          - stage: build
                            image: [a, b]
                            steps:
                              - sh: ./build.sh
                        """));
    assertTrue(e.getMessage().contains("'image'"), e.getMessage());
    assertTrue(e.getMessage().contains("must be a string"), e.getMessage());
    assertTrue(e.getMessage().contains("an array"), e.getMessage());
  }
}

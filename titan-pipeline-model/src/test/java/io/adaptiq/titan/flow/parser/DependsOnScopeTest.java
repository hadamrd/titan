package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests {@code dependsOn:} parsing — the DAG-edge scope ({@link DependsOnScope}, design/29 §3,
 * design/42 §4.7). Mirrors {@code RetryParsingTest}'s helper + fixture style.
 *
 * <p>{@code dependsOn:} has three subtle parse properties this class guards: (1) it is
 * <em>stage-only</em> — {@link DependsOnScope#appliesToStep()} is {@code false} and {@link
 * DependsOnScope#parseStep} is a deliberate no-op, so a step-level {@code dependsOn:} is rejected
 * as an unknown step key; (2) it accepts <em>string-or-list</em> via {@link
 * TypedNodeReader#stringList} — a bare string folds to a one-element list; (3) per design/48
 * D2/D3/D4 it is typed-read, so {@code dependsOn: 5} is a located type error (no coercion to {@code
 * "5"}) and {@code dependsOn: null} a present-and-null error.
 *
 * <p>DAG-level validation (cycles, missing/duplicate ids) is a {@code PipelineDagValidator} concern
 * and is intentionally <em>not</em> exercised here — these tests call {@link TitanYamlParser#parse}
 * (parse only), not {@link TitanYamlParser#parseAndValidate}.
 */
class DependsOnScopeTest {

  private static StageModel stageAt(String yaml, int index) {
    return TitanYamlParser.parse(yaml).getStages().get(index);
  }

  // ── string-or-list surface forms ──────────────────────────────────────────

  @Test
  void singleStringFoldsToAOneElementList() {
    StageModel test =
        stageAt(
            """
                stages:
                  - stage: build
                    steps:
                      - sh: ./build.sh
                  - stage: test
                    dependsOn: build
                    steps:
                      - sh: ./test.sh
                """,
            1);
    assertEquals(
        List.of("build"), test.getDependsOn(), "a bare string folds to a one-element list");
  }

  @Test
  void aListPreservesItsIdsInOrder() {
    StageModel deploy =
        stageAt(
            """
                stages:
                  - stage: build
                    steps:
                      - sh: ./build.sh
                  - stage: lint
                    steps:
                      - sh: ./lint.sh
                  - stage: deploy
                    dependsOn: [build, lint]
                    steps:
                      - sh: ./deploy.sh
                """,
            2);
    assertEquals(List.of("build", "lint"), deploy.getDependsOn(), "ids are kept in declared order");
  }

  // ── absent — the documented "runs immediately" representation ──────────────

  @Test
  void aStageWithNoDependsOnHasAnEmptyListNotNull() {
    StageModel build =
        stageAt(
            """
                stages:
                  - stage: build
                    steps:
                      - sh: ./build.sh
                """,
            0);
    // StageModel.dependsOn defaults to a non-null empty list — empty == "runs immediately" (root).
    assertTrue(build.getDependsOn().isEmpty(), "no dependsOn: key -> getDependsOn() is empty");
  }

  // ── stage-only: no step-level meaning (no-op) ─────────────────────────────

  @Test
  void theScopeDoesNotApplyToAStepAndKeyIsDependsOn() {
    DependsOnScope scope = new DependsOnScope();
    assertFalse(scope.appliesToStep(), "dependsOn is a stage-only key");
    assertEquals("dependsOn", scope.key());
  }

  @Test
  void parseStepIsANoOpAndTouchesNothingOnTheStep() {
    StepModel step = new StepModel();
    step.setDescriptorId("sh"); // a sentinel the no-op must leave alone
    // parseStep is a deliberate no-op; calling it must not throw nor mutate the step.
    new DependsOnScope().parseStep(null, step, ParseContext.forStep("stage 'x' step 0"));
    assertEquals("sh", step.getDescriptorId(), "the no-op leaves the step untouched");
  }

  @Test
  void aStepLevelDependsOnIsRejectedAsAnUnknownKey() {
    // because dependsOn is stage-only it is not a recognised step key — the strict unknown-key
    // gate rejects it loudly rather than silently no-op'ing on the step node.
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                        stages:
                          - stage: test
                            steps:
                              - sh: ./test.sh
                                dependsOn: build
                        """));
    assertTrue(e.getMessage().contains("dependsOn"), e.getMessage());
  }

  // ── typed-read sad paths (design/48 D3/D4) — located, explanatory ─────────

  @Test
  void aNumberIsALocatedTypeErrorNotCoercedToAString() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                        stages:
                          - stage: test
                            dependsOn: 5
                            steps:
                              - sh: ./test.sh
                        """));
    String msg = e.getMessage();
    assertTrue(msg.contains("stage 'test'"), msg);
    assertTrue(msg.contains("dependsOn"), msg);
    assertTrue(msg.contains("must be a string or a list of strings"), msg);
    assertTrue(msg.contains("the number 5"), msg);
  }

  @Test
  void anExplicitNullIsRejectedAsPresentButNull() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                        stages:
                          - stage: test
                            dependsOn: null
                            steps:
                              - sh: ./test.sh
                        """));
    String msg = e.getMessage();
    assertTrue(msg.contains("dependsOn"), msg);
    assertTrue(msg.contains("present but null"), msg);
  }
}

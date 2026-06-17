package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests {@code onFailure:} parsing and validation (design/68, #947).
 *
 * <p>Stage-level scope that flips a stage into a failure handler. Covers:
 *
 * <ul>
 *   <li>parse of single-string and list shapes;
 *   <li>composition with {@code when:} (AND);
 *   <li>mutual exclusion with {@code dependsOn:};
 *   <li>empty-list rejection;
 *   <li>self-reference rejection;
 *   <li>unknown-target-id rejection;
 *   <li>matrix-prototype reference is accepted (pre-expansion contract);
 *   <li>typed-read strictness (null and wrong-type values rejected).
 * </ul>
 */
class OnFailureScopeTest {

  private static StageModel stage(PipelineModel m, String name) {
    return m.getStages().stream()
        .filter(s -> s.getName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no stage named " + name));
  }

  // ── positive parse ───────────────────────────────────────────────────────

  @Test
  void onFailureWithListIsSetOnTheStageModel() {
    PipelineModel m =
        TitanYamlParser.parse(
            """
            stages:
              - stage: Build
                steps:
                  - sh: ./build.sh
              - stage: Test
                steps:
                  - sh: ./test.sh
              - stage: NotifyOps
                onFailure: [Build, Test]
                steps:
                  - sh: ./notify.sh
            """);
    assertEquals(List.of("Build", "Test"), stage(m, "NotifyOps").getOnFailureStages());
  }

  @Test
  void onFailureWithSingleStringNormalisesToSingletonList() {
    PipelineModel m =
        TitanYamlParser.parse(
            """
            stages:
              - stage: Build
                steps:
                  - sh: ./build.sh
              - stage: NotifyOps
                onFailure: Build
                steps:
                  - sh: ./notify.sh
            """);
    assertEquals(List.of("Build"), stage(m, "NotifyOps").getOnFailureStages());
  }

  @Test
  void onFailureAbsentLeavesEmptyList() {
    PipelineModel m =
        TitanYamlParser.parse(
            """
            stages:
              - stage: Build
                steps:
                  - sh: ./build.sh
            """);
    assertTrue(stage(m, "Build").getOnFailureStages().isEmpty());
  }

  // ── composition with when: (design/68 D2) ────────────────────────────────

  @Test
  void onFailureAndWhenComposeOnTheSameStage() {
    PipelineModel m =
        TitanYamlParser.parse(
            """
            stages:
              - stage: Build
                steps:
                  - sh: ./build.sh
              - stage: NotifyOps
                onFailure: [Build]
                when: "${params.notifyOnFailure}"
                steps:
                  - sh: ./notify.sh
            """);
    StageModel notify = stage(m, "NotifyOps");
    assertEquals(List.of("Build"), notify.getOnFailureStages());
    assertEquals("${params.notifyOnFailure}", notify.getWhen());
  }

  // ── mutual exclusion with dependsOn: (design/68 D1) ──────────────────────

  @Test
  void onFailureAndDependsOnOnTheSameStageIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                    stages:
                      - stage: Build
                        steps:
                          - sh: ./build.sh
                      - stage: NotifyOps
                        dependsOn: [Build]
                        onFailure: [Build]
                        steps:
                          - sh: ./notify.sh
                    """));
    assertTrue(
        e.getMessage().contains("mutually exclusive"),
        "expected mutual-exclusion error, got: " + e.getMessage());
    assertTrue(
        e.getMessage().contains("NotifyOps"),
        "expected error to name the offending stage, got: " + e.getMessage());
  }

  // ── empty list (design/68 D4.1) ──────────────────────────────────────────

  @Test
  void onFailureEmptyListIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                    stages:
                      - stage: NotifyOps
                        onFailure: []
                        steps:
                          - sh: ./notify.sh
                    """));
    assertTrue(
        e.getMessage().contains("at least one"),
        "expected empty-list error, got: " + e.getMessage());
  }

  // ── self-reference (design/68 D4.3) ──────────────────────────────────────

  @Test
  void onFailureSelfReferenceIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                    stages:
                      - stage: NotifyOps
                        onFailure: [NotifyOps]
                        steps:
                          - sh: ./notify.sh
                    """));
    assertTrue(
        e.getMessage().contains("must not reference the stage itself"),
        "expected self-reference error, got: " + e.getMessage());
    assertTrue(
        e.getMessage().contains("NotifyOps"),
        "expected error to name the stage, got: " + e.getMessage());
  }

  // ── unknown target id (design/68 D4.2) ───────────────────────────────────

  @Test
  void onFailureUnknownTargetIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                    stages:
                      - stage: Build
                        steps:
                          - sh: ./build.sh
                      - stage: NotifyOps
                        onFailure: [DoesNotExist]
                        steps:
                          - sh: ./notify.sh
                    """));
    assertTrue(
        e.getMessage().contains("DoesNotExist"),
        "expected error to name the unknown target, got: " + e.getMessage());
    assertTrue(
        e.getMessage().contains("not a defined stage"),
        "expected unknown-stage diagnostic, got: " + e.getMessage());
  }

  // ── matrix prototype reference (design/68 D3) ────────────────────────────

  @Test
  void onFailureCanReferenceAMatrixPrototypeName() {
    // The PDL contract is the user-written name. A reference to `Build` resolves even
    // when `Build` has been expanded into `Build [java=17]` / `Build [java=21]` cells.
    // Since #947 / design/68 §6, the bake-time PrototypeExpansion ALSO rewrites
    // onFailure: targets — the prototype name expands to the full cell list so the
    // orchestrator's "any listed upstream FAILED" gate naturally means "any cell failed".
    PipelineModel m =
        TitanYamlParser.parse(
            """
            stages:
              - stage: Build
                matrix:
                  axes:
                    java: [17, 21]
                steps:
                  - sh: ./build.sh
              - stage: NotifyOps
                onFailure: [Build]
                steps:
                  - sh: ./notify.sh
            """);
    // The prototype name "Build" has been expanded to the cell names — "any cell failed"
    // semantics, parity with the dependsOn-rewrite contract (#398).
    assertEquals(
        List.of("Build [java=17]", "Build [java=21]"), stage(m, "NotifyOps").getOnFailureStages());
    // Sanity: the prototype itself fanned out into cells (no stage literally named "Build").
    assertTrue(
        m.getStages().stream().noneMatch(s -> s.getName().equals("Build")),
        "matrix prototype must have been expanded away");
  }

  // ── typed-read strictness (design/48 D2/D3) ──────────────────────────────

  @Test
  void onFailureNullIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                    stages:
                      - stage: NotifyOps
                        onFailure: ~
                        steps:
                          - sh: ./notify.sh
                    """));
    assertTrue(e.getMessage().contains("onFailure"), e.getMessage());
  }

  @Test
  void onFailureWrongTypeIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                    stages:
                      - stage: NotifyOps
                        onFailure: 42
                        steps:
                          - sh: ./notify.sh
                    """));
    assertTrue(e.getMessage().contains("onFailure"), e.getMessage());
  }

  // ── scope is stage-only (no step-level meaning) ──────────────────────────

  @Test
  void onFailureOnAStepIsAUnknownKeyError() {
    // `onFailure:` is stage-only; on a step it would be a (bogus) descriptor key. Since
    // a step has at most one descriptor and `sh:` already claims that slot, the parser
    // raises a duplicate-descriptor error.
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                    stages:
                      - stage: S
                        steps:
                          - sh: ./go.sh
                            onFailure: [X]
                    """));
    // Either the duplicate-descriptor diagnostic or an unknown-key diagnostic is fine —
    // both mean "onFailure is not a step key".
    String msg = e.getMessage();
    assertTrue(
        msg.contains("onFailure") || msg.contains("descriptor"),
        "expected step-level rejection, got: " + msg);
  }
}

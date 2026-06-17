package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests {@code matrix:} parsing and Cartesian fan-out (design/54).
 *
 * <p>The matrix scope is stage-structural: it parses the {@code matrix:} key on a stage and the
 * engine expands the single source stage into N cell stages. Each cell stage carries the source
 * stage's step list, with {@code ${matrix.<axis>}} substituted in step arguments. These tests cover
 * the Cartesian product, exclude removal, include addition, located errors for empty axes /
 * all-excluded / negative {@code maxParallel}, and the {@code maxParallel} round-trip onto the
 * baked step metadata.
 */
class MatrixScopeTest {

  private static List<StageModel> stages(String yaml) {
    return TitanYamlParser.parse(yaml).getStages();
  }

  // ── product / exclude / include ──────────────────────────────────────────

  @Test
  void twoByThreeMatrixYieldsSixCellStages() {
    List<StageModel> out =
        stages(
            """
                stages:
                  - stage: build
                    matrix:
                      axes:
                        arch: [amd64, arm64, aarch64]
                        jdk:  [17, 21]
                    steps:
                      - sh: "make build-${matrix.arch}-${matrix.jdk}"
                """);
    assertEquals(6, out.size(), "2x3 cartesian must yield 6 stages");
    // ids are cell-suffixed with the axis values, slugged.
    List<String> ids = out.stream().map(StageModel::getId).toList();
    assertTrue(ids.contains("build-arch-amd64-jdk-17"));
    assertTrue(ids.contains("build-arch-aarch64-jdk-21"));
    // step arguments are substituted per cell.
    StageModel cell =
        out.stream()
            .filter(s -> s.getId().equals("build-arch-arm64-jdk-21"))
            .findFirst()
            .orElseThrow();
    StepModel step = cell.getSteps().get(0);
    assertEquals("make build-arm64-21", step.getArguments().get("value"));
  }

  @Test
  void excludeDropsMatchingCells() {
    List<StageModel> out =
        stages(
            """
                stages:
                  - stage: build
                    matrix:
                      axes:
                        arch: [amd64, arm64, aarch64]
                        jdk:  [17, 21]
                      exclude:
                        - { arch: aarch64, jdk: 17 }
                    steps:
                      - sh: "echo ${matrix.arch} ${matrix.jdk}"
                """);
    assertEquals(5, out.size(), "exclude must drop one cell from the 6-cell grid");
    // the excluded cell must not be present.
    boolean present = out.stream().anyMatch(s -> s.getId().equals("build-arch-aarch64-jdk-17"));
    assertEquals(false, present, "excluded cell must not be present");
  }

  @Test
  void partialExcludeDropsEveryMatchingCell() {
    // exclude { arch: arm64 } drops every cell where arch=arm64, regardless of jdk.
    List<StageModel> out =
        stages(
            """
                stages:
                  - stage: build
                    matrix:
                      axes:
                        arch: [amd64, arm64]
                        jdk:  [17, 21]
                      exclude:
                        - { arch: arm64 }
                    steps:
                      - sh: "build"
                """);
    assertEquals(2, out.size(), "partial exclude must drop both arm64 cells");
    assertTrue(out.stream().allMatch(s -> s.getId().contains("amd64")));
  }

  @Test
  void includeAddsOffGridCell() {
    List<StageModel> out =
        stages(
            """
                stages:
                  - stage: build
                    matrix:
                      axes:
                        arch: [amd64, arm64]
                        jdk:  [17, 21]
                      include:
                        - { arch: s390x, jdk: 21 }
                    steps:
                      - sh: "build"
                """);
    assertEquals(5, out.size(), "2x2 + 1 include = 5 cells");
    assertTrue(
        out.stream().anyMatch(s -> s.getId().equals("build-arch-s390x-jdk-21")),
        "the off-grid include cell must be present");
  }

  @Test
  void maxParallelIsCarriedOntoEveryCellStep() {
    List<StageModel> out =
        stages(
            """
                stages:
                  - stage: build
                    matrix:
                      axes:
                        arch: [amd64, arm64]
                      maxParallel: 4
                    steps:
                      - sh: "build"
                """);
    assertEquals(2, out.size());
    for (StageModel cell : out) {
      Map<String, Object> meta =
          (Map<String, Object>) cell.getSteps().get(0).getArguments().get("__matrix");
      assertNotNull(meta, "every cell step must carry the __matrix metadata");
      assertEquals(4, meta.get("maxParallel"));
      // design/54 + #309: matrixGroup carries the prototype stage id, so the orchestrator
      // can count concurrent cells in the same fan-out group at dispatch time.
      assertEquals(
          "build",
          meta.get("matrixGroup"),
          "every cell step must carry the prototype stage id under matrixGroup");
    }
  }

  // ── error cases ──────────────────────────────────────────────────────────

  @Test
  void emptyAxisIsAParseError() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                stages(
                    """
                        stages:
                          - stage: build
                            matrix:
                              axes:
                                arch: []
                            steps:
                              - sh: "build"
                        """));
    assertTrue(
        ex.getMessage().contains("axis 'arch'") && ex.getMessage().contains("non-empty"),
        "empty axis must be reported; was: " + ex.getMessage());
  }

  @Test
  void excludeRemovingEveryCellIsAParseError() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                stages(
                    """
                        stages:
                          - stage: build
                            matrix:
                              axes:
                                arch: [amd64]
                                jdk:  [17]
                              exclude:
                                - { arch: amd64, jdk: 17 }
                            steps:
                              - sh: "build"
                        """));
    assertTrue(
        ex.getMessage().contains("zero cells"),
        "all-excluded matrix must fail; was: " + ex.getMessage());
  }

  @Test
  void maxParallelBelowOneIsAParseError() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                stages(
                    """
                        stages:
                          - stage: build
                            matrix:
                              axes:
                                arch: [amd64]
                              maxParallel: 0
                            steps:
                              - sh: "build"
                        """));
    assertTrue(
        ex.getMessage().contains("'maxParallel' must be >= 1"),
        "maxParallel<1 must fail; was: " + ex.getMessage());
  }

  @Test
  void includeDuplicatingExistingCellIsAParseError() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                stages(
                    """
                        stages:
                          - stage: build
                            matrix:
                              axes:
                                arch: [amd64]
                                jdk:  [17]
                              include:
                                - { arch: amd64, jdk: 17 }
                            steps:
                              - sh: "build"
                        """));
    assertTrue(
        ex.getMessage().contains("duplicates an existing cell"),
        "include duplicating an existing cell must fail; was: " + ex.getMessage());
  }

  @Test
  void includeMissingAxisIsAParseError() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                stages(
                    """
                        stages:
                          - stage: build
                            matrix:
                              axes:
                                arch: [amd64]
                                jdk:  [17]
                              include:
                                - { arch: s390x }
                            steps:
                              - sh: "build"
                        """));
    assertTrue(
        ex.getMessage().contains("missing axis 'jdk'"),
        "include missing a declared axis must fail; was: " + ex.getMessage());
  }

  @Test
  void axesNotAnObjectIsAParseError() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                stages(
                    """
                        stages:
                          - stage: build
                            matrix:
                              axes: []
                            steps:
                              - sh: "build"
                        """));
    assertTrue(
        ex.getMessage().contains("'axes' must be a non-empty object"),
        "axes:[] must fail; was: " + ex.getMessage());
  }

  // ── end-to-end shape ──────────────────────────────────────────────────────

  @Test
  void nonMatrixStagesAreUnchanged() {
    PipelineModel model =
        TitanYamlParser.parse(
            """
                stages:
                  - stage: build
                    steps:
                      - sh: "build"
                  - stage: deploy
                    dependsOn: build
                    steps:
                      - sh: "deploy"
                """);
    assertEquals(2, model.getStages().size(), "a pipeline without matrix is unaffected");
    assertEquals("build", model.getStages().get(0).getId());
    assertEquals("deploy", model.getStages().get(1).getId());
  }

  // ── #1092 — fail_fast, MATRIX_* env vars, DoS cap ────────────────────────

  @Test
  void everyCellExposesMatrixEnvVarsToTheStep() {
    // Per #1092 acceptance criterion: child tasks get MATRIX_OS / MATRIX_JDK env vars.
    List<StageModel> out =
        stages(
            """
                stages:
                  - stage: smoke
                    matrix:
                      axes:
                        os:  [linux, macos]
                        jdk: [17, 21]
                    steps:
                      - sh: "uname -a"
                """);
    assertEquals(4, out.size(), "2x2 matrix must yield 4 cells");
    StageModel macos21 =
        out.stream()
            .filter(s -> s.getId().equals("smoke-os-macos-jdk-21"))
            .findFirst()
            .orElseThrow();
    Map<String, String> env = macos21.getSteps().get(0).getEnv();
    assertNotNull(env, "every cell step must carry an env map");
    assertEquals("macos", env.get("MATRIX_OS"), "MATRIX_OS must reflect the cell value");
    assertEquals("21", env.get("MATRIX_JDK"), "MATRIX_JDK must reflect the cell value");
    // and stage env carries them too — so any step inheriting from stage env still sees them.
    assertEquals("macos", macos21.getEnv().get("MATRIX_OS"));
  }

  @Test
  void failFastDefaultsToTrueAndIsPropagatedAsStepMetadata() {
    List<StageModel> out =
        stages(
            """
                stages:
                  - stage: build
                    matrix:
                      axes:
                        arch: [amd64, arm64]
                    steps:
                      - sh: "build"
                """);
    Map<String, Object> meta =
        (Map<String, Object>) out.get(0).getSteps().get(0).getArguments().get("__matrix");
    assertEquals(true, meta.get("failFast"), "fail_fast default must be true");
  }

  @Test
  void failFastFalseIsPropagatedAsStepMetadata() {
    List<StageModel> out =
        stages(
            """
                stages:
                  - stage: smoke
                    matrix:
                      axes:
                        region: [us, eu]
                      fail_fast: false
                    steps:
                      - sh: "smoke"
                """);
    for (StageModel cell : out) {
      Map<String, Object> meta =
          (Map<String, Object>) cell.getSteps().get(0).getArguments().get("__matrix");
      assertEquals(
          false,
          meta.get("failFast"),
          "every cell must carry fail_fast=false on its __matrix metadata");
    }
  }

  @Test
  void failFastMustBeBoolean() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                stages(
                    """
                        stages:
                          - stage: smoke
                            matrix:
                              axes:
                                region: [us, eu]
                              fail_fast: "yes"
                            steps:
                              - sh: "smoke"
                        """));
    assertTrue(
        ex.getMessage().contains("'fail_fast' must be a boolean"),
        "non-boolean fail_fast must fail; was: " + ex.getMessage());
  }

  @Test
  void tenAxisMatrixIsRejectedByDoSGuard() {
    // Adversarial: 10 axes × 2 values = 2^10 = 1024 cells — way past the 100-cell cap.
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                stages(
                    """
                        stages:
                          - stage: smoke
                            matrix:
                              axes:
                                a: [1, 2]
                                b: [1, 2]
                                c: [1, 2]
                                d: [1, 2]
                                e: [1, 2]
                                f: [1, 2]
                                g: [1, 2]
                                h: [1, 2]
                                i: [1, 2]
                                j: [1, 2]
                            steps:
                              - sh: "smoke"
                        """));
    assertTrue(
        ex.getMessage().contains("DoS guard") && ex.getMessage().contains("100"),
        "10-axis matrix must trip the DoS guard; was: " + ex.getMessage());
  }

  @Test
  void axisNameIsSanitisedIntoEnvName() {
    // unit-level guard for the matrixEnvName helper; documents the contract.
    assertEquals("MATRIX_OS", MatrixScope.matrixEnvName("os"));
    assertEquals("MATRIX_NODEVERSION", MatrixScope.matrixEnvName("nodeVersion"));
    assertEquals("MATRIX_JDK_MINOR", MatrixScope.matrixEnvName("jdk.minor"));
    assertEquals("MATRIX_X", MatrixScope.matrixEnvName("___"));
  }

  @Test
  void matrixAndPlainStagesCoexist() {
    PipelineModel model =
        TitanYamlParser.parse(
            """
                stages:
                  - stage: build
                    matrix:
                      axes:
                        arch: [amd64, arm64]
                    steps:
                      - sh: "build ${matrix.arch}"
                  - stage: deploy
                    steps:
                      - sh: "deploy"
                """);
    assertEquals(3, model.getStages().size(), "2 matrix cells + 1 plain = 3 stages");
    assertEquals("deploy", model.getStages().get(2).getId());
  }
}

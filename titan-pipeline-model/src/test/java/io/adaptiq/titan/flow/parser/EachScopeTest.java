package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests {@code each:} parsing and 1D fan-out (design/55).
 *
 * <p>Sibling of {@link MatrixScopeTest}: the each scope is stage-structural; it parses the {@code
 * each:} key on a stage and the engine expands the single source stage into N cell stages. Each
 * cell carries the source stage's step list with {@code ${each.<var>}} substituted in step
 * arguments. These tests cover the happy path, the error cases and the matrix-exclusion guard.
 */
class EachScopeTest {

  private static List<StageModel> stages(String yaml) {
    return TitanYamlParser.parse(yaml).getStages();
  }

  // ── happy path ───────────────────────────────────────────────────────────

  @Test
  void threeElementEachYieldsThreeStagesWithSubstitution() {
    List<StageModel> out =
        stages(
            """
                stages:
                  - stage: deploy
                    each:
                      var: region
                      in: [us-east-1, eu-west-1, ap-southeast-2]
                    steps:
                      - sh: "kubectl --context ${each.region} apply -f deploy.yaml"
                """);
    assertEquals(3, out.size(), "3-element each must yield 3 stages");
    List<String> ids = out.stream().map(StageModel::getId).toList();
    assertTrue(ids.contains("deploy-region-us-east-1"));
    assertTrue(ids.contains("deploy-region-eu-west-1"));
    assertTrue(ids.contains("deploy-region-ap-southeast-2"));
    StageModel mid =
        out.stream()
            .filter(s -> s.getId().equals("deploy-region-eu-west-1"))
            .findFirst()
            .orElseThrow();
    StepModel step = mid.getSteps().get(0);
    assertEquals(
        "kubectl --context eu-west-1 apply -f deploy.yaml", step.getArguments().get("value"));
  }

  @Test
  void cellStageLabelCarriesTheBinding() {
    List<StageModel> out =
        stages(
            """
                stages:
                  - stage: deploy
                    each:
                      var: region
                      in: [us-east-1]
                    steps:
                      - sh: "x"
                """);
    assertEquals(1, out.size());
    assertEquals("deploy [region=us-east-1]", out.get(0).getName());
  }

  @Test
  void maxParallelIsCarriedOntoEveryCellStep() {
    List<StageModel> out =
        stages(
            """
                stages:
                  - stage: deploy
                    each:
                      var: region
                      in: [us-east-1, eu-west-1]
                      maxParallel: 2
                    steps:
                      - sh: "x"
                """);
    assertEquals(2, out.size());
    for (StageModel cell : out) {
      @SuppressWarnings("unchecked")
      Map<String, Object> meta =
          (Map<String, Object>) cell.getSteps().get(0).getArguments().get("__each");
      assertNotNull(meta, "every cell step must carry the __each metadata");
      assertEquals(2, meta.get("maxParallel"));
      assertEquals("region", meta.get("var"));
    }
  }

  @Test
  void eachCellStampsSharedMatrixMetadataForFailFast() {
    // #1213: each: must stamp the SAME __matrix metadata MatrixScope writes (matrixGroup +
    // failFast=true) so the orchestrator's fail-fast sibling-cancellation applies to each fan-outs.
    List<StageModel> out =
        stages(
            """
                stages:
                  - stage: deploy
                    each:
                      var: region
                      in: [us-east-1, eu-west-1]
                    steps:
                      - sh: "x"
                """);
    assertEquals(2, out.size());
    for (StageModel cell : out) {
      @SuppressWarnings("unchecked")
      Map<String, Object> matrix =
          (Map<String, Object>) cell.getSteps().get(0).getArguments().get("__matrix");
      assertNotNull(matrix, "every each cell must carry the shared __matrix metadata");
      assertEquals(
          "deploy", matrix.get("matrixGroup"), "matrixGroup must be the prototype stage id");
      assertEquals(true, matrix.get("failFast"), "each defaults to fail-fast (GHA semantics)");
      // __each metadata is preserved alongside it.
      assertNotNull(
          cell.getSteps().get(0).getArguments().get("__each"), "__each metadata still present");
    }
  }

  // ── error cases ──────────────────────────────────────────────────────────

  @Test
  void emptyInListIsAParseError() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                stages(
                    """
                        stages:
                          - stage: deploy
                            each:
                              var: region
                              in: []
                            steps:
                              - sh: "x"
                        """));
    assertTrue(
        ex.getMessage().contains("'in' must be a non-empty list"),
        "empty in must be reported; was: " + ex.getMessage());
  }

  @Test
  void missingVarIsAParseError() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                stages(
                    """
                        stages:
                          - stage: deploy
                            each:
                              in: [a, b]
                            steps:
                              - sh: "x"
                        """));
    assertTrue(
        ex.getMessage().contains("'var' is required"),
        "missing var must be reported; was: " + ex.getMessage());
  }

  @Test
  void missingInIsAParseError() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                stages(
                    """
                        stages:
                          - stage: deploy
                            each:
                              var: region
                            steps:
                              - sh: "x"
                        """));
    assertTrue(
        ex.getMessage().contains("'in' is required"),
        "missing in must be reported; was: " + ex.getMessage());
  }

  @Test
  void reservedVarNameIsAParseError() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                stages(
                    """
                        stages:
                          - stage: deploy
                            each:
                              var: each
                              in: [a]
                            steps:
                              - sh: "x"
                        """));
    assertTrue(
        ex.getMessage().contains("'var' is reserved"),
        "reserved var must be reported; was: " + ex.getMessage());
  }

  @Test
  void invalidVarIdentifierIsAParseError() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                stages(
                    """
                        stages:
                          - stage: deploy
                            each:
                              var: "1bad"
                              in: [a]
                            steps:
                              - sh: "x"
                        """));
    assertTrue(
        ex.getMessage().contains("[A-Za-z_]"),
        "invalid var identifier must be reported; was: " + ex.getMessage());
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
                          - stage: deploy
                            each:
                              var: region
                              in: [a]
                              maxParallel: 0
                            steps:
                              - sh: "x"
                        """));
    assertTrue(
        ex.getMessage().contains("'maxParallel' must be >= 1"),
        "maxParallel<1 must fail; was: " + ex.getMessage());
  }

  @Test
  void nonScalarElementInListIsAParseError() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                stages(
                    """
                        stages:
                          - stage: deploy
                            each:
                              var: region
                              in: [a, [nested]]
                            steps:
                              - sh: "x"
                        """));
    assertTrue(
        ex.getMessage().contains("in[1] must be a scalar"),
        "non-scalar list element must fail; was: " + ex.getMessage());
  }

  @Test
  void unknownEachReferenceIsAParseError() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                stages(
                    """
                        stages:
                          - stage: deploy
                            each:
                              var: region
                              in: [us-east-1]
                            steps:
                              - sh: "echo ${each.zone}"
                        """));
    assertTrue(
        ex.getMessage().contains("unknown binding 'zone'"),
        "unknown each reference must fail; was: " + ex.getMessage());
  }

  @Test
  void eachAndMatrixOnSameStageIsAParseError() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                stages(
                    """
                        stages:
                          - stage: deploy
                            matrix:
                              axes:
                                arch: [amd64]
                            each:
                              var: region
                              in: [us-east-1]
                            steps:
                              - sh: "x"
                        """));
    assertTrue(
        ex.getMessage().contains("mutually exclusive"),
        "each+matrix together must fail; was: " + ex.getMessage());
  }

  // ── end-to-end shape ──────────────────────────────────────────────────────

  @Test
  void eachAndPlainStagesCoexist() {
    List<StageModel> out =
        stages(
            """
                stages:
                  - stage: deploy
                    each:
                      var: region
                      in: [us-east-1, eu-west-1]
                    steps:
                      - sh: "deploy ${each.region}"
                  - stage: smoke
                    steps:
                      - sh: "smoke"
                """);
    assertEquals(3, out.size(), "2 each cells + 1 plain = 3 stages");
    assertEquals("smoke", out.get(2).getId());
  }
}

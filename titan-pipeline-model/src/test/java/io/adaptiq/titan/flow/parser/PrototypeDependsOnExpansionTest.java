package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.GateModel;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue #398 — when a downstream stage's {@code dependsOn:} names a matrix/each prototype, the
 * baker resolves it to all cell names of that prototype (fan-in semantics; Buildkite / GHA matrix /
 * Bazel parity). A literal cell name still passes through unchanged, so existing pipelines that
 * already spell out cells keep working.
 */
class PrototypeDependsOnExpansionTest {

  @Test
  void dependsOnMatrixPrototypeFansInToAllCells() {
    PipelineModel model =
        TitanYamlParser.parseAndValidate(
            """
                stages:
                  - stage: build
                    matrix:
                      axes:
                        module: [a, b, c]
                    steps:
                      - sh: "build ${matrix.module}"
                  - stage: deploy
                    dependsOn: [build]
                    steps:
                      - sh: "deploy"
                """);
    // 3 matrix cells + 1 deploy
    assertEquals(4, model.getStages().size());
    StageModel deploy = model.getStages().get(3);
    assertEquals("deploy", deploy.getName());
    // The prototype name "build" should have been expanded to all three cell names.
    List<String> deps = deploy.getDependsOn();
    assertEquals(3, deps.size(), "dependsOn [build] should expand to 3 cell names, was " + deps);
    assertTrue(deps.contains("build [module=a]"));
    assertTrue(deps.contains("build [module=b]"));
    assertTrue(deps.contains("build [module=c]"));
    assertFalse(deps.contains("build"), "the prototype name itself must be gone");
  }

  @Test
  void dependsOnEachPrototypeFansInToAllCells() {
    PipelineModel model =
        TitanYamlParser.parseAndValidate(
            """
                stages:
                  - stage: tests
                    each:
                      var: module
                      in: [server, worker]
                    steps:
                      - sh: "./gradlew :${each.module}:test"
                  - stage: report
                    dependsOn: [tests]
                    steps:
                      - sh: "report"
                """);
    assertEquals(3, model.getStages().size());
    StageModel report = model.getStages().get(2);
    List<String> deps = report.getDependsOn();
    assertEquals(
        2, deps.size(), "dependsOn [tests] should fan into 2 each-cell names, was " + deps);
    assertTrue(deps.contains("tests [module=server]"));
    assertTrue(deps.contains("tests [module=worker]"));
  }

  @Test
  void onFailureMatrixPrototypeFansInToAllCells() {
    // design/68 D3 / #947: an onFailure: reference to a matrix prototype must resolve to ANY
    // cell failing, encoded by expanding the prototype name to the full cell list at bake time.
    PipelineModel model =
        TitanYamlParser.parseAndValidate(
            """
                stages:
                  - stage: flaky
                    matrix:
                      axes:
                        os: [linux, mac]
                    steps:
                      - sh: "./run ${matrix.os}"
                  - stage: notify
                    onFailure: [flaky]
                    steps:
                      - sh: "notify"
                """);
    StageModel notify = model.getStages().get(model.getStages().size() - 1);
    assertEquals("notify", notify.getName());
    List<String> on = notify.getOnFailureStages();
    assertEquals(2, on.size(), "onFailure [flaky] should expand to 2 cell names, was " + on);
    assertTrue(on.contains("flaky [os=linux]"));
    assertTrue(on.contains("flaky [os=mac]"));
    assertFalse(on.contains("flaky"), "the prototype name itself must be gone from onFailure");
  }

  @Test
  void gateDependsOnPrototypeFansInToAllCells() {
    PipelineModel model =
        TitanYamlParser.parseAndValidate(
            """
                stages:
                  - stage: build
                    matrix:
                      axes:
                        arch: [amd64, arm64]
                    steps:
                      - sh: "build"
                  - gate: ship
                    requiresApproval: true
                    dependsOn: [build]
                """);
    assertEquals(2, model.getStages().size());
    GateModel gate = model.getGates().get(0);
    List<String> deps = gate.getDependsOn();
    assertEquals(
        2, deps.size(), "gate dependsOn [build] should resolve to both cells, was " + deps);
    assertTrue(deps.contains("build [arch=amd64]"));
    assertTrue(deps.contains("build [arch=arm64]"));
  }

  @Test
  void literalCellNameStillResolvesAfterExpansion() {
    // A pre-#398 pipeline that spells the cell name out by hand must keep working.
    PipelineModel model =
        TitanYamlParser.parseAndValidate(
            """
                stages:
                  - stage: build
                    matrix:
                      axes:
                        arch: [amd64, arm64]
                    steps:
                      - sh: "build"
                  - stage: ship
                    dependsOn: ["build [arch=amd64]"]
                    steps:
                      - sh: "ship"
                """);
    StageModel ship = model.getStages().get(2);
    assertEquals(List.of("build [arch=amd64]"), ship.getDependsOn());
  }

  @Test
  void mixingPrototypeAndCellRefDedupes() {
    // If someone writes `dependsOn: [build, "build [arch=amd64]"]` we must not produce a
    // duplicate edge — the prototype expansion already covers the cell. LinkedHashSet de-dupes.
    PipelineModel model =
        TitanYamlParser.parseAndValidate(
            """
                stages:
                  - stage: build
                    matrix:
                      axes:
                        arch: [amd64, arm64]
                    steps:
                      - sh: "build"
                  - stage: ship
                    dependsOn: [build, "build [arch=amd64]"]
                    steps:
                      - sh: "ship"
                """);
    StageModel ship = model.getStages().get(2);
    List<String> deps = ship.getDependsOn();
    assertEquals(2, deps.size(), "prototype expansion + explicit cell must de-dup, was " + deps);
    assertTrue(deps.contains("build [arch=amd64]"));
    assertTrue(deps.contains("build [arch=arm64]"));
  }

  @Test
  void nonPrototypeDependsOnIsUnchanged() {
    // Sanity: a plain dependsOn between two non-fanned stages is byte-identical post-rewrite.
    PipelineModel model =
        TitanYamlParser.parseAndValidate(
            """
                stages:
                  - stage: a
                    steps:
                      - sh: "a"
                  - stage: b
                    dependsOn: [a]
                    steps:
                      - sh: "b"
                """);
    assertEquals(List.of("a"), model.getStages().get(1).getDependsOn());
  }
}

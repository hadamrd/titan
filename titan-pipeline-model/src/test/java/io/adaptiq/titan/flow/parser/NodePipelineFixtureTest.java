package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Parses {@code e2e/pipelines/node-app/titan-pipeline.yml} (issue #1119) and asserts the parse-time
 * invariants the real-shaped Node fixture depends on.
 *
 * <ul>
 *   <li>Top-level {@code env:} carries {@code NODE_ENV} + {@code REGISTRY_URL} (#1094).
 *   <li>The {@code lint} stage's first step overrides {@code NODE_ENV} per-step — proves the
 *       step-wins merge order at parse time (the merge itself is exercised in {@link
 *       MergedEnvTest}).
 *   <li>Stage {@code deploy-staging} carries a BAKE-LEGAL {@code when:} guard reading {@code
 *       params.*} only (#1093, reworked in #43 — stage-level {@code when:} is evaluated at bake
 *       time against {@code params.*} and must never reference the runtime-only {@code
 *       steps.*}/{@code branch} variables the engine now rejects loudly since #42).
 *   <li>Stage {@code smoke-test} carries NO {@code when:} — it gates on {@code dependsOn:
 *       [deploy-staging]} + the engine's default blockOnFailure policy, the same repair as the
 *       node-app-with-failing-test sibling.
 *   <li>All 6 expected stages are present in declaration order.
 * </ul>
 *
 * <p>If a future PR breaks the fixture YAML in a way the e2e specs would only catch on a live rig,
 * this test fails fast in unit-time and points at the offending field. Adversarial by design — the
 * assertions name the customer-shaped value, not just "non-null".
 */
class NodePipelineFixtureTest {

  /** Walks up from this module to find the repo root, then reads the fixture YAML. */
  private static String readFixture() throws IOException {
    Path cur = Path.of("").toAbsolutePath();
    for (int i = 0; i < 6 && cur != null; i++) {
      Path candidate = cur.resolve("e2e/pipelines/node-app/titan-pipeline.yml");
      if (Files.isRegularFile(candidate)) {
        return Files.readString(candidate);
      }
      cur = cur.getParent();
    }
    throw new IOException("could not locate e2e/pipelines/node-app/titan-pipeline.yml");
  }

  @Test
  void fixtureParsesAndCarriesTopLevelEnv() throws IOException {
    PipelineModel model = TitanYamlParser.parse(readFixture());

    Map<String, String> env = model.getEnv();
    assertNotNull(env, "top-level env: should be parsed");
    assertEquals("production", env.get("NODE_ENV"));
    assertEquals("https://registry.npmjs.org", env.get("REGISTRY_URL"));
  }

  @Test
  void fixtureDeclaresSixStagesInOrder() throws IOException {
    PipelineModel model = TitanYamlParser.parse(readFixture());
    List<String> names = model.getStages().stream().map(StageModel::getName).toList();
    assertEquals(
        List.of("install", "lint", "unit-test", "build", "deploy-staging", "smoke-test"),
        names,
        "fixture stage order is part of the customer-shaped contract");
  }

  @Test
  void lintStageOverridesNodeEnvAtStepLevel() throws IOException {
    PipelineModel model = TitanYamlParser.parse(readFixture());
    StageModel lint =
        model.getStages().stream()
            .filter(s -> "lint".equals(s.getName()))
            .findFirst()
            .orElseThrow();
    StepModel firstStep = lint.getSteps().get(0);
    Map<String, String> stepEnv = firstStep.getEnv();
    assertNotNull(stepEnv, "lint step should declare a per-step env: override");
    assertEquals(
        "development",
        stepEnv.get("NODE_ENV"),
        "step-level NODE_ENV must override the top-level production value");
  }

  @Test
  void fixtureDeclaresDeployEnvParameter() throws IOException {
    PipelineModel model = TitanYamlParser.parse(readFixture());
    assertTrue(
        model.getParameters().stream().anyMatch(p -> "deployEnv".equals(p.getName())),
        "the fixture must declare the deployEnv parameter its bake-time when: reads (#43)");
  }

  @Test
  void deployStagingCarriesBakeLegalParamsWhen() throws IOException {
    PipelineModel model = TitanYamlParser.parse(readFixture());
    StageModel deploy =
        model.getStages().stream()
            .filter(s -> "deploy-staging".equals(s.getName()))
            .findFirst()
            .orElseThrow();
    String when = deploy.getWhen();
    assertNotNull(when, "deploy-staging must carry a stage-level when:");
    assertTrue(
        when.contains("params.deployEnv"),
        "deploy-staging when: should gate on params.deployEnv, got: " + when);
    // Adversarial (#43): stage-level when: is bake-time — the runtime-only variables must
    // never creep back in (they made every bake of this fixture fail loudly post-#42).
    assertFalse(
        when.contains("steps.") || when.contains("branch"),
        "deploy-staging when: references runtime-only steps.*/branch — bake-illegal: " + when);
  }

  @Test
  void smokeTestGatesViaDependsOnNotWhen() throws IOException {
    PipelineModel model = TitanYamlParser.parse(readFixture());
    StageModel smoke =
        model.getStages().stream()
            .filter(s -> "smoke-test".equals(s.getName()))
            .findFirst()
            .orElseThrow();
    // The old `when: "steps.deploy-staging.result == 'success'"` was bake-illegal (#43).
    // Result-conditional execution is what dependsOn + blockOnFailure already provide.
    assertNull(
        smoke.getWhen(),
        "smoke-test must NOT carry a stage-level when: — steps.* results are runtime-only");
    assertEquals(
        List.of("deploy-staging"),
        smoke.getDependsOn(),
        "smoke-test gates on dependsOn: [deploy-staging] + default blockOnFailure");
  }
}

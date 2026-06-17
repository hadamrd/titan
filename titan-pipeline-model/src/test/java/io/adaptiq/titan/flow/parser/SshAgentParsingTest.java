package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StepModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests {@code sshAgent:} parsing on a step and a stage (design/41 §2, §2.1). {@code sshAgent} is a
 * declarative per-step property — Titan has no CPS and no blocks, so it is not a wrapping construct
 * (design/41 §1). These tests cover both shapes (step-local and stage-flattened), the scalar
 * shorthand, the {@code sh}-only restriction enforced on the effective (post-flatten) list, and the
 * round-trip of the ids through {@code pipeline_model_json} — the model carries ids, never key
 * material.
 */
class SshAgentParsingTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  // ── step-level sshAgent ───────────────────────────────────────────────────

  @Test
  void parsesAnSshAgentListOnAShStep() {
    String yaml =
        """
                stages:
                  - stage: Deploy
                    steps:
                      - sh: git push origin main
                        sshAgent:
                          - prod-deploy-key
                          - github-bot-key
                """;
    StepModel step = TitanYamlParser.parse(yaml).getStages().get(0).getSteps().get(0);
    assertEquals(List.of("prod-deploy-key", "github-bot-key"), step.getSshAgent());
  }

  @Test
  void parsesABareScalarSshAgentAsAOneElementList() {
    String yaml =
        """
                stages:
                  - stage: Deploy
                    steps:
                      - sh: git push
                        sshAgent: prod-deploy-key
                """;
    StepModel step = TitanYamlParser.parse(yaml).getStages().get(0).getSteps().get(0);
    assertEquals(List.of("prod-deploy-key"), step.getSshAgent());
  }

  @Test
  void aStepWithoutSshAgentHasAnEmptyList() {
    StepModel step =
        TitanYamlParser.parse("stages:\n  - stage: Build\n    steps:\n      - sh: mvn package\n")
            .getStages()
            .get(0)
            .getSteps()
            .get(0);
    assertTrue(step.getSshAgent().isEmpty());
  }

  // ── stage-level sshAgent flattens onto every sh step ──────────────────────

  @Test
  void stageLevelSshAgentIsFlattenedOntoEveryStep() {
    String yaml =
        """
                stages:
                  - stage: Deploy
                    sshAgent:
                      - shared-infra-key
                    steps:
                      - sh: deploy-a.sh
                      - sh: deploy-b.sh
                """;
    List<StepModel> steps = TitanYamlParser.parse(yaml).getStages().get(0).getSteps();
    assertEquals(2, steps.size());
    for (StepModel step : steps) {
      assertEquals(
          List.of("shared-infra-key"),
          step.getSshAgent(),
          "every sh step inherits the stage-level sshAgent");
    }
  }

  @Test
  void stageIdsComeFirstAndStepIdsAreAppendedDeDuplicated() {
    // The effective list: stage ids first, the step's own ids appended after, order preserved,
    // and a duplicate (here `shared-infra-key` declared at both levels) collapsed once.
    String yaml =
        """
                stages:
                  - stage: Deploy
                    sshAgent:
                      - shared-infra-key
                    steps:
                      - sh: deploy.sh
                        sshAgent:
                          - shared-infra-key
                          - prod-deploy-key
                """;
    StepModel step = TitanYamlParser.parse(yaml).getStages().get(0).getSteps().get(0);
    assertEquals(List.of("shared-infra-key", "prod-deploy-key"), step.getSshAgent());
  }

  // ── the sh-only restriction (design/41 §2.1) ──────────────────────────────

  @Test
  void aStepLevelSshAgentOnANonShStepIsRejected() {
    String yaml =
        """
                stages:
                  - stage: Deploy
                    steps:
                      - git: https://example.com/repo.git
                        sshAgent:
                          - prod-deploy-key
                """;
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
    assertTrue(e.getMessage().contains("sshAgent"), e.getMessage());
    assertTrue(e.getMessage().contains("git"), e.getMessage());
    assertTrue(e.getMessage().contains("step-level"), e.getMessage());
  }

  @Test
  void aStageLevelSshAgentFlatteningOntoANonShStepIsRejected() {
    // The effective-list check — this is the stage-flatten gap design/41 §2.1 specifically
    // fixed: a stage-level sshAgent: that would land on a non-sh step is caught too.
    String yaml =
        """
                stages:
                  - stage: Deploy
                    sshAgent:
                      - shared-infra-key
                    steps:
                      - git: https://example.com/repo.git
                """;
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
    assertTrue(e.getMessage().contains("sshAgent"), e.getMessage());
    assertTrue(e.getMessage().contains("flattened from a stage-level"), e.getMessage());
  }

  @Test
  void aBlankSshAgentIdIsRejected() {
    String yaml =
        """
                stages:
                  - stage: Deploy
                    steps:
                      - sh: git push
                        sshAgent:
                          - ""
                """;
    assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
  }

  // ── persistence round-trip ────────────────────────────────────────────────

  @Test
  void sshAgentIdsRoundTripThroughPipelineModelJson() throws Exception {
    String yaml =
        """
                stages:
                  - stage: Deploy
                    steps:
                      - sh: git push origin main
                        sshAgent:
                          - prod-deploy-key
                          - github-bot-key
                """;
    PipelineModel model = TitanYamlParser.parse(yaml);
    String json = JSON.writeValueAsString(model);
    // The persisted model carries only the credential ids — never key material.
    assertTrue(json.contains("prod-deploy-key"));
    assertTrue(json.contains("github-bot-key"));

    PipelineModel reloaded = JSON.readValue(json, PipelineModel.class);
    StepModel step = reloaded.getStages().get(0).getSteps().get(0);
    assertEquals(List.of("prod-deploy-key", "github-bot-key"), step.getSshAgent());
  }
}

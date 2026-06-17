package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.flow.model.CredentialBinding;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests {@code credentials:} parsing on a step and a stage (design/39 §2, implementing design/32
 * §12 D6). A credential binding is a declarative per-step property — Titan has no CPS and no
 * blocks, so it is not a wrapping construct (design/39 §1). The tests cover both shapes (step-local
 * and stage-flattened), the supported types, the error paths, and the round-trip of the binding
 * through {@code pipeline_model_json} (it must carry ids, never secrets).
 */
class CredentialBindingParsingTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  // ── step-level credentials ────────────────────────────────────────────────

  @Test
  void parsesAUsernamePasswordBindingOnAStep() {
    String yaml =
        """
                stages:
                  - stage: Deploy
                    steps:
                      - sh: docker login
                        credentials:
                          - id: docker-registry
                            type: usernamePassword
                            usernameVariable: REG_USER
                            passwordVariable: REG_PASS
                """;
    StepModel step = TitanYamlParser.parse(yaml).getStages().get(0).getSteps().get(0);
    assertEquals(1, step.getCredentials().size());
    CredentialBinding binding = step.getCredentials().get(0);
    assertEquals("docker-registry", binding.getId());
    assertEquals(CredentialBinding.TYPE_USERNAME_PASSWORD, binding.getType());
    assertEquals("REG_USER", binding.binding("usernameVariable"));
    assertEquals("REG_PASS", binding.binding("passwordVariable"));
  }

  @Test
  void parsesAStringBindingOnAStep() {
    String yaml =
        """
                stages:
                  - stage: Deploy
                    steps:
                      - sh: deploy.sh
                        credentials:
                          - id: deploy-token
                            type: string
                            variable: DEPLOY_TOKEN
                """;
    CredentialBinding binding =
        TitanYamlParser.parse(yaml).getStages().get(0).getSteps().get(0).getCredentials().get(0);
    assertEquals("deploy-token", binding.getId());
    assertEquals(CredentialBinding.TYPE_STRING, binding.getType());
    assertEquals("DEPLOY_TOKEN", binding.binding("variable"));
  }

  @Test
  void parsesAFileAndSshKeyBinding() {
    String yaml =
        """
                stages:
                  - stage: Deploy
                    steps:
                      - sh: kubectl apply
                        credentials:
                          - id: kubeconfig
                            type: file
                            variable: KUBECONFIG
                          - id: prod-ssh
                            type: sshKey
                            keyFileVariable: KEYFILE
                            usernameVariable: SSH_USER
                            passphraseVariable: SSH_PASS
                """;
    List<CredentialBinding> creds =
        TitanYamlParser.parse(yaml).getStages().get(0).getSteps().get(0).getCredentials();
    assertEquals(2, creds.size());
    assertEquals(CredentialBinding.TYPE_FILE, creds.get(0).getType());
    assertEquals("KUBECONFIG", creds.get(0).binding("variable"));
    assertEquals(CredentialBinding.TYPE_SSH_KEY, creds.get(1).getType());
    assertEquals("KEYFILE", creds.get(1).binding("keyFileVariable"));
    assertEquals("SSH_USER", creds.get(1).binding("usernameVariable"));
    assertEquals("SSH_PASS", creds.get(1).binding("passphraseVariable"));
  }

  @Test
  void aStepWithoutCredentialsHasAnEmptyList() {
    StepModel step =
        TitanYamlParser.parse("stages:\n  - stage: Build\n    steps:\n      - sh: mvn package\n")
            .getStages()
            .get(0)
            .getSteps()
            .get(0);
    assertTrue(step.getCredentials().isEmpty());
  }

  // ── stage-level credentials flatten onto every step ───────────────────────

  @Test
  void stageLevelCredentialsAreFlattenedOntoEveryStep() {
    String yaml =
        """
                stages:
                  - stage: Deploy
                    credentials:
                      - id: shared-token
                        type: string
                        variable: TOKEN
                    steps:
                      - sh: step-one.sh
                      - sh: step-two.sh
                """;
    List<StepModel> steps = TitanYamlParser.parse(yaml).getStages().get(0).getSteps();
    assertEquals(2, steps.size());
    for (StepModel step : steps) {
      assertEquals(1, step.getCredentials().size(), "every step inherits the stage binding");
      assertEquals("shared-token", step.getCredentials().get(0).getId());
    }
  }

  @Test
  void aStepCredentialIsAppendedAfterTheFlattenedStageCredential() {
    String yaml =
        """
                stages:
                  - stage: Deploy
                    credentials:
                      - id: stage-cred
                        type: string
                        variable: STAGE_VAR
                    steps:
                      - sh: deploy.sh
                        credentials:
                          - id: step-cred
                            type: string
                            variable: STEP_VAR
                """;
    List<CredentialBinding> creds =
        TitanYamlParser.parse(yaml).getStages().get(0).getSteps().get(0).getCredentials();
    assertEquals(2, creds.size());
    // Stage binding first, step binding second — so the step entry wins a variable clash.
    assertEquals("stage-cred", creds.get(0).getId());
    assertEquals("step-cred", creds.get(1).getId());
  }

  // ── pipeline-level credentials flatten onto every step (design/42) ────────

  @Test
  void pipelineLevelCredentialsAreFlattenedOntoEveryStepOfEveryStage() {
    String yaml =
        """
                credentials:
                  - id: org-token
                    type: string
                    variable: ORG_TOKEN
                stages:
                  - stage: Build
                    steps:
                      - sh: build.sh
                  - stage: Deploy
                    dependsOn: [Build]
                    steps:
                      - sh: deploy-a.sh
                      - sh: deploy-b.sh
                """;
    for (StageModel stage : TitanYamlParser.parse(yaml).getStages()) {
      for (StepModel step : stage.getSteps()) {
        assertEquals(
            1, step.getCredentials().size(), "every step inherits the pipeline-level binding");
        assertEquals("org-token", step.getCredentials().get(0).getId());
      }
    }
  }

  @Test
  void pipelineStageAndStepCredentialsMergeInPrecedenceOrder() {
    String yaml =
        """
                credentials:
                  - id: pipeline-cred
                    type: string
                    variable: PIPELINE_VAR
                stages:
                  - stage: Deploy
                    credentials:
                      - id: stage-cred
                        type: string
                        variable: STAGE_VAR
                    steps:
                      - sh: deploy.sh
                        credentials:
                          - id: step-cred
                            type: string
                            variable: STEP_VAR
                """;
    List<CredentialBinding> creds =
        TitanYamlParser.parse(yaml).getStages().get(0).getSteps().get(0).getCredentials();
    assertEquals(3, creds.size());
    // pipeline, then stage, then step — later (inner) wins a clash: step > stage > pipeline.
    assertEquals("pipeline-cred", creds.get(0).getId());
    assertEquals("stage-cred", creds.get(1).getId());
    assertEquals("step-cred", creds.get(2).getId());
  }

  @Test
  void aCrossScopeVariableShadowIsAllowed() {
    // pipeline binds TOKEN, the step re-binds TOKEN — legitimate shadowing, not an error.
    // The merge order (pipeline then step) gives the step precedence.
    String yaml =
        """
                credentials:
                  - id: pipeline-token
                    type: string
                    variable: TOKEN
                stages:
                  - stage: Deploy
                    steps:
                      - sh: deploy.sh
                        credentials:
                          - id: step-token
                            type: string
                            variable: TOKEN
                """;
    List<CredentialBinding> creds =
        TitanYamlParser.parse(yaml).getStages().get(0).getSteps().get(0).getCredentials();
    assertEquals(2, creds.size());
    assertEquals("pipeline-token", creds.get(0).getId());
    assertEquals("step-token", creds.get(1).getId());
  }

  // ── error paths ───────────────────────────────────────────────────────────

  @Test
  void twoBindingsInOneListNamingTheSameVariableAreRejected() {
    String yaml =
        """
                stages:
                  - stage: Deploy
                    steps:
                      - sh: deploy.sh
                        credentials:
                          - id: cred-a
                            type: string
                            variable: TOKEN
                          - id: cred-b
                            type: string
                            variable: TOKEN
                """;
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
    assertTrue(e.getMessage().contains("TOKEN"), e.getMessage());
  }

  @Test
  void anUnknownCredentialTypeIsRejected() {
    String yaml =
        """
                stages:
                  - stage: Deploy
                    steps:
                      - sh: deploy.sh
                        credentials:
                          - id: x
                            type: certificate
                            variable: V
                """;
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
    assertTrue(e.getMessage().contains("certificate"), e.getMessage());
  }

  @Test
  void aCredentialMissingItsIdIsRejected() {
    String yaml =
        """
                stages:
                  - stage: Deploy
                    steps:
                      - sh: deploy.sh
                        credentials:
                          - type: string
                            variable: V
                """;
    assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
  }

  @Test
  void aCredentialWithNoBindingVariableIsRejected() {
    String yaml =
        """
                stages:
                  - stage: Deploy
                    steps:
                      - sh: deploy.sh
                        credentials:
                          - id: x
                            type: string
                """;
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
    assertTrue(e.getMessage().contains("binding variable"), e.getMessage());
  }

  @Test
  void anUnknownKeyInACredentialEntryIsRejected() {
    String yaml =
        """
                stages:
                  - stage: Deploy
                    steps:
                      - sh: deploy.sh
                        credentials:
                          - id: x
                            type: string
                            variable: V
                            bogus: y
                """;
    assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
  }

  @Test
  void credentialsMustBeAnArray() {
    String yaml =
        """
                stages:
                  - stage: Deploy
                    steps:
                      - sh: deploy.sh
                        credentials:
                          id: x
                """;
    assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
  }

  // ── persistence round-trip ────────────────────────────────────────────────

  @Test
  void credentialBindingsRoundTripThroughPipelineModelJson() throws Exception {
    String yaml =
        """
                stages:
                  - stage: Deploy
                    steps:
                      - sh: docker login
                        credentials:
                          - id: docker-registry
                            type: usernamePassword
                            usernameVariable: REG_USER
                            passwordVariable: REG_PASS
                """;
    PipelineModel model = TitanYamlParser.parse(yaml);
    String json = JSON.writeValueAsString(model);
    // The persisted model carries the credential id and binding shape — never a secret.
    assertTrue(json.contains("docker-registry"));
    assertTrue(json.contains("REG_USER"));

    PipelineModel reloaded = JSON.readValue(json, PipelineModel.class);
    CredentialBinding binding =
        reloaded.getStages().get(0).getSteps().get(0).getCredentials().get(0);
    assertEquals("docker-registry", binding.getId());
    assertEquals("REG_PASS", binding.binding("passwordVariable"));
  }
}

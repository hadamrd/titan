package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.StepModel;
import org.junit.jupiter.api.Test;

/**
 * 42-T item 7 — confirms {@code STAGE_KEYS} / {@code STEP_KEYS} are the <em>computed</em> union of
 * the registered {@link StepScope}s' {@code key()}s, not a hand-maintained literal (design/42
 * §4.7). The decomposed-scope parsing itself is exercised by {@code SshAgentParsingTest}, {@code
 * CredentialBindingParsingTest} and the {@link TitanYamlParser} test suite; this class pins the
 * key-set computation: a registered scope key is accepted where it applies, and an unknown
 * scope-shaped key is rejected exactly as any other unknown key.
 */
class StepScopeKeysTest {

  // ── every registered scope's key() is accepted on a stage (computed STAGE_KEYS) ──

  @Test
  void everyRegisteredScopeKeyIsAcceptedOnAStage() {
    // credentials / sshAgent / image / when / dependsOn — all five registered scopes' keys
    // must be valid stage keys because STAGE_KEYS is the computed union of scope.key()s.
    String yaml =
        """
                stages:
                  - stage: First
                    image: busybox
                    credentials: []
                    sshAgent: []
                    steps:
                      - sh: echo one
                  - stage: Second
                    dependsOn: [First]
                    when: "true"
                    steps:
                      - sh: echo two
                """;
    assertEquals(2, TitanYamlParser.parse(yaml).getStages().size());
  }

  // ── step-applicable scope keys are accepted on a step (computed STEP_KEYS) ──

  @Test
  void stepApplicableScopeKeysAreAcceptedOnAStep() {
    // credentials / sshAgent / image are step-and-stage scopes — valid besides the descriptor
    // key on a step node; STEP_KEYS is the computed union of the appliesToStep() scopes.
    String yaml =
        """
                stages:
                  - stage: S
                    steps:
                      - sh: echo hi
                        image: busybox
                        credentials: []
                        sshAgent: []
                """;
    StepModel step = TitanYamlParser.parse(yaml).getStages().get(0).getSteps().get(0);
    assertEquals("sh", step.getDescriptorId());
  }

  @Test
  void whenIsNowAStepScopeKeyAndIsAcceptedOnAStep() {
    // `when` is a step-and-stage scope since GH #240 (appliesToStep() == true) — so it IS in
    // STEP_KEYS and must be accepted beside a descriptor key on a step node.
    String yaml =
        """
                stages:
                  - stage: S
                    steps:
                      - sh: echo hi
                        when: "true"
                """;
    StepModel step = TitanYamlParser.parse(yaml).getStages().get(0).getSteps().get(0);
    assertEquals("sh", step.getDescriptorId());
    assertEquals("true", step.getWhen());
  }

  // ── an unknown scope-shaped key is still rejected as an unknown key ──

  @Test
  void anUnknownScopeShapedKeyOnAStageIsRejected() {
    // A key that *looks* like a scope but is not registered must be rejected — STAGE_KEYS is
    // the closed computed union of registered scopes, not an open namespace.
    String yaml =
        """
                stages:
                  - stage: S
                    notAScope: something
                    steps:
                      - sh: echo hi
                """;
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
    assertTrue(
        e.getMessage().contains("notAScope") || e.getMessage().toLowerCase().contains("unknown"),
        e.getMessage());
  }

  @Test
  void aRootLevelUnknownScopeShapedKeyIsRejected() {
    String yaml =
        """
                notAScope: x
                stages:
                  - stage: S
                    steps:
                      - sh: echo hi
                """;
    assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
  }
}

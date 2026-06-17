package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests {@code env:} parsing at pipeline, stage, and step scope (GH #239).
 *
 * <p>{@code env:} is a {@link StepScope} that stores a {@code Map<String,String>} on each model
 * level. The "absent vs empty" distinction is preserved: an absent {@code env:} key yields {@code
 * null}, while {@code env: {}} yields an empty map. Non-string values ({@code KEY: 42}, {@code KEY:
 * [a,b]}) are rejected with a located error naming the offending key. The merge logic (pipeline ←
 * stage ← step precedence) is exercised in {@link MergedEnvTest}.
 */
class EnvParsingTest {

  private static PipelineModel parse(String yaml) {
    return TitanYamlParser.parse(yaml);
  }

  private static StageModel firstStage(String yaml) {
    return parse(yaml).getStages().get(0);
  }

  private static StepModel firstStep(String yaml) {
    return firstStage(yaml).getSteps().get(0);
  }

  // ── pipeline-level env: ───────────────────────────────────────────────────

  @Test
  void pipelineLevelEnvIsParsedOntoTheModel() {
    PipelineModel model =
        parse(
            """
            env:
              REGISTRY: registry.example.com
              LOG_LEVEL: info
            stages:
              - stage: Build
                steps:
                  - sh: mvn package
            """);
    Map<String, String> env = model.getEnv();
    assertNotNull(env, "pipeline env: should be non-null");
    assertEquals("registry.example.com", env.get("REGISTRY"));
    assertEquals("info", env.get("LOG_LEVEL"));
    assertEquals(2, env.size());
  }

  @Test
  void absentPipelineEnvIsNull() {
    PipelineModel model =
        parse(
            """
            stages:
              - stage: Build
                steps:
                  - sh: mvn package
            """);
    assertNull(model.getEnv(), "absent pipeline env: should be null, not an empty map");
  }

  @Test
  void emptyPipelineEnvBlockIsEmptyMapNotNull() {
    PipelineModel model =
        parse(
            """
            env: {}
            stages:
              - stage: Build
                steps:
                  - sh: mvn package
            """);
    assertNotNull(model.getEnv(), "env: {} should yield an empty map, not null");
    assertTrue(model.getEnv().isEmpty(), "env: {} should yield an empty map");
  }

  // ── stage-level env: ──────────────────────────────────────────────────────

  @Test
  void stageLevelEnvIsParsedOntoTheStageModel() {
    StageModel stage =
        firstStage(
            """
            stages:
              - stage: Build
                env:
                  BUILD_OPTS: "-Dmaven.test.skip=true"
                  PROFILE: ci
                steps:
                  - sh: mvn package
            """);
    Map<String, String> env = stage.getEnv();
    assertNotNull(env, "stage env: should be non-null");
    assertEquals("-Dmaven.test.skip=true", env.get("BUILD_OPTS"));
    assertEquals("ci", env.get("PROFILE"));
    assertEquals(2, env.size());
  }

  @Test
  void absentStageEnvIsNull() {
    StageModel stage =
        firstStage(
            """
            stages:
              - stage: Build
                steps:
                  - sh: mvn package
            """);
    assertNull(stage.getEnv(), "absent stage env: should be null");
  }

  @Test
  void emptyStageEnvBlockIsEmptyMapNotNull() {
    StageModel stage =
        firstStage(
            """
            stages:
              - stage: Build
                env: {}
                steps:
                  - sh: mvn package
            """);
    assertNotNull(stage.getEnv(), "stage env: {} should yield an empty map, not null");
    assertTrue(stage.getEnv().isEmpty());
  }

  // ── step-level env: ───────────────────────────────────────────────────────

  @Test
  void stepLevelEnvIsParsedOntoTheStepModel() {
    StepModel step =
        firstStep(
            """
            stages:
              - stage: Build
                steps:
                  - sh: mvn package
                    env:
                      MAVEN_COLOR: "true"
                      MAVEN_OPTS: "-Xmx2g"
            """);
    Map<String, String> env = step.getEnv();
    assertNotNull(env, "step env: should be non-null");
    assertEquals("true", env.get("MAVEN_COLOR"));
    assertEquals("-Xmx2g", env.get("MAVEN_OPTS"));
    assertEquals(2, env.size());
  }

  @Test
  void absentStepEnvIsNull() {
    StepModel step =
        firstStep(
            """
            stages:
              - stage: Build
                steps:
                  - sh: mvn package
            """);
    assertNull(step.getEnv(), "absent step env: should be null");
  }

  @Test
  void emptyStepEnvBlockIsEmptyMapNotNull() {
    StepModel step =
        firstStep(
            """
            stages:
              - stage: Build
                steps:
                  - sh: mvn package
                    env: {}
            """);
    assertNotNull(step.getEnv(), "step env: {} should yield an empty map, not null");
    assertTrue(step.getEnv().isEmpty());
  }

  // ── all three levels declared simultaneously ──────────────────────────────

  @Test
  void allThreeLevelsCoexistIndependently() {
    PipelineModel model =
        parse(
            """
            env:
              SHARED: pipeline-value
              REGISTRY: registry.example.com
            stages:
              - stage: Build
                env:
                  SHARED: stage-value
                  BUILD_OPTS: "-q"
                steps:
                  - sh: mvn package
                    env:
                      SHARED: step-value
                      MAVEN_COLOR: "true"
                  - sh: docker build .
            """);

    // pipeline level
    assertEquals("pipeline-value", model.getEnv().get("SHARED"));
    assertEquals("registry.example.com", model.getEnv().get("REGISTRY"));

    StageModel stage = model.getStages().get(0);
    // stage level — independent; does NOT inherit from pipeline
    assertEquals("stage-value", stage.getEnv().get("SHARED"));
    assertEquals("-q", stage.getEnv().get("BUILD_OPTS"));
    assertNull(stage.getEnv().get("REGISTRY"), "stage env does not inherit from pipeline");

    // step 0 level — independent; does NOT inherit from stage or pipeline
    StepModel step0 = stage.getSteps().get(0);
    assertEquals("step-value", step0.getEnv().get("SHARED"));
    assertEquals("true", step0.getEnv().get("MAVEN_COLOR"));
    assertNull(
        step0.getEnv().get("BUILD_OPTS"), "step env does not inherit from stage at parse time");

    // step 1 has no env:
    assertNull(stage.getSteps().get(1).getEnv(), "step 1 has no env: key");
  }

  // ── type-error cases ──────────────────────────────────────────────────────

  @Test
  void nonStringValueIsRejectedWithOffendingKeyInMessage() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                parse(
                    """
                    stages:
                      - stage: Build
                        steps:
                          - sh: mvn package
                            env:
                              PORT: 8080
                    """));
    // The error must name the offending key
    assertTrue(e.getMessage().contains("PORT"), e.getMessage());
    assertTrue(e.getMessage().contains("string"), e.getMessage());
  }

  @Test
  void listValueIsRejectedWithOffendingKeyInMessage() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                parse(
                    """
                    stages:
                      - stage: Build
                        steps:
                          - sh: mvn package
                            env:
                              TAGS: [a, b, c]
                    """));
    assertTrue(e.getMessage().contains("TAGS"), e.getMessage());
  }

  @Test
  void nullValueIsRejectedWithOffendingKeyInMessage() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                parse(
                    """
                    stages:
                      - stage: Build
                        steps:
                          - sh: mvn package
                            env:
                              MY_VAR: ~
                    """));
    assertTrue(e.getMessage().contains("MY_VAR"), e.getMessage());
  }

  @Test
  void pipelineLevelEnvPresentButNullIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                parse(
                    """
                    env: ~
                    stages:
                      - stage: Build
                        steps:
                          - sh: mvn package
                    """));
    assertTrue(e.getMessage().contains("env"), e.getMessage());
  }

  @Test
  void stageLevelEnvNonObjectIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                parse(
                    """
                    stages:
                      - stage: Build
                        env: "not-a-map"
                        steps:
                          - sh: mvn package
                    """));
    assertTrue(e.getMessage().contains("env"), e.getMessage());
  }

  // ── secret:<id> reference parsing (GH #1094) ──────────────────────────────

  @Test
  void secretRefBareKeyIsAcceptedAsStringValue() {
    StepModel step =
        firstStep(
            """
            stages:
              - stage: Build
                steps:
                  - sh: deploy
                    env:
                      GH_TOKEN: "secret:gh-token"
            """);
    // The parser preserves the literal string — resolution happens at dispatch.
    assertEquals("secret:gh-token", step.getEnv().get("GH_TOKEN"));
  }

  @Test
  void secretRefScopeSlashKeyIsAcceptedAsStringValue() {
    StepModel step =
        firstStep(
            """
            stages:
              - stage: Build
                steps:
                  - sh: deploy
                    env:
                      AWS_KEY: "secret:aws/dev-key"
            """);
    assertEquals("secret:aws/dev-key", step.getEnv().get("AWS_KEY"));
  }

  @Test
  void malformedSecretRefWithExtraColonIsRejectedAtParse() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                parse(
                    """
                    stages:
                      - stage: Build
                        steps:
                          - sh: deploy
                            env:
                              GH_TOKEN: "secret:nope:extra"
                    """));
    // Error must name the env var AND show why
    assertTrue(e.getMessage().contains("GH_TOKEN"), e.getMessage());
    assertTrue(e.getMessage().contains("secret"), e.getMessage());
  }

  @Test
  void emptySecretRefIsRejectedAtParse() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                parse(
                    """
                    stages:
                      - stage: Build
                        steps:
                          - sh: deploy
                            env:
                              GH_TOKEN: "secret:"
                    """));
    assertTrue(e.getMessage().contains("GH_TOKEN"), e.getMessage());
    assertTrue(e.getMessage().contains("empty"), e.getMessage());
  }

  @Test
  void secretRefWithWhitespaceInIdIsRejectedAtParse() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                parse(
                    """
                    stages:
                      - stage: Build
                        steps:
                          - sh: deploy
                            env:
                              GH_TOKEN: "secret:hello world"
                    """));
    assertTrue(e.getMessage().contains("GH_TOKEN"), e.getMessage());
    assertTrue(e.getMessage().contains("whitespace"), e.getMessage());
  }

  @Test
  void literalValueContainingColonIsAccepted() {
    // A literal env value may carry a ':' — common shape for PATH-like vars.
    // The 'secret:' restriction applies only to values that START with that prefix.
    StepModel step =
        firstStep(
            """
            stages:
              - stage: Build
                steps:
                  - sh: deploy
                    env:
                      LD_PATH: "/usr/lib:/opt/lib"
            """);
    assertEquals("/usr/lib:/opt/lib", step.getEnv().get("LD_PATH"));
  }

  @Test
  void secretRefAtPipelineLevelIsAccepted() {
    PipelineModel model =
        parse(
            """
            env:
              GH_TOKEN: "secret:gh-token"
            stages:
              - stage: Build
                steps:
                  - sh: gh release list
            """);
    assertEquals("secret:gh-token", model.getEnv().get("GH_TOKEN"));
  }

  @Test
  void malformedSecretRefAtStageLevelIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                parse(
                    """
                    stages:
                      - stage: Build
                        env:
                          BAD: "secret:a:b"
                        steps:
                          - sh: noop
                    """));
    assertTrue(e.getMessage().contains("BAD"), e.getMessage());
  }
}

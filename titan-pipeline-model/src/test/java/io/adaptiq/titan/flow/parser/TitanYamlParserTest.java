package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.Fixtures;
import io.adaptiq.titan.flow.model.FailurePolicy;
import io.adaptiq.titan.flow.model.GateModel;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.PreconditionModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TitanYamlParser} — design/31 6A. Pure: no DB, no engine. */
class TitanYamlParserTest {

  /** The design/29 §3 reference pipeline — a stage, a conditional stage, a gate, a script step. */
  private static final String REFERENCE = Fixtures.load("reference-pipeline.yml");

  @Test
  void parsesTheReferencePipeline() {
    PipelineModel model = TitanYamlParser.parseAndValidate(REFERENCE);

    assertEquals("linux", model.getAgent());
    assertEquals(FailurePolicy.BLOCK_ON_FAILURE, model.getFailurePolicy());
    assertEquals(3, model.getStages().size(), "Build, Smoke Tests, Deploy Production");
    assertEquals(1, model.getGates().size());
    assertEquals(4, model.getAllNodes().size());

    StageModel build = model.getStage("build");
    assertEquals("Build", build.getName());
    assertTrue(build.getDependsOn().isEmpty(), "Build is a root stage");
    assertEquals(2, build.getSteps().size());

    StageModel smoke = model.getStage("smoke-tests");
    assertEquals("params.runSmokeTests == true", smoke.getWhen());
    assertEquals(java.util.List.of("Build"), smoke.getDependsOn());

    GateModel gate = model.getGates().get(0);
    assertEquals("QA Approval", gate.getName());
    assertTrue(gate.isRequiresApproval());
    assertEquals(java.util.List.of("qa-team"), gate.getApprovers());
    assertEquals(java.util.List.of("Smoke Tests"), gate.getDependsOn());
    assertEquals("GATE", gate.getNodeType());
  }

  @Test
  void parsesScalarStepShorthandAndObjectArgs() {
    PipelineModel model = TitanYamlParser.parse(REFERENCE);
    StepModel sh = model.getStage("build").getSteps().get(0);
    assertEquals("sh", sh.getDescriptorId());
    assertEquals("mvn clean package", sh.getArguments().get("value"));

    StepModel containerBuild = model.getStage("build").getSteps().get(1);
    assertEquals("containerBuild", containerBuild.getDescriptorId());
    assertEquals("my-registry", containerBuild.getArguments().get("registry"));
  }

  /**
   * The {@code junit} step parses generically — like every built-in step, the grammar and the
   * parser are step-agnostic (design/42 §7). Its scalar shorthand folds into {@code value} (the
   * worker's {@code JUnitStepHandler} resolves {@code value} to {@code testResults}); its object
   * form carries named arguments through verbatim, preserving the boolean.
   */
  @Test
  void parsesJUnitStepInScalarAndObjectForms() {
    String yaml =
        "titan:\n  stages:\n    - stage: A\n      steps:\n"
            + "        - junit: '**/surefire-reports/TEST-*.xml'\n"
            + "        - junit:\n"
            + "            testResults: target/*.xml\n"
            + "            skipMarkingBuildUnstable: true\n";
    PipelineModel model = TitanYamlParser.parse(yaml);
    java.util.List<StepModel> steps = model.getStage("a").getSteps();
    assertEquals(2, steps.size());

    StepModel shorthand = steps.get(0);
    assertEquals("junit", shorthand.getDescriptorId());
    assertEquals("**/surefire-reports/TEST-*.xml", shorthand.getArguments().get("value"));

    StepModel object = steps.get(1);
    assertEquals("junit", object.getDescriptorId());
    assertEquals("target/*.xml", object.getArguments().get("testResults"));
    assertEquals(
        "true",
        String.valueOf(object.getArguments().get("skipMarkingBuildUnstable")),
        "the boolean argument survives the parse");
  }

  /** {@code error} parses generically — scalar shorthand folds into {@code value}. */
  @Test
  void parsesErrorStepInScalarAndObjectForms() {
    String yaml =
        "titan:\n  stages:\n    - stage: A\n      steps:\n"
            + "        - error: 'version tag missing'\n"
            + "        - error:\n"
            + "            message: explicit reason\n";
    PipelineModel model = TitanYamlParser.parse(yaml);
    java.util.List<StepModel> steps = model.getStage("a").getSteps();
    assertEquals(2, steps.size());

    StepModel shorthand = steps.get(0);
    assertEquals("error", shorthand.getDescriptorId());
    assertEquals("version tag missing", shorthand.getArguments().get("value"));

    StepModel object = steps.get(1);
    assertEquals("error", object.getDescriptorId());
    assertEquals("explicit reason", object.getArguments().get("message"));
  }

  /**
   * {@code setBuildName} parses generically (#762) — like every built-in step, the parser carries
   * no per-step knowledge (design/42 §4.6/§7). A scalar with a {@code ${{ … }}} template folds into
   * the conventional {@code value} key (resolved at dispatch time by the controller-native {@link
   * io.adaptiq.titan.flow.SetBuildNameResolver}); the object form carries {@code name} verbatim.
   * Both yield {@code descriptorId == "setBuildName"} so the orchestrator's classifier matches
   * either shape.
   */
  @Test
  void parsesSetBuildNameStepInScalarAndObjectForms() {
    String yaml =
        "titan:\n  stages:\n    - stage: A\n      steps:\n"
            + "        - setBuildName: \"deploy-${{ params.BRANCH }}\"\n"
            + "        - setBuildName:\n"
            + "            name: explicit-name\n";
    PipelineModel model = TitanYamlParser.parse(yaml);
    java.util.List<StepModel> steps = model.getStage("a").getSteps();
    assertEquals(2, steps.size());

    StepModel shorthand = steps.get(0);
    assertEquals("setBuildName", shorthand.getDescriptorId());
    assertEquals("deploy-${{ params.BRANCH }}", shorthand.getArguments().get("value"));

    StepModel object = steps.get(1);
    assertEquals("setBuildName", object.getDescriptorId());
    assertEquals("explicit-name", object.getArguments().get("name"));
  }

  /** {@code echo} parses generically — scalar shorthand folds into {@code value}. */
  @Test
  void parsesEchoStepInScalarForm() {
    String yaml =
        "titan:\n  stages:\n    - stage: A\n      steps:\n"
            + "        - echo: 'building component core'\n";
    PipelineModel model = TitanYamlParser.parse(yaml);
    StepModel echo = model.getStage("a").getSteps().get(0);
    assertEquals("echo", echo.getDescriptorId());
    assertEquals("building component core", echo.getArguments().get("value"));
  }

  /**
   * {@code k8sApply} parses generically — like every built-in step, the grammar and the parser are
   * step-agnostic (design/42 §7, design/62, #242). Scalar shorthand {@code k8sApply: foo.yaml}
   * folds into {@code value} (the worker's {@code K8sApplyStepHandler} resolves {@code value} to
   * {@code manifest}); the object form carries {@code manifest} / {@code namespace} verbatim.
   */
  @Test
  void parsesK8sApplyStepInScalarAndObjectForms() {
    String yaml =
        "titan:\n  stages:\n    - stage: A\n      steps:\n"
            + "        - k8sApply: ./deps/postgres.yaml\n"
            + "        - k8sApply:\n"
            + "            manifest: ./deps/redis.yaml\n"
            + "            namespace: integration\n"
            + "            wait: true\n"
            + "            timeout: 30s\n";
    PipelineModel model = TitanYamlParser.parse(yaml);
    java.util.List<StepModel> steps = model.getStage("a").getSteps();
    assertEquals(2, steps.size());

    StepModel shorthand = steps.get(0);
    assertEquals("k8sApply", shorthand.getDescriptorId());
    assertEquals("./deps/postgres.yaml", shorthand.getArguments().get("value"));

    StepModel object = steps.get(1);
    assertEquals("k8sApply", object.getDescriptorId());
    assertEquals("./deps/redis.yaml", object.getArguments().get("manifest"));
    assertEquals("integration", object.getArguments().get("namespace"));
    assertEquals("true", String.valueOf(object.getArguments().get("wait")));
    assertEquals("30s", object.getArguments().get("timeout"));
  }

  /** {@code sleep} parses generically in both the scalar and the explicit time/unit form. */
  @Test
  void parsesSleepStepInScalarAndObjectForms() {
    String yaml =
        "titan:\n  stages:\n    - stage: A\n      steps:\n"
            + "        - sleep: 30\n"
            + "        - sleep:\n"
            + "            time: 5\n"
            + "            unit: MINUTES\n";
    PipelineModel model = TitanYamlParser.parse(yaml);
    java.util.List<StepModel> steps = model.getStage("a").getSteps();
    assertEquals(2, steps.size());

    StepModel shorthand = steps.get(0);
    assertEquals("sleep", shorthand.getDescriptorId());
    assertEquals(
        "30",
        String.valueOf(shorthand.getArguments().get("value")),
        "the scalar shorthand carries the duration through as value");

    StepModel object = steps.get(1);
    assertEquals("sleep", object.getDescriptorId());
    assertEquals("5", String.valueOf(object.getArguments().get("time")));
    assertEquals("MINUTES", object.getArguments().get("unit"));
  }

  /**
   * The ergonomic {@code wait:} alias (closes #706) rewrites to the canonical {@code sleep:}
   * descriptor at parse-time — both the scalar shorthand and the object form. The model sees only
   * the canonical key; the alias is invisible past the parser, so worker dispatch + JSON schema
   * stay single-source.
   */
  @Test
  void waitAliasRewritesToSleepDescriptor() {
    String yaml =
        "titan:\n  stages:\n    - stage: A\n      steps:\n"
            + "        - wait: 30s\n"
            + "        - wait:\n"
            + "            time: 30\n"
            + "            unit: SECONDS\n";
    PipelineModel model = TitanYamlParser.parse(yaml);
    java.util.List<StepModel> steps = model.getStage("a").getSteps();
    assertEquals(2, steps.size());

    StepModel shorthand = steps.get(0);
    assertEquals("sleep", shorthand.getDescriptorId(), "wait: <scalar> must canonicalise to sleep");
    assertEquals("30s", String.valueOf(shorthand.getArguments().get("value")));

    StepModel object = steps.get(1);
    assertEquals(
        "sleep", object.getDescriptorId(), "wait: { time, unit } must canonicalise to sleep");
    assertEquals("30", String.valueOf(object.getArguments().get("time")));
    assertEquals("SECONDS", object.getArguments().get("unit"));
  }

  /**
   * The {@code httpRequest} step (design/50) parses generically like every built-in step — the
   * parser is step-agnostic. Its scalar shorthand folds into {@code value} (the worker resolves
   * {@code value} to {@code url}); its object form carries named arguments, including a nested
   * {@code headers} map, through verbatim.
   */
  @Test
  void parsesHttpRequestStepInScalarAndObjectForms() {
    String yaml =
        "titan:\n  stages:\n    - stage: A\n      steps:\n"
            + "        - httpRequest: 'https://example.com/health'\n"
            + "        - httpRequest:\n"
            + "            url: https://example.com/deploy\n"
            + "            method: POST\n"
            + "            headers:\n"
            + "              Accept: application/json\n";
    PipelineModel model = TitanYamlParser.parse(yaml);
    java.util.List<StepModel> steps = model.getStage("a").getSteps();
    assertEquals(2, steps.size());

    StepModel shorthand = steps.get(0);
    assertEquals("httpRequest", shorthand.getDescriptorId());
    assertEquals("https://example.com/health", shorthand.getArguments().get("value"));

    StepModel object = steps.get(1);
    assertEquals("httpRequest", object.getDescriptorId());
    assertEquals("https://example.com/deploy", object.getArguments().get("url"));
    assertEquals("POST", object.getArguments().get("method"));
    assertTrue(
        object.getArguments().get("headers") instanceof java.util.Map,
        "the nested headers map survives the parse");
  }

  /**
   * The {@code gitTag} step (closes #758) parses generically like every built-in step — the parser
   * is step-agnostic. Scalar shorthand {@code gitTag: v1.2.3} folds into {@code value} (the worker
   * resolves {@code value} to {@code tag}); the object form carries named arguments verbatim.
   */
  @Test
  void parsesGitTagStepInScalarAndObjectForms() {
    String yaml =
        "titan:\n  stages:\n    - stage: A\n      steps:\n"
            + "        - gitTag: v1.2.3\n"
            + "        - gitTag:\n"
            + "            tag: v1.2.3\n"
            + "            message: 'Release'\n"
            + "            push: false\n";
    PipelineModel model = TitanYamlParser.parse(yaml);
    java.util.List<StepModel> steps = model.getStage("a").getSteps();
    assertEquals(2, steps.size());

    StepModel shorthand = steps.get(0);
    assertEquals("gitTag", shorthand.getDescriptorId());
    assertEquals("v1.2.3", shorthand.getArguments().get("value"));

    StepModel object = steps.get(1);
    assertEquals("gitTag", object.getDescriptorId());
    assertEquals("v1.2.3", object.getArguments().get("tag"));
    assertEquals("Release", object.getArguments().get("message"));
    assertEquals("false", String.valueOf(object.getArguments().get("push")));
  }

  /**
   * The {@code setOutput} step parses generically — both its single {@code name}/{@code value} form
   * and its {@code values} map form carry through as plain arguments; the worker's handler
   * interprets them.
   */
  @Test
  void parsesSetOutputStepInSingleAndMapForms() {
    String yaml =
        "titan:\n  stages:\n    - stage: A\n      steps:\n"
            + "        - setOutput: { name: deployEnv, value: production }\n"
            + "        - setOutput:\n"
            + "            values:\n"
            + "              version: '1.2.3'\n"
            + "              region: eu-west-1\n";
    PipelineModel model = TitanYamlParser.parse(yaml);
    java.util.List<StepModel> steps = model.getStage("a").getSteps();
    assertEquals(2, steps.size());

    StepModel single = steps.get(0);
    assertEquals("setOutput", single.getDescriptorId());
    assertEquals("deployEnv", single.getArguments().get("name"));
    assertEquals("production", single.getArguments().get("value"));

    StepModel map = steps.get(1);
    assertEquals("setOutput", map.getDescriptorId());
    assertTrue(
        map.getArguments().get("values") instanceof java.util.Map,
        "the values map survives the parse");
  }

  @Test
  void parsesScriptStepRuntimeAndBody() {
    PipelineModel model = TitanYamlParser.parse(REFERENCE);
    StepModel script = model.getStage("deploy-production").getSteps().get(0);
    assertEquals("script", script.getDescriptorId());
    assertEquals("groovy", script.getRuntime());
    assertTrue(script.getBody().contains("readVersion()"), "script body is shipped verbatim");
  }

  @Test
  void parsesPreconditionNode() {
    PipelineModel model =
        TitanYamlParser.parseAndValidate(Fixtures.load("precondition-pipeline.yml"));
    assertEquals(1, model.getPreconditions().size());
    PreconditionModel pre = model.getPreconditions().get(0);
    assertEquals("Tests Passed", pre.getName());
    assertEquals("steps['Build'].outputs.passed == true", pre.getExpression());
    assertEquals("PRECONDITION", pre.getNodeType());
  }

  /** {@code timeout:} parses as a step-level and a stage-level scope key. */
  @Test
  void parsesTimeoutStepAndStageScope() {
    String yaml =
        "titan:\n  stages:\n    - stage: A\n      timeout: 10m\n      steps:\n"
            + "        - sh: ./slow.sh\n"
            + "        - sh: ./fast.sh\n          timeout: 30s\n";
    PipelineModel model = TitanYamlParser.parse(yaml);
    java.util.List<StepModel> steps = model.getStage("a").getSteps();
    assertEquals(2, steps.size());

    // Stage-level timeout (10m) flattens onto the step that has none.
    assertEquals(
        java.time.Duration.ofMinutes(10).toMillis(), (long) steps.get(0).getTimeoutMillis());
    // A step's own timeout (30s) wins over the stage default.
    assertEquals(
        java.time.Duration.ofSeconds(30).toMillis(), (long) steps.get(1).getTimeoutMillis());
  }

  /** A step with no timeout has a null timeoutMillis. */
  @Test
  void aStepWithoutTimeoutHasNoTimeout() {
    String yaml = "titan:\n  stages:\n    - stage: A\n      steps:\n        - sh: ./x.sh\n";
    StepModel step = TitanYamlParser.parse(yaml).getStage("a").getSteps().get(0);
    assertEquals(null, step.getTimeoutMillis());
  }

  @Test
  void emptyDefinitionIsRejected() {
    assertTrue(
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse("  "))
            .getMessage()
            .contains("empty"));
  }

  @Test
  void missingTitanRootIsRejected() {
    assertThrows(
        PipelineParseException.class, () -> TitanYamlParser.parse("pipeline:\n  foo: bar\n"));
  }

  @Test
  void invalidYamlIsRejectedWithContext() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () -> TitanYamlParser.parse("titan:\n  stages: [ unbalanced"));
    assertTrue(e.getMessage().contains("invalid YAML"), e.getMessage());
  }

  @Test
  void unknownKeyAtRootIsRejected() {
    String yaml = "titan:\n  banana: yes\n  stages: [ { stage: A, steps: [] } ]\n";
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
    assertTrue(e.getMessage().contains("banana"), e.getMessage());
  }

  @Test
  void aParallelStageKeyIsRejectedWithAPointerToDependsOn() {
    String yaml = "titan:\n  stages:\n    - stage: A\n      parallel: true\n      steps: []\n";
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
    assertTrue(
        e.getMessage().contains("dependsOn"),
        "the error must point the author at dependsOn: " + e.getMessage());
  }

  @Test
  void unknownKeyOnAStageIsRejected() {
    String yaml = "titan:\n  stages:\n    - stage: A\n      retries: 3\n      steps: []\n";
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
    assertTrue(e.getMessage().contains("retries"), e.getMessage());
  }

  @Test
  void stageWithNoNameIsRejected() {
    String yaml = "titan:\n  stages:\n    - stage: \"\"\n      steps: []\n";
    assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
  }

  @Test
  void nodeEntryWithoutADiscriminatorKeyIsRejected() {
    String yaml = "titan:\n  stages:\n    - steps: []\n";
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
    assertTrue(e.getMessage().contains("exactly one"), e.getMessage());
  }

  @Test
  void stepWithTwoDescriptorKeysIsRejected() {
    String yaml = "titan:\n  stages:\n    - stage: A\n      steps:\n        - { sh: x, echo: y }\n";
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
    assertTrue(e.getMessage().contains("exactly one descriptor key"), e.getMessage());
  }

  @Test
  void stepImageKeyIsParsedAlongsideTheDescriptor() {
    // design/31 §6G — a step may carry an `image:` sibling key declaring its container.
    String yaml =
        "titan:\n  stages:\n    - stage: A\n      image: maven:3.9\n      steps:\n"
            + "        - { sh: mvn -version, image: eclipse-temurin:21 }\n        - sh: echo hi\n";
    PipelineModel model = TitanYamlParser.parse(yaml);
    StageModel stage = model.getStages().get(0);
    assertEquals("maven:3.9", stage.getImage(), "the stage image is parsed");
    assertEquals(
        "eclipse-temurin:21",
        stage.getSteps().get(0).getImage(),
        "the per-step image override is parsed");
    assertEquals(
        "sh",
        stage.getSteps().get(0).getDescriptorId(),
        "the descriptor key is still resolved next to image");
    assertNull(stage.getSteps().get(1).getImage(), "a step with no image inherits (null here)");
  }

  @Test
  void scriptStepMissingBodyIsRejected() {
    String yaml =
        "titan:\n  stages:\n    - stage: A\n      steps:\n"
            + "        - script:\n            runtime: groovy\n";
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
    assertTrue(e.getMessage().contains("body"), e.getMessage());
  }

  @Test
  void noArgStepParsesToEmptyArguments() {
    String yaml = "titan:\n  stages:\n    - stage: A\n      steps:\n        - checkout:\n";
    PipelineModel model = TitanYamlParser.parse(yaml);
    StepModel checkout = model.getStage("a").getSteps().get(0);
    assertEquals("checkout", checkout.getDescriptorId());
    assertTrue(checkout.getArguments().isEmpty());
    assertNull(checkout.getRuntime());
  }

  @Test
  void failurePolicyDefaultsWhenAbsent() {
    String yaml = "titan:\n  stages: [ { stage: A, steps: [] } ]\n";
    assertEquals(FailurePolicy.BLOCK_ON_FAILURE, TitanYamlParser.parse(yaml).getFailurePolicy());
    assertNull(TitanYamlParser.parse(yaml).getAgent());
  }

  /** Issue #392: explicit camelCase YAML literals map to the typed enum. */
  @Test
  void failurePolicyParsesContinueOnFailure() {
    String yaml =
        "titan:\n  failurePolicy: continueOnFailure\n  stages: [ { stage: A, steps: [] } ]\n";
    assertEquals(FailurePolicy.CONTINUE_ON_FAILURE, TitanYamlParser.parse(yaml).getFailurePolicy());
  }

  /** Issue #392: unknown values are rejected at parse time, not silently dropped at runtime. */
  @Test
  void failurePolicyRejectsUnknownValue() {
    String yaml =
        "titan:\n  failurePolicy: blockOnFailureXYZ\n  stages: [ { stage: A, steps: [] } ]\n";
    PipelineParseException ex =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
    assertTrue(
        ex.getMessage().contains("failurePolicy"),
        "error message should name the offending key: " + ex.getMessage());
    assertTrue(
        ex.getMessage().contains("blockOnFailureXYZ"),
        "error message should echo the bad literal: " + ex.getMessage());
  }

  @Test
  void emptyStagesArrayIsRejected() {
    assertThrows(
        PipelineParseException.class, () -> TitanYamlParser.parse("titan:\n  stages: []\n"));
  }

  @Test
  void slugIsStableLowercaseAndDbSafe() {
    assertEquals("deploy-production", TitanYamlParser.slug("Deploy Production"));
    assertEquals("build", TitanYamlParser.slug("  Build! "));
    assertFalse(TitanYamlParser.slug("Smoke Tests").contains(" "));
  }

  // ── approval: step (#715) ─────────────────────────────────────────────────

  @Test
  void approvalScalarFoldsIntoValueArgUnchanged() {
    // design/42 §4.6: the parser carries zero per-step knowledge — `approval: "..."` folds the
    // scalar into the conventional `value` key, exactly like `sh: ...`. The controller-side
    // ApprovalResolver consumes it.
    String yaml =
        "stages:\n"
            + "  - stage: Deploy\n"
            + "    steps:\n"
            + "      - approval: \"Deploy to prod?\"\n";
    PipelineModel model = TitanYamlParser.parseAndValidate(yaml);
    StepModel step = model.getStage("deploy").getSteps().get(0);
    assertEquals("approval", step.getDescriptorId());
    assertEquals("Deploy to prod?", step.getArguments().get("value"));
  }

  @Test
  void approvalObjectFormPreservesPromptApproversTimeout() {
    String yaml =
        "stages:\n"
            + "  - stage: Deploy\n"
            + "    steps:\n"
            + "      - approval:\n"
            + "          prompt: \"Promote to prod?\"\n"
            + "          approvers: [alice, bob]\n"
            + "          timeout: 2h\n";
    PipelineModel model = TitanYamlParser.parseAndValidate(yaml);
    StepModel step = model.getStage("deploy").getSteps().get(0);
    assertEquals("approval", step.getDescriptorId());
    assertEquals("Promote to prod?", step.getArguments().get("prompt"));
    assertEquals(java.util.List.of("alice", "bob"), step.getArguments().get("approvers"));
    assertEquals("2h", step.getArguments().get("timeout"));
  }
}

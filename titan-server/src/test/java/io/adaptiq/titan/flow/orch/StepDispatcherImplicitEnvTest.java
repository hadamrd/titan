package io.adaptiq.titan.flow.orch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.flow.CredentialsPort;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage of the implicit-env path (closes #847): a build whose {@code
 * trigger_meta_json} carries {@code commitSha} / {@code branch} must surface those values as {@code
 * GIT_COMMIT} / {@code GIT_BRANCH} in every step's {@code EXECUTE_COMMAND} payload — without the
 * pipeline declaring anything under {@code parameters:}.
 *
 * <p>This is the contract the worker reads in {@code TaskExecutor.buildEnv(payload)}: a flat {@code
 * env} object on the payload is hydrated into the step process's real OS env, which is what {@code
 * echo $GIT_COMMIT} in a {@code sh:} step reads.
 */
class StepDispatcherImplicitEnvTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void implicitGitCommitAndBranchAppearInDispatchedPayload() throws Exception {
    TitanStores stores = FakeTitanStores.create();
    long buildId = seedBuildWithTriggerMeta(stores, "deadbeef1234", "trunk");
    StepDispatcher dispatcher = new StepDispatcher(stores, buildId, CredentialsPort.noOp());

    StageModel stage = stage("stage-a", "linux", List.of(shStep("step-1", "echo $GIT_COMMIT")));
    PipelineModel model = model(List.of(stage));

    dispatcher.enqueueStep(stage, stage.getSteps().get(0), model, Map.of());

    List<TaskQueueRow> tasks = stores.taskQueue().listByBuild(buildId);
    assertEquals(1, tasks.size());
    JsonNode payload = JSON.readTree(tasks.get(0).payloadJson);
    JsonNode env = payload.get("env");
    assertTrue(env != null && env.isObject(), "payload.env must be present");
    assertEquals("deadbeef1234", env.get("GIT_COMMIT").asText());
    assertEquals("trunk", env.get("GIT_BRANCH").asText());
    assertTrue(env.has("BUILD_NUMBER"));
    assertTrue(env.has("BUILD_ID"));
  }

  /**
   * No trigger meta (a manual / dogfood build) → no GIT_* keys, but BUILD_NUMBER / BUILD_ID still.
   */
  @Test
  void buildWithoutTriggerMetaOmitsGitKeysButStampsBuildIdentifiers() throws Exception {
    TitanStores stores = FakeTitanStores.create();
    long buildId = seedBuildWithTriggerMeta(stores, null, null);
    StepDispatcher dispatcher = new StepDispatcher(stores, buildId, CredentialsPort.noOp());

    StageModel stage = stage("stage-a", null, List.of(shStep("step-1", "true")));
    PipelineModel model = model(List.of(stage));

    dispatcher.enqueueStep(stage, stage.getSteps().get(0), model, Map.of());

    TaskQueueRow t = stores.taskQueue().listByBuild(buildId).get(0);
    JsonNode payload = JSON.readTree(t.payloadJson);
    JsonNode env = payload.get("env");
    assertTrue(env != null && env.isObject());
    assertFalse(env.has("GIT_COMMIT"), "no commitSha → no GIT_COMMIT");
    assertFalse(env.has("GIT_BRANCH"));
    assertTrue(env.has("BUILD_NUMBER"));
    assertTrue(env.has("BUILD_ID"));
  }

  /**
   * Engine-reserved semantics: a pipeline that defines {@code env.GIT_COMMIT} in its YAML must be
   * <em>overridden</em> by the engine's implicit value. The engine owns the name and a user mistake
   * (or stale CI re-export) must not silently shadow the real SHA.
   */
  @Test
  void implicitEnvOverridesUserDeclaredEnvOnTheSameKey() throws Exception {
    TitanStores stores = FakeTitanStores.create();
    long buildId = seedBuildWithTriggerMeta(stores, "real-sha", "main");
    StepDispatcher dispatcher = new StepDispatcher(stores, buildId, CredentialsPort.noOp());

    StageModel stage = stage("stage-a", null, List.of(shStep("step-1", "true")));
    PipelineModel model = model(List.of(stage));
    // User tries to shadow GIT_COMMIT with a literal. Engine must win.
    model.setEnv(Map.of("GIT_COMMIT", "user-set-bogus-value"));

    dispatcher.enqueueStep(stage, stage.getSteps().get(0), model, Map.of());

    TaskQueueRow t = stores.taskQueue().listByBuild(buildId).get(0);
    JsonNode env = JSON.readTree(t.payloadJson).get("env");
    assertEquals(
        "real-sha",
        env.get("GIT_COMMIT").asText(),
        "engine-owned implicit env must override user env on the same key");
  }

  /**
   * A pipeline that defines an unrelated user env var must still see it merged alongside the
   * implicit env (back-compat). User env is not erased — only same-key collisions are overridden.
   */
  @Test
  void userEnvCoexistsWithImplicitEnvOnDifferentKeys() throws Exception {
    TitanStores stores = FakeTitanStores.create();
    long buildId = seedBuildWithTriggerMeta(stores, "sha-7", "feature/x");
    StepDispatcher dispatcher = new StepDispatcher(stores, buildId, CredentialsPort.noOp());

    Map<String, String> userEnv = new LinkedHashMap<>();
    userEnv.put("MY_FLAG", "yes");
    StepModel step = shStep("step-1", "true");
    step.setEnv(userEnv);
    StageModel stage = stage("stage-a", null, List.of(step));
    PipelineModel model = model(List.of(stage));

    dispatcher.enqueueStep(stage, step, model, Map.of());

    JsonNode env =
        JSON.readTree(stores.taskQueue().listByBuild(buildId).get(0).payloadJson).get("env");
    assertEquals("yes", env.get("MY_FLAG").asText());
    assertEquals("sha-7", env.get("GIT_COMMIT").asText());
    assertEquals("feature/x", env.get("GIT_BRANCH").asText());
  }

  /**
   * Regression for #1212: {@code ${{ env.<KEY> }}} inside a step argument must resolve to the
   * merged effective env value (pipeline ← stage ← step) at dispatch — the SAME map that lands in
   * {@code payload.env}. Before the fix this threw {@code ExpressionException: unknown variable
   * 'env'}.
   */
  @Test
  void envTemplateInStepArgumentResolvesToMergedEnvValue() throws Exception {
    TitanStores stores = FakeTitanStores.create();
    long buildId = seedBuildWithTriggerMeta(stores, null, null);
    StepDispatcher dispatcher = new StepDispatcher(stores, buildId, CredentialsPort.noOp());

    StepModel step = shStep("step-1", "publish --registry ${{ env.REGISTRY }}");
    StageModel stage = stage("stage-a", null, List.of(step));
    PipelineModel model = model(List.of(stage));
    model.setEnv(Map.of("REGISTRY", "registry.example.com"));

    dispatcher.enqueueStep(stage, step, model, Map.of());

    TaskQueueRow t = stores.taskQueue().listByBuild(buildId).get(0);
    JsonNode payload = JSON.readTree(t.payloadJson);
    String command = payload.get("command").get(2).asText();
    assertEquals("publish --registry registry.example.com", command);
    // No drift: the env the ${{ }} resolved against is the same env in payload.env.
    assertEquals("registry.example.com", payload.get("env").get("REGISTRY").asText());
  }

  /** #1212 precedence: step env overrides stage overrides pipeline for {@code ${{ env.X }}}. */
  @Test
  void envTemplateRespectsPipelineStageStepPrecedence() throws Exception {
    TitanStores stores = FakeTitanStores.create();
    long buildId = seedBuildWithTriggerMeta(stores, null, null);
    StepDispatcher dispatcher = new StepDispatcher(stores, buildId, CredentialsPort.noOp());

    StepModel step = shStep("step-1", "tag=${{ env.TAG }} reg=${{ env.REGISTRY }}");
    step.setEnv(Map.of("TAG", "step-tag"));
    StageModel stage = stage("stage-a", null, List.of(step));
    stage.setEnv(Map.of("TAG", "stage-tag", "REGISTRY", "stage-reg"));
    PipelineModel model = model(List.of(stage));
    model.setEnv(Map.of("TAG", "pipe-tag", "REGISTRY", "pipe-reg"));

    dispatcher.enqueueStep(stage, step, model, Map.of());

    TaskQueueRow t = stores.taskQueue().listByBuild(buildId).get(0);
    String command = JSON.readTree(t.payloadJson).get("command").get(2).asText();
    assertEquals("tag=step-tag reg=stage-reg", command);
  }

  /**
   * #1212 adversarial: a missing env key behaves exactly like a missing param key today — the
   * member access yields null and renders to the empty string, it does NOT throw (only an unknown
   * top-level namespace throws). Nested/combined templates in one argument both resolve.
   */
  @Test
  void envTemplateMissingKeyRendersEmptyAndCombinesWithOtherTemplates() throws Exception {
    TitanStores stores = FakeTitanStores.create();
    long buildId = seedBuildWithTriggerMeta(stores, null, null);
    StepDispatcher dispatcher = new StepDispatcher(stores, buildId, CredentialsPort.noOp());

    StepModel step = shStep("step-1", "image:${{ env.REGISTRY }}/app:${{ env.MISSING }}");
    StageModel stage = stage("stage-a", null, List.of(step));
    PipelineModel model = model(List.of(stage));
    model.setEnv(Map.of("REGISTRY", "reg.io"));

    dispatcher.enqueueStep(stage, step, model, Map.of());

    TaskQueueRow t = stores.taskQueue().listByBuild(buildId).get(0);
    String command = JSON.readTree(t.payloadJson).get("command").get(2).asText();
    assertEquals("image:reg.io/app:", command);
  }

  /**
   * #1212 + #847: an engine-implicit env value (GIT_COMMIT) is readable via {@code ${{ env.X }}} in
   * a step arg — exactly as the StepDispatcher comment promises — without the pipeline declaring
   * it.
   */
  @Test
  void envTemplateReadsImplicitEngineEnv() throws Exception {
    TitanStores stores = FakeTitanStores.create();
    long buildId = seedBuildWithTriggerMeta(stores, "cafe1234", "trunk");
    StepDispatcher dispatcher = new StepDispatcher(stores, buildId, CredentialsPort.noOp());

    StepModel step = shStep("step-1", "deploy --sha ${{ env.GIT_COMMIT }}");
    StageModel stage = stage("stage-a", null, List.of(step));
    PipelineModel model = model(List.of(stage));

    dispatcher.enqueueStep(stage, step, model, Map.of());

    TaskQueueRow t = stores.taskQueue().listByBuild(buildId).get(0);
    String command = JSON.readTree(t.payloadJson).get("command").get(2).asText();
    assertEquals("deploy --sha cafe1234", command);
  }

  // ── seed helpers ────────────────────────────────────────────────────────────

  private static long seedBuildWithTriggerMeta(
      TitanStores stores, String commitSha, String branch) {
    JobRow job = new JobRow();
    job.fullName = "step-dispatcher-implicit-env-test/" + System.nanoTime();
    job.pipelineScript = "titan: {}";
    job.configJson = "{}";
    job.enabled = true;
    job.createdAt = Instant.now();
    job.updatedAt = job.createdAt;
    long jobId = stores.jobs().insert(job);

    BuildRow build = new BuildRow();
    build.jobId = jobId;
    build.buildNumber = 1;
    build.status = "RUNNING";
    build.queuedAt = Instant.now();
    if (commitSha != null || branch != null) {
      StringBuilder sb = new StringBuilder("{");
      boolean first = true;
      if (commitSha != null) {
        sb.append("\"commitSha\":\"").append(commitSha).append("\"");
        first = false;
      }
      if (branch != null) {
        if (!first) {
          sb.append(",");
        }
        sb.append("\"branch\":\"").append(branch).append("\"");
      }
      sb.append("}");
      build.triggerMetaJson = sb.toString();
    }
    return stores.builds().insert(build);
  }

  private static StepModel shStep(String id, String script) {
    StepModel s = new StepModel();
    s.setId(id);
    s.setDescriptorId("sh");
    s.setArguments(Map.of("script", script));
    return s;
  }

  private static StageModel stage(String id, String agentLabel, List<StepModel> steps) {
    StageModel s = new StageModel();
    s.setId(id);
    s.setName(id);
    s.setAgentLabel(agentLabel);
    s.setSteps(steps);
    return s;
  }

  private static PipelineModel model(List<StageModel> stages) {
    PipelineModel m = new PipelineModel();
    m.setStages(stages);
    return m;
  }
}

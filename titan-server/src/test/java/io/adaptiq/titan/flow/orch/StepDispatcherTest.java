package io.adaptiq.titan.flow.orch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StepDispatcher} — the queue + payload-build seam. Exercises a happy-path enqueue
 * against a {@link FakeTitanStores} and verifies routing fallback. Credential-resolution failures
 * and full payload sealing are covered by the existing IT suite ({@code TitanCredentialFailureIT})
 * which is the regression oracle for this refactor.
 */
class StepDispatcherTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void enqueueStepEmitsAnExecuteCommandTaskOnTheStagesAgentLabel() throws Exception {
    TitanStores stores = FakeTitanStores.create();
    long buildId = seedBuild(stores);
    StepDispatcher dispatcher = new StepDispatcher(stores, buildId, CredentialsPort.noOp());

    StageModel stage = stage("stage-a", "linux", List.of(shStep("step-1", "echo hi")));
    PipelineModel model = model(List.of(stage));

    dispatcher.enqueueStep(stage, stage.getSteps().get(0), model, Map.of());

    List<TaskQueueRow> tasks = stores.taskQueue().listByBuild(buildId);
    assertEquals(1, tasks.size(), "exactly one task enqueued");
    TaskQueueRow t = tasks.get(0);
    assertEquals("EXECUTE_COMMAND", t.type);
    assertEquals("linux", t.queueName, "task routed to the stage's agent label");
    assertEquals("step-1", t.nodeId);

    JsonNode payload = JSON.readTree(t.payloadJson);
    assertEquals(buildId, payload.get("buildId").asLong());
    assertEquals("step-1", payload.get("nodeId").asText());
    assertEquals("sh", payload.get("stepDescriptor").asText());
    // sh step → command array with sh -c <script>
    assertTrue(payload.get("command").isArray());
    assertEquals("sh", payload.get("command").get(0).asText());
    assertEquals("-c", payload.get("command").get(1).asText());
    assertEquals("echo hi", payload.get("command").get(2).asText());
  }

  @Test
  void enqueueStepFallsBackToDefaultQueueWhenStageHasNoAgentLabel() {
    TitanStores stores = FakeTitanStores.create();
    long buildId = seedBuild(stores);
    StepDispatcher dispatcher = new StepDispatcher(stores, buildId, CredentialsPort.noOp());

    // No stage agent label, no pipeline-wide agent → "default".
    StageModel stage = stage("stage-a", null, List.of(shStep("step-1", "true")));
    PipelineModel model = model(List.of(stage));

    dispatcher.enqueueStep(stage, stage.getSteps().get(0), model, Map.of());

    List<TaskQueueRow> tasks = stores.taskQueue().listByBuild(buildId);
    assertNotNull(tasks);
    assertEquals(1, tasks.size());
    assertEquals("default", tasks.get(0).queueName);
  }

  private static long seedBuild(TitanStores stores) {
    JobRow job = new JobRow();
    job.fullName = "step-dispatcher-test/" + System.nanoTime();
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

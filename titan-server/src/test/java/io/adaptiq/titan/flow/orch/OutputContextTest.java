package io.adaptiq.titan.flow.orch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.JobRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OutputContext} — the {@code ${{ … }}} resolution-context builder. Static helpers
 * are exercised directly; the instance {@code build(...)} is exercised against a {@link
 * FakeTitanStores} so params are read from a real build row (the only DB access in the class).
 */
class OutputContextTest {

  @Test
  void outputsOfReturnsEmptyMapForNullOrBlankOrMissingOutputs() {
    assertTrue(OutputContext.outputsOf(null).isEmpty());
    assertTrue(OutputContext.outputsOf("").isEmpty());
    assertTrue(OutputContext.outputsOf("{}").isEmpty(), "no outputs key → empty");
    assertTrue(OutputContext.outputsOf("not-json").isEmpty(), "garbage → empty, never throws");
  }

  @Test
  void outputsOfExtractsTheOutputsObject() {
    Map<String, Object> out =
        OutputContext.outputsOf("{\"exitCode\":0,\"outputs\":{\"k\":\"v\",\"n\":3}}");
    assertEquals("v", out.get("k"));
    assertEquals(3, out.get("n"));
  }

  @Test
  void parseParamsReturnsEmptyMapForNullOrBlankOrInvalid() {
    assertTrue(OutputContext.parseParams(null).isEmpty());
    assertTrue(OutputContext.parseParams("").isEmpty());
    assertTrue(
        OutputContext.parseParams("garbage").isEmpty(), "invalid JSON → empty, never throws");
  }

  @Test
  void parseParamsParsesABuildParametersBlob() {
    Map<String, Object> p = OutputContext.parseParams("{\"branch\":\"main\",\"runIt\":true}");
    assertEquals("main", p.get("branch"));
    assertEquals(true, p.get("runIt"));
  }

  @Test
  void buildProducesParamsStepsAndPipelineOutputs() {
    TitanStores stores = FakeTitanStores.create();
    long jobId = insertJob(stores);
    long buildId = insertBuild(stores, jobId, "{\"branch\":\"main\"}");

    StepModel s1 = step("step-1");
    StageModel stage = stage("stage-a", List.of(s1));
    stage.setName("Build");
    PipelineModel model = model(List.of(stage));

    FlowNodeRow stepNode = new FlowNodeRow();
    stepNode.nodeId = "step-1";
    stepNode.status = "SUCCESS";
    stepNode.resultJson = "{\"outputs\":{\"version\":\"1.2.3\"}}";

    OutputContext ctx = new OutputContext(stores, buildId);
    Map<String, Object> built = ctx.build(model, Map.of("step-1", stepNode));

    // params are read from the build row
    assertNotNull(built.get("params"));
    assertEquals("main", ((Map<?, ?>) built.get("params")).get("branch"));

    // steps[stage-name].outputs carries the merged outputs of the stage's steps
    Map<?, ?> stepsCtx = (Map<?, ?>) built.get("steps");
    Map<?, ?> buildOutputs = (Map<?, ?>) ((Map<?, ?>) stepsCtx.get("Build")).get("outputs");
    assertEquals("1.2.3", buildOutputs.get("version"));

    // pipeline.outputs is the build-global merge
    Map<?, ?> pipelineOutputs = (Map<?, ?>) ((Map<?, ?>) built.get("pipeline")).get("outputs");
    assertEquals("1.2.3", pipelineOutputs.get("version"));
  }

  private static long insertJob(TitanStores stores) {
    JobRow row = new JobRow();
    row.fullName = "output-context-test/" + System.nanoTime();
    row.pipelineScript = "titan: {}";
    row.configJson = "{}";
    row.enabled = true;
    row.createdAt = Instant.now();
    row.updatedAt = row.createdAt;
    return stores.jobs().insert(row);
  }

  private static long insertBuild(TitanStores stores, long jobId, String parametersJson) {
    BuildRow row = new BuildRow();
    row.jobId = jobId;
    row.buildNumber = 1;
    row.status = "QUEUED";
    row.parametersJson = parametersJson;
    row.queuedAt = Instant.now();
    return stores.builds().insert(row);
  }

  private static StepModel step(String id) {
    StepModel s = new StepModel();
    s.setId(id);
    s.setDescriptorId("sh");
    return s;
  }

  private static StageModel stage(String id, List<StepModel> steps) {
    StageModel s = new StageModel();
    s.setId(id);
    s.setName(id);
    s.setSteps(steps);
    return s;
  }

  private static PipelineModel model(List<StageModel> stages) {
    PipelineModel m = new PipelineModel();
    m.setStages(stages);
    return m;
  }
}

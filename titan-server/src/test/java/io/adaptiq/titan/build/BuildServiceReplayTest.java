package io.adaptiq.titan.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.api.ApiBadRequestException;
import io.adaptiq.titan.api.ApiNotFoundException;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Service-layer tests for the replay-from-node path (issue #307).
 *
 * <p>These hunt the sad paths — missing parent, missing node, non-terminal node — and the happy
 * path's structural invariants: a new build row with the parent's pipeline model copied across,
 * params merged from parent + overrides, and a REPLAY_FROM_NODE orchestration task enqueued.
 *
 * <p>The orchestration leg itself (the skip-upstream loop) is covered separately in {@link
 * io.adaptiq.titan.queue.OrchestratorReplayTest}.
 */
@QuarkusTest
class BuildServiceReplayTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject TitanStores stores;

  // ── 404 / 400 surface ────────────────────────────────────────────────────

  @Test
  void replay_missingParent_throwsNotFound() {
    BuildService svc = new BuildServiceImpl(stores);
    ApiNotFoundException ex =
        assertThrows(
            ApiNotFoundException.class,
            () -> svc.replay(999_999_999L, "any-node", ReplayOptions.none()));
    assertTrue(ex.getMessage().contains("999999999"));
  }

  @Test
  void replay_parentWithoutModel_throwsBadRequest() {
    BuildService svc = new BuildServiceImpl(stores);
    long jobId = freshJob();
    Build parent = svc.create(newReq(jobId, "alice", "manual"));
    // No pipeline_model_json — parent never reached BAKE.
    ApiBadRequestException ex =
        assertThrows(
            ApiBadRequestException.class,
            () -> svc.replay(parent.id(), "stage-1", ReplayOptions.none()));
    assertTrue(ex.getMessage().contains("no synthesised pipeline model"));
  }

  @Test
  void replay_unknownNodeId_throwsBadRequest() {
    BuildService svc = new BuildServiceImpl(stores);
    long parentId = freshBakedParent("stage-1", "SUCCESS");
    ApiBadRequestException ex =
        assertThrows(
            ApiBadRequestException.class,
            () -> svc.replay(parentId, "ghost-node", ReplayOptions.none()));
    assertTrue(ex.getMessage().contains("ghost-node"));
  }

  @Test
  void replay_nonTerminalNode_throwsBadRequest() {
    BuildService svc = new BuildServiceImpl(stores);
    long parentId = freshBakedParent("stage-1", "RUNNING");
    ApiBadRequestException ex =
        assertThrows(
            ApiBadRequestException.class,
            () -> svc.replay(parentId, "stage-1", ReplayOptions.none()));
    assertTrue(ex.getMessage().contains("RUNNING"));
  }

  // ── happy path ───────────────────────────────────────────────────────────

  @Test
  void replay_happyPath_createsNewBuildWithReplayMetadata() {
    BuildService svc = new BuildServiceImpl(stores);
    long parentId = freshBakedParent("stage-1", "SUCCESS");

    Build replay = svc.replay(parentId, "stage-1", ReplayOptions.none());

    assertNotNull(replay);
    assertNotEquals(parentId, replay.id());
    assertEquals("QUEUED", replay.status());
    assertEquals(Long.valueOf(parentId), replay.replayedFromBuildId());
    assertEquals("stage-1", replay.replayedFromNodeId());
    assertEquals("replay", replay.triggerType());
    // The parent's pipeline_model_json must be carried over verbatim (no re-parse, no SCM fetch).
    BuildRow parentRow = stores.builds().findById(parentId).orElseThrow();
    BuildRow replayRow = stores.builds().findById(replay.id()).orElseThrow();
    assertEquals(parentRow.pipelineModelJson, replayRow.pipelineModelJson);
  }

  @Test
  void replay_paramOverrides_areMergedWithParentParams() throws Exception {
    BuildService svc = new BuildServiceImpl(stores);
    long parentId = freshBakedParentWithParams("{\"a\":\"1\",\"b\":\"2\"}", "stage-1", "SUCCESS");

    Build replay = svc.replay(parentId, "stage-1", new ReplayOptions(Map.of("b", "20", "c", "30")));

    BuildRow replayRow = stores.builds().findById(replay.id()).orElseThrow();
    assertNotNull(replayRow.parametersJson);
    @SuppressWarnings("unchecked")
    Map<String, Object> merged = JSON.readValue(replayRow.parametersJson, Map.class);
    assertEquals("1", merged.get("a"));
    assertEquals("20", merged.get("b")); // override wins on collision
    assertEquals("30", merged.get("c")); // new key added
    assertEquals(3, merged.size());
  }

  @Test
  void replay_enqueuesReplayFromNodeTask() {
    BuildService svc = new BuildServiceImpl(stores);
    long parentId = freshBakedParent("stage-1", "SUCCESS");

    Build replay = svc.replay(parentId, "stage-1", ReplayOptions.none());

    // The transaction must have inserted exactly one orchestration task for the new build, and
    // it must carry the REPLAY_FROM_NODE action.
    var tasks = stores.taskQueue().listByBuild(replay.id());
    assertEquals(1, tasks.size(), "expected exactly one task for the new build");
    var task = tasks.get(0);
    assertEquals("ORCHESTRATE", task.type);
    assertTrue(
        task.payloadJson.contains("REPLAY_FROM_NODE"),
        "payload must dispatch the REPLAY_FROM_NODE action: " + task.payloadJson);
    assertTrue(
        task.payloadJson.contains(String.valueOf(replay.id())),
        "payload must carry the new build id: " + task.payloadJson);
  }

  @Test
  void replay_buildNumbering_continuesFromParentJob() {
    BuildService svc = new BuildServiceImpl(stores);
    long parentId = freshBakedParent("stage-1", "SUCCESS");
    int parentNumber = stores.builds().findById(parentId).orElseThrow().buildNumber;

    Build replay = svc.replay(parentId, "stage-1", ReplayOptions.none());

    // The replay's number is the next slot in the SAME job — no number reuse.
    assertEquals(parentNumber + 1, replay.buildNumber());
  }

  @Test
  void replay_stepIdAnchor_isAcceptedAndCreatesNewBuild() {
    // Issue #621: the per-step "Replay from here" button submits a STEP id, not a stage id.
    // BuildService.replay must accept step ids that exist in the parent's pipeline model and
    // are materialised terminal in flow_nodes — the orchestrator's REPLAY_FROM_NODE handler
    // (PR #620) knows how to anchor on a step.
    BuildService svc = new BuildServiceImpl(stores);
    long parentId = freshBakedParentWithStep("stage-1", "stage-1-step-1", "SUCCESS");

    Build replay = svc.replay(parentId, "stage-1-step-1", ReplayOptions.none());

    assertNotNull(replay);
    assertEquals("stage-1-step-1", replay.replayedFromNodeId());
    assertEquals(Long.valueOf(parentId), replay.replayedFromBuildId());
    assertEquals("replay", replay.triggerType());
  }

  @Test
  void replay_unknownStepId_throwsBadRequest() {
    BuildService svc = new BuildServiceImpl(stores);
    long parentId = freshBakedParentWithStep("stage-1", "stage-1-step-1", "SUCCESS");
    ApiBadRequestException ex =
        assertThrows(
            ApiBadRequestException.class,
            () -> svc.replay(parentId, "stage-1-step-ghost", ReplayOptions.none()));
    assertTrue(ex.getMessage().contains("stage-1-step-ghost"));
  }

  @Test
  void replay_emptyParamsAndNoOverrides_persistsNullParameters() {
    BuildService svc = new BuildServiceImpl(stores);
    long parentId = freshBakedParentWithParams(null, "stage-1", "SUCCESS");

    Build replay = svc.replay(parentId, "stage-1", ReplayOptions.none());

    BuildRow row = stores.builds().findById(replay.id()).orElseThrow();
    assertNull(row.parametersJson, "no params on parent + no overrides → null on replay");
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private long freshJob() {
    JobRow row = new JobRow();
    row.fullName = "replay-test/" + System.nanoTime();
    row.pipelineScript = "";
    row.configJson = "{}";
    row.enabled = true;
    return stores.jobs().insert(row);
  }

  /**
   * Produce a parent build that is fully "baked": a pipeline_model_json shaped so {@code stage-1}
   * exists, and a flow_nodes row for it in the requested terminal/non-terminal status. Used by the
   * replay tests as a stand-in for the QueueProcessor's bake phase.
   */
  private long freshBakedParent(String nodeId, String nodeStatus) {
    return freshBakedParentWithParams(null, nodeId, nodeStatus);
  }

  private long freshBakedParentWithParams(String paramsJson, String nodeId, String nodeStatus) {
    long jobId = freshJob();
    BuildRow b = new BuildRow();
    b.jobId = jobId;
    b.buildNumber = stores.builds().nextBuildNumber(jobId);
    b.status = "SUCCESS";
    b.queuedAt = Instant.now();
    b.triggeredBy = "test";
    b.triggerType = "manual";
    b.parametersJson = paramsJson;
    // Minimal valid PipelineModel JSON: one stage named "stage-1" with id "stage-1", no steps.
    b.pipelineModelJson =
        "{\"stages\":[{\"name\":\"stage-1\",\"id\":\""
            + nodeId
            + "\",\"steps\":[],\"parentStageIds\":[],\"parallel\":false,\"dependsOn\":[]}],"
            + "\"parameters\":[],\"triggers\":[],\"gates\":[],\"preconditions\":[],"
            + "\"failurePolicy\":\"blockOnFailure\"}";
    long buildId = stores.withTransaction(c -> stores.builds().insert(c, b));

    FlowNodeRow node = new FlowNodeRow();
    node.buildId = buildId;
    node.nodeId = nodeId;
    node.nodeType = "STAGE";
    node.displayName = nodeId;
    node.status = nodeStatus;
    stores.flowNodes().insert(node);

    return buildId;
  }

  /**
   * Bake a parent build whose model carries one stage with one step; both are materialised in
   * flow_nodes. Used by the step-anchor replay tests (#621).
   */
  private long freshBakedParentWithStep(String stageId, String stepId, String stepStatus) {
    long jobId = freshJob();
    BuildRow b = new BuildRow();
    b.jobId = jobId;
    b.buildNumber = stores.builds().nextBuildNumber(jobId);
    b.status = "SUCCESS";
    b.queuedAt = Instant.now();
    b.triggeredBy = "test";
    b.triggerType = "manual";
    b.pipelineModelJson =
        "{\"stages\":[{\"name\":\"stage-1\",\"id\":\""
            + stageId
            + "\",\"steps\":[{\"id\":\""
            + stepId
            + "\",\"descriptorId\":\"sh\",\"arguments\":{},\"parentIds\":[]}],"
            + "\"parentStageIds\":[],\"parallel\":false,\"dependsOn\":[]}],"
            + "\"parameters\":[],\"triggers\":[],\"gates\":[],\"preconditions\":[],"
            + "\"failurePolicy\":\"blockOnFailure\"}";
    long buildId = stores.withTransaction(c -> stores.builds().insert(c, b));

    FlowNodeRow stage = new FlowNodeRow();
    stage.buildId = buildId;
    stage.nodeId = stageId;
    stage.nodeType = "STAGE";
    stage.displayName = stageId;
    stage.status = "SUCCESS";
    stores.flowNodes().insert(stage);

    FlowNodeRow step = new FlowNodeRow();
    step.buildId = buildId;
    step.nodeId = stepId;
    step.nodeType = "STEP";
    step.displayName = stepId;
    step.status = stepStatus;
    stores.flowNodes().insert(step);

    return buildId;
  }

  private static NewBuildRequest newReq(long jobId, String triggeredBy, String triggerType) {
    return new NewBuildRequest(jobId, null, triggeredBy, triggerType, null, null, null);
  }

  /** Keep the import live even if a test reorders. */
  @SuppressWarnings("unused")
  private static List<Object> keepImport() {
    return List.of();
  }
}

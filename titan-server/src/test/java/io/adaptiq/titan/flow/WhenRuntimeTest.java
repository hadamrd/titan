package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Unit-level proof (against an H2-backed {@link FakeTitanStores}) that {@link
 * TitanOrchestrator#advance()} honours a step-level {@code when:} guard at runtime — closes #260,
 * follow-up to PR #259.
 *
 * <p>The DAG has one stage with two steps; the first step carries {@code when: "params.runIt ==
 * false"}. Bake materialises both steps {@code PENDING} (stage-level {@code when:} is the only one
 * that pre-skips at bake; step-level is runtime). One {@code advance()} pass must:
 *
 * <ul>
 *   <li>CAS the guarded step PENDING → SKIPPED — no {@code EXECUTE_COMMAND} row in {@code
 *       task_queue}.
 *   <li>Dispatch the next step in the same stage (SKIPPED is not a blocker — sequential walk
 *       continues).
 * </ul>
 *
 * <p>This is the "no parallel evaluator" contract: the same {@code WhenEvaluator} the bake-time
 * stage-level skip uses (see {@code TitanFlowExecution#isSkippedByWhen}) is invoked by the
 * orchestrator at dispatch time.
 */
class WhenRuntimeTest {

  private static final String YAML =
      """
      titan:
        parameters:
          - name: runIt
            type: boolean
            default: true
        stages:
          - stage: Only
            steps:
              - sh: "echo guarded"
                when: "params.runIt == false"
              - sh: "echo always"
      """;

  @Test
  void stepWithFalsyWhenIsSkippedAndNotDispatched() {
    TitanStores stores = FakeTitanStores.create();

    long jobId = insertJob(stores, YAML);
    // runIt=true → the `when: params.runIt == false` guard evaluates false → SKIP.
    long buildId = insertBuild(stores, jobId, "{\"runIt\":true}");

    // Bake the DAG so flow_nodes exist for the stage + steps.
    new TitanFlowExecution(stores, buildId).bake(YAML);

    Map<String, String> beforeNodes = nodeStatuses(stores, buildId);
    // Sanity — bake did NOT pre-skip the step (step-level when: is a runtime guard).
    String guardedStepId = stepIdFor(stores, buildId, "Only", 0);
    String alwaysStepId = stepIdFor(stores, buildId, "Only", 1);
    assertEquals(
        "PENDING", beforeNodes.get(guardedStepId), "bake leaves step-level when as PENDING");
    assertEquals("PENDING", beforeNodes.get(alwaysStepId));

    // One advance pass — the guarded step must skip; the next step in the stage dispatches.
    TitanOrchestrator orchestrator = new TitanOrchestrator(stores, buildId);
    TitanOrchestrator.AdvanceResult result = orchestrator.advance();

    Map<String, String> after = nodeStatuses(stores, buildId);
    assertEquals("SKIPPED", after.get(guardedStepId), "the falsy `when:` step ends SKIPPED");
    assertEquals(
        "QUEUED", after.get(alwaysStepId), "the next step in the stage was dispatched (QUEUED)");

    // No EXECUTE_COMMAND task was enqueued for the skipped step — the runtime never paid for it.
    List<TaskQueueRow> tasks = stores.taskQueue().listByBuild(buildId);
    long taskCountForSkipped =
        tasks.stream()
            .filter(t -> "EXECUTE_COMMAND".equals(t.type))
            .filter(t -> guardedStepId.equals(t.nodeId))
            .count();
    assertEquals(0, taskCountForSkipped, "no task_queue row for the skipped step");

    // Exactly one EXECUTE_COMMAND was dispatched — for the un-guarded step.
    long execTasks = tasks.stream().filter(t -> "EXECUTE_COMMAND".equals(t.type)).count();
    assertEquals(1, execTasks);
    assertEquals(1, result.dispatched(), "advance() reports the one dispatched (un-guarded) step");
  }

  @Test
  void stepWithTruthyWhenIsDispatchedAsNormal() {
    TitanStores stores = FakeTitanStores.create();

    long jobId = insertJob(stores, YAML);
    // runIt=false → the `when: params.runIt == false` guard evaluates true → RUN.
    long buildId = insertBuild(stores, jobId, "{\"runIt\":false}");
    new TitanFlowExecution(stores, buildId).bake(YAML);

    String guardedStepId = stepIdFor(stores, buildId, "Only", 0);
    TitanOrchestrator.AdvanceResult result = new TitanOrchestrator(stores, buildId).advance();

    Map<String, String> after = nodeStatuses(stores, buildId);
    assertEquals("QUEUED", after.get(guardedStepId), "the truthy `when:` step proceeds to QUEUED");
    // Stage walks one step at a time; the second step stays PENDING this tick.
    assertEquals(1, result.dispatched());
    long execTasks =
        stores.taskQueue().listByBuild(buildId).stream()
            .filter(t -> "EXECUTE_COMMAND".equals(t.type))
            .filter(t -> guardedStepId.equals(t.nodeId))
            .count();
    assertTrue(execTasks == 1, "a truthy when: dispatches the step as normal");
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static Map<String, String> nodeStatuses(TitanStores stores, long buildId) {
    return stores.flowNodes().listByBuild(buildId).stream()
        .collect(Collectors.toMap(n -> n.nodeId, n -> n.status));
  }

  /** Look up the nth step node id of a stage by its display name. */
  private static String stepIdFor(TitanStores stores, long buildId, String stageName, int index) {
    FlowNodeRow stage =
        stores.flowNodes().listByBuild(buildId).stream()
            .filter(n -> "STAGE".equals(n.nodeType) && stageName.equals(n.displayName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("stage not materialised: " + stageName));
    List<FlowNodeRow> steps =
        stores.flowNodes().listByBuild(buildId).stream()
            .filter(n -> "STEP".equals(n.nodeType) && stage.nodeId.equals(n.parentIds))
            .sorted(Comparator.comparing(n -> n.nodeId))
            .collect(Collectors.toList());
    if (index >= steps.size()) {
      throw new AssertionError(
          "stage " + stageName + " has " + steps.size() + " steps, asked for index " + index);
    }
    return steps.get(index).nodeId;
  }

  private static long insertJob(TitanStores stores, String yaml) {
    JobRow row = new JobRow();
    row.fullName = "when-test/" + System.nanoTime();
    row.pipelineScript = yaml;
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
}

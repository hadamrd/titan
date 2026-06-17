package io.adaptiq.titan.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.expr.WhenEvaluator;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.orch.AncestorClosure;
import io.adaptiq.titan.flow.orch.ApprovalParkDetector;
import io.adaptiq.titan.flow.orch.BuildCloser;
import io.adaptiq.titan.flow.orch.ControlPlaneSteps;
import io.adaptiq.titan.flow.orch.GateEvaluator;
import io.adaptiq.titan.flow.orch.MatrixCoordinator;
import io.adaptiq.titan.flow.orch.OutputContext;
import io.adaptiq.titan.flow.orch.PipelineNodes;
import io.adaptiq.titan.flow.orch.QueueSubscriptions;
import io.adaptiq.titan.flow.orch.StageTeardownService;
import io.adaptiq.titan.flow.orch.StepDispatcher;
import io.adaptiq.titan.flow.orch.StepRetryPolicy;
import io.adaptiq.titan.flow.orch.StructuredWhenGate;
import io.adaptiq.titan.flow.orch.TimeoutEnforcer;
import io.adaptiq.titan.observability.StepMetrics;
import io.adaptiq.titan.store.FlowNodeDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.AgentRow;
import io.adaptiq.titan.store.rows.ApprovalRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import io.adaptiq.titan.timer.TimerService;
import io.adaptiq.titan.timer.WaitResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Advances a build's DAG — the reactive heart of the engine (Chunk 6D, design/29 §5, design/30).
 *
 * <p>{@link #advance()} is a <strong>reconciler</strong>, not a continuation: it holds no state,
 * reads {@code flow_nodes} + {@code task_queue}, derives the next move, and acts. Run it again and
 * it converges further; run it twice and the compare-and-set transitions make the second run a
 * no-op. This is what makes a crashed orchestrator safe — a survivor just calls {@code advance()}
 * (design/30: "the next poller re-derives the next move").
 *
 * <p>One pass:
 *
 * <ol>
 *   <li><b>Reconcile</b> — a finished {@code EXECUTE_COMMAND} task flips its {@code flow_nodes}
 *       step from {@code QUEUED} to {@code SUCCESS}/{@code FAILED} (CAS).
 *   <li><b>Advance</b> — a stage whose {@code dependsOn} are all done becomes {@code RUNNING}; its
 *       steps are dispatched one at a time, in order, as {@code EXECUTE_COMMAND} tasks; a stage
 *       whose steps all succeeded becomes {@code SUCCESS}.
 *   <li><b>Finish</b> — under {@code blockOnFailure}, a failure skips only the failed node's
 *       descendants (issue #945) — independent sibling chains run to completion. Once every node is
 *       terminal the build is closed {@code SUCCESS}/{@code FAILED}.
 * </ol>
 *
 * <p>Every transition is compare-and-set ({@link FlowNodeDao#compareAndSetStatus}), so two
 * controllers reconciling the same build cannot double-advance a node (design/26 Tier B).
 *
 * <p>Heavy lifting is delegated to package-private collaborators under {@link
 * io.adaptiq.titan.flow.orch} (#357 decomposition): {@code StepRetryPolicy} (retry decisions),
 * {@code TimeoutEnforcer} (TIMEOUT timers), {@code StepDispatcher} (payload + queue), {@code
 * MatrixCoordinator} (matrix throttle), {@code OutputContext} (the {@code ${{ … }}} context),
 * {@code PipelineNodes} (static model lookups). This class owns the state-machine itself — gates,
 * preconditions, stage walk, build close.
 */
public final class TitanOrchestrator {

  private static final Logger LOGGER = Logger.getLogger(TitanOrchestrator.class.getName());
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Set<String> NODE_TERMINAL =
      Set.of("SUCCESS", "FAILED", "ABORTED", "SKIPPED");
  private static final Set<String> TASK_TERMINAL =
      Set.of("COMPLETED", "FAILED", "TIMED_OUT", "CANCELLED");
  private static final Set<String> DEP_SATISFIED = Set.of("SUCCESS", "SKIPPED");

  /**
   * Grace window before an unclaimed {@code QUEUED} EXECUTE_COMMAND is declared unschedulable
   * (#824). A real worker claim is sub-second on a healthy rig; 60s comfortably covers a worker
   * restart / heartbeat blip without spuriously failing a step.
   */
  static final long UNSCHEDULABLE_GRACE_SECONDS = 60L;

  /**
   * Liveness window matching {@code AgentDao#listOnline} elsewhere in the orchestrator — a worker
   * is "subscribed" only if its last heartbeat is this fresh.
   */
  private static final int AGENT_LIVENESS_SECONDS = 30;

  private final TitanStores daos;
  private final long buildId;
  private final TimerService timerService;
  private final NotificationDispatcher notifications;
  private final StepDispatcher stepDispatcher;
  private final TimeoutEnforcer timeoutEnforcer;
  private final OutputContext outputContext;
  private final StepRetryPolicy retryPolicy;
  private final StageTeardownService stageTeardown;
  private final BuildCloser buildCloser;
  private final GateEvaluator gateEvaluator;

  /**
   * Lazily-resolved job {@code full_name} for this build, used as the {@code job} label on the
   * {@code titan_step_duration_seconds} histogram (issue #1081). Null until first terminal
   * transition; from then on cached for the lifetime of this orchestrator instance. {@code null}
   * stays a legitimate value when the lookup fails — {@link StepMetrics} folds it to {@code
   * "unknown"} on emission.
   */
  @Nullable private String cachedJobName;

  /** Dispatch-time decision for structured {@code when:} guards (GH #1093); lazily created. */
  @Nullable private StructuredWhenGate whenGate;

  private boolean jobNameResolved;

  /**
   * Construct with a no-op credentials port (no credential bindings resolved). Suitable for test
   * scenarios and deployments where credentials are not yet wired.
   */
  public TitanOrchestrator(@NonNull TitanStores daos, long buildId) {
    this(daos, buildId, CredentialsPort.noOp(), new NotificationDispatcher());
  }

  /**
   * Construct with an explicit {@link CredentialsPort} — the production path in {@code
   * titan-server} where a real secrets back-end (Vault, DB-backed store) is injected.
   */
  public TitanOrchestrator(
      @NonNull TitanStores daos, long buildId, @NonNull CredentialsPort credentialsPort) {
    this(daos, buildId, credentialsPort, new NotificationDispatcher());
  }

  /**
   * Construct with an explicit {@link NotificationDispatcher} — the test seam that lets an IT
   * inject an HttpClient bound to a local sink (#245).
   */
  public TitanOrchestrator(
      @NonNull TitanStores daos,
      long buildId,
      @NonNull CredentialsPort credentialsPort,
      @NonNull NotificationDispatcher notifications) {
    this.daos = daos;
    this.buildId = buildId;
    this.timerService = new TimerService(daos.timers());
    this.notifications = notifications;
    this.stepDispatcher = new StepDispatcher(daos, buildId, credentialsPort);
    this.timeoutEnforcer = new TimeoutEnforcer(daos, buildId, timerService);
    this.outputContext = new OutputContext(daos, buildId);
    this.retryPolicy = new StepRetryPolicy(daos, buildId, stepDispatcher, outputContext);
    this.stageTeardown = new StageTeardownService();
    this.buildCloser = new BuildCloser(daos, notifications);
    this.gateEvaluator = new GateEvaluator(daos, buildId, timerService);
  }

  /**
   * Test-only constructor — lets an IT inject a recording {@link StageTeardownService} so the
   * kubectl shell-out can be asserted without a real cluster (#662).
   */
  public TitanOrchestrator(
      @NonNull TitanStores daos,
      long buildId,
      @NonNull CredentialsPort credentialsPort,
      @NonNull NotificationDispatcher notifications,
      @NonNull StageTeardownService stageTeardown) {
    this.daos = daos;
    this.buildId = buildId;
    this.timerService = new TimerService(daos.timers());
    this.notifications = notifications;
    this.stepDispatcher = new StepDispatcher(daos, buildId, credentialsPort);
    this.timeoutEnforcer = new TimeoutEnforcer(daos, buildId, timerService);
    this.outputContext = new OutputContext(daos, buildId);
    this.retryPolicy = new StepRetryPolicy(daos, buildId, stepDispatcher, outputContext);
    this.stageTeardown = stageTeardown;
    this.buildCloser = new BuildCloser(daos, notifications);
    this.gateEvaluator = new GateEvaluator(daos, buildId, timerService);
  }

  /**
   * Outcome of one {@link #advance()} pass.
   *
   * @param dispatched step {@code EXECUTE_COMMAND} tasks enqueued this pass
   * @param reconciled finished step tasks folded into {@code flow_nodes} this pass
   * @param buildFinished whether every node is now terminal and the build was closed
   * @param buildResult {@code SUCCESS}/{@code FAILED} if finished, else {@code null}
   * @param parked {@code true} if at least one node is awaiting a human decision (an {@code
   *     approval:} step parked SLEEPING with a PENDING approval row) and the orchestrator should
   *     NOT be re-ticked until an external event un-parks it: a winning {@code
   *     ApprovalService.decide} call (APPROVED / REJECTED) or the approval-timeout sweep
   *     (TIMED_OUT) — each of which enqueues a fresh ADVANCE. Re-ticking a parked build on a fixed
   *     cadence is the source of the "compareAndSetStatus failed" race on titan.test (build 14):
   *     every poll CASes against the parked node and eventually loses, fail-closing the build with
   *     a cryptic engine error. GitHub Actions and Buildkite take the same stance — parked is a
   *     real state, resume is event-driven.
   */
  public record AdvanceResult(
      int dispatched,
      int reconciled,
      boolean buildFinished,
      @Nullable String buildResult,
      boolean parked) {}

  /** Run one reconcile + advance pass over the build's DAG. */
  @NonNull
  public AdvanceResult advance() {
    // Hot path: every ADVANCE tick used to re-fetch + re-parse pipeline_model_json. The
    // PipelineModelCache (Caffeine, 30M TTL, invalidated on terminal writes) collapses
    // that to one parse per build across a tick burst. Static accessor here avoids
    // threading the cache through the constructor of every QueueProcessor.handleAdvance
    // call site — falls back to a fresh load when CDI is not running (raw JUnit).
    PipelineModel model = io.adaptiq.titan.cache.PipelineModelCache.loadOrFresh(daos, buildId);
    FlowNodeDao flowNodes = daos.flowNodes();
    // issue #493: read both task_queue AND task_archive. A terminal EXECUTE_COMMAND task is
    // archived by QueueProcessor's per-tick sweep (PR #491). If the very next ADVANCE reads
    // only task_queue, the reconciler can't see the just-completed task and the step node
    // stays QUEUED — then advanceSteps' "dispatch was lost" self-heal fires and re-enqueues
    // a fresh EXECUTE_COMMAND on every tick (infinite ORCHESTRATE loop). The union view
    // makes the reconciler see archived terminal tasks and fold the node to SUCCESS/FAILED.
    List<TaskQueueRow> tasks = daos.taskQueue().listByBuildIncludingArchive(buildId);

    int reconciled = reconcileFinishedSteps(flowNodes, model, tasks);
    wakeSleepingNodes(flowNodes);
    timeoutEnforcer.enforceTimeouts(flowNodes, tasks);

    Map<String, FlowNodeRow> nodes = PipelineNodes.byId(flowNodes.listByBuild(buildId));
    Map<String, String> nameToId = PipelineNodes.nameToId(model);
    // The ${{ steps[...].outputs }} resolution context — built from the outputs published by
    // already-finished steps. Resolution happens at dispatch, never at bake (design/29 §7.1).
    Map<String, Object> ctx = outputContext.build(model, nodes);

    int dispatched = 0;
    for (StageModel stage : model.getStages()) {
      FlowNodeRow stageNode = nodes.get(stage.getId());
      if (stageNode == null || NODE_TERMINAL.contains(stageNode.status)) {
        continue;
      }
      // design/68 / #947: a failure-handler stage (non-empty onFailureStages) is
      // gated on the terminal state of its listed upstreams rather than on
      // dependsOn success-edges (the two are mutually exclusive at parse time).
      if (!stage.getOnFailureStages().isEmpty()) {
        OnFailureGate decision = evaluateOnFailureGate(stage, nameToId, flowNodes);
        if (decision == OnFailureGate.WAIT) {
          continue;
        }
        if (decision == OnFailureGate.SKIP) {
          // None of the listed upstreams failed — handler stage is SKIPPED, and its
          // steps must also be SKIPPED so downstream depsSatisfied sees a "done" node.
          if (cas(flowNodes, stage.getId(), stageNode.status, "SKIPPED", true) == 1) {
            Instant now = Instant.now();
            for (StepModel s : stage.getSteps()) {
              FlowNodeRow sn = nodes.get(s.getId());
              if (sn != null && !NODE_TERMINAL.contains(sn.status)) {
                flowNodes.compareAndSetStatus(
                    buildId, s.getId(), sn.status, "SKIPPED", null, now, null, null);
              }
            }
          }
          continue;
        }
        // FIRE — fall through to the normal RUNNING transition + step dispatch.
      } else if (!depsSatisfied(stage.getDependsOn(), nameToId, flowNodes)) {
        continue; // a dependency is unfinished or failed — wait (failure handled below)
      }
      if (stage.getSteps().isEmpty()) {
        cas(flowNodes, stage.getId(), stageNode.status, "SUCCESS", true);
        continue;
      }
      // design/54 §2.5 / #309: honour matrix `maxParallel` at scheduler time.
      // If this stage is a matrix cell and its fan-out group already has `maxParallel`
      // cells in RUNNING, defer the PENDING → RUNNING transition to a later tick.
      // The check is in-memory over the just-loaded model + nodes map; re-ticks converge.
      if (!"RUNNING".equals(stageNode.status)
          && MatrixCoordinator.matrixThrottled(stage, model, nodes)) {
        continue;
      }
      if (!"RUNNING".equals(stageNode.status)) {
        cas(flowNodes, stage.getId(), stageNode.status, "RUNNING", false);
      }
      dispatched += advanceSteps(flowNodes, stage, model, nodes, tasks, ctx);
    }

    gateEvaluator.evaluateGates(flowNodes, model, nameToId, nodes);
    gateEvaluator.evaluatePreconditions(flowNodes, model, nameToId, nodes, ctx);

    // #824: any EXECUTE_COMMAND that has sat QUEUED + unclaimed past the grace window AND
    // targets a queue NO live worker subscribes to is structurally unschedulable — fail it
    // fast with a clear customer-facing reason rather than letting the orchestrator tight-loop
    // forever. Runs after dispatch so a JUST-enqueued task has a tick to be claimed before we
    // even look at it.
    failUnschedulableSteps(flowNodes, tasks);

    return finishIfDone(flowNodes, model, dispatched, reconciled);
  }

  /**
   * Fail every {@code EXECUTE_COMMAND} task that is structurally unschedulable: still {@code
   * QUEUED}, still unclaimed, older than {@link #UNSCHEDULABLE_GRACE_SECONDS}, and routed to a
   * queue that no ONLINE worker currently subscribes to (#824).
   *
   * <p>Without this guard the bug from #824 — a worker that does not poll the {@code agent:}
   * label's queue — manifests as a step parked QUEUED forever and the orchestrator tight-looping on
   * every tick. The fix on the worker side (poll all label queues) covers the common case; this
   * guard covers the configuration error (operator typoed {@code agent: linxu} or there is
   * genuinely no worker for that label) and surfaces the failure where the customer can see it: the
   * step's {@code failure_category=DISPATCH} + a sentence naming the missing queue.
   *
   * <p>Idempotent: a step already moved to {@code FAILED} by an earlier pass is skipped on the CAS;
   * a later pass with a worker that came online never sees the task because the previous pass
   * already terminated it. The DAG's normal failure-policy machinery handles the rest.
   */
  private void failUnschedulableSteps(
      @NonNull FlowNodeDao flowNodes, @NonNull List<TaskQueueRow> tasks) {
    Instant now = Instant.now();
    Instant cutoff = now.minusSeconds(UNSCHEDULABLE_GRACE_SECONDS);
    List<TaskQueueRow> candidates =
        tasks.stream()
            .filter(t -> "EXECUTE_COMMAND".equals(t.type))
            .filter(t -> "QUEUED".equals(t.status))
            .filter(t -> t.claimedAt == null)
            .filter(t -> t.createdAt != null && t.createdAt.isBefore(cutoff))
            .filter(t -> t.nodeId != null && t.queueName != null)
            .toList();
    if (candidates.isEmpty()) {
      return;
    }
    // One agent-list read per tick (only when a candidate exists) — kept off the hot path.
    List<AgentRow> online = daos.agents().listOnline(AGENT_LIVENESS_SECONDS);
    Set<String> served = QueueSubscriptions.servedQueues(online);
    for (TaskQueueRow t : candidates) {
      if (served.contains(t.queueName)) {
        continue;
      }
      String reason =
          "No worker subscribes to queue '"
              + t.queueName
              + "' (no ONLINE agent advertises a matching label). "
              + "Either add a label '"
              + t.queueName
              + "' to a running worker (set TITAN_LABELS) or change the stage's `agent:` to a "
              + "served label.";
      flowNodes.updateFailure(buildId, t.nodeId, "DISPATCH", reason);
      ObjectNode result = JSON.createObjectNode();
      result.put("exitCode", -1);
      result.put("error", reason);
      int won =
          flowNodes.compareAndSetStatus(
              buildId, t.nodeId, "QUEUED", "FAILED", null, now, null, result.toString());
      if (won == 1) {
        LOGGER.log(
            Level.WARNING,
            "[titan] build {0}: step {1} failed UNSCHEDULABLE — queue ''{2}'' has no subscriber",
            new Object[] {buildId, t.nodeId, t.queueName});
        // Cancel the underlying task so the queue feed / depth gauges stop showing a row that
        // can never run. The cancel is independent of the step CAS — if the cancel races a
        // late worker claim and loses, that worker will run + complete the task normally; the
        // step is already FAILED so the result is folded as a no-op on the next reconcile.
        daos.taskQueue().cancel(t.id);
      }
    }
  }

  // ── 1. reconcile ──────────────────────────────────────────────────────

  /**
   * Fold every finished {@code EXECUTE_COMMAND} task into its step's {@code flow_nodes} status.
   *
   * <p>A step node may carry several {@code EXECUTE_COMMAND} tasks across its lifetime — a baked-in
   * {@code retry:} policy (design/44) re-enqueues a fresh task per attempt. Only the
   * <em>latest</em> task per node is the current attempt; an earlier (superseded) FAILED task must
   * not be re-folded onto the node it has already moved past. {@code tasks} is chronological
   * ({@code listByBuild} orders by {@code created_at}), so the last {@code EXECUTE_COMMAND} per
   * node id wins.
   *
   * <p>For a FAILED current task the orchestrator consults the step's retry policy: if attempts
   * remain and the failure is retryable (design/44 §3) it re-dispatches with backoff instead of
   * folding the node to {@code FAILED} (see {@code StepRetryPolicy#retryStep}).
   */
  private int reconcileFinishedSteps(
      @NonNull FlowNodeDao flowNodes,
      @NonNull PipelineModel model,
      @NonNull List<TaskQueueRow> tasks) {
    // Latest EXECUTE_COMMAND task per node id — the current attempt.
    Map<String, TaskQueueRow> latestByNode = new HashMap<>();
    for (TaskQueueRow t : tasks) {
      if ("EXECUTE_COMMAND".equals(t.type) && t.nodeId != null) {
        latestByNode.put(t.nodeId, t); // chronological — last write wins
      }
    }
    int reconciled = 0;
    for (TaskQueueRow t : latestByNode.values()) {
      // issue #508: wire the EXECUTE_COMMAND task_token into flow_nodes.log_task_id so the
      // UI's per-node Logs panel can join titan.logs by task_id. Done for EVERY observed task
      // (including non-terminal RUNNING ones) so live builds also light up. Idempotent — the
      // UPDATE is a no-op when the token already matches. A retry that dispatches a fresh
      // task replaces the pointer so the panel reflects the latest attempt.
      if (t.taskToken != null) {
        try {
          flowNodes.setLogTaskId(buildId, t.nodeId, t.taskToken);
        } catch (RuntimeException e) {
          LOGGER.log(
              Level.WARNING,
              "[titan] build {0}: setLogTaskId failed for node {1}: {2}",
              new Object[] {buildId, t.nodeId, e.getMessage()});
        }
      }
      if (!TASK_TERMINAL.contains(t.status)) {
        continue;
      }
      boolean ok = "COMPLETED".equals(t.status) && exitCodeOk(t.resultJson);
      if (ok) {
        int won =
            flowNodes.compareAndSetStatus(
                buildId, t.nodeId, "QUEUED", "SUCCESS", null, Instant.now(), null, t.resultJson);
        if (won == 1) {
          timerService.cancel(TimerService.Kind.TIMEOUT, buildId, t.nodeId);
          emitStepDurationMetric(flowNodes, t.nodeId, "SUCCESS");
          reconciled++;
          LOGGER.log(
              Level.FINE,
              "[titan] build {0}: step {1} -> SUCCESS",
              new Object[] {buildId, t.nodeId});
        }
        continue;
      }
      // The task FAILED (or COMPLETED with a non-zero exit). Consult the step's retry policy
      // before folding the node to FAILED — a retry re-dispatches instead (design/44 §4).
      if (retryPolicy.retryStep(flowNodes, model, t)) {
        reconciled++;
        continue;
      }
      // The step ran and exited non-zero — its reason is in its own log (design/45 §1). The
      // node records STEP_EXIT plus a one-line summary; the failure model does not duplicate
      // the log content. An infra failure (the worker died, the reaper TIMED_OUT the task)
      // has no exit code — categorise it TIMEOUT and surface the reaper's note.
      flowNodes.updateFailure(
          buildId,
          t.nodeId,
          StepRetryPolicy.stepExitCategory(t),
          StepRetryPolicy.stepExitReason(t));
      int won =
          flowNodes.compareAndSetStatus(
              buildId, t.nodeId, "QUEUED", "FAILED", null, Instant.now(), null, t.resultJson);
      if (won == 1) {
        timerService.cancel(TimerService.Kind.TIMEOUT, buildId, t.nodeId);
        emitStepDurationMetric(flowNodes, t.nodeId, "FAILED");
        reconciled++;
        LOGGER.log(
            Level.FINE, "[titan] build {0}: step {1} -> FAILED", new Object[] {buildId, t.nodeId});
      }
    }
    return reconciled;
  }

  // ── 1b. wake durable-sleep nodes ──────────────────────────────────────

  /**
   * Wake any {@code SLEEPING} node whose {@code wake_at} has passed — CAS {@code SLEEPING →
   * SUCCESS}. The mirror of {@link #reconcileFinishedSteps} for durable-wait nodes (timer
   * subsystem, Phase 2). Self-healing: every {@code advance()} re-evaluates, so a node wakes on the
   * first pass after its instant regardless of which {@code ADVANCE} delivered the tick.
   */
  private int wakeSleepingNodes(@NonNull FlowNodeDao flowNodes) {
    int woken = 0;
    Instant now = Instant.now();
    for (FlowNodeRow node : flowNodes.listByBuildAndStatus(buildId, "SLEEPING")) {
      if (node.wakeAt != null && !node.wakeAt.isAfter(now)) {
        int won =
            flowNodes.compareAndSetStatus(
                buildId, node.nodeId, "SLEEPING", "SUCCESS", null, now, null, null);
        if (won == 1) {
          emitStepDurationMetric(flowNodes, node.nodeId, "SUCCESS");
          woken++;
        }
      }
    }
    return woken;
  }

  // ── 2. advance a stage's steps, sequentially ──────────────────────────

  /** Walk a RUNNING stage's steps in order: dispatch the active one, close the stage when done. */
  private int advanceSteps(
      @NonNull FlowNodeDao flowNodes,
      @NonNull StageModel stage,
      @NonNull PipelineModel model,
      @NonNull Map<String, FlowNodeRow> nodes,
      @NonNull List<TaskQueueRow> tasks,
      @NonNull Map<String, Object> ctx) {
    int dispatched = 0;
    boolean stageFailed = false;
    boolean stageBlocked = false;

    for (StepModel step : stage.getSteps()) {
      FlowNodeRow sn = nodes.get(step.getId());
      if (sn == null) {
        stageBlocked = true;
        break;
      }
      String status = sn.status;
      if ("SUCCESS".equals(status) || "SKIPPED".equals(status)) {
        continue;
      }
      if ("FAILED".equals(status)) {
        stageFailed = true;
        break;
      }
      // PENDING / QUEUED / SLEEPING — the active step. Steps are sequential, so stop here.
      stageBlocked = true;
      if (SetBuildNameResolver.isSetBuildName(step.getDescriptorId())) {
        // Controller-native rename step (#762): no worker, no SLEEPING — the orchestrator
        // resolves args, writes the column, audits, and CASes the node SUCCESS in the same pass.
        // Idempotent on replay; a build that is already terminal still completes SUCCESS so the
        // surrounding DAG drains.
        if ("PENDING".equals(status)) {
          SetBuildNameResolver.applyAndComplete(daos, null, buildId, flowNodes, step, ctx);
        }
        // After applying we should not keep walking — the stage advances on the next pass when
        // the node is observed SUCCESS via the standard "continue if SUCCESS" branch at the top.
        stageBlocked = false;
        continue;
      }
      if (ApprovalResolver.isApproval(step.getDescriptorId())) {
        // Durable human-approval gate (#715): the orchestrator parks the node itself — no worker
        // task. The PENDING row in titan.approvals is the source of truth for the decision;
        // we resume the node SUCCESS / FAILED based on its terminal status on a later pass.
        //
        // Build-22 fix (2026-05-26): the PENDING entry now goes through GateEvaluator
        // which consults findLatestForNode FIRST — a terminal REJECTED / TIMED_OUT row is
        // honoured (no second PENDING row gets inserted). See GateEvaluator class javadoc.
        if ("PENDING".equals(status)) {
          GateEvaluator.ApprovalDecision decision =
              gateEvaluator.evaluateApproval(flowNodes, step, sn, ctx);
          if (decision == GateEvaluator.ApprovalDecision.SKIP) {
            // A terminal-rejected row folded the node FAILED — surface that to the surrounding
            // stage walk so the stage transitions FAILED and the build can drain.
            stageFailed = true;
          }
        } else if ("SLEEPING".equals(status)) {
          resumeApprovalIfDecided(flowNodes, sn);
        }
        break;
      }
      if (WaitResolver.isWait(step.getDescriptorId())) {
        // Durable wait: the orchestrator parks the node itself — no worker task.
        if ("PENDING".equals(status)) {
          Instant wakeAt;
          try {
            wakeAt =
                WaitResolver.resolveWakeAt(
                    step.getDescriptorId(),
                    TemplateResolver.resolveArguments(step.getArguments(), ctx),
                    Instant.now());
          } catch (IllegalArgumentException badInput) {
            flowNodes.updateFailure(buildId, step.getId(), "CONFIG", badInput.getMessage());
            flowNodes.compareAndSetStatus(
                buildId, step.getId(), "PENDING", "FAILED", null, Instant.now(), null, null);
            stageFailed = true;
            break;
          }
          if (flowNodes.compareAndSetStatus(
                  buildId, step.getId(), "PENDING", "SLEEPING", Instant.now(), null, null, null)
              == 1) {
            flowNodes.setWakeAt(buildId, step.getId(), wakeAt);
            timerService.arm(TimerService.Kind.SLEEP, buildId, step.getId(), wakeAt, null);
          }
        }
        // PENDING-just-parked, or already SLEEPING — either way the stage waits here.
        break;
      }
      if ("PENDING".equals(status)) {
        // design/29 §3, #260: a step-level `when:` is evaluated at dispatch time against the
        // same `${{ … }}` context the orchestrator uses for argument resolution (params + the
        // outputs of already-finished steps). Reuses the shared WhenEvaluator the bake-time
        // stage-level skip already calls (TitanFlowExecution#isSkippedByWhen) — one evaluator,
        // two phases (design/29 §4). A falsy guard ends the node SKIPPED with no task_queue
        // row; depsSatisfied treats SKIPPED as "done", so downstream stages proceed.
        boolean skipByWhen;
        try {
          if (step.getWhenCondition() != null) {
            // GH #1093: a structured (typed) `when:` block — evaluate against build-context facts
            // (branch, changed files) and the prior step outcome. Already validated at parse time,
            // so this cannot fail on bad shape; only the legacy CEL string path can throw.
            skipByWhen = whenGate().isSkipped(step, nodes);
          } else {
            skipByWhen = WhenEvaluator.isSkipped(step.getWhen(), ctx);
          }
        } catch (RuntimeException e) {
          // A malformed step `when:` is a configuration failure on the controller — surface it
          // through the same failed-at-dispatch path credentials / payload errors take.
          flowNodes.updateFailure(
              buildId,
              step.getId(),
              "CONFIG",
              "Step '"
                  + step.getId()
                  + "' has an invalid `when:` expression: "
                  + describe(e)
                  + " — fix the expression in your pipeline.");
          flowNodes.compareAndSetStatus(
              buildId, step.getId(), "PENDING", "FAILED", null, Instant.now(), null, null);
          stageFailed = true;
          break;
        }
        if (skipByWhen) {
          if (flowNodes.compareAndSetStatus(
                  buildId, step.getId(), "PENDING", "SKIPPED", null, Instant.now(), null, null)
              == 1) {
            LOGGER.log(
                Level.FINE,
                "[titan] build {0}: step {1} -> SKIPPED (when: false)",
                new Object[] {buildId, step.getId()});
          }
          // A skipped step does not block the stage — let the loop advance to the next step.
          stageBlocked = false;
          continue;
        }
        // Defense-in-depth (GH #805): a control-plane descriptor MUST be handled by an explicit
        // branch above (sleep/waitUntil/approval/setBuildName today). Reaching the worker-dispatch
        // branch with such a descriptor means a future resolver was added to ControlPlaneSteps.IDS
        // without a matching handler — fail the build CONFIG instead of leaving it QUEUED forever.
        if (ControlPlaneSteps.isControlPlane(step.getDescriptorId())) {
          String msg =
              "Internal error: control-plane step '"
                  + step.getDescriptorId()
                  + "' reached worker dispatch with no controller-side handler. "
                  + "Register a handler branch in TitanOrchestrator#advanceSteps.";
          flowNodes.updateFailure(buildId, step.getId(), "CONFIG", msg);
          flowNodes.compareAndSetStatus(
              buildId, step.getId(), "PENDING", "FAILED", null, Instant.now(), null, null);
          stageFailed = true;
          break;
        }
        if (flowNodes.compareAndSetStatus(
                buildId, step.getId(), "PENDING", "QUEUED", Instant.now(), null, null, null)
            == 1) {
          stepDispatcher.enqueueStep(stage, step, model, ctx);
          timeoutEnforcer.armTimeout(step);
          dispatched++;
        }
      } else if ("QUEUED".equals(status) && !hasExecuteTask(tasks, step.getId())) {
        // a dispatch was lost (crash between CAS and enqueue) — self-heal.
        // Defense-in-depth: don't re-dispatch a control-plane step that was wrongly QUEUED.
        if (ControlPlaneSteps.isControlPlane(step.getDescriptorId())) {
          String msg =
              "Internal error: control-plane step '"
                  + step.getDescriptorId()
                  + "' was QUEUED but has no worker handler.";
          flowNodes.updateFailure(buildId, step.getId(), "CONFIG", msg);
          flowNodes.compareAndSetStatus(
              buildId, step.getId(), "QUEUED", "FAILED", null, Instant.now(), null, null);
          stageFailed = true;
          break;
        }
        stepDispatcher.enqueueStep(stage, step, model, ctx);
        timeoutEnforcer.armTimeout(step);
        dispatched++;
      }
      break;
    }

    if (stageFailed) {
      if (cas(flowNodes, stage.getId(), "RUNNING", "FAILED", true) == 1) {
        fireStageHooks(stage, "FAILED");
        // design/62 §3, #662: sweep any k8sApply-stamped resources. Runs even on stage failure
        // — that is the whole point — and the service short-circuits when no step in the stage
        // was k8sApply so the kubectl shell-out is paid for only when needed.
        stageTeardown.teardownIfNeeded(buildId, stage);
      }
    } else if (!stageBlocked) {
      if (cas(flowNodes, stage.getId(), "RUNNING", "SUCCESS", true) == 1) {
        fireStageHooks(stage, "SUCCESS");
        stageTeardown.teardownIfNeeded(buildId, stage);
      }
    }
    return dispatched;
  }

  /**
   * Fire stage-level {@code notify:} hooks at the stage's terminal transition (#359). Mirror of the
   * pipeline-level firing in {@link #finishIfDone}: best-effort, fire-and-forget — any failure to
   * deliver MUST NOT abort the orchestrator's terminal-write path. The dispatcher itself swallows
   * delivery errors; this guard catches anything escaping it (e.g. NPE in payload assembly).
   */
  private void fireStageHooks(@NonNull StageModel stage, @NonNull String result) {
    if (stage.getNotify().isEmpty()) {
      return;
    }
    try {
      notifications.fireStageHooks(buildId, stage.getName(), result, stage.getNotify());
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] build {0}: stage notify hook dispatch threw (swallowed) for stage ''{1}''",
          new Object[] {buildId, stage.getName(), e});
    }
  }

  // ── 3. failure propagation + build completion ─────────────────────────

  private AdvanceResult finishIfDone(
      @NonNull FlowNodeDao flowNodes,
      @NonNull PipelineModel model,
      int dispatched,
      int reconciled) {
    Map<String, FlowNodeRow> nodes = PipelineNodes.byId(flowNodes.listByBuild(buildId));
    boolean anyFailed = nodes.values().stream().anyMatch(n -> "FAILED".equals(n.status));

    if (anyFailed
        && model.getFailurePolicy() == io.adaptiq.titan.flow.model.FailurePolicy.BLOCK_ON_FAILURE) {
      // Issue #945: blockOnFailure must skip ONLY the descendants of a failed node — not its
      // independent siblings. We compute the ancestor closure once per pass and SKIP a non-terminal
      // node iff some ancestor in its OWN closure has failed (GHA / GitLab CI / Buildkite).
      AncestorClosure ancestors = AncestorClosure.of(model);
      Set<String> tainted = new HashSet<>();
      for (FlowNodeRow n : nodes.values()) {
        if ("FAILED".equals(n.status)) {
          tainted.add(n.nodeId);
        }
      }
      Instant now = Instant.now();
      // #1213: a `fail_fast` matrix/each group ABORTs its active siblings on first cell failure,
      // tainting each so the sweep below SKIPs its descendant steps. See MatrixCoordinator.
      for (String siblingId : MatrixCoordinator.failFastAbortSet(model, nodes)) {
        flowNodes.compareAndSetStatus(
            buildId, siblingId, nodes.get(siblingId).status, "ABORTED", null, now, null, null);
        tainted.add(siblingId);
      }
      for (FlowNodeRow n : nodes.values()) {
        if (NODE_TERMINAL.contains(n.status)) {
          continue;
        }
        // SKIP this node iff any node in its transitive ancestor closure is FAILED. A node whose
        // ancestor chain is clean stays in its current state and the normal advance loop walks
        // it on the next tick.
        for (String a : ancestors.ancestorsOf(n.nodeId)) {
          if (tainted.contains(a)) {
            flowNodes.compareAndSetStatus(
                buildId, n.nodeId, n.status, "SKIPPED", null, now, null, null);
            break;
          }
        }
      }
    }

    boolean finished = flowNodes.countNonTerminal(buildId) == 0;
    // A build is "parked" if any of its SLEEPING nodes is an approval step awaiting a human
    // decision (a PENDING row in titan.approvals). The orchestrator must NOT be re-ticked on a
    // fixed cadence while parked — resume is event-driven (ApprovalService.decide /
    // sweepTimedOut each enqueue a fresh ADVANCE). Durable-wait `sleep:` nodes are NOT a park
    // reason: their resume is timer-driven, but the QueueProcessor's poll cadence is still the
    // safety net that catches wake_at slipping past; preserve that behaviour by only treating
    // approval-style parks as "stop ticking".
    boolean parked =
        !finished && ApprovalParkDetector.hasPendingApprovalPark(daos, buildId, nodes.values());
    String result = null;
    if (finished) {
      result = anyFailed ? "FAILED" : "SUCCESS";
      // Terminal write — delegated to BuildCloser (design 67 step 5). The collaborator owns the
      // terminal status update, duration compute, PipelineModelCache invalidation, the CDI
      // BuildStateChangedEvent emit (#835 follow-up) and the declarative `notify:` hook fan-out
      // (#245). Idempotent: a build whose row is already terminal is a no-op there.
      buildCloser.close(buildId, result, model);
    }
    return new AdvanceResult(dispatched, reconciled, finished, result, parked);
  }

  // ── helpers ───────────────────────────────────────────────────────────

  /** The structured-{@code when:} dispatch gate (GH #1093), created on first use. */
  @NonNull
  private StructuredWhenGate whenGate() {
    StructuredWhenGate gate = whenGate;
    if (gate == null) {
      gate = new StructuredWhenGate(daos, buildId);
      whenGate = gate;
    }
    return gate;
  }

  /** A short, human-readable description of an exception — its message, or its type if none. */
  @NonNull
  private static String describe(@NonNull Throwable t) {
    String msg = t.getMessage();
    return msg != null && !msg.isBlank() ? msg : t.getClass().getSimpleName();
  }

  /**
   * Emit one observation onto {@code titan_step_duration_seconds{job, step, status}} for a step
   * node we just CAS'd into a terminal state (issue #1081). Looks the row up <em>after</em> the CAS
   * so the {@code completedAt} the histogram observes is the one the CAS just wrote, not a stale
   * pre-transition snapshot. Best-effort: every failure mode (DAO blow-up, registry blow-up) is
   * folded silently by {@link StepMetrics#recordStepDuration} — the metric is observability, not
   * correctness.
   */
  private void emitStepDurationMetric(
      @NonNull FlowNodeDao flowNodes, @NonNull String nodeId, @NonNull String status) {
    try {
      FlowNodeRow row = flowNodes.findByBuildAndNode(buildId, nodeId).orElse(null);
      if (row == null) {
        return;
      }
      Duration dur =
          (row.startedAt != null && row.completedAt != null)
              ? Duration.between(row.startedAt, row.completedAt)
              : Duration.ZERO;
      String stepLabel = row.displayName != null ? row.displayName : nodeId;
      StepMetrics.recordStepDuration(resolveJobName(), stepLabel, status, dur);
    } catch (RuntimeException e) {
      LOGGER.log(Level.FINE, "[titan] step-duration metric emit failed: {0}", e.getMessage());
    }
  }

  /**
   * Look up the job's {@code full_name} once per orchestrator instance for the {@code job} label.
   * Cached even on failure — a single DAO miss won't be retried per-step. Null is returned when
   * unresolved; {@link StepMetrics} substitutes {@code "unknown"}.
   */
  @Nullable
  private String resolveJobName() {
    if (jobNameResolved) {
      return cachedJobName;
    }
    try {
      cachedJobName =
          daos.builds()
              .findById(buildId)
              .flatMap(b -> daos.jobs().findById(b.jobId))
              .map(j -> j.fullName)
              .orElse(null);
    } catch (RuntimeException e) {
      cachedJobName = null;
    }
    jobNameResolved = true;
    return cachedJobName;
  }

  /** Compare-and-set a node's status; returns rows affected (1 if this caller won, else 0). */
  private int cas(
      @NonNull FlowNodeDao flowNodes,
      @NonNull String nodeId,
      @NonNull String from,
      @NonNull String to,
      boolean terminal) {
    Instant now = Instant.now();
    return flowNodes.compareAndSetStatus(
        buildId, nodeId, from, to, terminal ? null : now, terminal ? now : null, null, null);
  }

  /**
   * True once every {@code dependsOn} node is {@code SUCCESS} or {@code SKIPPED}. Reads each
   * dependency fresh from the DB — an earlier stage in the same {@link #advance()} pass may have
   * just transitioned, and a stale in-memory snapshot would wrongly hold this stage back.
   */
  /**
   * Outcome of an {@code onFailure:} gate evaluation (design/68 / #947): {@link #FIRE} once any
   * listed upstream has reached {@code FAILED}, {@link #SKIP} once every listed upstream is
   * terminal but none failed, {@link #WAIT} while at least one listed upstream is still
   * non-terminal.
   */
  private enum OnFailureGate {
    FIRE,
    SKIP,
    WAIT
  }

  /**
   * Evaluate a failure-handler stage's gate (design/68 D2/D3 / #947).
   *
   * <p>Fires (FIRE) iff at least one listed upstream is currently {@code FAILED} — meaning the
   * upstream exhausted its retry policy and folded terminal (a transient retry failure flips the
   * step QUEUED → re-dispatched, not the stage to FAILED, so transient flakes don't trip the
   * handler). Returns SKIP iff every listed upstream is terminal but none is {@code FAILED} (any
   * mix of {@code SUCCESS}, {@code SKIPPED}, {@code ABORTED}, {@code CANCELLED} — only {@code
   * FAILED} fires the handler; criterion #4 / criterion adversarial-CANCELLED). Returns WAIT
   * otherwise — at least one listed upstream is still running / pending / queued / sleeping.
   *
   * <p>Reads each upstream fresh from the DB (mirroring {@link #depsSatisfied}) so a transition
   * earlier in the same {@code advance()} pass is observed.
   */
  @NonNull
  private OnFailureGate evaluateOnFailureGate(
      @NonNull StageModel stage,
      @NonNull Map<String, String> nameToId,
      @NonNull FlowNodeDao flowNodes) {
    boolean anyFailed = false;
    for (String upstreamName : stage.getOnFailureStages()) {
      String upstreamId = nameToId.get(upstreamName);
      if (upstreamId == null) {
        // Parser + PrototypeExpansion already validated targets resolve; treat an unknown
        // ref defensively as "still pending" rather than tripping the handler.
        return OnFailureGate.WAIT;
      }
      FlowNodeRow up = flowNodes.findByBuildAndNode(buildId, upstreamId).orElse(null);
      if (up == null || !NODE_TERMINAL.contains(up.status)) {
        return OnFailureGate.WAIT;
      }
      if ("FAILED".equals(up.status)) {
        anyFailed = true;
      }
    }
    return anyFailed ? OnFailureGate.FIRE : OnFailureGate.SKIP;
  }

  private boolean depsSatisfied(
      @NonNull List<String> dependsOnNames,
      @NonNull Map<String, String> nameToId,
      @NonNull FlowNodeDao flowNodes) {
    for (String name : dependsOnNames) {
      FlowNodeRow dep = flowNodes.findByBuildAndNode(buildId, nameToId.get(name)).orElse(null);
      if (dep == null || !DEP_SATISFIED.contains(dep.status)) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasExecuteTask(@NonNull List<TaskQueueRow> tasks, @NonNull String nodeId) {
    return tasks.stream()
        .anyMatch(t -> "EXECUTE_COMMAND".equals(t.type) && nodeId.equals(t.nodeId));
  }

  private static boolean exitCodeOk(@Nullable String resultJson) {
    if (resultJson == null || resultJson.isBlank()) {
      return true; // a COMPLETED task with no result is treated as success
    }
    try {
      JsonNode node = JSON.readTree(resultJson);
      return !node.has("exitCode") || node.get("exitCode").asInt() == 0;
    } catch (Exception e) {
      return true;
    }
  }

  // ── approval: parked-step gate (#715) ─────────────────────────────────────

  /**
   * Resume a SLEEPING approval step if its DB row is terminal. APPROVED → node SUCCESS, REJECTED /
   * TIMED_OUT → node FAILED. PENDING (no decision yet, timer not fired) leaves the node parked.
   */
  private void resumeApprovalIfDecided(@NonNull FlowNodeDao flowNodes, @NonNull FlowNodeRow node) {
    ApprovalRow row = daos.approvals().findLatestForNode(buildId, node.nodeId).orElse(null);
    if (row == null || "PENDING".equals(row.status)) {
      return;
    }
    String to;
    String category = null;
    String reason = null;
    switch (row.status) {
      case "APPROVED" -> to = "SUCCESS";
      case "REJECTED" -> {
        to = "FAILED";
        category = "APPROVAL";
        reason = "approval rejected by " + row.decidedBy;
      }
      case "TIMED_OUT" -> {
        to = "FAILED";
        category = "APPROVAL";
        reason = "auto-rejected: approval timeout";
      }
      default -> {
        LOGGER.log(
            Level.WARNING,
            "[titan] approval row {0} has unknown status {1} — leaving node parked",
            new Object[] {row.id, row.status});
        return;
      }
    }
    if (category != null) {
      flowNodes.updateFailure(buildId, node.nodeId, category, reason);
    }
    String resultJson =
        "{\"approval\":{\"id\":"
            + row.id
            + ",\"status\":\""
            + row.status
            + "\",\"decidedBy\":\""
            + (row.decidedBy == null ? "" : row.decidedBy)
            + "\"}}";
    if (flowNodes.compareAndSetStatus(
            buildId, node.nodeId, "SLEEPING", to, null, Instant.now(), null, resultJson)
        == 1) {
      // Cancel the timeout timer — irrelevant once the node is terminal.
      timerService.cancel(TimerService.Kind.GATE_RESUME, buildId, node.nodeId);
      LOGGER.log(
          Level.INFO,
          "[titan] build {0}: step {1} -> {2} (approval {3} {4})",
          new Object[] {buildId, node.nodeId, to, row.id, row.status});
    }
  }
}

package io.adaptiq.titan.build;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.api.ApiBadRequestException;
import io.adaptiq.titan.api.ApiNotFoundException;
import io.adaptiq.titan.flow.BuildAbortService;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.observability.TitanMetrics;
import io.adaptiq.titan.store.TaskQueueDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.AuditLogRow;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import io.quarkus.cache.CacheInvalidate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JDBI-backed {@link BuildService}. Translates between the persistent {@link BuildRow} POJO and the
 * domain {@link Build} record so callers never see the storage layer's mutable row type.
 *
 * <p>Constructor-injection only: {@link TitanStores} is produced by {@link
 * io.adaptiq.titan.boot.StoresProducer}; this class is wired as an {@code @ApplicationScoped} CDI
 * bean.
 */
@ApplicationScoped
public class BuildServiceImpl implements BuildService {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  /**
   * Terminal flow-node statuses — the only statuses from which a replay-from-node is allowed (issue
   * #307). A still-RUNNING/QUEUED/PENDING/SLEEPING node has not finished, so its outcome is not yet
   * defined and forking from it would race the orchestrator.
   */
  private static final Set<String> NODE_TERMINAL =
      Set.of("SUCCESS", "FAILED", "ABORTED", "SKIPPED");

  /**
   * Canonical terminal build statuses observable on {@code titan_builds_total{status}} and {@code
   * titan_build_duration_seconds{status}} (#649). Any value outside this set is bucketed as {@code
   * unknown} on the meter — keeps Prometheus label cardinality bounded.
   */
  private static final Set<String> TERMINAL_BUILD_STATUSES =
      Set.of("SUCCESS", "FAILED", "ABORTED", "CANCELLED", "UNSTABLE");

  private static final Logger LOGGER = Logger.getLogger(BuildServiceImpl.class.getName());

  private final TitanStores stores;
  private final Instance<TitanMetrics> metrics;
  private final Instance<Event<BuildStateChangedEvent>> stateChangedEvent;

  @Inject
  public BuildServiceImpl(
      TitanStores stores,
      Instance<TitanMetrics> metrics,
      Instance<Event<BuildStateChangedEvent>> stateChangedEvent) {
    this.stores = stores;
    this.metrics = metrics;
    this.stateChangedEvent = stateChangedEvent;
  }

  /**
   * Back-compat constructor for unit tests built before Prometheus instrumentation (#649). Skips
   * meter emission — production wiring always goes through the CDI three-arg form above.
   */
  public BuildServiceImpl(TitanStores stores) {
    this(stores, null, null);
  }

  /** Back-compat constructor for tests that wire metrics but not the state-change event bus. */
  public BuildServiceImpl(TitanStores stores, Instance<TitanMetrics> metrics) {
    this(stores, metrics, null);
  }

  // ── reads ───────────────────────────────────────────────────────────────────

  @Override
  @NonNull
  public Optional<Build> findById(long id) {
    return stores.builds().findById(id).map(BuildServiceImpl::toDomain);
  }

  @Override
  @NonNull
  public Optional<Build> findByJobIdAndNumber(long jobId, int buildNumber) {
    return stores.builds().findByJobAndNumber(jobId, buildNumber).map(BuildServiceImpl::toDomain);
  }

  @Override
  @NonNull
  public List<Build> listByJobId(long jobId) {
    return stores.builds().listByJob(jobId).stream().map(BuildServiceImpl::toDomain).toList();
  }

  @Override
  @NonNull
  public List<Build> listActive() {
    // listActive() returns ActiveBuildRow (a join projection); for a domain Build view we
    // re-resolve via QUEUED + RUNNING listings on the build store. Two queries, both indexed.
    List<Build> queued =
        stores.builds().listByStatus("QUEUED").stream().map(BuildServiceImpl::toDomain).toList();
    List<Build> running =
        stores.builds().listByStatus("RUNNING").stream().map(BuildServiceImpl::toDomain).toList();
    // RUNNING-before-QUEUED matches BuildDao.listActive ordering.
    return java.util.stream.Stream.concat(running.stream(), queued.stream()).toList();
  }

  @Override
  public int countActiveByJobId(long jobId) {
    return stores.builds().countNonTerminalBuilds(jobId);
  }

  // ── writes ──────────────────────────────────────────────────────────────────

  @Override
  @NonNull
  public Build create(@NonNull NewBuildRequest request) {
    long id =
        stores.withTransaction(
            conn -> {
              int buildNumber = stores.builds().nextBuildNumber(conn, request.jobId());
              BuildRow row = new BuildRow();
              row.jobId = request.jobId();
              row.buildNumber = buildNumber;
              row.status = "QUEUED";
              row.parametersJson = request.parametersJson();
              row.triggeredBy = request.triggeredBy();
              row.triggerType = request.triggerType();
              row.deploymentId = request.deploymentId();
              row.pipelineModelJson = request.pipelineModelJson();
              row.startedByInstance = request.startedByInstance();
              row.queuedAt = Instant.now();
              row.replayedFromBuildId = request.replayedFromBuildId();
              row.replayedFromNodeId = request.replayedFromNodeId();
              return stores.builds().insert(conn, row);
            });
    return stores
        .builds()
        .findById(id)
        .map(BuildServiceImpl::toDomain)
        .orElseThrow(() -> new IllegalStateException("inserted build " + id + " not retrievable"));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Cache: invalidates {@code pipeline-model} for {@code id} — a status / timing rewrite is the
   * canonical terminal transition path through this service and must drop the parsed model.
   */
  @Override
  @NonNull
  @CacheInvalidate(cacheName = "pipeline-model")
  public Build update(@io.quarkus.cache.CacheKey long id, @NonNull BuildUpdate update) {
    // Mirrors the JobService pattern: existence check, then mutate, then re-read.
    stores.builds().findById(id).orElseThrow(() -> new BuildNotFoundException(id));
    stores
        .builds()
        .updateStatus(
            id,
            update.status(),
            update.startedAt(),
            update.finishedAt(),
            update.durationMs(),
            update.errorMessage());
    Build fresh =
        stores
            .builds()
            .findById(id)
            .map(BuildServiceImpl::toDomain)
            .orElseThrow(() -> new BuildNotFoundException(id));
    // Prometheus emission (#649): on terminal transition, increment titan_builds_total{status}
    // and observe titan_build_duration_seconds. Best-effort — never fails the write.
    if (update.status() != null && TERMINAL_BUILD_STATUSES.contains(update.status())) {
      try {
        if (metrics != null && !metrics.isUnsatisfied()) {
          long durMs = update.durationMs() != null ? update.durationMs() : 0L;
          metrics.get().recordBuildTerminal(update.status(), durMs);
        }
      } catch (RuntimeException me) {
        LOGGER.log(Level.FINE, "[titan] build terminal meter emit failed: {0}", me.getMessage());
      }
    }
    // Build-state-change event (#835): observers (e.g. GithubStatusReporter) post commit
    // statuses back to the originating SCM. Best-effort — never fails the write. CDI's default
    // observer dispatch is synchronous; a slow observer would block this thread, so observers
    // MUST keep their I/O bounded (the GitHub reporter caps every call with a request timeout).
    if (update.status() != null
        && stateChangedEvent != null
        && !stateChangedEvent.isUnsatisfied()) {
      try {
        BuildStateChangedEvent evt =
            new BuildStateChangedEvent(
                fresh.id(),
                update.status(),
                fresh.triggerType(),
                fresh.triggerMetaJson(),
                fresh.jobId(),
                fresh.buildNumber());
        stateChangedEvent.get().fire(evt);
      } catch (RuntimeException ee) {
        LOGGER.log(
            Level.FINE, "[titan] build state-change event dispatch failed: {0}", ee.getMessage());
      }
    }
    return fresh;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Cache: invalidates {@code pipeline-model} for {@code id} — abort terminalises the build.
   */
  @Override
  @CacheInvalidate(cacheName = "pipeline-model")
  public void abort(@io.quarkus.cache.CacheKey long id, @NonNull String actor) {
    // Pass the state-change event channel through so SCM status reporters (#1080) observe the
    // cancellation. The channel may be null in legacy unit-test wiring; BuildAbortService treats
    // null as "no fan-out", preserving back-compat.
    Event<BuildStateChangedEvent> channel =
        (stateChangedEvent != null && !stateChangedEvent.isUnsatisfied())
            ? stateChangedEvent.get()
            : null;
    BuildAbortService.abort(stores, id, actor, channel);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Cache: invalidates {@code pipeline-model} for {@code id} — the row (and any parsed model) is
   * gone after this call.
   */
  @Override
  @CacheInvalidate(cacheName = "pipeline-model")
  public void delete(long id) {
    stores.builds().delete(id);
  }

  // ── mapping ─────────────────────────────────────────────────────────────────

  /** Translate a storage row to the domain record. */
  @NonNull
  static Build toDomain(@NonNull BuildRow row) {
    return new Build(
        row.id,
        row.jobId,
        row.buildNumber,
        row.status,
        row.parametersJson,
        row.triggeredBy,
        row.triggerType,
        row.deploymentId,
        row.queuedAt,
        row.startedAt,
        row.finishedAt,
        row.durationMs,
        row.errorMessage,
        row.pipelineModelJson,
        row.startedByInstance,
        row.failureSummary,
        row.replayedFromBuildId,
        row.replayedFromNodeId,
        row.triggerMetaJson,
        row.displayName,
        row.failureCause,
        row.failureCauseDetail,
        row.pipelineScript);
  }

  // ── Replay-from-node (issue #307) ──────────────────────────────────────────

  @Override
  @NonNull
  public Build replay(long parentBuildId, @NonNull String nodeId, @NonNull ReplayOptions options) {
    // 1) Parent must exist.
    BuildRow parent =
        stores
            .builds()
            .findById(parentBuildId)
            .orElseThrow(() -> new ApiNotFoundException("build " + parentBuildId + " not found"));

    // 2) Parent must have been baked — we reuse its pipeline_model_json on the new build, so the
    //    replay never re-fetches from SCM nor re-parses (hard-rule: idempotent, deterministic).
    if (parent.pipelineModelJson == null || parent.pipelineModelJson.isBlank()) {
      throw new ApiBadRequestException(
          "cannot replay build "
              + parentBuildId
              + ": it has no synthesised pipeline model "
              + "(it never reached the BAKE phase)");
    }

    // 3) The node must exist in the parent's pipeline model. STEPs are valid replay anchors too —
    //    the UI's per-step "Replay from here" button submits a step id, not just a stage id
    //    (issue #621). containsReplayAnchor covers stages + gates + preconditions + steps;
    //    getAllNodes() is the narrower DAG-only view consumed by the validator and orchestrator.
    PipelineModel parentModel = deserialiseModel(parent.pipelineModelJson);
    if (!parentModel.containsReplayAnchor(nodeId)) {
      throw new ApiBadRequestException(
          "node '" + nodeId + "' does not exist in build " + parentBuildId);
    }

    // 4) The node must be terminal in the parent's DAG — replaying from a still-running node is
    //    a race against the orchestrator and the upstream outcome is undefined.
    String parentNodeStatus =
        stores
            .flowNodes()
            .findByBuildAndNode(parentBuildId, nodeId)
            .orElseThrow(
                () ->
                    new ApiBadRequestException(
                        "node '" + nodeId + "' is not materialised in build " + parentBuildId))
            .status;
    if (!NODE_TERMINAL.contains(parentNodeStatus)) {
      throw new ApiBadRequestException(
          "node '"
              + nodeId
              + "' is "
              + parentNodeStatus
              + " — replay-from-node requires a terminal node "
              + "(SUCCESS / FAILED / SKIPPED / ABORTED)");
    }

    // 5) Compose the new build's parameters: parent's effective params + caller's overrides.
    String mergedParamsJson = mergeParams(parent.parametersJson, options.paramOverrides());

    // 6) Materialise the new build row + enqueue REPLAY_FROM_NODE in one transaction.
    long newBuildId =
        stores.withTransaction(
            conn -> {
              int buildNumber = stores.builds().nextBuildNumber(conn, parent.jobId);

              BuildRow row = new BuildRow();
              row.jobId = parent.jobId;
              row.buildNumber = buildNumber;
              row.status = "QUEUED";
              row.parametersJson = mergedParamsJson;
              row.triggeredBy = "api";
              row.triggerType = "replay";
              // The new build carries the parent's already-synthesised model — REPLAY_FROM_NODE
              // bakes from this, no re-parse, no SCM round-trip.
              row.pipelineModelJson = parent.pipelineModelJson;
              row.queuedAt = Instant.now();
              row.replayedFromBuildId = parentBuildId;
              row.replayedFromNodeId = nodeId;

              long id = stores.builds().insert(conn, row);

              TaskQueueRow task = new TaskQueueRow();
              task.type = "ORCHESTRATE";
              task.queueName = "default";
              task.status = "QUEUED";
              task.priority = 5;
              // The replay handler reads buildId + replayFromNodeId off the build row itself, so
              // the payload is intentionally compact — replay metadata lives in titan.builds.
              task.payloadJson = "{\"action\":\"REPLAY_FROM_NODE\",\"buildId\":" + id + "}";
              task.attempts = 0;
              task.maxAttempts = 3;
              task.visibilityTimeoutSeconds = 300;
              task.buildId = id;
              task.availableAt = Instant.now();
              TitanStores.onConnection(conn, TaskQueueDao.class, dao -> dao.insert(task));

              return id;
            });

    return stores
        .builds()
        .findById(newBuildId)
        .map(BuildServiceImpl::toDomain)
        .orElseThrow(
            () -> new IllegalStateException("replay: inserted build " + newBuildId + " vanished"));
  }

  @Override
  @NonNull
  public Build replayFromFirstFailed(long parentBuildId, @NonNull ReplayOptions options) {
    // 1) Parent must exist — same 404 surface as replay().
    BuildRow parent =
        stores
            .builds()
            .findById(parentBuildId)
            .orElseThrow(() -> new ApiNotFoundException("build " + parentBuildId + " not found"));

    // 2) Parent must have been baked — required so we can walk the stage list.
    if (parent.pipelineModelJson == null || parent.pipelineModelJson.isBlank()) {
      throw new ApiBadRequestException(
          "cannot replay build "
              + parentBuildId
              + ": it has no synthesised pipeline model "
              + "(it never reached the BAKE phase)");
    }
    PipelineModel parentModel = deserialiseModel(parent.pipelineModelJson);

    // 3) Walk stages in declared YAML order — the most predictable mental model for the SRE
    //    clicking the button ("the first failed stage I see top-to-bottom"). Pick the first
    //    one whose materialised flow_node ended FAILED.
    String firstFailedStageId = null;
    for (StageModel stage : parentModel.getStages()) {
      String stageNodeId = stage.getId();
      if (stageNodeId == null || stageNodeId.isBlank()) {
        continue;
      }
      var nodeRow = stores.flowNodes().findByBuildAndNode(parentBuildId, stageNodeId);
      if (nodeRow.isPresent() && "FAILED".equals(nodeRow.get().status)) {
        firstFailedStageId = stageNodeId;
        break;
      }
    }
    if (firstFailedStageId == null) {
      throw new ApiBadRequestException(
          "cannot replay-from-failed on build "
              + parentBuildId
              + ": no stage ended FAILED "
              + "(replay-from-failed requires at least one failed stage on the parent)");
    }

    // 4) Delegate to the existing replay() path — same orchestration, same idempotency, same
    //    "stages upstream of the failed one are materialised SKIPPED" semantics handled by the
    //    REPLAY_FROM_NODE orchestrator handler.
    return replay(parentBuildId, firstFailedStageId, options);
  }

  // ── Retry single failed stage in place (issue #744) ───────────────────────

  @Override
  @NonNull
  public RetryStageOutcome retryStage(
      long buildId, @NonNull String stageId, @NonNull String actor) {
    // 1) Build must exist.
    BuildRow build =
        stores
            .builds()
            .findById(buildId)
            .orElseThrow(() -> new ApiNotFoundException("build " + buildId + " not found"));

    // 2) Stage node must exist for that build.
    FlowNodeRow stageNode =
        stores
            .flowNodes()
            .findByBuildAndNode(buildId, stageId)
            .orElseThrow(
                () ->
                    new ApiNotFoundException(
                        "stage '" + stageId + "' not found in build " + buildId));

    // 3) Stage must be in FAILED state — fail-fast precondition (HTTP 409).
    if (!"FAILED".equals(stageNode.status)) {
      throw new RetryStagePreconditionException("stage is not in FAILED state");
    }

    // 4) Compute the descendants set in-memory from the DAG (parent_ids CSV on each row).
    //    We reset the stage itself + every transitive descendant: a stage's children steps and
    //    any downstream stages that depend on it (some of which the upstream FAILED prevented from
    //    running at all — they may be SKIPPED today; resetting to QUEUED lets advance() re-evaluate
    //    their gating). Nodes are matched by their declared parent_ids list (DAG, not tree).
    List<FlowNodeRow> allNodes = stores.flowNodes().listByBuild(buildId);
    List<String> resetIds = collectDescendants(stageId, allNodes);

    // 5) Atomic transition: reset descendants + flip build + audit + enqueue ADVANCE.
    Instant now = Instant.now();
    long taskId =
        stores.withTransaction(
            conn -> {
              // Reset stage + descendants: status → QUEUED and clear the previous attempt's
              // outcome (timing + result + failure stamps) via raw SQL so a single round-trip per
              // node touches every "previous attempt" column. updateStatus() uses COALESCE which
              // would refuse to overwrite started_at / completed_at with NULL.
              try (var ps =
                  conn.prepareStatement(
                      "UPDATE titan.flow_nodes SET status = 'QUEUED', "
                          + "started_at = NULL, completed_at = NULL, duration_ms = NULL, "
                          + "result_json = NULL, failure_category = NULL, failure_reason = NULL "
                          + "WHERE build_id = ? AND node_id = ?")) {
                for (String nodeId : resetIds) {
                  ps.setLong(1, buildId);
                  ps.setString(2, nodeId);
                  ps.addBatch();
                }
                ps.executeBatch();
              } catch (java.sql.SQLException sqlEx) {
                throw new IllegalStateException(
                    "retryStage: failed to reset descendants for build " + buildId, sqlEx);
              }
              // Compare-and-set the build row out of FAILED — guards against double-retry. Raw
              // SQL because BuildDao.updateStatus COALESCEs nulls; we need to actively NULL
              // finished_at / duration_ms / error_message so the build presents as in-flight again.
              try (var ps =
                  conn.prepareStatement(
                      "UPDATE titan.builds SET status = 'RUNNING', finished_at = NULL, "
                          + "duration_ms = NULL, error_message = NULL "
                          + "WHERE id = ? AND status = 'FAILED'")) {
                ps.setLong(1, buildId);
                ps.executeUpdate();
              } catch (java.sql.SQLException sqlEx) {
                throw new IllegalStateException(
                    "retryStage: failed to flip build " + buildId + " back to RUNNING", sqlEx);
              }
              long id =
                  TitanStores.onConnection(
                      conn,
                      TaskQueueDao.class,
                      dao ->
                          dao.enqueue(
                              "ORCHESTRATE",
                              "default",
                              5,
                              "{\"action\":\"ADVANCE\",\"buildId\":" + buildId + "}",
                              3,
                              300,
                              buildId,
                              null,
                              null));
              writeAudit(actor, buildId, stageId);
              return id;
            });

    LOGGER.log(
        Level.INFO,
        "[titan] stage retried: build={0} stage={1} actor={2} reset={3} task={4}",
        new Object[] {buildId, stageId, actor, resetIds.size(), taskId});

    return new RetryStageOutcome.Applied(buildId, stageId, resetIds, taskId);
  }

  /**
   * BFS from {@code rootStageId} through the {@code parent_ids} DAG. Returns the stage id followed
   * by every transitive descendant, de-duplicated, in stable insertion order so the response is
   * deterministic across runs.
   */
  @NonNull
  static List<String> collectDescendants(
      @NonNull String rootStageId, @NonNull List<FlowNodeRow> allNodes) {
    // Build a parent → children adjacency list once. parent_ids is CSV (see TitanFlowExecution
    // §parentIdsOf) — split on ',' and trim.
    Map<String, List<String>> children = new HashMap<>();
    for (FlowNodeRow n : allNodes) {
      if (n.parentIds == null || n.parentIds.isBlank()) {
        continue;
      }
      for (String parent : n.parentIds.split(",")) {
        String trimmed = parent.trim();
        if (!trimmed.isEmpty()) {
          children.computeIfAbsent(trimmed, k -> new ArrayList<>()).add(n.nodeId);
        }
      }
    }

    Set<String> seen = new LinkedHashSet<>();
    Deque<String> queue = new ArrayDeque<>();
    queue.add(rootStageId);
    while (!queue.isEmpty()) {
      String cur = queue.poll();
      if (!seen.add(cur)) {
        continue;
      }
      List<String> kids = children.get(cur);
      if (kids != null) {
        for (String k : kids) {
          if (!seen.contains(k)) {
            queue.add(k);
          }
        }
      }
    }
    return new ArrayList<>(seen);
  }

  private void writeAudit(@NonNull String actor, long buildId, @NonNull String stageId) {
    try {
      AuditLogRow row = new AuditLogRow();
      row.actor = actor;
      row.action = "STAGE_RETRIED";
      row.targetType = "BUILD";
      row.targetId = String.valueOf(buildId);
      // detailsJson contains no secrets — just the (build,stage) pair so the audit row is
      // searchable for "who retried which stage" without joining elsewhere.
      String safeStage = stageId.replace("\\", "\\\\").replace("\"", "\\\"");
      row.detailsJson = "{\"buildId\":" + buildId + ",\"stageId\":\"" + safeStage + "\"}";
      stores.auditLog().insert(row);
    } catch (RuntimeException e) {
      // Audit failure must never fail the retry — mirror of ApprovalService.audit().
      LOGGER.log(
          Level.WARNING, "[titan] failed to record STAGE_RETRIED audit for build " + buildId, e);
    }
  }

  /** Deserialise a parent build's stored pipeline model. */
  @NonNull
  private static PipelineModel deserialiseModel(@NonNull String modelJson) {
    try {
      return JSON.readValue(modelJson, PipelineModel.class);
    } catch (Exception e) {
      throw new IllegalStateException("replay: cannot deserialise parent pipeline model", e);
    }
  }

  /**
   * Merge the parent build's effective {@code parameters_json} with the caller's overrides.
   * Overrides win on a key collision (the API caller stamped a fresh value). Returns the JSON blob
   * to persist on the new build row; {@code null} if both sides are empty.
   */
  @edu.umd.cs.findbugs.annotations.Nullable
  private static String mergeParams(
      @edu.umd.cs.findbugs.annotations.Nullable String parentParamsJson,
      @edu.umd.cs.findbugs.annotations.Nullable Map<String, String> overrides) {
    Map<String, Object> merged = new HashMap<>();
    if (parentParamsJson != null && !parentParamsJson.isBlank()) {
      try {
        Map<String, Object> parent = JSON.readValue(parentParamsJson, MAP_TYPE);
        if (parent != null) {
          merged.putAll(parent);
        }
      } catch (JsonProcessingException e) {
        throw new ApiBadRequestException(
            "replay: parent build parameters_json is not a JSON object: " + e.getMessage());
      }
    }
    if (overrides != null && !overrides.isEmpty()) {
      merged.putAll(overrides);
    }
    if (merged.isEmpty()) {
      return null;
    }
    try {
      return JSON.writeValueAsString(merged);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("replay: failed to serialise merged params", e);
    }
  }
}

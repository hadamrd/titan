package io.adaptiq.titan.build;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Optional;

/**
 * Domain-facing API for Titan builds — CRUD over the {@code titan.builds} table. The Quarkus REST
 * layer in {@link io.adaptiq.titan.api.JobBuildsApi} / {@link io.adaptiq.titan.api.BuildDetailApi}
 * calls these methods directly. Errors at the storage layer surface as {@link
 * io.adaptiq.titan.db.TitanDataException}.
 *
 * <p>Lifecycle transitions (orchestration-side: pushing a {@code START_PIPELINE} task, the cancel
 * path that reaps live tasks + nodes) stay on the engine services ({@link
 * io.adaptiq.titan.flow.BuildAbortService}, the orchestrator); this service is the persistent-row
 * facade.
 */
public interface BuildService {

  @NonNull
  Optional<Build> findById(long id);

  @NonNull
  Optional<Build> findByJobIdAndNumber(long jobId, int buildNumber);

  /** Every build of a job, newest first ({@code queued_at DESC}). */
  @NonNull
  List<Build> listByJobId(long jobId);

  /** Every in-flight build ({@code QUEUED} or {@code RUNNING}) across all jobs, oldest first. */
  @NonNull
  List<Build> listActive();

  /**
   * Count of in-flight builds ({@code QUEUED} or {@code RUNNING}) for a single job — used by the
   * job-delete gate (issue #964) to refuse hard-delete while a build is still live. Returns 0 when
   * no builds exist for the job (including when the job does not exist).
   */
  int countActiveByJobId(long jobId);

  /**
   * Materialise a new build row. Allocates the next {@code build_number} for the job ({@code MAX +
   * 1}) atomically with the insert via a transaction. The new row starts at {@code QUEUED} with
   * {@code queued_at = now()}. Returns the persisted record.
   */
  @NonNull
  Build create(@NonNull NewBuildRequest request);

  /**
   * Apply lifecycle status + timing fields to an existing build row.
   *
   * @throws BuildNotFoundException if no row exists for {@code id}.
   */
  @NonNull
  Build update(long id, @NonNull BuildUpdate update);

  /**
   * Engine-side cancel — delegates to {@link io.adaptiq.titan.flow.BuildAbortService}: cancels live
   * tasks, drives non-terminal nodes to terminal, and writes the build row to {@code ABORTED}.
   * No-op when the build is already terminal or missing.
   *
   * @param actor the principal name to record on the cancel (audit / log)
   */
  void abort(long id, @NonNull String actor);

  /**
   * Remove a build row. Cascades to {@code task_queue} / {@code task_archive} / {@code flow_nodes}
   * / {@code artifact} via {@code ON DELETE CASCADE}. {@code titan.logs} and artifact-store blobs
   * are <em>not</em> reached by the cascade — callers that need a full reap must remove those
   * separately. No-op if absent.
   */
  void delete(long id);

  /**
   * Replay-from-node (issue #307): create a fresh build of the same job, baked from the
   * <em>parent</em> build's already-synthesised pipeline model, that re-runs from {@code nodeId}
   * onward. Every upstream node (per the DAG's topological order) is materialised as terminal
   * {@code SKIPPED} with a {@code reason: REPLAY} sentinel in its {@code result_json} so the
   * console renders the historical outcome instead of a re-run.
   *
   * <p>Validation surface (delivered as {@link io.adaptiq.titan.api.ApiNotFoundException} / {@link
   * io.adaptiq.titan.api.ApiBadRequestException} for the REST layer to map):
   *
   * <ul>
   *   <li>404 — parent build does not exist.
   *   <li>400 — parent build has no synthesised pipeline model (never reached BAKE).
   *   <li>400 — node id does not exist in the parent's pipeline model.
   *   <li>400 — node is not terminal in the parent's DAG (still RUNNING / QUEUED / etc.).
   * </ul>
   *
   * <p>The new build is enqueued as a {@code REPLAY_FROM_NODE} orchestration task — the
   * orchestrator handler is idempotent (a re-delivered task that finds the DAG already materialised
   * is a no-op).
   *
   * @param parentBuildId the build whose ancestry the replay forks from
   * @param nodeId the flow-node id in the parent's pipeline model to replay from
   * @param options merge-overrides for the parent's parameters (may be {@link
   *     ReplayOptions#none()})
   * @return the newly created replay build
   */
  @NonNull
  Build replay(long parentBuildId, @NonNull String nodeId, @NonNull ReplayOptions options);

  /**
   * Replay-from-first-failed-stage (issue #664): convenience wrapper that locates the first stage
   * (in declared YAML order) whose materialised flow node ended {@code FAILED}, then delegates to
   * {@link #replay(long, String, ReplayOptions)} with that stage's id. The intent: for a 20-min
   * pipeline where a late stage flaked, an SRE wants a single click that re-runs from the first
   * failure without having to open the DAG and pick the node by hand.
   *
   * <p>Strictly distinct from {@link #replay} on the wire — discriminated-union typed: this method
   * is a separate endpoint with its own clear name, not an overload of {@code /replay} with a
   * "fromStage" parameter. The orchestration that runs once the new build is created is identical
   * (the same {@code REPLAY_FROM_NODE} task is enqueued).
   *
   * <p>Validation surface (mapped to HTTP by the REST resource):
   *
   * <ul>
   *   <li>404 — parent build does not exist (no row in {@code titan.builds}).
   *   <li>400 — parent build has no synthesised pipeline model (never reached BAKE).
   *   <li>400 — parent build has no failed stages (it already succeeded, or every stage is still
   *       non-terminal, or every failure was on a non-stage node).
   * </ul>
   *
   * @param parentBuildId the build whose ancestry the replay forks from
   * @param options merge-overrides for the parent's parameters (may be {@link
   *     ReplayOptions#none()})
   * @return the newly created replay build
   */
  @NonNull
  Build replayFromFirstFailed(long parentBuildId, @NonNull ReplayOptions options);

  /**
   * Retry a single failed stage in place — #744. Unlike {@link #replay} which forks a fresh build,
   * this resets the named stage's flow node (and every DAG descendant) back to {@code QUEUED},
   * flips the build row from {@code FAILED} back to {@code RUNNING}, and enqueues an {@code
   * ORCHESTRATE/ADVANCE} so the orchestrator re-dispatches the work in the same build row.
   *
   * <p>Strict preconditions, fail-fast in declared order:
   *
   * <ol>
   *   <li>build exists — else {@link io.adaptiq.titan.api.ApiNotFoundException} (404)
   *   <li>flow_node {@code stageId} exists for that build — else 404
   *   <li>the node's status is exactly {@code FAILED} — else {@link
   *       RetryStagePreconditionException} (409)
   * </ol>
   *
   * <p>Idempotent: a double-call resolves the stage out of {@code FAILED} on the first call, so the
   * second call sees the new status and 409s.
   *
   * @param buildId the build that owns the stage
   * @param stageId the flow-node id of the failed stage
   * @param actor the OIDC subject (preferred_username or sub) — never a body-supplied identity
   * @return discriminated-union result; today only the {@code Applied} variant is returned on the
   *     success path (preconditions throw rather than return a {@code Rejected} variant — keeps the
   *     REST layer's error mapping in line with replay).
   */
  @NonNull
  RetryStageOutcome retryStage(long buildId, @NonNull String stageId, @NonNull String actor);
}

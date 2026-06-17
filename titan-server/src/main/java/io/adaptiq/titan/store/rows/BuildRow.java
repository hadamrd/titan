package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/** Mutable POJO mapping to a row in {@code rf_builds}. */
public class BuildRow {
  public long id;
  public long jobId;
  public int buildNumber;
  public String status;

  @Nullable public String parametersJson;

  @Nullable public String triggeredBy;

  @Nullable public String triggerType;

  @Nullable public Long deploymentId;

  public Instant queuedAt;

  @Nullable public Instant startedAt;

  @Nullable public Instant finishedAt;

  @Nullable public Long durationMs;

  @Nullable public String errorMessage;

  @Nullable public String pipelineModelJson;

  /**
   * Structured trigger metadata (issue #589) — webhook-derived facts about what kicked the build
   * off: {@code {"branch":"trunk","commitSha":"a3f9c12","actor":"kira.rai"}}. Null on manual /
   * dogfood / replay builds. Written once at insert time by the webhook receiver; never updated.
   *
   * <p>The shape is owned by {@code BuildDto.TriggerMetaDto} on the API side — this column is a
   * plain blob from the DAO's point of view. Hard cap: VARCHAR(8192).
   */
  @Nullable public String triggerMetaJson;

  @Nullable public String startedByInstance;

  /**
   * Why a build failed <em>before any node ran or failed</em> — a synthesis or bake failure
   * (design/45 §1). There is no flow node to attach the reason to, so it lives on the build. Null
   * on a build that succeeded, or that failed at a node (the reason is on the node then).
   */
  @Nullable public String failureSummary;

  /**
   * The parent build id this build was replayed from (issue #307). Null on a build that was
   * triggered fresh; non-null on a build born from {@code POST /api/v1/builds/{id}/replay}. FK to
   * {@code titan.builds(id)} with {@code ON DELETE SET NULL} — reaping the parent breaks the
   * ancestry link but does not cascade to the replay.
   */
  @Nullable public Long replayedFromBuildId;

  /**
   * The flow-node id within the parent build this replay was forked at (issue #307). Every node
   * <em>upstream</em> of this one carries its parent's outcome (SKIPPED w/ reason=REPLAY in
   * result_json); the node itself and everything downstream is re-run.
   */
  @Nullable public String replayedFromNodeId;

  /**
   * Wall-clock deadline for the entire pipeline (issue #244). Computed at BAKE from the
   * pipeline-root {@code timeout:} field — {@code deadlineAt = bakeTime + timeoutMillis}. {@code
   * null} when the pipeline declared no root-level timeout. The {@code QueueProcessor} reaps any
   * {@code RUNNING} build past its {@code deadlineAt} as {@code FAILED}.
   */
  @Nullable public java.time.Instant deadlineAt;

  /**
   * Human-friendly build name set by the {@code setBuildName:} pipeline step (#762). Last-write
   * wins across multiple steps in a single build; {@code null} when the pipeline never invokes the
   * step (the UI falls back to {@code #<buildNumber>}). Hard cap 200 chars enforced at resolve time
   * — over-length names FAIL the step rather than being silently truncated.
   */
  @Nullable public String displayName;

  /**
   * GitHub Check-Run id (#965) — populated when {@link
   * io.adaptiq.titan.scm.github.GithubCheckRunReporter} creates a check-run at build start for an
   * App-triggered build. {@code null} on manual / cron / replay builds, on builds whose job has no
   * GitHub-App linkage, and on builds where the create-check-run POST failed (we log + skip — the
   * matching PATCH at finish then no-ops).
   */
  @Nullable public Long externalCheckRunId;

  /**
   * Diagnosed root cause of a FAILED build (issue #1105) — one lowercase wire name from the closed
   * {@code FailureCause} set ({@code test_failure}, {@code compile_error}, {@code oom}, {@code
   * timeout}, {@code network}, {@code rate_limit}, {@code unknown}). Written best-effort and async
   * by {@code BuildFailureClassifier} after the build's terminal transition; {@code null} on a
   * build that succeeded or that has not yet been classified.
   */
  @Nullable public String failureCause;

  /**
   * The matching log snippet that drove the {@link #failureCause} classification (issue #1105) —
   * surfaced verbatim in the build-detail "why this was classified" tooltip. {@code null} for an
   * {@code unknown} verdict (no signature matched) and for un-classified builds.
   */
  @Nullable public String failureCauseDetail;
}

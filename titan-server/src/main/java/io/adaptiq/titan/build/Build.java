package io.adaptiq.titan.build;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Plain domain record for a single Titan build execution — the source of truth for a build's
 * persistent state.
 *
 * <p>Mirrors the persistent state of {@code titan.builds}. Code in {@code titan-server} flows
 * through this record, reading and writing {@code Build}s via {@link BuildService}.
 *
 * <p>{@code parametersJson} and {@code pipelineModelJson} carry blob-shaped payloads stored as-is.
 * {@code failureSummary} is the customer-facing reason a build failed before any flow node ran
 * (synthesis / bake) — see design/45 §1.
 *
 * <p>{@code replayedFromBuildId} / {@code replayedFromNodeId} are non-null on a build born from
 * {@code POST /api/v1/builds/{id}/replay} (issue #307) — the parent build's id and the flow-node id
 * within it the replay was forked at.
 */
public record Build(
    long id,
    long jobId,
    int buildNumber,
    String status,
    @Nullable String parametersJson,
    @Nullable String triggeredBy,
    @Nullable String triggerType,
    @Nullable Long deploymentId,
    Instant queuedAt,
    @Nullable Instant startedAt,
    @Nullable Instant finishedAt,
    @Nullable Long durationMs,
    @Nullable String errorMessage,
    @Nullable String pipelineModelJson,
    @Nullable String startedByInstance,
    @Nullable String failureSummary,
    @Nullable Long replayedFromBuildId,
    @Nullable String replayedFromNodeId,
    /**
     * Structured trigger metadata (issue #589) — webhook-derived {branch, commitSha, actor}.
     * JSON-encoded string; the API DTO parses it into a typed object. Null on manual / dogfood /
     * replay builds (the receiver is the only writer).
     */
    @Nullable String triggerMetaJson,
    /**
     * Optional human-friendly name set by the {@code setBuildName:} step (#762). Null when the
     * pipeline never invokes the step.
     */
    @Nullable String displayName,
    /**
     * Diagnosed root cause of a FAILED build (issue #1105) — a lowercase {@code FailureCause} wire
     * name. Null on success / not-yet-classified.
     */
    @Nullable String failureCause,
    /** The matching log snippet that drove {@link #failureCause} (issue #1105). */
    @Nullable String failureCauseDetail) {}

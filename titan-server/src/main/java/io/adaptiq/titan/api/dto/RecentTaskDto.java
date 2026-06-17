package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * One row in the "Recent activity" feed returned by {@code GET /api/v1/queue/recent} (issue #523).
 *
 * <p>Shape mirrors {@link QueueEntryDto} + {@code status} + {@code completedAt} + {@code nodeId} so
 * the UI can render the same build/job columns it already renders for the live queue, plus a status
 * pill and a "when did this finish" timestamp. Rows are sourced from {@code task_archive}; a
 * terminal {@code task_queue} row is moved to the archive by the per-tick sweep within seconds of
 * completion.
 *
 * <p>{@code @JsonInclude(NON_NULL)} keeps the wire format compact when the orchestration-class
 * tasks (no build link) come through.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecentTaskDto(
    long taskId,
    @Nullable Long buildId,
    @Nullable Long jobId,
    @Nullable String jobName,
    @Nullable String nodeId,
    String type,
    String status,
    Instant queuedAt,
    @Nullable Instant completedAt,
    int priority,
    String requestedLabels) {}

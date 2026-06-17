package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Single row in the queue-feed payload returned by {@code GET /api/v1/queue} (UI §5.2). One entry
 * per currently-claimable {@code QUEUED} task in {@code titan.task_queue}.
 *
 * <ul>
 *   <li>{@code buildId} / {@code jobId} / {@code jobName} — the build the task drives; nullable
 *       since a small number of orchestration tasks (synthesis on the {@code synthesis} queue)
 *       carry no build link.
 *   <li>{@code queuedAt} — {@code task_queue.created_at}.
 *   <li>{@code waitingMs} — server-computed: {@code now() - createdAt}, in millis.
 *   <li>{@code priority} — claim-order priority.
 *   <li>{@code requestedLabels} — proxied from the task's {@code queue_name} (Titan does not yet
 *       expose a separate label set; the queue name is the routing target).
 * </ul>
 *
 * <p>{@code @JsonInclude(NON_NULL)} so null build links do not pollute the wire format.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueueEntryDto(
    long taskId,
    @Nullable Long buildId,
    @Nullable Long jobId,
    @Nullable String jobName,
    Instant queuedAt,
    long waitingMs,
    int priority,
    String requestedLabels) {}

package io.adaptiq.titan.api.dto;

import java.util.List;

/**
 * Request body for {@code POST /api/v1/queue/reorder} (admin queue controls, #347).
 *
 * <p>{@code taskIds} is the new priority order, head-first: the first id becomes the
 * highest-priority {@code QUEUED} task, the last id the lowest. The server assigns descending
 * priorities to enforce the order — the worker claim order ({@code priority DESC, created_at ASC})
 * does the rest.
 *
 * <p>Ids must reference currently-{@code QUEUED} tasks; an unknown id, an already-claimed task, or
 * a non-distinct list is rejected as a 400.
 */
public record QueueReorderRequest(List<Long> taskIds) {}

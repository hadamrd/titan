package io.adaptiq.titan.api.dto;

/**
 * Response body for {@code POST /api/v1/queue/drain} (admin queue controls, #347). {@code drained}
 * is the number of {@code QUEUED} tasks moved to {@code CANCELLED} on this call — idempotent: a
 * follow-up call on an empty queue returns {@code 0}.
 */
public record QueueDrainResponse(int drained) {}

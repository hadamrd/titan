package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response body for {@code POST /api/v1/workers/{workerId}/drain} and {@code .../undrain} (UI
 * §5.3). {@code state} is the post-update agent status ({@code DRAINING} or {@code ONLINE}). {@code
 * inflightBuilds} is the agent's current task count from {@code titan.agents.current_tasks}; {@code
 * estimatedSecondsRemaining} is a coarse estimate — see {@link io.adaptiq.titan.api.WorkersApi} for
 * the formula.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DrainResponseDto(
    String workerId, String state, int inflightBuilds, long estimatedSecondsRemaining) {}

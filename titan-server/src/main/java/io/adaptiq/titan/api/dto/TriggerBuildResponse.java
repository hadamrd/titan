package io.adaptiq.titan.api.dto;

/**
 * Response body for {@code POST /api/v1/jobs/{jobId}/builds}.
 *
 * <p>Returns just enough to let the caller immediately poll the status endpoint.
 */
public record TriggerBuildResponse(long buildId, int buildNumber, String status) {}

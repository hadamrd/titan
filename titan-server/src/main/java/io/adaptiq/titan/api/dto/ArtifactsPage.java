package io.adaptiq.titan.api.dto;

import java.util.List;

/**
 * Wire shape for {@code GET /api/v1/builds/{buildId}/artifacts}: {@code items} is the requested
 * window, {@code total} is the full count across the whole build (so the UI can render the right
 * pagination footer regardless of the slice it asked for).
 */
public record ArtifactsPage(List<ArtifactDto> items, int total) {}

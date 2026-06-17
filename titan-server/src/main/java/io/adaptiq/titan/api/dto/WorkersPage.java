package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Paginated list of workers returned by {@code GET /api/v1/workers} (UI §5.3). Mirrors the shape of
 * {@link JobsPage} and {@link QueuePage}: {@code (items, total, offset, limit)} so the UI page
 * controls behave identically across surfaces.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkersPage(List<WorkerDto> items, int total, int offset, int limit) {}

package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Response body for {@code GET /api/v1/queue} — a page of {@link QueueEntryDto} plus the total
 * count of currently-claimable {@code QUEUED} tasks. Mirrors the {@code (items, total, offset,
 * limit)} shape used by {@link CredentialsPage} and {@link BuildsPage}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueuePage(List<QueueEntryDto> items, int total, int offset, int limit) {}

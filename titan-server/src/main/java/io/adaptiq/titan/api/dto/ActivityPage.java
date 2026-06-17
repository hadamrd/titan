package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Wire shape for {@code GET /api/v1/activity} (closes #304).
 *
 * <p>{@code items} is the requested window (never {@code null} — empty list on an empty feed).
 * {@code nextCursor} is the opaque base64-encoded epoch-ms timestamp of the last item in the page,
 * to be passed back as {@code ?before=...} for the next page; {@code null} when the page is the
 * last one (fewer items returned than {@code limit}).
 *
 * <p>{@link JsonInclude.Include#ALWAYS} on {@code nextCursor} is the default — we want it to
 * serialise as JSON {@code null} rather than be omitted, so clients always see the field.
 */
public record ActivityPage(List<ActivityItemDto> items, String nextCursor) {}

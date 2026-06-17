package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Paginated wire shape for {@code GET /api/v1/rbac-audit} (closes #1167).
 *
 * <p>Mirrors {@link AuditPage}: {@code total} is the count matching the active filter set
 * (independent of {@code offset}/{@code limit}) so the UI can render a "showing N of M" footer and
 * disable the Next button at the tail. Rows are ordered newest-first ({@code occurred_at DESC, id
 * DESC}).
 *
 * <p>{@code JsonInclude.ALWAYS} so {@code items} serialises as {@code []} (not omitted) on the
 * system-quiet empty state — the UI distinguishes "no rows at all" from "filter too narrow" and
 * relies on the array always being present.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RbacAuditPage(List<RbacAuditEventDto> items, long total, int offset, int limit) {}

package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Paginated wire shape for {@code GET /api/v1/audit} (closes #478, #1098).
 *
 * <p>{@code total} is the count matching the filter set (independent of {@code offset}/{@code
 * limit}) so the UI can render "showing N of M" footer alongside the new cursor flow.
 *
 * <p>{@code next} is the cursor for the following page — see {@link BuildsPage} for the contract.
 * Audit rows are ordered newest-first (occurred_at DESC, id DESC).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AuditPage(
    List<AuditEventDto> items, long total, int offset, int limit, @Nullable String next) {

  /** Legacy 4-arg constructor — see {@link BuildsPage}. */
  public AuditPage(List<AuditEventDto> items, long total, int offset, int limit) {
    this(items, total, offset, limit, null);
  }
}

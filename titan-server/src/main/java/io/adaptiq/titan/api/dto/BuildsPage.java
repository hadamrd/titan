package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Paginated list of builds returned by {@code GET /api/v1/builds} and {@code GET
 * /api/v1/jobs/{jobId}/builds}.
 *
 * <p>Builds are ordered newest-first (queued_at DESC, id DESC). {@code total} is the count before
 * slicing — kept for legacy callers that rendered a "showing N of M" footer; new callers should
 * page through {@code next} instead.
 *
 * <p>Cursor pagination (#1098): {@code next} is an opaque, URL-safe base64 cursor; pass it back as
 * {@code ?after=&lt;cursor&gt;} to fetch the following page. {@code null} when this is the last
 * page. Stable across mid-stream inserts (it encodes the last row's {@code (queued_at, id)}, not an
 * offset).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BuildsPage(
    List<BuildDto> items, int total, int offset, int limit, @Nullable String next) {

  /**
   * Legacy 4-arg constructor — kept so call sites that pre-date cursor pagination (closes #1098)
   * compile unchanged. Equivalent to passing {@code next=null} (caller didn't surface a cursor).
   */
  public BuildsPage(List<BuildDto> items, int total, int offset, int limit) {
    this(items, total, offset, limit, null);
  }
}

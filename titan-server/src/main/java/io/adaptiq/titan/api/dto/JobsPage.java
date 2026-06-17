package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Paginated list of jobs returned by {@code GET /api/v1/jobs}.
 *
 * <p>Pagination defaults to simple offset/limit for legacy callers — {@code total} is the total
 * count of jobs matching the query (before slicing). New callers should follow {@code next} (cursor
 * encodes the last job's {@code (createdAt, id)}; stable across mid-stream inserts). Jobs are
 * returned newest-first (createdAt DESC, id DESC).
 *
 * <p>{@code next} is null when this is the last page.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record JobsPage(
    List<JobDto> items, int total, int offset, int limit, @Nullable String next) {

  /** Legacy 4-arg constructor — see {@link BuildsPage}. */
  public JobsPage(List<JobDto> items, int total, int offset, int limit) {
    this(items, total, offset, limit, null);
  }
}

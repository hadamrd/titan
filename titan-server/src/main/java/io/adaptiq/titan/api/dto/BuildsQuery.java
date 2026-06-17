package io.adaptiq.titan.api.dto;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;

/**
 * Typed query DTO for {@code GET /api/v1/builds} (closes #682) — the global build-list filter.
 *
 * <p>Every filter is optional; an instance built from an empty query string ({@code new
 * BuildsQuery(List.of(), null, null, null, 50, 0)}) MUST be semantically identical to "unfiltered"
 * — that's the back-compat contract the controller relies on so callers that still hit the endpoint
 * with no params see the same result set they used to.
 *
 * <p>AND semantics across all populated filters — every present filter must match. {@code status}
 * is a value-set (multi-select chips on the UI map to {@code ?status=A&status=B}).
 *
 * <p>{@code afterTs}/{@code afterId} (closes #1098) carry the decoded cursor for the next page.
 * When both are populated the DAO appends {@code (queued_at, id) &lt; (:afterTs, :afterId)} so the
 * next page is stable across mid-stream inserts. Null on the first page.
 */
public record BuildsQuery(
    List<String> status,
    @Nullable String branch,
    @Nullable String search,
    @Nullable Instant since,
    @Nullable String triggeredBy,
    @Nullable String headSha,
    @Nullable Instant afterTs,
    @Nullable Long afterId,
    int limit,
    int offset) {

  public BuildsQuery {
    if (status == null) {
      status = List.of();
    }
    if (limit < 0) {
      throw new IllegalArgumentException("limit must be >= 0");
    }
    if (offset < 0) {
      throw new IllegalArgumentException("offset must be >= 0");
    }
    // Either both cursor halves are present, or both null. The HTTP controller decodes the wire
    // cursor and constructs both in lock-step, but defensive validation guards against a future
    // engine-side caller bypassing the decoder.
    if ((afterTs == null) != (afterId == null)) {
      throw new IllegalArgumentException("afterTs and afterId must be both null or both non-null");
    }
  }

  /**
   * Legacy 6-arg constructor — kept so call sites that pre-date the {@code triggeredBy} filter
   * (closes #746) compile unchanged. New code SHOULD use the canonical 10-arg form.
   */
  public BuildsQuery(
      List<String> status,
      @Nullable String branch,
      @Nullable String search,
      @Nullable Instant since,
      int limit,
      int offset) {
    this(status, branch, search, since, null, null, null, null, limit, offset);
  }

  /**
   * Legacy 7-arg constructor — kept so call sites that pre-date the {@code headSha} filter (closes
   * #967) compile unchanged. New code SHOULD use the canonical 10-arg form.
   */
  public BuildsQuery(
      List<String> status,
      @Nullable String branch,
      @Nullable String search,
      @Nullable Instant since,
      @Nullable String triggeredBy,
      int limit,
      int offset) {
    this(status, branch, search, since, triggeredBy, null, null, null, limit, offset);
  }

  /**
   * Legacy 8-arg constructor — kept so call sites that pre-date cursor pagination (closes #1098)
   * compile unchanged. New code SHOULD use the canonical 10-arg form.
   */
  public BuildsQuery(
      List<String> status,
      @Nullable String branch,
      @Nullable String search,
      @Nullable Instant since,
      @Nullable String triggeredBy,
      @Nullable String headSha,
      int limit,
      int offset) {
    this(status, branch, search, since, triggeredBy, headSha, null, null, limit, offset);
  }

  /** True when no filter is set — controller short-circuits to the plain list query. */
  public boolean isUnfiltered() {
    return status.isEmpty()
        && (branch == null || branch.isBlank())
        && (search == null || search.isBlank())
        && since == null
        && (triggeredBy == null || triggeredBy.isBlank())
        && (headSha == null || headSha.isBlank())
        && afterTs == null
        && afterId == null;
  }
}

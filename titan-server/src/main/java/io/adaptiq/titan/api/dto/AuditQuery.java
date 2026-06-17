package io.adaptiq.titan.api.dto;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;

/**
 * Filter shape for the audit-log list endpoint — backs the extended {@code GET /api/v1/audit}
 * (closes #727, #1098). Mirrors the {@link BuildsQuery} record next door, one-to-one with the wire
 * params.
 *
 * <p>Every field is optional. Empty / null fields disable the corresponding filter so the legacy
 * "no filter" path still returns the full log. AND-composed across populated fields.
 *
 * <ul>
 *   <li>{@code actor} — case-insensitive substring on the {@code actor} column.
 *   <li>{@code actions} — closed set of {@code AuditAction} codes, OR-within / AND-against the
 *       other filters. Empty list = no action filter (NOT "match nothing").
 *   <li>{@code resource} — case-insensitive substring across {@code target_type} and {@code
 *       target_id}.
 *   <li>{@code since} — {@code occurred_at &gt;= since} (ISO-8601 instant).
 *   <li>{@code afterTs} / {@code afterId} — cursor pagination (closes #1098). When populated the
 *       DAO appends {@code (occurred_at, id) &lt; (:afterTs, :afterId)}.
 * </ul>
 *
 * <p>{@code limit}/{@code offset} are pre-capped by {@code AuditApi} so the DAO doesn't have to
 * worry about absurd values.
 */
public record AuditQuery(
    @Nullable String actor,
    @Nullable List<String> actions,
    @Nullable String resource,
    @Nullable Instant since,
    @Nullable Instant afterTs,
    @Nullable Long afterId,
    int limit,
    int offset) {

  public AuditQuery {
    if (limit < 0) {
      throw new IllegalArgumentException("limit must be >= 0");
    }
    if (offset < 0) {
      throw new IllegalArgumentException("offset must be >= 0");
    }
    actions = actions == null ? List.of() : List.copyOf(actions);
    if ((afterTs == null) != (afterId == null)) {
      throw new IllegalArgumentException("afterTs and afterId must be both null or both non-null");
    }
  }

  /**
   * Legacy 6-arg constructor — kept so call sites that pre-date cursor pagination (closes #1098)
   * compile unchanged.
   */
  public AuditQuery(
      @Nullable String actor,
      @Nullable List<String> actions,
      @Nullable String resource,
      @Nullable Instant since,
      int limit,
      int offset) {
    this(actor, actions, resource, since, null, null, limit, offset);
  }
}

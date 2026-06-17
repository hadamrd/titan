package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.AuditEventDto;
import io.adaptiq.titan.api.dto.AuditPage;
import io.adaptiq.titan.api.dto.AuditQuery;
import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.audit.AuditTargetType;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.store.TitanStores;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Jakarta REST resource: {@code /api/v1/audit} — admin-only audit log surface (closes #478,
 * extended for #727).
 *
 * <ul>
 *   <li>{@code GET /api/v1/audit} — paginated, filterable by {@code actor} (ILIKE substring),
 *       {@code action} (repeatable, validated against the {@link AuditAction} enum), {@code
 *       targetType} (legacy single-enum filter, kept for back-compat), {@code resource} (ILIKE
 *       substring on target_type/target_id), and {@code since} (ISO-8601 timestamp).
 * </ul>
 *
 * <p>{@link Roles#READ_AUDIT} grants view-only access; {@link Roles#ADMIN} continues to work since
 * ADMIN is a super-role. Audit logs include who did what and are not user-scoped — there is no
 * per-user narrowing applied below either role. {@code action} tokens are validated against the
 * server-side enum so a caller cannot fish for arbitrary values; an invalid filter surfaces as HTTP
 * 400 (not a silent empty result). Empty {@code action=} param degrades to no filter, never "match
 * nothing" — a stale URL with a half-typed filter must not appear to lose all audit data.
 */
@Path("/api/v1/audit")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RolesAllowed({Roles.READ_AUDIT, Roles.ADMIN})
public class AuditApi {

  private static final int MAX_LIMIT = 200;
  private static final int MAX_ACTOR_LEN = 200;
  private static final int MAX_RESOURCE_LEN = 200;

  private final TitanStores stores;

  AuditApi(TitanStores stores) {
    this.stores = stores;
  }

  @GET
  public AuditPage list(
      @QueryParam("actor") String actor,
      @QueryParam("action") List<String> actionRaw,
      @QueryParam("targetType") String targetType,
      @QueryParam("resource") String resource,
      @QueryParam("since") String sinceStr,
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("limit") @DefaultValue("50") int limit,
      @QueryParam("after") String afterCursor) {
    int cappedLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
    int safeOffset = Math.max(offset, 0);

    String actorFilter = nullIfBlank(actor);
    if (actorFilter != null && actorFilter.length() > MAX_ACTOR_LEN) {
      throw new ApiBadRequestException(
          "actor length " + actorFilter.length() + " exceeds cap " + MAX_ACTOR_LEN);
    }
    String resourceFilter = nullIfBlank(resource);
    if (resourceFilter != null && resourceFilter.length() > MAX_RESOURCE_LEN) {
      throw new ApiBadRequestException(
          "resource length " + resourceFilter.length() + " exceeds cap " + MAX_RESOURCE_LEN);
    }
    List<String> actionFilters = normaliseActions(actionRaw);
    Instant since = parseSince(sinceStr);

    // targetType (legacy single-enum) — kept for back-compat with the v0 filter shape. When
    // populated, narrows by exact target_type. Otherwise the new `resource` substring is the
    // operator-facing path.
    String targetTypeFilter = validateEnum(nullIfBlank(targetType), AuditTargetType.class);

    // Cursor decode at the HTTP boundary — malformed cursor → 400. The decoded cursor flows
    // into AuditQuery so the DAO can apply `(occurred_at, id) < (afterTs, afterId)` (closes
    // #1098).
    io.adaptiq.titan.api.Pagination.Cursor cursor =
        io.adaptiq.titan.api.Pagination.decode(afterCursor);
    Instant afterTs = cursor == null ? null : cursor.ts();
    Long afterId = cursor == null ? null : cursor.id();
    int queryOffset = cursor == null ? safeOffset : 0;

    // If the caller supplied the legacy single targetType filter we route through the legacy
    // code path that supports exact-match on actor + action + targetType (used by AuditApiTest).
    // Otherwise we use the new multi-filter shape from AuditQuery.
    if (targetTypeFilter != null
        && actionFilters.size() <= 1
        && resourceFilter == null
        && cursor == null) {
      String singleAction = actionFilters.isEmpty() ? null : actionFilters.get(0);
      // +1 probe — see BuildsApi for the rationale. The extra row is trimmed before serialising;
      // its presence is what tells us whether to emit a `next` cursor, so a full final page
      // (size == limit, exact multiple) does NOT spawn a dangling empty page.
      List<io.adaptiq.titan.store.rows.AuditLogRow> fetched =
          stores
              .auditLog()
              .findRecent(
                  actorFilter, singleAction, targetTypeFilter, since, cappedLimit + 1, queryOffset);
      boolean hasMore = fetched.size() > cappedLimit;
      List<io.adaptiq.titan.store.rows.AuditLogRow> rows =
          hasMore ? new ArrayList<>(fetched.subList(0, cappedLimit)) : fetched;
      List<AuditEventDto> page = rows.stream().map(AuditEventDto::from).toList();
      long total =
          stores.auditLog().countRecent(actorFilter, singleAction, targetTypeFilter, since);
      String next = hasMore ? auditNextCursor(rows) : null;
      return new AuditPage(page, total, safeOffset, cappedLimit, next);
    }

    // +1 probe on the multi-filter / cursor path too.
    AuditQuery query =
        new AuditQuery(
            actorFilter,
            actionFilters,
            resourceFilter,
            since,
            afterTs,
            afterId,
            cappedLimit + 1,
            queryOffset);
    List<io.adaptiq.titan.store.rows.AuditLogRow> fetched = stores.auditLog().findAll(query);
    boolean hasMore = fetched.size() > cappedLimit;
    List<io.adaptiq.titan.store.rows.AuditLogRow> rows =
        hasMore ? new ArrayList<>(fetched.subList(0, cappedLimit)) : fetched;
    List<AuditEventDto> page = rows.stream().map(AuditEventDto::from).toList();
    long total = stores.auditLog().countAll(query);
    String next = hasMore ? auditNextCursor(rows) : null;
    return new AuditPage(page, total, safeOffset, cappedLimit, next);
  }

  /**
   * Legacy 7-arg overload — kept so tests that pre-date {@code ?after=} (closes #1098) compile
   * unchanged.
   */
  public AuditPage list(
      String actor,
      List<String> actionRaw,
      String targetType,
      String resource,
      String sinceStr,
      int offset,
      int limit) {
    return list(actor, actionRaw, targetType, resource, sinceStr, offset, limit, null);
  }

  /** Compute the next-page cursor from the last row of the trimmed page — see {@code BuildsApi}. */
  @edu.umd.cs.findbugs.annotations.Nullable
  static String auditNextCursor(List<io.adaptiq.titan.store.rows.AuditLogRow> rows) {
    if (rows.isEmpty()) {
      return null;
    }
    io.adaptiq.titan.store.rows.AuditLogRow last = rows.get(rows.size() - 1);
    if (last.occurredAt == null) {
      return null;
    }
    return io.adaptiq.titan.api.Pagination.encode(
        new io.adaptiq.titan.api.Pagination.Cursor(last.occurredAt, last.id));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static String nullIfBlank(String s) {
    return (s == null || s.isBlank()) ? null : s.trim();
  }

  /**
   * De-dupe the repeated {@code action=} query params, drop blanks, and validate each token against
   * {@link AuditAction}. An empty / all-blank list returns {@link List#of()} — the DAO treats that
   * as "no action filter", which is the contract: a half-typed {@code action=} query string must
   * NOT mean "match nothing".
   */
  private static List<String> normaliseActions(List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    List<String> out = new ArrayList<>(raw.size());
    for (String s : raw) {
      if (s == null) {
        continue;
      }
      String t = s.trim().toUpperCase(Locale.ROOT);
      if (t.isEmpty()) {
        continue;
      }
      try {
        AuditAction.valueOf(t);
      } catch (IllegalArgumentException e) {
        throw new ApiBadRequestException(
            "query parameter 'action' is not a recognised value: " + s);
      }
      if (!out.contains(t)) {
        out.add(t);
      }
    }
    return out;
  }

  private static <E extends Enum<E>> String validateEnum(String value, Class<E> enumType) {
    if (value == null) {
      return null;
    }
    try {
      return Enum.valueOf(enumType, value).name();
    } catch (IllegalArgumentException e) {
      throw new ApiBadRequestException(
          "query parameter 'targetType' is not a recognised value: " + value);
    }
  }

  private static Instant parseSince(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(raw.trim());
    } catch (DateTimeParseException e) {
      throw new ApiBadRequestException(
          "query parameter 'since' must be an ISO-8601 timestamp (e.g." + " 2026-05-01T00:00:00Z)");
    }
  }
}

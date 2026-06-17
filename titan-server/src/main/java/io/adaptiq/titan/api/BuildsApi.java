package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.BuildDto;
import io.adaptiq.titan.api.dto.BuildsPage;
import io.adaptiq.titan.api.dto.BuildsQuery;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
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
import java.util.Set;

/**
 * Jakarta REST resource: global build list — {@code GET /api/v1/builds} (closes #682).
 *
 * <p>Supports the {@code /builds} page's filter strip: status (repeatable), branch, free-text
 * search (commit / failure / triggered-by / build number), and a since-cutoff. Pagination via
 * {@code limit}/{@code offset}. Empty filter params are a no-op (the call degenerates to the legacy
 * "newest-first" list), so existing callers see no behaviour change.
 *
 * <p>All filter bindings go through {@code BuildDao.findAll} which uses parameterised JDBI binds —
 * no string concat of caller input into SQL. Unknown {@code status} tokens are rejected at this
 * layer (HTTP 400) rather than silently producing zero rows.
 *
 * <p>Co-resident with {@link BuildDetailApi} under {@code @Path("/api/v1/builds")}: the detail
 * class owns the {@code {buildId}}-suffixed routes, this class owns the bare collection GET. Both
 * classes serve different sub-paths so RESTEasy route resolution is unambiguous.
 *
 * <p>Wired by Quarkus ARC constructor injection; no field injection.
 */
@Path("/api/v1/builds")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RolesAllowed({Roles.READ_JOB, Roles.ADMIN})
public class BuildsApi {

  /** Hard cap on {@code limit} — mirrors the per-job builds endpoint. */
  private static final int MAX_LIMIT = 200;

  /** Hard cap on {@code search} length — guards against pathological inputs. */
  private static final int MAX_SEARCH_LEN = 200;

  /**
   * Closed set of acceptable {@code status} tokens — mirrors {@code BuildStatus} on the wire (see
   * {@code titan-ui/src/api/types.ts}). Adding a new status here is a one-line change but is
   * intentional: an unknown token from the UI is more often a typo than a new state.
   */
  public static final Set<String> ALLOWED_STATUSES =
      Set.of("SUCCESS", "FAILED", "RUNNING", "QUEUED", "ABORTED", "UNSTABLE");

  private final TitanStores stores;

  BuildsApi(TitanStores stores) {
    this.stores = stores;
  }

  @GET
  public BuildsPage list(
      @QueryParam("status") List<String> statusRaw,
      @QueryParam("branch") String branch,
      @QueryParam("search") String search,
      @QueryParam("since") String sinceRaw,
      @QueryParam("triggeredBy") String triggeredByRaw,
      @QueryParam("headSha") String headShaRaw,
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("limit") @DefaultValue("50") int limit,
      @QueryParam("after") String afterCursor) {

    List<String> status = normaliseStatuses(statusRaw);
    String normalisedBranch = nullIfBlank(branch);
    String normalisedSearch = nullIfBlank(search);
    if (normalisedSearch != null && normalisedSearch.length() > MAX_SEARCH_LEN) {
      throw new ApiBadRequestException(
          "search length " + normalisedSearch.length() + " exceeds cap " + MAX_SEARCH_LEN);
    }
    String normalisedTriggeredBy = nullIfBlank(triggeredByRaw);
    if (normalisedTriggeredBy != null && normalisedTriggeredBy.length() > MAX_SEARCH_LEN) {
      throw new ApiBadRequestException(
          "triggeredBy length "
              + normalisedTriggeredBy.length()
              + " exceeds cap "
              + MAX_SEARCH_LEN);
    }
    String normalisedHeadSha = validateHeadSha(headShaRaw);
    Instant since = parseSince(sinceRaw);
    int cappedLimit = Math.min(Math.max(limit, 0), MAX_LIMIT);
    int safeOffset = Math.max(offset, 0);

    // Cursor-based pagination (closes #1098). When ?after= is present we drop offset so
    // the cursor is the single source of truth — offset stays on the response purely so the
    // legacy "showing N of M" footer keeps rendering against the same fields.
    Pagination.Cursor cursor = Pagination.decode(afterCursor);
    Instant afterTs = cursor == null ? null : cursor.ts();
    Long afterId = cursor == null ? null : cursor.id();
    int queryOffset = cursor == null ? safeOffset : 0;

    // Over-fetch by one (the "+1 probe" idiom). The probe row is NOT returned to the caller; its
    // mere existence proves a further page exists. This is the only way to avoid emitting a
    // dangling `next` cursor that leads to an empty page when the result set is an exact multiple
    // of the limit (250 rows / limit 50 → page 5 is full, yet there is no page 6). The previous
    // "fewer rows than limit" heuristic mis-fired on that fence-post and forced clients through a
    // spurious empty 6th fetch. A COUNT-based heuristic would instead be wrong under concurrent
    // inserts; the probe is exact.
    BuildsQuery q =
        new BuildsQuery(
            status,
            normalisedBranch,
            normalisedSearch,
            since,
            normalisedTriggeredBy,
            normalisedHeadSha,
            afterTs,
            afterId,
            cappedLimit + 1,
            queryOffset);

    List<BuildRow> fetched = stores.builds().findAll(q);
    long total = stores.builds().countAll(q);

    boolean hasMore = fetched.size() > cappedLimit;
    List<BuildRow> rows = hasMore ? new ArrayList<>(fetched.subList(0, cappedLimit)) : fetched;

    List<BuildDto> items = rows.stream().map(BuildDto::from).toList();
    String next = hasMore ? buildNextCursor(rows) : null;
    return new BuildsPage(
        items, (int) Math.min(total, Integer.MAX_VALUE), safeOffset, cappedLimit, next);
  }

  /**
   * Encode the cursor for the next page from the last row of the current (already-trimmed) page.
   * Callers invoke this only when a +1 probe row proved a further page exists, so it never returns
   * a cursor that leads to an empty page. Returns {@code null} only defensively — when the boundary
   * row is missing the {@code queued_at} it would be ordered by.
   */
  @edu.umd.cs.findbugs.annotations.Nullable
  static String buildNextCursor(List<BuildRow> rows) {
    if (rows.isEmpty()) {
      return null;
    }
    BuildRow last = rows.get(rows.size() - 1);
    if (last.queuedAt == null) {
      // Defensive: a row missing queued_at couldn't have been ordered consistently anyway.
      // Better to terminate the cursor stream than to encode a null timestamp.
      return null;
    }
    return Pagination.encode(new Pagination.Cursor(last.queuedAt, last.id));
  }

  /**
   * Legacy 8-arg overload — kept so test callers that pre-date {@code ?after=} (closes #1098)
   * continue to compile. Equivalent to passing {@code afterCursor = null}.
   */
  public BuildsPage list(
      List<String> statusRaw,
      String branch,
      String search,
      String sinceRaw,
      String triggeredByRaw,
      String headShaRaw,
      int offset,
      int limit) {
    return list(
        statusRaw, branch, search, sinceRaw, triggeredByRaw, headShaRaw, offset, limit, null);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  static String nullIfBlank(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }

  /**
   * Validate and de-dupe the repeated {@code status} query params. Blank entries are ignored.
   * Tokens are upper-cased (the UI sends already-upper but operators with curl don't always);
   * unknown tokens surface as HTTP 400 with a helpful message rather than silently returning zero
   * rows.
   */
  static List<String> normaliseStatuses(List<String> raw) {
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
      if (!ALLOWED_STATUSES.contains(t)) {
        throw new ApiBadRequestException(
            "unknown status '" + s + "'; expected one of " + ALLOWED_STATUSES);
      }
      if (!out.contains(t)) {
        out.add(t);
      }
    }
    return out;
  }

  /**
   * Validate and normalise the {@code ?headSha=} query parameter (closes #967). The wire contract
   * is the FULL 40-char hex commit SHA — the only form that round-trips through git, GitHub's
   * {@code POST /repos/.../statuses/{sha}}, and our own {@code trigger_meta_json->>'commitSha'}
   * post-#971. A short SHA would silently match zero rows (the column stores full SHAs), so we fail
   * loud with HTTP 400 rather than return a confusing empty page.
   *
   * <p>Null/blank input yields {@code null} (no filter). Any non-empty value must be exactly 40
   * characters of pure hexadecimal (case-insensitive). The check is done here, not in {@link
   * BuildsQuery} or {@link io.adaptiq.titan.store.BuildDao}, because input validation belongs at
   * the HTTP boundary — the DAO trusts its query record.
   */
  @edu.umd.cs.findbugs.annotations.Nullable
  static String validateHeadSha(String raw) {
    if (raw == null) {
      return null;
    }
    String t = raw.trim();
    if (t.isEmpty()) {
      return null;
    }
    if (t.length() != 40) {
      throw new ApiBadRequestException(
          "headSha must be a 40-character hex commit SHA; got length " + t.length());
    }
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
      if (!hex) {
        throw new ApiBadRequestException(
            "headSha must be hex (0-9, a-f); got non-hex character at offset " + i);
      }
    }
    // Normalise to lowercase so the equality match is case-insensitive on the wire while the
    // DB binding is a single deterministic value.
    return t.toLowerCase(Locale.ROOT);
  }

  /**
   * Parse an ISO-8601 instant ({@code 2026-05-20T09:00:00Z}). Null/blank yields {@code null} (no
   * since filter). Malformed input surfaces as HTTP 400 — the alternative (silently ignoring) would
   * cause a confusing "everything appears" result that the UI cannot debug.
   */
  static Instant parseSince(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(raw.trim());
    } catch (DateTimeParseException e) {
      throw new ApiBadRequestException(
          "since must be an ISO-8601 instant (e.g. 2026-05-20T09:00:00Z); got '" + raw + "'");
    }
  }
}

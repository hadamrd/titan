package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.BuildSearchHitDto;
import io.adaptiq.titan.api.dto.BuildSearchPage;
import io.adaptiq.titan.api.dto.BuildSearchQuery;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildSearchHitRow;
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
 * Jakarta REST resource: full-text search over build logs — {@code GET /api/v1/builds/search}
 * (closes #1083).
 *
 * <p>Distinct from {@link BuildsApi} (the structured filter list): this endpoint expects a free-
 * text query {@code q} and returns hits with server-rendered highlighted snippets. The query is
 * parsed by Postgres' {@code websearch_to_tsquery} — see {@link
 * io.adaptiq.titan.store.BuildSearchDao} — so operators ({@code "exact phrase"}, {@code -negation},
 * {@code OR}) work out of the box and user input is never concatenated into SQL.
 *
 * <p>Acceptance criteria (per the ticket):
 *
 * <ul>
 *   <li>{@code q} required — empty / blank → HTTP 400 (this endpoint is search-by-query, not
 *       list-everything-via-search).
 *   <li>{@code job} optional glob — {@code *} mapped to SQL {@code %}; {@code _} / {@code %} in
 *       user input are rejected to prevent LIKE-pattern injection of unintended wildcards.
 *   <li>{@code status} optional CSV — same allow-list as {@link BuildsApi#ALLOWED_STATUSES}.
 *   <li>{@code since} optional ISO-8601 instant.
 *   <li>{@code limit} / {@code offset} — same caps as the rest of the build surface.
 * </ul>
 */
@Path("/api/v1/builds/search")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RolesAllowed({Roles.READ_JOB, Roles.ADMIN})
public class BuildSearchApi {

  /** Hard cap on {@code limit}. Same value as {@link BuildsApi#MAX_LIMIT}. */
  static final int MAX_LIMIT = 200;

  /** Hard cap on {@code q} length — guards against pathological inputs that blow up tsquery. */
  static final int MAX_Q_LEN = 500;

  /** Hard cap on {@code job} length. */
  static final int MAX_JOB_LEN = 200;

  private final TitanStores stores;

  BuildSearchApi(TitanStores stores) {
    this.stores = stores;
  }

  @GET
  public BuildSearchPage search(
      @QueryParam("q") String qRaw,
      @QueryParam("job") String jobRaw,
      @QueryParam("status") List<String> statusRaw,
      @QueryParam("since") String sinceRaw,
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("limit") @DefaultValue("25") int limit) {

    String q = nullIfBlank(qRaw);
    if (q == null) {
      throw new ApiBadRequestException("q is required (free-text query)");
    }
    if (q.length() > MAX_Q_LEN) {
      throw new ApiBadRequestException("q length " + q.length() + " exceeds cap " + MAX_Q_LEN);
    }

    String job = validateJobGlob(jobRaw);
    List<String> status = normaliseStatuses(statusRaw);
    Instant since = parseSince(sinceRaw);
    int cappedLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
    int safeOffset = Math.max(offset, 0);

    BuildSearchQuery query = new BuildSearchQuery(q, job, status, since, cappedLimit, safeOffset);

    List<BuildSearchHitRow> rows = stores.buildSearch().search(query);
    long total = stores.buildSearch().countMatches(query);

    List<BuildSearchHitDto> items = rows.stream().map(BuildSearchHitDto::from).toList();
    return new BuildSearchPage(
        items, (int) Math.min(total, Integer.MAX_VALUE), safeOffset, cappedLimit);
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
   * Validate the {@code job} glob. {@code *} is the only wildcard (mapped to SQL {@code %} in the
   * DAO). Bare {@code _} / {@code %} in user input are rejected because they would silently widen
   * the match. Null / blank → no filter.
   */
  static String validateJobGlob(String raw) {
    String t = nullIfBlank(raw);
    if (t == null) {
      return null;
    }
    if (t.length() > MAX_JOB_LEN) {
      throw new ApiBadRequestException("job length " + t.length() + " exceeds cap " + MAX_JOB_LEN);
    }
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      if (c == '%' || c == '_' || c == '\\') {
        throw new ApiBadRequestException(
            "job glob contains reserved character '" + c + "' (use '*' as wildcard)");
      }
    }
    return t;
  }

  /**
   * Accept either a repeated {@code status=FOO&status=BAR} OR a single {@code status=FOO,BAR}
   * (CSV). Empty / blank entries are ignored. Tokens normalised to uppercase. Unknown tokens → HTTP
   * 400.
   */
  static List<String> normaliseStatuses(List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    Set<String> allowed = BuildsApi.ALLOWED_STATUSES;
    List<String> out = new ArrayList<>();
    for (String entry : raw) {
      if (entry == null) {
        continue;
      }
      for (String token : entry.split(",")) {
        String t = token.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) {
          continue;
        }
        if (!allowed.contains(t)) {
          throw new ApiBadRequestException(
              "unknown status '" + token + "'; expected one of " + allowed);
        }
        if (!out.contains(t)) {
          out.add(t);
        }
      }
    }
    return out;
  }

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

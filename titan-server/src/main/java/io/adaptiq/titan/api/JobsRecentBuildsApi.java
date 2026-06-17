package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.BuildDto;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jakarta REST resource: bulk recent-builds fetch for the {@code /jobs} sparkline (closes #650).
 *
 * <p>The {@code /jobs} page renders a 20-bar sparkline per row. PR #648 implemented it with one
 * {@code GET /api/v1/jobs/{id}/builds} per row — fine for the seed fleet but textbook N+1 at scale.
 * This endpoint collapses the fan-out to a single round-trip:
 *
 * <pre>
 *   GET /api/v1/jobs/recent-builds?jobIds=1,2,3&amp;limit=20
 *   → {"1":[{...}, ...], "2":[{...}, ...], "3":[]}
 * </pre>
 *
 * <p>The SQL is a single window-function pass — see {@link
 * io.adaptiq.titan.store.BuildDao#findRecentBuildsForJobs}. An empty {@code jobIds} returns {@code
 * {}} (not a 400); a job id that has no builds and an unknown id both surface as absent map keys
 * (the UI renders the "no builds" placeholder either way), so the caller never has to distinguish
 * the two.
 *
 * <p>Wire shape is {@code Map<Long, List<BuildDto>>} — Jackson serializes long keys as JSON
 * strings, which TS callers already handle (the `JobDto.id` round-trip uses the same encoding).
 */
@Path("/api/v1/jobs/recent-builds")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RolesAllowed({Roles.READ_JOB, Roles.ADMIN})
public class JobsRecentBuildsApi {

  /** Server-side cap on {@code limit} — mirrors the per-job builds endpoint. */
  private static final int MAX_LIMIT = 200;

  /** Server-side cap on number of job ids per request — protects the IN(...) list size. */
  private static final int MAX_JOB_IDS = 500;

  private final TitanStores stores;

  JobsRecentBuildsApi(TitanStores stores) {
    this.stores = stores;
  }

  @GET
  public Map<Long, List<BuildDto>> recentBuilds(
      @QueryParam("jobIds") String jobIdsCsv, @QueryParam("limit") @DefaultValue("20") int limit) {

    List<Long> jobIds = parseJobIds(jobIdsCsv);
    if (jobIds.isEmpty()) {
      // Short-circuit — IN () would be invalid SQL and there is nothing to ask the DAO for.
      return Map.of();
    }
    if (jobIds.size() > MAX_JOB_IDS) {
      throw new ApiBadRequestException(
          "jobIds size " + jobIds.size() + " exceeds cap " + MAX_JOB_IDS);
    }
    int cappedLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);

    List<BuildRow> rows = stores.builds().findRecentBuildsForJobs(jobIds, cappedLimit);

    // LinkedHashMap so output ordering is stable (jobId asc, matching the SQL ORDER BY) — easier on
    // human debugging and on snapshot tests.
    Map<Long, List<BuildDto>> out = new LinkedHashMap<>();
    for (BuildRow row : rows) {
      out.computeIfAbsent(row.jobId, k -> new ArrayList<>()).add(BuildDto.from(row));
    }
    return out;
  }

  /**
   * Parse the comma-separated {@code jobIds} query param into a deduplicated, order-preserving list
   * of {@code long}. Blank / null input returns an empty list (caller decides what to do).
   * Non-numeric tokens surface as {@link ApiBadRequestException} → HTTP 400 with a helpful message,
   * rather than a 500 on the DAO.
   */
  static List<Long> parseJobIds(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    String[] parts = csv.split(",");
    // Linked-hash semantics via ArrayList + contains check — input is bounded by MAX_JOB_IDS so the
    // O(n^2) dedupe is fine and avoids the allocation cost of LinkedHashSet for typical sizes.
    List<Long> out = new ArrayList<>(parts.length);
    for (String raw : parts) {
      String token = raw.trim();
      if (token.isEmpty()) {
        continue;
      }
      long id;
      try {
        id = Long.parseLong(token);
      } catch (NumberFormatException e) {
        throw new ApiBadRequestException("jobIds contains non-numeric token '" + token + "'");
      }
      if (!out.contains(id)) {
        out.add(id);
      }
    }
    return out;
  }
}

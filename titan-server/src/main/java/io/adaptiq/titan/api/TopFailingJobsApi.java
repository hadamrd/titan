package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.TopFailingJobDto;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.TopFailingJobDao;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Jakarta REST resource: {@code GET /api/v1/jobs/top-failing} (closes #769).
 *
 * <p>Returns the Home "Top failing jobs" widget rows — jobs ranked by failure rate over the
 * requested time window. Inputs are clamp-validated:
 *
 * <ul>
 *   <li>{@code since} — discriminator value, {@code "24h"} or {@code "7d"}. Default {@code "24h"}.
 *       Any other value → HTTP 400. We deliberately do not accept free-form durations: this is a
 *       UI-facing endpoint, and the two windows match the spec in #769.
 *   <li>{@code limit} — 1..20, default 5. Out-of-range → HTTP 400.
 * </ul>
 *
 * <p>Only jobs with ≥ 3 builds in the window and at least one FAILED build appear in the response —
 * the threshold filters out one-off failures (acceptance criterion in #769). Empty windows return
 * {@code []}, never {@code null}.
 *
 * <p><strong>RBAC.</strong> {@code READ_JOB} or {@code ADMIN} — same guard as {@link JobsApi}.
 * Missing / invalid bearer surfaces as 401 via the OIDC filter.
 */
@Path("/api/v1/jobs/top-failing")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RolesAllowed({Roles.READ_JOB, Roles.ADMIN})
public class TopFailingJobsApi {

  // Default value (5) lives on the @DefaultValue("5") query-param annotation —
  // the JAX-RS layer is the canonical source for the missing-param fallback.
  private static final int MIN_LIMIT = 1;
  private static final int MAX_LIMIT = 20;

  private final TitanStores stores;

  TopFailingJobsApi(TitanStores stores) {
    this.stores = stores;
  }

  @GET
  public List<TopFailingJobDto> topFailing(
      @QueryParam("since") @DefaultValue("24h") String since,
      @QueryParam("limit") @DefaultValue("5") int limit) {
    Instant sinceInstant = parseSince(since);
    int safeLimit = clampLimit(limit);

    List<TopFailingJobDao.TopFailingJobRow> rows =
        stores.topFailingJobs().topFailing(sinceInstant, safeLimit);

    return rows.stream()
        .map(
            r ->
                new TopFailingJobDto(
                    r.jobId(),
                    r.jobName(),
                    r.totalBuilds(),
                    r.failedBuilds(),
                    r.failureRate(),
                    r.lastFailedBuildId()))
        .toList();
  }

  /**
   * Map the {@code since} discriminator to an absolute {@link Instant}. Only the two values
   * documented in #769 are accepted — anything else is a 400 so callers (and the UI) cannot
   * silently degrade to a different window than they asked for.
   */
  private static Instant parseSince(String since) {
    String norm = since == null ? "" : since.trim().toLowerCase();
    Instant now = Instant.now();
    return switch (norm) {
      case "", "24h" -> now.minus(24, ChronoUnit.HOURS);
      case "7d" -> now.minus(7, ChronoUnit.DAYS);
      default ->
          throw new ApiBadRequestException(
              "query parameter 'since' must be one of {24h, 7d}; got '" + since + "'");
    };
  }

  /**
   * Clamp the {@code limit} param into the documented range and reject obvious abuse. Values
   * outside {@code [1, 20]} are 400 rather than silently clamped — the UI never asks for more than
   * 20, so anything that hits this branch is a client bug worth reporting back.
   */
  private static int clampLimit(int limit) {
    if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
      throw new ApiBadRequestException(
          "query parameter 'limit' must be in ["
              + MIN_LIMIT
              + ", "
              + MAX_LIMIT
              + "]; got "
              + limit);
    }
    // Missing-param case is handled by @DefaultValue("5") on the query
    // parameter — by the time we reach here limit is in [MIN_LIMIT, MAX_LIMIT],
    // so the old `limit == 0 ? DEFAULT_LIMIT : limit` branch was dead code
    // (SpotBugs UC, surfaced on T73 #815).
    return limit;
  }
}

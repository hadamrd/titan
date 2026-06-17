package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.JobStatsDto;
import io.adaptiq.titan.api.dto.JobStatsDto.DailyBucketDto;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.store.JobStatsDao;
import io.adaptiq.titan.store.TitanStores;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Jakarta REST resource: {@code GET /api/v1/jobs/{jobId}/stats} (closes #775).
 *
 * <p>Per-job analytics over a clamped sliding window — totals, failure rate, p50/p95 duration, and
 * a dense daily-bucket array suitable for a sparkline. Mirrors the response-shape pattern of {@link
 * TopFailingJobsApi} (closed discriminator on the {@code window} param, no free-form durations) so
 * the wire surface stays predictable for the UI.
 *
 * <p><strong>Window discriminator.</strong> Accepts {@code 7d}, {@code 30d}, {@code 90d} (default
 * {@code 30d}). Any other value yields HTTP 400 — silent fallback would lie about the data range.
 *
 * <p><strong>RBAC.</strong> {@code READ_JOB} or {@code ADMIN}, matching the rest of {@link
 * JobsApi}.
 *
 * <p>The endpoint does <strong>not</strong> 404 on jobs that have no builds — a freshly-created job
 * is a legitimate {@code totalBuilds=0} response. Validating job existence here would couple this
 * endpoint to the {@code titan.jobs} table for no UX win (the UI hits {@code /jobs/{id}} first and
 * renders a 404 page from that response).
 */
@Path("/api/v1/jobs/{jobId}/stats")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RolesAllowed({Roles.READ_JOB, Roles.ADMIN})
public class JobStatsApi {

  private static final String DEFAULT_WINDOW = "30d";

  private final TitanStores stores;

  JobStatsApi(TitanStores stores) {
    this.stores = stores;
  }

  @GET
  public JobStatsDto stats(
      @PathParam("jobId") String jobIdStr,
      @QueryParam("window") @DefaultValue(DEFAULT_WINDOW) String window) {
    long jobId = JobsApi.parseLong(jobIdStr, "jobId");
    int windowDays = parseWindow(window);
    Instant now = Instant.now();
    // Bucket boundary: start of (today - (windowDays - 1)) UTC. That way a 7d request
    // covers exactly 7 calendar days ending today (today inclusive), matching what the
    // sparkline labels read out as "the last 7 days".
    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    LocalDate firstDay = today.minusDays((long) windowDays - 1L);
    Instant since = firstDay.atStartOfDay(ZoneOffset.UTC).toInstant();

    JobStatsDao dao = stores.jobStats();
    JobStatsDao.TotalsRow totals = dao.totals(jobId, since);
    List<JobStatsDao.DailyBucketRow> rawBuckets = dao.dailyBuckets(jobId, since);

    double failureRate =
        totals.totalBuilds() == 0
            ? 0.0
            : (double) totals.failedBuilds() / (double) totals.totalBuilds();

    return new JobStatsDto(
        totals.totalBuilds(),
        totals.failedBuilds(),
        failureRate,
        totals.p50DurationMs(),
        totals.p95DurationMs(),
        denseBuckets(firstDay, windowDays, rawBuckets),
        normaliseWindow(windowDays));
  }

  /**
   * Map the {@code window} discriminator to a day count. Closed-set parse — anything else is a 400
   * so a stale client cannot silently land in a different window. Default ({@link #DEFAULT_WINDOW})
   * is also accepted explicitly to keep the test surface simple.
   */
  static int parseWindow(String window) {
    String norm = window == null ? "" : window.trim().toLowerCase();
    return switch (norm) {
      case "", "30d" -> 30;
      case "7d" -> 7;
      case "90d" -> 90;
      default ->
          throw new ApiBadRequestException(
              "query parameter 'window' must be one of {7d, 30d, 90d}; got '" + window + "'");
    };
  }

  /** Inverse of {@link #parseWindow} for the response echo. Keeps the wire string canonical. */
  private static String normaliseWindow(int days) {
    return days + "d";
  }

  /**
   * Fill in zero-count entries for days with no builds so the response always has exactly {@code
   * windowDays} entries, oldest-first. Without this, a sparkline of a quiet job would have a
   * variable-length x-axis and the UI would have to invent its own date scaffolding.
   */
  private static List<DailyBucketDto> denseBuckets(
      LocalDate firstDay, int windowDays, List<JobStatsDao.DailyBucketRow> raw) {
    Map<LocalDate, JobStatsDao.DailyBucketRow> byDay = new HashMap<>(raw.size() * 2);
    for (JobStatsDao.DailyBucketRow r : raw) {
      byDay.put(r.day(), r);
    }
    List<DailyBucketDto> out = new ArrayList<>(windowDays);
    for (int i = 0; i < windowDays; i++) {
      LocalDate d = firstDay.plusDays(i);
      JobStatsDao.DailyBucketRow row = byDay.get(d);
      if (row == null) {
        out.add(new DailyBucketDto(d, 0L, 0L));
      } else {
        out.add(new DailyBucketDto(d, row.totalBuilds(), row.failedBuilds()));
      }
    }
    return out;
  }
}

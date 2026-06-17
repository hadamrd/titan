package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.StageTimingsDto;
import io.adaptiq.titan.api.dto.StageTimingsDto.StageSampleDto;
import io.adaptiq.titan.api.dto.StageTimingsDto.StageTimingDto;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.store.JobTimingsDao;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jakarta REST resource: {@code GET /api/v1/jobs/{jobId}/stage-timings?n=30} (closes #1095).
 *
 * <p>Returns per-stage duration percentiles + per-build samples over the last {@code n} finished
 * builds of the job. Used by the "Stage Timing — last 30 builds" panel on the pipeline detail page
 * so SREs can answer "is this build slower than usual?" without clicking through 30 builds one by
 * one.
 *
 * <p><strong>Defensive clamps.</strong>
 *
 * <ul>
 *   <li>{@code n} defaults to {@link JobTimingsDao#DEFAULT_BUILDS} (30) when absent.
 *   <li>{@code n &lt;= 0} → HTTP 400 — silent "give me everything" would invert the contract.
 *   <li>{@code n &gt; }{@link JobTimingsDao#MAX_BUILDS} → HTTP 400 — caps the histogram payload at
 *       100 builds × #stages so a malicious caller can't ask for a year's worth of samples.
 *   <li>An invalid {@code jobId} (non-numeric) → HTTP 400 via {@link JobsApi#parseLong}.
 * </ul>
 *
 * <p><strong>RBAC.</strong> {@code READ_JOB} or {@code ADMIN} — same surface as {@link JobsApi} and
 * {@link JobStatsApi}.
 *
 * <p><strong>Empty contract.</strong> A job that exists but has no finished builds in the window
 * returns {@code {n, buildsConsidered: 0, stages: []}}, NOT 404. Confusing "job missing" with "job
 * idle" would lie to the operator about provisioning state.
 */
@Path("/api/v1/jobs/{jobId}/stage-timings")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RolesAllowed({Roles.READ_JOB, Roles.ADMIN})
public class JobTimingsHandler {

  private final TitanStores stores;

  JobTimingsHandler(TitanStores stores) {
    this.stores = stores;
  }

  @GET
  public StageTimingsDto stageTimings(
      @PathParam("jobId") String jobIdStr, @QueryParam("n") @DefaultValue("30") int n) {
    long jobId = JobsApi.parseLong(jobIdStr, "jobId");
    int window = validateN(n);

    JobTimingsDao dao = stores.jobTimings();
    List<JobTimingsDao.StagePercentileRow> rows = dao.stagePercentiles(jobId, window);
    List<JobTimingsDao.StageSampleRow> samples = dao.stageSamples(jobId, window);
    long buildsConsidered = dao.countFinishedBuildsInWindow(jobId, window);

    return new StageTimingsDto(window, buildsConsidered, zip(rows, samples));
  }

  /**
   * Closed-range validation for {@code n}. The default-value annotation already covers the "absent"
   * case; we only need to defend against actively bad client input.
   */
  static int validateN(int n) {
    if (n <= 0) {
      throw new ApiBadRequestException("query parameter 'n' must be a positive integer; got " + n);
    }
    if (n > JobTimingsDao.MAX_BUILDS) {
      throw new ApiBadRequestException(
          "query parameter 'n' must be <= " + JobTimingsDao.MAX_BUILDS + "; got " + n);
    }
    return n;
  }

  /**
   * Fold the two DAO outputs (one row per stage, many rows of samples) into one stage-keyed list.
   * We preserve the percentile-rows' ordering (DAO returns slowest-p50 first) by using a {@link
   * LinkedHashMap} keyed on {@code stageName}.
   */
  private static List<StageTimingDto> zip(
      List<JobTimingsDao.StagePercentileRow> rows, List<JobTimingsDao.StageSampleRow> samples) {
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<String, List<StageSampleDto>> samplesByStage = new LinkedHashMap<>();
    for (JobTimingsDao.StageSampleRow s : samples) {
      samplesByStage
          .computeIfAbsent(s.stageName(), k -> new ArrayList<>())
          .add(new StageSampleDto(s.buildId(), s.buildNumber(), s.durationMs(), s.status()));
    }
    List<StageTimingDto> out = new ArrayList<>(rows.size());
    for (JobTimingsDao.StagePercentileRow r : rows) {
      List<StageSampleDto> stageSamples = samplesByStage.getOrDefault(r.stageName(), List.of());
      out.add(
          new StageTimingDto(
              r.stageName(),
              r.sampleCount(),
              r.p50Ms(),
              r.p95Ms(),
              r.p99Ms(),
              r.minMs(),
              r.maxMs(),
              stageSamples));
    }
    return out;
  }
}

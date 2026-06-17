package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.DurationTrendPointDto;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.store.DurationTrendDao;
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
import java.util.List;

/**
 * Jakarta REST resource: {@code GET /api/v1/jobs/{jobId}/duration-trend?n=30} (closes #1096).
 *
 * <p>Returns the last {@code n} finished builds' durations (oldest→newest) so the /pipelines index
 * can render a small inline duration-trend sparkline per row. An operator eyeballing "which
 * pipeline is degrading" reads the trend without drilling into each job's stats page.
 *
 * <p><strong>Defensive clamps.</strong>
 *
 * <ul>
 *   <li>{@code n} defaults to {@link DurationTrendDao#DEFAULT_BUILDS} (30) when absent.
 *   <li>{@code n <= 0} → HTTP 400 — a non-positive window is a caller bug, not "give me
 *       everything".
 *   <li>{@code n > }{@link DurationTrendDao#MAX_BUILDS} → HTTP 400 — caps the per-row payload.
 *   <li>A non-numeric {@code jobId} → HTTP 400 via {@link JobsApi#parseLong}.
 * </ul>
 *
 * <p><strong>RBAC.</strong> {@code READ_JOB} or {@code ADMIN} — same surface as {@link JobsApi} and
 * {@link JobTimingsHandler}.
 *
 * <p><strong>Empty contract.</strong> A job that exists but has no finished builds (or a job id
 * with no rows at all) returns {@code []}, NOT 404 — mirrors {@link JobTimingsHandler}. Confusing
 * "job idle" with "job missing" would lie to the operator about provisioning state.
 */
@Path("/api/v1/jobs/{jobId}/duration-trend")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RolesAllowed({Roles.READ_JOB, Roles.ADMIN})
public class DurationTrendHandler {

  private final TitanStores stores;

  DurationTrendHandler(TitanStores stores) {
    this.stores = stores;
  }

  @GET
  public List<DurationTrendPointDto> durationTrend(
      @PathParam("jobId") String jobIdStr, @QueryParam("n") @DefaultValue("30") int n) {
    long jobId = JobsApi.parseLong(jobIdStr, "jobId");
    int window = validateN(n);
    return stores.durationTrend().durationTrend(jobId, window).stream()
        .map(DurationTrendPointDto::from)
        .toList();
  }

  /**
   * Closed-range validation for {@code n}. The {@code @DefaultValue} annotation covers the "absent"
   * case; this only defends against actively bad client input.
   */
  static int validateN(int n) {
    if (n <= 0) {
      throw new ApiBadRequestException("query parameter 'n' must be a positive integer; got " + n);
    }
    if (n > DurationTrendDao.MAX_BUILDS) {
      throw new ApiBadRequestException(
          "query parameter 'n' must be <= " + DurationTrendDao.MAX_BUILDS + "; got " + n);
    }
    return n;
  }
}

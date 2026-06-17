package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.StatsDto;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.store.StatsDao;
import io.adaptiq.titan.store.TitanStores;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Jakarta REST resource: {@code GET /api/v1/stats} — Overview-page KPI tiles (closes #346).
 *
 * <p>Returns three numbers in one shot, never null:
 *
 * <ul>
 *   <li>{@code buildsToday} — builds queued since start-of-UTC-day.
 *   <li>{@code successRate} — terminal-SUCCESS / terminal-any over the last 24h, in {@code [0, 1]}.
 *   <li>{@code medianDurationMs} — median {@code finished_at - started_at} over terminal builds in
 *       the last 24h.
 * </ul>
 *
 * <p>The aggregate lives in a single SQL pass via {@link StatsDao#overview} so the Overview tile
 * doesn't fan-out to multiple round-trips. Empty windows return {@code 0 / 0.0 / 0L} — never {@code
 * null}.
 *
 * <p><strong>RBAC.</strong> {@code READ_JOB}, {@code TRIGGER_BUILD}, or {@code ADMIN} — same guard
 * as {@link ArtifactsApi} and {@link QueueApi}. Missing/invalid bearer → 401 via the OIDC filter.
 */
@Path("/api/v1/stats")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class StatsApi {

  private final TitanStores stores;

  StatsApi(TitanStores stores) {
    this.stores = stores;
  }

  @GET
  @RolesAllowed({Roles.READ_JOB, Roles.TRIGGER_BUILD, Roles.ADMIN})
  public StatsDto overview() {
    Instant now = Instant.now();
    Instant startOfDayUtc =
        now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant since = now.minus(24, ChronoUnit.HOURS);

    StatsDao.StatsRow row = stores.stats().overview(startOfDayUtc, since);
    return new StatsDto(row.buildsToday(), row.successRate(), row.medianDurationMs());
  }
}

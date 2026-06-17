package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.DrainResponseDto;
import io.adaptiq.titan.api.dto.WorkerDto;
import io.adaptiq.titan.api.dto.WorkersPage;
import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.AgentRow;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * Jakarta REST resource: worker (agent) endpoints — list + drain/undrain lifecycle controls (design
 * §5.3).
 *
 * <ul>
 *   <li>{@code GET /api/v1/workers} — paginated list for the Workers grid. Returns {@link
 *       WorkerDto} rows (never the internal {@link AgentRow}; see {@link WorkerDto#from} for the
 *       enumerated wire fields). Read-tier role required.
 *   <li>{@code POST /api/v1/workers/{workerId}/drain} — flip an agent to {@code DRAINING}. The
 *       worker polling loop reads this and stops claiming new tasks; in-flight tasks finish.
 *   <li>{@code POST /api/v1/workers/{workerId}/undrain} — flip a {@code DRAINING} agent back to
 *       {@code ONLINE}. Idempotent: undraining an already-ONLINE worker is a no-op (still 200).
 * </ul>
 *
 * <p>Drain/undrain responses carry {@link DrainResponseDto} with the post-update state and a coarse
 * estimate of seconds to fully drain — a back-of-envelope {@code currentTasks * 60}, because the
 * controller has no per-task runtime model. The UI uses it as a "minutes-ish" hint, not a hard SLA.
 * Admin-only — drain affects scheduling capacity.
 */
@Path("/api/v1/workers")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class WorkersApi {

  /** Coarse seconds-per-task hint used to estimate remaining drain time. */
  static final long SECONDS_PER_TASK_HINT = 60L;

  /** Hard cap on the page size — the Workers grid never paginates beyond this. */
  static final int MAX_LIMIT = 500;

  private final TitanStores stores;

  WorkersApi(TitanStores stores) {
    this.stores = stores;
  }

  // ── GET /api/v1/workers ────────────────────────────────────────────────────

  @GET
  @RolesAllowed({Roles.READ_JOB, Roles.TRIGGER_BUILD, Roles.ADMIN})
  public WorkersPage list(
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    int safeOffset = Math.max(offset, 0);
    int cappedLimit = Math.min(Math.max(limit, 0), MAX_LIMIT);

    // AgentDao.listAll() is a single SQL query (no per-row joins) — the agent fleet is tiny
    // compared to the build/queue tables, so a slice-in-memory after the read is cheap and
    // keeps the SqlObject surface minimal.
    List<AgentRow> all = stores.agents().listAll();
    int total = all.size();
    if (cappedLimit == 0 || total == 0) {
      return new WorkersPage(List.of(), total, safeOffset, cappedLimit);
    }
    List<WorkerDto> page =
        all.stream().skip(safeOffset).limit(cappedLimit).map(WorkerDto::from).toList();
    return new WorkersPage(page, total, safeOffset, cappedLimit);
  }

  // ── POST /api/v1/workers/{workerId}/drain ──────────────────────────────────

  @POST
  @Path("/{workerId}/drain")
  @RolesAllowed({Roles.OPERATE_WORKER, Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeId = "global")
  public Response drain(@PathParam("workerId") String workerId) {
    AgentRow agent =
        stores
            .agents()
            .findById(workerId)
            .orElseThrow(() -> new ApiNotFoundException("worker " + workerId + " not found"));
    stores.agents().markDraining(workerId);
    // Re-read so the response reflects whatever state the DB ended up in (already-DRAINING,
    // already-OFFLINE, etc. — markDraining is a no-op on those).
    AgentRow after = stores.agents().findById(workerId).orElse(agent);
    return Response.ok(toDrainDto(after)).build();
  }

  // ── POST /api/v1/workers/{workerId}/undrain ────────────────────────────────

  @POST
  @Path("/{workerId}/undrain")
  @RolesAllowed({Roles.OPERATE_WORKER, Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeId = "global")
  public Response undrain(@PathParam("workerId") String workerId) {
    AgentRow agent =
        stores
            .agents()
            .findById(workerId)
            .orElseThrow(() -> new ApiNotFoundException("worker " + workerId + " not found"));
    stores.agents().markOnline(workerId);
    AgentRow after = stores.agents().findById(workerId).orElse(agent);
    return Response.ok(toDrainDto(after)).build();
  }

  private static DrainResponseDto toDrainDto(AgentRow a) {
    long est = Math.max(0L, (long) a.currentTasks) * SECONDS_PER_TASK_HINT;
    return new DrainResponseDto(a.agentId, a.status, a.currentTasks, est);
  }
}

package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.AgentEventDto;
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
import java.util.List;

/**
 * Jakarta REST resource: worker lifecycle event feed — {@code GET /api/v1/agents/events?limit=N}
 * (closes #714).
 *
 * <p>Returns the most-recent worker {@code JOINED}/{@code LEFT} events, newest first. The Home
 * activity timeline interleaves these with terminal-build events to give SREs a unified "what's
 * happening on the controller right now" view.
 *
 * <p>Read-tier RBAC ({@link Roles#READ_JOB}+{@link Roles#TRIGGER_BUILD}+{@link Roles#ADMIN}) —
 * matches {@link WorkersApi#list} since this surface exposes the same agent population in a
 * different time slice.
 */
@Path("/api/v1/agents/events")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class AgentEventsApi {

  /** Hard cap so a misbehaving client can't pull the whole event log. */
  static final int MAX_LIMIT = 500;

  /** Server-side default when {@code ?limit=} is omitted. */
  static final int DEFAULT_LIMIT = 20;

  private final TitanStores stores;

  AgentEventsApi(TitanStores stores) {
    this.stores = stores;
  }

  @GET
  @RolesAllowed({Roles.READ_JOB, Roles.TRIGGER_BUILD, Roles.ADMIN})
  public List<AgentEventDto> list(@QueryParam("limit") @DefaultValue("20") int limit) {
    int capped = Math.min(Math.max(limit, 0), MAX_LIMIT);
    if (capped == 0) {
      return List.of();
    }
    return stores.agentEvents().listRecent(capped).stream().map(AgentEventDto::from).toList();
  }
}

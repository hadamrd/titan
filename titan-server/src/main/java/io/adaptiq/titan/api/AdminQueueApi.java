package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.QueueDrainResponse;
import io.adaptiq.titan.api.dto.QueueReorderRequest;
import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.store.TaskQueueDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * Jakarta REST resource: admin-only queue controls (closes #347).
 *
 * <ul>
 *   <li>{@code POST /api/v1/queue/drain} — cancel every currently-{@code QUEUED} task. Idempotent.
 *       Returns {@code {"drained": N}}.
 *   <li>{@code POST /api/v1/queue/reorder} — re-prioritize a set of {@code QUEUED} tasks. Body
 *       carries the new head-first id list; the server assigns descending priorities so the worker
 *       claim order ({@code priority DESC, created_at ASC}) yields the requested ordering. Returns
 *       {@code 204}; unknown / non-{@code QUEUED} / duplicate ids → {@code 400}.
 * </ul>
 *
 * <p><strong>RBAC.</strong> {@link Roles#ADMIN} only. {@code READ_JOB} / {@code TRIGGER_BUILD} →
 * 403 (these are destructive ops). Missing/invalid bearer → 401 via the OIDC filter.
 *
 * <p>In-flight ({@code CLAIMED}/{@code PROCESSING}) tasks are intentionally untouched by both
 * endpoints — drain is a queue-clear, not a worker-kill.
 */
@Path("/api/v1/queue")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class AdminQueueApi {

  private final TitanStores stores;

  AdminQueueApi(TitanStores stores) {
    this.stores = stores;
  }

  // ── POST /api/v1/queue/drain ──────────────────────────────────────────────

  @POST
  @Path("/drain")
  @RolesAllowed({Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeId = "global")
  public QueueDrainResponse drain() {
    int n = stores.taskQueue().drainAllQueued();
    return new QueueDrainResponse(n);
  }

  // ── POST /api/v1/queue/reorder ────────────────────────────────────────────

  @POST
  @Path("/reorder")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeId = "global")
  public Response reorder(QueueReorderRequest req) {
    if (req == null || req.taskIds() == null) {
      throw new ApiBadRequestException("field 'taskIds' is required");
    }
    List<Long> ids = req.taskIds();
    if (ids.isEmpty()) {
      // Nothing to do — accept the no-op.
      return Response.noContent().build();
    }
    if (new HashSet<>(ids).size() != ids.size()) {
      throw new ApiBadRequestException("field 'taskIds' must contain distinct ids");
    }
    if (ids.stream().anyMatch(java.util.Objects::isNull)) {
      throw new ApiBadRequestException("field 'taskIds' must not contain null ids");
    }

    TaskQueueDao dao = stores.taskQueue();

    // Pre-validate every id is currently QUEUED — fail fast with 400 before mutating.
    for (Long id : ids) {
      Optional<TaskQueueRow> row = dao.findById(id);
      if (row.isEmpty()) {
        throw new ApiBadRequestException("task " + id + " not found");
      }
      if (!"QUEUED".equals(row.get().status)) {
        throw new ApiBadRequestException(
            "task " + id + " is not QUEUED (status=" + row.get().status + ")");
      }
    }

    // Assign descending priorities head-first. We deliberately use a high base so we don't collide
    // with whatever priorities the caller had in flight; ids beyond Integer.MAX_VALUE - n would
    // wrap, so cap base at a sane value.
    int base = Math.min(1_000_000, Integer.MAX_VALUE - ids.size());
    for (int i = 0; i < ids.size(); i++) {
      int priority = base - i;
      int updated = dao.setPriority(ids.get(i), priority);
      if (updated != 1) {
        // Race: someone claimed the task between pre-validate and update. Treat as 400 — the
        // requested ordering can no longer be enforced.
        throw new ApiBadRequestException("task " + ids.get(i) + " is no longer QUEUED");
      }
    }
    return Response.noContent().build();
  }
}

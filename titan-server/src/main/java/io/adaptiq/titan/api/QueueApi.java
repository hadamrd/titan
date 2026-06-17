package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.QueueEntryDto;
import io.adaptiq.titan.api.dto.QueuePage;
import io.adaptiq.titan.api.dto.RecentTaskDto;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Jakarta REST resource: {@code GET /api/v1/queue} — the queue-feed for the UI Queue page (design
 * §5.2).
 *
 * <p>Returns currently-claimable {@code QUEUED} rows from {@code titan.task_queue} joined to their
 * build + job (when the task carries a build id). Sort matches the worker claim order: {@code
 * priority DESC}, then {@code created_at ASC}. {@code waitingMs} is computed server-side as {@code
 * now() - createdAt} so clients do not need to do clock arithmetic.
 *
 * <p>Pagination is hard-capped at {@code 500} (the queue UI never paginates beyond that — anything
 * over 500 means a controller-side flush problem, not a UI problem).
 */
@Path("/api/v1/queue")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class QueueApi {

  /** Hard cap per spec §5.2. */
  static final int MAX_LIMIT = 500;

  private final TitanStores stores;

  QueueApi(TitanStores stores) {
    this.stores = stores;
  }

  @GET
  @RolesAllowed({Roles.READ_JOB, Roles.TRIGGER_BUILD, Roles.ADMIN})
  public QueuePage list(
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    int safeOffset = Math.max(offset, 0);
    int cappedLimit = Math.min(Math.max(limit, 0), MAX_LIMIT);

    int total = stores.taskQueue().countQueued();
    if (cappedLimit == 0 || total == 0) {
      return new QueuePage(List.of(), total, safeOffset, cappedLimit);
    }

    List<TaskQueueRow> tasks = stores.taskQueue().listQueued(safeOffset, cappedLimit);
    if (tasks.isEmpty()) {
      return new QueuePage(List.of(), total, safeOffset, cappedLimit);
    }

    // Bulk-resolve builds → jobs so we don't N+1 the DB. The build id set is small (<= cap).
    Set<Long> buildIds =
        tasks.stream()
            .map(t -> t.buildId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());

    Map<Long, BuildRow> buildById = new HashMap<>();
    for (Long bid : buildIds) {
      stores.builds().findById(bid).ifPresent(b -> buildById.put(b.id, b));
    }
    Set<Long> jobIds = buildById.values().stream().map(b -> b.jobId).collect(Collectors.toSet());
    Map<Long, JobRow> jobById = new HashMap<>();
    for (Long jid : jobIds) {
      stores.jobs().findById(jid).ifPresent(j -> jobById.put(j.id, j));
    }

    Instant now = Instant.now();
    List<QueueEntryDto> items =
        tasks.stream()
            .map(
                t -> {
                  BuildRow b = t.buildId != null ? buildById.get(t.buildId) : null;
                  JobRow j = b != null ? jobById.get(b.jobId) : null;
                  long waitingMs = Math.max(0L, now.toEpochMilli() - t.createdAt.toEpochMilli());
                  return new QueueEntryDto(
                      t.id,
                      t.buildId,
                      b != null ? b.jobId : null,
                      j != null ? j.fullName : null,
                      t.createdAt,
                      waitingMs,
                      t.priority,
                      t.queueName);
                })
            .toList();

    return new QueuePage(items, total, safeOffset, cappedLimit);
  }

  /** Default page size for {@code GET /api/v1/queue/recent} (issue #523). */
  static final int RECENT_DEFAULT_LIMIT = 20;

  /** Hard cap for {@code GET /api/v1/queue/recent} — same envelope as {@link #MAX_LIMIT}. */
  static final int RECENT_MAX_LIMIT = 100;

  /**
   * {@code GET /api/v1/queue/recent?limit=N} — the "Recent activity" feed for the UI Queue page
   * when the live queue is idle (issue #523). Returns the last {@code limit} rows from {@code
   * task_archive}, newest-first by {@code completed_at}. The DTO mirrors the queue entry shape plus
   * {@code status}, {@code completedAt}, {@code type}, and {@code nodeId} so the UI can render a
   * proper table without an extra round-trip.
   *
   * <p>The empty-queue case is the load-bearing UX: a blank queue card reads as broken (issue
   * #523), so the UI calls this in parallel and renders the resulting card below the queue. When
   * the live queue is non-empty the UI hides this card; the endpoint is unconditionally available
   * to clients (it is a normal read).
   */
  @GET
  @Path("/recent")
  @RolesAllowed({Roles.READ_JOB, Roles.TRIGGER_BUILD, Roles.ADMIN})
  public List<RecentTaskDto> recent(@QueryParam("limit") @DefaultValue("20") int limit) {
    int cappedLimit = Math.min(Math.max(limit, 0), RECENT_MAX_LIMIT);
    if (cappedLimit == 0) {
      return List.of();
    }

    List<TaskQueueRow> rows = stores.taskQueue().listRecentArchived(cappedLimit);
    if (rows.isEmpty()) {
      return List.of();
    }

    Set<Long> buildIds =
        rows.stream()
            .map(t -> t.buildId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());

    Map<Long, BuildRow> buildById = new HashMap<>();
    for (Long bid : buildIds) {
      stores.builds().findById(bid).ifPresent(b -> buildById.put(b.id, b));
    }
    Set<Long> jobIds = buildById.values().stream().map(b -> b.jobId).collect(Collectors.toSet());
    Map<Long, JobRow> jobById = new HashMap<>();
    for (Long jid : jobIds) {
      stores.jobs().findById(jid).ifPresent(j -> jobById.put(j.id, j));
    }

    return rows.stream()
        .map(
            t -> {
              BuildRow b = t.buildId != null ? buildById.get(t.buildId) : null;
              JobRow j = b != null ? jobById.get(b.jobId) : null;
              return new RecentTaskDto(
                  t.id,
                  t.buildId,
                  b != null ? b.jobId : null,
                  j != null ? j.fullName : null,
                  t.nodeId,
                  t.type,
                  t.status,
                  t.createdAt,
                  t.completedAt,
                  t.priority,
                  t.queueName);
            })
        .toList();
  }
}

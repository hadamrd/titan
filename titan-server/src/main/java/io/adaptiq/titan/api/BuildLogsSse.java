package io.adaptiq.titan.api;

import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.LogRow;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Jakarta REST SSE endpoint: {@code GET /api/v1/builds/{buildId}/logs}.
 *
 * <p>Streams live log lines for a build by polling {@code titan.logs} every 500 ms. Each chunk is
 * sent as a Server-Sent Event with {@code event: log} and the chunk's text as data.
 *
 * <p>The stream terminates automatically when:
 *
 * <ul>
 *   <li>The build reaches a terminal status ({@code SUCCESS / FAILED / ABORTED / UNSTABLE}).
 *   <li>The SSE client disconnects.
 * </ul>
 *
 * <p>Constructor-injection only — {@link TitanStores} wired by Quarkus ARC via {@link
 * io.adaptiq.titan.boot.StoresProducer}. {@link Sse} is injected via {@code @Context} (JAX-RS
 * infrastructure object — not a CDI bean).
 */
@Path("/api/v1/builds/{buildId}/logs")
@ApplicationScoped
public class BuildLogsSse {

  private static final Logger LOGGER = Logger.getLogger(BuildLogsSse.class.getName());

  /** Poll interval: 500 ms. */
  private static final long POLL_INTERVAL_MS = 500L;

  private static final Set<String> TERMINAL_STATUSES =
      Set.of("SUCCESS", "FAILED", "ABORTED", "UNSTABLE");

  private final TitanStores stores;

  // Sse is a JAX-RS context object; @Inject is the standard way to get it in
  // a CDI bean — this is NOT field injection of a business dependency.
  @Inject Sse sse;

  BuildLogsSse(TitanStores stores) {
    this.stores = stores;
  }

  @GET
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RolesAllowed({Roles.READ_JOB, Roles.ADMIN})
  public void streamLogs(
      @PathParam("buildId") String buildIdStr,
      @QueryParam("taskId") String taskIdStr,
      @Context SseEventSink sink) {

    long buildId;
    try {
      buildId = Long.parseLong(buildIdStr);
    } catch (NumberFormatException e) {
      try (sink) {
        sink.send(sse.newEventBuilder().name("error").data("invalid buildId").build());
      }
      return;
    }

    // Optional per-step filter (closes #537). When present, restricts the
    // polling loop to a single task token instead of every task token for
    // the build. Invalid UUID → error event and close (no silent fallthrough
    // to the unfiltered stream — that would surprise SREs).
    final UUID filterTaskId;
    if (taskIdStr == null || taskIdStr.isBlank()) {
      filterTaskId = null;
    } else {
      try {
        filterTaskId = UUID.fromString(taskIdStr);
      } catch (IllegalArgumentException e) {
        try (sink) {
          sink.send(sse.newEventBuilder().name("error").data("invalid taskId").build());
        }
        return;
      }
    }

    // Verify build exists before starting the stream.
    if (stores.builds().findById(buildId).isEmpty()) {
      try (sink) {
        sink.send(
            sse.newEventBuilder().name("error").data("build " + buildId + " not found").build());
      }
      return;
    }

    // Run the polling loop on a virtual thread so the JAX-RS thread is freed immediately.
    Thread.ofVirtual()
        .name("sse-logs-build-" + buildId)
        .start(() -> pollAndStream(buildId, filterTaskId, sink));
  }

  // ── polling loop ──────────────────────────────────────────────────────────

  private void pollAndStream(long buildId, UUID filterTaskId, SseEventSink sink) {
    long[] cursor = {0L};

    try {
      while (!sink.isClosed()) {
        // Refresh task tokens each poll — new tasks may have been enqueued since stream started.
        // When a per-step filter is set, the token list is forced to that single token (the
        // server still checks it belongs to this build by intersection; an unknown token
        // simply yields zero rows).
        List<UUID> tokens = resolveTokens(buildId, filterTaskId);

        boolean sentAny = false;
        for (UUID token : tokens) {
          List<LogRow> rows = stores.logs().listByTask(token);
          for (LogRow row : rows) {
            if (row.id > cursor[0]) {
              sink.send(sse.newEventBuilder().name("log").data(row.data).build());
              cursor[0] = row.id;
              sentAny = true;
            }
          }
        }

        // Check build terminal status — if done and logs drained, close the stream.
        var build = stores.builds().findById(buildId).orElse(null);
        if (build != null && TERMINAL_STATUSES.contains(build.status) && !sentAny) {
          // One final drain pass to pick up any logs written between last poll and terminal.
          boolean hasMore = false;
          List<UUID> finalTokens = resolveTokens(buildId, filterTaskId);
          for (UUID token : finalTokens) {
            for (LogRow row : stores.logs().listByTask(token)) {
              if (row.id > cursor[0]) {
                sink.send(sse.newEventBuilder().name("log").data(row.data).build());
                cursor[0] = row.id;
                hasMore = true;
              }
            }
          }
          if (!hasMore) {
            sink.send(sse.newEventBuilder().name("done").data(build.status).build());
            sink.close();
            return;
          }
        }

        //noinspection BusyWait
        Thread.sleep(POLL_INTERVAL_MS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.log(Level.FINE, "[sse] log stream interrupted for build {0}", buildId);
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "[sse] log stream error for build " + buildId, e);
    } finally {
      if (!sink.isClosed()) {
        sink.close();
      }
    }
  }

  /**
   * Resolves the token list for this poll. With no filter, returns all of the build's task tokens
   * (legacy behaviour). With a filter, returns the filter token IFF it belongs to this build —
   * otherwise an empty list (defence-in-depth against cross-build token snooping).
   */
  private List<UUID> resolveTokens(long buildId, UUID filterTaskId) {
    List<UUID> all = stores.taskQueue().logTokensForBuild(buildId);
    if (filterTaskId == null) return all;
    return all.contains(filterTaskId) ? List.of(filterTaskId) : List.of();
  }
}

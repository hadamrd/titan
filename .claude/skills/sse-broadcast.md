# Skill: Streaming SSE events from server-side code

## Pattern

Titan pushes real-time updates to the UI over Server-Sent Events using a
**Jakarta REST SSE endpoint** on `titan-server` (Quarkus). There is no global
event hub and no plugin-host singleton — an SSE endpoint is an ordinary
`@ApplicationScoped` JAX-RS resource that produces `MediaType.SERVER_SENT_EVENTS`,
gets `Sse` + `SseEventSink` injected, and streams events to the connected
client. The canonical implementation is `BuildLogsSse`
(`titan-server/src/main/java/io/adaptiq/titan/api/BuildLogsSse.java`), which
streams a build's live log lines.

The shape is **poll-the-store + push-to-sink on a virtual thread**: the JAX-RS
request thread is freed immediately, a virtual thread polls the relevant store
table on an interval, and each new row is sent as a named SSE event. The stream
self-terminates when the underlying entity reaches a terminal state or the
client disconnects.

## Worked example

```java
@Path("/api/v1/builds/{buildId}/logs")
@ApplicationScoped
public class BuildLogsSse {

  private final TitanStores stores;

  // Sse is a JAX-RS context object; @Inject is the standard way to obtain it in
  // a CDI bean — this is NOT field injection of a business dependency.
  @Inject Sse sse;

  BuildLogsSse(TitanStores stores) { // constructor injection of dependencies
    this.stores = stores;
  }

  @GET
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RolesAllowed({Roles.READ_JOB, Roles.ADMIN})
  public void streamLogs(@PathParam("buildId") String buildIdStr,
                         @Context SseEventSink sink) {
    long buildId = Long.parseLong(buildIdStr);
    if (stores.builds().findById(buildId).isEmpty()) {
      try (sink) {
        sink.send(sse.newEventBuilder().name("error").data("not found").build());
      }
      return;
    }
    // Free the JAX-RS thread immediately; poll on a virtual thread.
    Thread.ofVirtual().name("sse-logs-build-" + buildId)
        .start(() -> pollAndStream(buildId, sink));
  }

  private void pollAndStream(long buildId, SseEventSink sink) {
    long cursor = 0L;
    try {
      while (!sink.isClosed()) {
        for (LogRow row : newRowsSince(buildId, cursor)) {
          sink.send(sse.newEventBuilder().name("log").data(row.data).build());
          cursor = row.id;
        }
        var build = stores.builds().findById(buildId).orElse(null);
        if (build != null && TERMINAL_STATUSES.contains(build.status)) {
          sink.send(sse.newEventBuilder().name("done").data(build.status).build());
          sink.close();
          return;
        }
        Thread.sleep(POLL_INTERVAL_MS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      if (!sink.isClosed()) sink.close();
    }
  }
}
```

## Key points

1. **Constructor-inject business dependencies** (`TitanStores`). Only the JAX-RS
   `Sse` context object is `@Inject`-ed as a field — it is infrastructure, not a
   CDI business bean.
2. **`@Produces(MediaType.SERVER_SENT_EVENTS)`** + `@Context SseEventSink` is the
   whole contract. No broadcaster, no registry, no singleton.
3. **Poll on a virtual thread**, never on the JAX-RS request thread — `Thread.ofVirtual()`
   so the request returns immediately and the connection stays open for streaming.
4. **Name every event** (`event: log`, `event: done`, `event: error`) so the UI's
   `EventSource` can dispatch per type.
5. **Always close the sink** in a `finally`, and **terminate** the stream when the
   entity reaches a terminal status or the client disconnects (`sink.isClosed()`).
   A leaked sink is a leaked virtual thread.
6. **Guard with `@RolesAllowed`** — SSE endpoints carry data and must be RBAC-gated
   exactly like any other read endpoint.

## Cross-references

- `titan-server/src/main/java/io/adaptiq/titan/api/BuildLogsSse.java` — the reference SSE endpoint
- The UI side consumes it via `EventSource` in `titan-ui/`

package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.ActivityItemDto;
import io.adaptiq.titan.api.dto.ActivityPage;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.store.ActivityDao;
import io.adaptiq.titan.store.TitanStores;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Jakarta REST resource: {@code GET /api/v1/activity} — the Overview-page activity feed (closes
 * #304).
 *
 * <p><strong>v1 source.</strong> No dedicated events table yet — each terminal-state row in {@code
 * titan.builds} ({@code SUCCESS / FAILED / CANCELLED}) becomes one feed item with {@code type =
 * "build.terminal"}. Forward-compat: the discriminator is on the wire from day one so future event
 * sources slot in without changing JSON shape.
 *
 * <p><strong>Pagination.</strong> {@code ?limit=N&before=<cursor>}. {@code limit} defaults to 25
 * and is hard-capped at 100. {@code before} is the opaque cursor returned as {@code nextCursor} on
 * the previous page — base64 of the last item's {@code finished_at} epoch-ms. The query orders by
 * {@code finished_at DESC} and the cursor enforces strict {@code <}, so identical timestamps don't
 * loop.
 *
 * <p>{@code nextCursor} is {@code null} when the page is the last one (fewer rows returned than
 * {@code limit}). An invalid {@code before} cursor returns 400.
 *
 * <p><strong>RBAC.</strong> Same guard as {@link StatsApi}: {@code READ_JOB}, {@code TRIGGER_BUILD}
 * or {@code ADMIN}. Missing/invalid bearer → 401 via the OIDC filter; mismatched role → 403.
 */
@Path("/api/v1/activity")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class ActivityApi {

  /** Server-side hard cap on a single page. */
  static final int MAX_LIMIT = 100;

  /** Default page size when the client omits {@code limit}. */
  static final int DEFAULT_LIMIT = 25;

  private final TitanStores stores;

  ActivityApi(TitanStores stores) {
    this.stores = stores;
  }

  @GET
  @RolesAllowed({Roles.READ_JOB, Roles.TRIGGER_BUILD, Roles.ADMIN})
  public ActivityPage list(
      @QueryParam("limit") @DefaultValue("25") int limit, @QueryParam("before") String before) {
    int cappedLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
    Instant beforeTs = decodeCursor(before);

    List<ActivityDao.ActivityRow> rows =
        stores.activity().recentTerminalBuilds(cappedLimit, beforeTs);

    List<ActivityItemDto> items = rows.stream().map(ActivityApi::toDto).toList();
    String nextCursor =
        items.size() < cappedLimit ? null : encodeCursor(rows.get(rows.size() - 1).finishedAt());
    return new ActivityPage(items, nextCursor);
  }

  private static ActivityItemDto toDto(ActivityDao.ActivityRow row) {
    long durationMs = row.durationMs() == null ? 0L : row.durationMs();
    return new ActivityItemDto(
        "build-" + row.id(),
        "build.terminal",
        row.finishedAt(),
        row.jobFullName(),
        row.id(),
        row.status(),
        durationMs);
  }

  /** Base64-encode {@code finishedAt} epoch-ms into the opaque cursor. */
  static String encodeCursor(Instant ts) {
    String raw = Long.toString(ts.toEpochMilli());
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
  }

  /**
   * Inverse of {@link #encodeCursor}. {@code null} / blank → {@code null} (= "page 1"). A non-blank
   * but un-parseable cursor is a client error → 400.
   */
  static Instant decodeCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(cursor);
      long epochMs = Long.parseLong(new String(decoded, StandardCharsets.US_ASCII));
      return Instant.ofEpochMilli(epochMs);
    } catch (IllegalArgumentException e) {
      throw new ApiBadRequestException("invalid 'before' cursor");
    }
  }
}

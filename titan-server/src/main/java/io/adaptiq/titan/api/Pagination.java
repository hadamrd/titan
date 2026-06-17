package io.adaptiq.titan.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Cursor-based pagination primitives shared by the list endpoints {@code /api/v1/builds}, {@code
 * /api/v1/jobs}, {@code /api/v1/audit} (closes #1098).
 *
 * <p>A cursor is an opaque, URL-safe base64 token that encodes {@code (epochMilli, id)} of the LAST
 * item on the previous page. The encoding is intentionally stable across paging: even if rows are
 * inserted into the middle of the stream while a client is paging through it, the next page is
 * defined as "every row strictly older than the cursor row" — not "skip N rows from the head" —
 * which would shift on every insert (closes ticket's "middle-of-stream new build doesn't shift a
 * cursor" criterion).
 *
 * <p>Wire format: {@code base64url("<epochMilli>:<id>")} with no padding (RFC 4648 §5). URL-safe so
 * a caller can paste it into a query string without percent-encoding. Decoder rejects anything
 * malformed with {@link ApiBadRequestException} (HTTP 400) and a structured message — never
 * silently degrading to "first page" which would make the bug invisible.
 *
 * <p>The cursor is NOT opaque from the server's point of view (we encode + decode the timestamp +
 * id) but IS opaque from the client's point of view — callers MUST treat it as a black box. A
 * future change to widen the cursor (e.g. include a tie-break column) is a back-compat field
 * addition; the client never has to know.
 */
public final class Pagination {

  private Pagination() {}

  /**
   * Parsed cursor — the (timestamp, id) of the last item on the previous page. The next page is
   * defined as "rows whose (sort_column, id) is strictly less than (ts, id)" — paired with the
   * endpoint's default sort (newest-first by occurredAt / queuedAt / createdAt, tie-broken by id
   * DESC). Holds primitives to make the SQL bind site trivial.
   */
  public record Cursor(@NonNull Instant ts, long id) {}

  /**
   * Encode a cursor to a URL-safe base64 token. The result is safe to round-trip through {@code
   * ?after=} without percent-encoding. Returns {@code null} when {@code cursor} is {@code null} so
   * the caller can write {@code next = encode(lastItemCursor)} and have a null-on-end-of-stream
   * signal.
   */
  @Nullable
  public static String encode(@Nullable Cursor cursor) {
    if (cursor == null) {
      return null;
    }
    String raw = cursor.ts().toEpochMilli() + ":" + cursor.id();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Decode a URL-safe base64 cursor. Null/blank input yields {@code null} (no cursor = first page).
   * Any malformed input — non-base64, missing the {@code ":"} separator, non-numeric components,
   * negative ids — surfaces as an HTTP 400 via {@link ApiBadRequestException} with a structured
   * message naming the failing field. We deliberately fail loud rather than silently treating a
   * mangled cursor as "first page" because a paging client that lost its place to a typo would
   * otherwise loop forever on page 1 without noticing.
   *
   * <p>The decoder is strict on length too: an {@code epochMillis} {@code &lt; 0} or {@code id
   * &lt;= 0} is rejected — both signal a fabricated or corrupted cursor (real ids start at 1; real
   * timestamps are positive post-1970).
   */
  @Nullable
  public static Cursor decode(@Nullable String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(trimmed);
    } catch (IllegalArgumentException e) {
      throw new ApiBadRequestException(
          "cursor 'after' is not valid URL-safe base64; got '" + truncate(raw) + "'");
    }
    String body = new String(decoded, StandardCharsets.UTF_8);
    int colon = body.indexOf(':');
    if (colon <= 0 || colon == body.length() - 1) {
      throw new ApiBadRequestException(
          "cursor 'after' must decode to '<epochMillis>:<id>'; got '" + truncate(body) + "'");
    }
    long ts;
    long id;
    try {
      ts = Long.parseLong(body.substring(0, colon));
      id = Long.parseLong(body.substring(colon + 1));
    } catch (NumberFormatException e) {
      throw new ApiBadRequestException(
          "cursor 'after' fields must be integers; got '" + truncate(body) + "'");
    }
    if (ts < 0) {
      throw new ApiBadRequestException("cursor 'after' epochMillis must be >= 0; got " + ts);
    }
    if (id <= 0) {
      throw new ApiBadRequestException("cursor 'after' id must be > 0; got " + id);
    }
    return new Cursor(Instant.ofEpochMilli(ts), id);
  }

  /**
   * Cap the human-facing fragment in a 400 message so a multi-kilobyte attacker-controlled cursor
   * never balloons the response or log line.
   */
  private static String truncate(String s) {
    if (s == null) {
      return "";
    }
    if (s.length() <= 64) {
      return s;
    }
    return s.substring(0, 64) + "...";
  }

  /**
   * Default page-size cap shared across endpoints. Matches the ticket's {@code limit=<1..200,
   * default 50>}.
   */
  public static final int DEFAULT_LIMIT = 50;

  /** Hard cap on {@code limit}. */
  public static final int MAX_LIMIT = 200;

  /**
   * Clamp a caller-supplied {@code limit} into {@code [1, MAX_LIMIT]}. Zero / negative input
   * degenerates to {@link #DEFAULT_LIMIT} so a malformed integer (Quarkus binds missing/blank as
   * the default value, but a hostile {@code ?limit=-1} or {@code ?limit=0} would otherwise return
   * no rows AND no cursor — a paging client would loop forever).
   */
  public static int clampLimit(int raw) {
    if (raw <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(raw, MAX_LIMIT);
  }
}

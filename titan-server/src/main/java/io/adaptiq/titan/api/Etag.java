package io.adaptiq.titan.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Conditional-GET (HTTP ETag / If-None-Match) helper for the Titan API.
 *
 * <p>Issue #1099 — the UI polls {@code /api/v1/builds/&lt;id&gt;} every few seconds while a build
 * streams. Most polls return identical state; emitting an {@code ETag} and answering 304 to a
 * matching {@code If-None-Match} cuts both bandwidth and latency dramatically.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li>{@link #compute(byte[])} is a pure SHA-256 hex digest — deterministic for identical bytes,
 *       independent of system state, no salt.
 *   <li>{@link #respond(HttpHeaders, Object)} is the one-stop helper handlers use: serialize the
 *       DTO once, hash, compare to the request's {@code If-None-Match}, and either return 304 with
 *       no body or 200 with the body + the {@code ETag} header set.
 *   <li>ETag values are <em>weak</em> ({@code W/"…"}) — Quarkus / RESTEasy may re-serialize the
 *       entity through MessageBodyWriters (whitespace, key ordering) and weak validators correctly
 *       allow byte-inequivalent but semantically equivalent representations.
 * </ul>
 *
 * <p>Stateless, no instance state — singleton {@link ObjectMapper} reused across calls.
 */
public final class Etag {

  /** Weak-validator prefix per RFC 7232 §2.3. */
  static final String WEAK_PREFIX = "W/";

  // Must register the JSR-310 module: the DTOs we hash (BuildDto, JobDetailDto, …) carry
  // java.time.Instant fields (e.g. BuildDto.queuedAt). A bare ObjectMapper throws
  // InvalidDefinitionException on those, which would surface as a 500 on every
  // GET /api/v1/builds/{id} and /jobs/{id} the moment the hash is computed. The hash only needs
  // to be deterministic, so the on-the-wire date format (handled by Quarkus' own mapper) is
  // irrelevant here — registering the module is enough.
  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());

  private Etag() {}

  /**
   * Compute the SHA-256 hex digest of {@code bytes}. Deterministic; identical input ⇒ identical
   * output. Returns lowercase hex, no separator.
   */
  public static String compute(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes");
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(bytes);
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandatory in every JRE — this is unreachable.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /**
   * Build the canonical weak ETag header value (including surrounding double quotes and {@code W/}
   * prefix) for {@code hash}.
   */
  public static String format(String hash) {
    return WEAK_PREFIX + "\"" + hash + "\"";
  }

  /**
   * Conditional-GET helper. Serializes {@code dto} once, computes its weak ETag, and:
   *
   * <ul>
   *   <li>If the client's {@code If-None-Match} matches the computed ETag, returns {@code 304 Not
   *       Modified} with an empty body and the {@code ETag} header echoed.
   *   <li>Otherwise returns {@code 200 OK} with the DTO as the entity and the {@code ETag} header
   *       set.
   * </ul>
   *
   * <p>An {@code If-None-Match: *} also produces 304 — this matches RFC 7232 §3.2 ("if the listed
   * value is {@code *}, the condition is false if the origin server has a current representation").
   *
   * <p>Multiple comma-separated values in {@code If-None-Match} are honored; any match wins.
   */
  public static Response respond(HttpHeaders headers, Object dto) {
    Objects.requireNonNull(dto, "dto");
    byte[] body = serialize(dto);
    String hash = compute(body);
    String etag = format(hash);

    String ifNoneMatch =
        headers == null ? null : headers.getHeaderString(HttpHeaders.IF_NONE_MATCH);
    if (matches(ifNoneMatch, etag)) {
      return Response.notModified().header(HttpHeaders.ETAG, etag).build();
    }
    return Response.ok(dto).header(HttpHeaders.ETAG, etag).build();
  }

  static boolean matches(String ifNoneMatchHeader, String etag) {
    if (ifNoneMatchHeader == null || ifNoneMatchHeader.isBlank()) {
      return false;
    }
    String trimmed = ifNoneMatchHeader.trim();
    if ("*".equals(trimmed)) {
      return true;
    }
    // Per RFC 7232 §2.3.2 weak comparison strips the W/ prefix before comparing opaque-tags.
    String normalizedEtag = stripWeakPrefix(etag);
    for (String candidate : trimmed.split(",")) {
      String c = stripWeakPrefix(candidate.trim());
      if (!c.isEmpty() && c.equals(normalizedEtag)) {
        return true;
      }
    }
    return false;
  }

  private static String stripWeakPrefix(String s) {
    if (s.startsWith(WEAK_PREFIX)) {
      return s.substring(WEAK_PREFIX.length());
    }
    return s;
  }

  private static byte[] serialize(Object dto) {
    try {
      return MAPPER.writeValueAsBytes(dto);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("ETag serialization failed for " + dto.getClass(), e);
    }
  }
}

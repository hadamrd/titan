package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Etag} — issue #1099.
 *
 * <p>The contract: ETag is a deterministic SHA-256 of the JSON-serialized DTO bytes; the helper
 * returns 304 with an empty body when {@code If-None-Match} matches, and 200 + the body otherwise.
 *
 * <p>Adversarial cases covered: null bytes, blank header, wildcard, weak-vs-strong comparison,
 * multi-value {@code If-None-Match}, payload mutation flips the hash, null DTO.
 *
 * <p>No Mockito on this module — uses a minimal hand-rolled {@link HttpHeaders} stub so the test
 * stays a plain unit test (no Quarkus boot).
 */
class EtagTest {

  // ── compute ────────────────────────────────────────────────────────────────

  @Test
  void compute_isDeterministicForIdenticalBytes() {
    byte[] payload = "{\"id\":1,\"status\":\"RUNNING\"}".getBytes(StandardCharsets.UTF_8);
    String a = Etag.compute(payload);
    String b = Etag.compute(payload);
    assertEquals(a, b, "SHA-256 must be deterministic");
    assertEquals(64, a.length(), "SHA-256 hex must be 64 chars");
  }

  @Test
  void compute_differsForDifferentBytes() {
    String a = Etag.compute("{\"id\":1}".getBytes(StandardCharsets.UTF_8));
    String b = Etag.compute("{\"id\":2}".getBytes(StandardCharsets.UTF_8));
    assertNotEquals(a, b);
  }

  @Test
  void compute_emptyPayloadIsKnownEmptyHash() {
    // sha256("") is a well-known constant — sanity-check the algorithm.
    assertEquals(
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        Etag.compute(new byte[0]));
  }

  @Test
  void compute_nullBytesThrows() {
    assertThrows(NullPointerException.class, () -> Etag.compute(null));
  }

  // ── format ─────────────────────────────────────────────────────────────────

  @Test
  void format_wrapsWithWeakPrefixAndQuotes() {
    assertEquals("W/\"abc123\"", Etag.format("abc123"));
  }

  // ── matches ────────────────────────────────────────────────────────────────

  @Test
  void matches_nullHeaderIsFalse() {
    assertFalse(Etag.matches(null, "W/\"abc\""));
  }

  @Test
  void matches_blankHeaderIsFalse() {
    assertFalse(Etag.matches("   ", "W/\"abc\""));
  }

  @Test
  void matches_wildcardAlwaysMatches() {
    assertTrue(Etag.matches("*", "W/\"abc\""));
  }

  @Test
  void matches_weakAndStrongCompareEqual() {
    // Per RFC 7232 §2.3.2 weak comparison strips W/ prefix before comparing opaque-tags.
    assertTrue(Etag.matches("\"abc\"", "W/\"abc\""));
    assertTrue(Etag.matches("W/\"abc\"", "\"abc\""));
  }

  @Test
  void matches_multiValueAnyMatchWins() {
    assertTrue(Etag.matches("W/\"old\", W/\"abc\", W/\"older\"", "W/\"abc\""));
  }

  @Test
  void matches_mismatchIsFalse() {
    assertFalse(Etag.matches("W/\"different\"", "W/\"abc\""));
  }

  // ── respond ────────────────────────────────────────────────────────────────

  @Test
  void respond_no_if_none_match_returns200AndEtag() {
    Response r = Etag.respond(stubHeaders(null), Map.of("k", "v"));
    assertEquals(200, r.getStatus());
    String etag = r.getHeaderString(HttpHeaders.ETAG);
    assertNotNull(etag);
    assertTrue(etag.startsWith("W/\""));
    assertNotNull(r.getEntity());
  }

  @Test
  void respond_matchingIfNoneMatchReturns304AndNoBody() {
    Map<String, String> dto = Map.of("k", "v");

    // First call to discover the etag.
    String etag = Etag.respond(stubHeaders(null), dto).getHeaderString(HttpHeaders.ETAG);
    assertNotNull(etag);

    Response r = Etag.respond(stubHeaders(etag), dto);
    assertEquals(304, r.getStatus(), "matching If-None-Match → 304");
    assertNull(r.getEntity(), "304 must have empty body");
    assertEquals(etag, r.getHeaderString(HttpHeaders.ETAG), "304 echoes the ETag");
  }

  @Test
  void respond_mismatchedIfNoneMatchReturns200() {
    Response r = Etag.respond(stubHeaders("W/\"stale\""), Map.of("k", "v"));
    assertEquals(200, r.getStatus());
    assertNotNull(r.getEntity());
  }

  @Test
  void respond_payloadMutationFlipsEtag() {
    String etagA =
        Etag.respond(stubHeaders(null), Map.of("status", "RUNNING"))
            .getHeaderString(HttpHeaders.ETAG);
    String etagB =
        Etag.respond(stubHeaders(null), Map.of("status", "SUCCESS"))
            .getHeaderString(HttpHeaders.ETAG);
    assertNotEquals(etagA, etagB, "different payloads → different ETag");
  }

  @Test
  void respond_nullDtoThrows() {
    assertThrows(NullPointerException.class, () -> Etag.respond(stubHeaders(null), null));
  }

  @Test
  void respond_nullHeadersStillReturns200() {
    // Defensive: jakarta @Context might inject nothing in adversarial test paths.
    Response r = Etag.respond(null, Map.of("k", "v"));
    assertEquals(200, r.getStatus());
    assertNotNull(r.getHeaderString(HttpHeaders.ETAG));
  }

  @Test
  void respond_dtoWithJavaTimeInstant_serializesWithoutThrowing() {
    // Regression: every real detail DTO (BuildDto, JobDetailDto, …) carries java.time.Instant
    // fields. A bare ObjectMapper throws InvalidDefinitionException on those, which surfaced as a
    // 500 on GET /api/v1/builds/{id} and /jobs/{id} once the ETag hash was computed. Etag's mapper
    // must register the JSR-310 module — assert respond() now handles an Instant-bearing payload.
    Map<String, Object> dto = Map.of("queuedAt", Instant.parse("2026-06-04T10:15:30Z"));
    Response r = Etag.respond(stubHeaders(null), dto);
    assertEquals(200, r.getStatus(), "an Instant-bearing DTO must not blow up ETag serialization");
    assertNotNull(r.getHeaderString(HttpHeaders.ETAG));
    assertNotNull(r.getEntity());
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static HttpHeaders stubHeaders(String ifNoneMatch) {
    return new StubHeaders(ifNoneMatch);
  }

  /**
   * Minimal {@link HttpHeaders} that only honours {@code getHeaderString(IF_NONE_MATCH)} — the only
   * method {@link Etag#respond} touches. Everything else throws so any future contract change shows
   * up as a test failure rather than a silent default.
   */
  private static final class StubHeaders implements HttpHeaders {
    private final String ifNoneMatch;

    StubHeaders(String ifNoneMatch) {
      this.ifNoneMatch = ifNoneMatch;
    }

    @Override
    public String getHeaderString(String name) {
      if (HttpHeaders.IF_NONE_MATCH.equalsIgnoreCase(name)) {
        return ifNoneMatch;
      }
      return null;
    }

    @Override
    public List<String> getRequestHeader(String name) {
      throw new UnsupportedOperationException();
    }

    @Override
    public MultivaluedMap<String, String> getRequestHeaders() {
      return new MultivaluedHashMap<>();
    }

    @Override
    public List<MediaType> getAcceptableMediaTypes() {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Locale> getAcceptableLanguages() {
      throw new UnsupportedOperationException();
    }

    @Override
    public MediaType getMediaType() {
      return null;
    }

    @Override
    public Locale getLanguage() {
      return null;
    }

    @Override
    public Map<String, Cookie> getCookies() {
      return Map.of();
    }

    @Override
    public Date getDate() {
      return null;
    }

    @Override
    public int getLength() {
      return -1;
    }
  }
}

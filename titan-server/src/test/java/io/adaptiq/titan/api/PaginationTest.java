package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the cursor primitives backing {@code /api/v1/{builds,jobs,audit}} pagination
 * (closes #1098).
 *
 * <p>Adversarial coverage is the point: the encode/decode pair is the entire surface area a paging
 * client trusts. A silent decode-bug would cause a client to loop on page 1 forever or 500 on a
 * hostile cursor.
 */
class PaginationTest {

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void encodeDecode_roundTripsTimestampAndId() {
    Instant ts = Instant.parse("2026-05-01T12:34:56.789Z");
    Pagination.Cursor original = new Pagination.Cursor(ts, 4242L);

    String encoded = Pagination.encode(original);
    assertNotNull(encoded);
    Pagination.Cursor decoded = Pagination.decode(encoded);

    assertNotNull(decoded);
    assertEquals(ts, decoded.ts());
    assertEquals(4242L, decoded.id());
  }

  @Test
  void encoded_isUrlSafeBase64NoPadding() {
    // Pick a value whose base64 encoding includes the characters that differ between standard
    // and URL-safe base64 (+ vs -, / vs _). Use Long.MAX_VALUE for id and a tail-microsecond
    // timestamp so the encoded body is long enough to provoke padding without explicit pad
    // chars in the output.
    Pagination.Cursor c =
        new Pagination.Cursor(Instant.ofEpochMilli(1700000000123L), Long.MAX_VALUE);
    String encoded = Pagination.encode(c);

    assertNotNull(encoded);
    // URL-safe: no '+', no '/', and no padding '=' tail.
    assertTrue(encoded.indexOf('+') < 0, "URL-safe alphabet must not contain '+': " + encoded);
    assertTrue(encoded.indexOf('/') < 0, "URL-safe alphabet must not contain '/': " + encoded);
    assertTrue(encoded.indexOf('=') < 0, "URL-safe encoder uses withoutPadding(): " + encoded);

    // And it round-trips.
    Pagination.Cursor back = Pagination.decode(encoded);
    assertEquals(c, back);
  }

  @Test
  void encode_nullCursor_returnsNull() {
    assertNull(Pagination.encode(null));
  }

  @Test
  void decode_nullOrBlank_returnsNull() {
    assertNull(Pagination.decode(null));
    assertNull(Pagination.decode(""));
    assertNull(Pagination.decode("   "));
  }

  // ── adversarial / sad paths ──────────────────────────────────────────────

  @Test
  void decode_garbageBase64_throws400() {
    ApiBadRequestException e =
        assertThrows(ApiBadRequestException.class, () -> Pagination.decode("!!!not-base64!!!"));
    assertTrue(
        e.getMessage().toLowerCase().contains("base64"),
        "message should name the malformed-base64 failure mode: " + e.getMessage());
  }

  @Test
  void decode_validBase64_butMissingColon_throws400() {
    String junk =
        Base64.getUrlEncoder().withoutPadding().encodeToString("nothing-to-see-here".getBytes());
    ApiBadRequestException e =
        assertThrows(ApiBadRequestException.class, () -> Pagination.decode(junk));
    assertTrue(
        e.getMessage().contains("epochMillis"),
        "message should describe the expected shape: " + e.getMessage());
  }

  @Test
  void decode_nonNumericComponents_throws400() {
    String junk = Base64.getUrlEncoder().withoutPadding().encodeToString("abc:xyz".getBytes());
    assertThrows(ApiBadRequestException.class, () -> Pagination.decode(junk));
  }

  @Test
  void decode_negativeTimestamp_throws400() {
    String junk = Base64.getUrlEncoder().withoutPadding().encodeToString("-1:42".getBytes());
    ApiBadRequestException e =
        assertThrows(ApiBadRequestException.class, () -> Pagination.decode(junk));
    assertTrue(e.getMessage().contains("epochMillis"), e.getMessage());
  }

  @Test
  void decode_zeroOrNegativeId_throws400() {
    String zero = Base64.getUrlEncoder().withoutPadding().encodeToString("0:0".getBytes());
    assertThrows(ApiBadRequestException.class, () -> Pagination.decode(zero));

    String neg = Base64.getUrlEncoder().withoutPadding().encodeToString("0:-1".getBytes());
    assertThrows(ApiBadRequestException.class, () -> Pagination.decode(neg));
  }

  @Test
  void decode_trailingColon_throws400() {
    String junk = Base64.getUrlEncoder().withoutPadding().encodeToString("12345:".getBytes());
    assertThrows(ApiBadRequestException.class, () -> Pagination.decode(junk));
  }

  @Test
  void decode_message_doesNotEchoMegabytePayload() {
    // Hostile caller sends a multi-KB cursor — the 400 message must NOT echo the whole thing
    // (DoS via log amplification). The truncate() cap is 64 chars + ellipsis.
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 4096; i++) {
      big.append("AAA");
    }
    String hostile = big.toString();
    ApiBadRequestException e =
        assertThrows(ApiBadRequestException.class, () -> Pagination.decode(hostile));
    assertTrue(
        e.getMessage().length() < 200,
        "400 message must not echo the full hostile payload: len=" + e.getMessage().length());
  }

  // ── limit clamp ──────────────────────────────────────────────────────────

  @Test
  void clampLimit_keepsValidValues_clampsAtMax_defaultsOnZeroOrNegative() {
    assertEquals(50, Pagination.clampLimit(50));
    assertEquals(1, Pagination.clampLimit(1));
    assertEquals(200, Pagination.clampLimit(200));
    assertEquals(200, Pagination.clampLimit(9999));
    assertEquals(Pagination.DEFAULT_LIMIT, Pagination.clampLimit(0));
    assertEquals(Pagination.DEFAULT_LIMIT, Pagination.clampLimit(-1));
  }
}

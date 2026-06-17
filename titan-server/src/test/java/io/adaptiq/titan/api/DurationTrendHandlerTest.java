package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.api.dto.DurationTrendPointDto;
import io.adaptiq.titan.store.DurationTrendDao;
import io.adaptiq.titan.store.DurationTrendDao.DurationTrendRow;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DurationTrendHandler}'s {@code n} validation and the DTO's ms→s conversion
 * (closes #1096). The SQL window itself is exercised against real Postgres in {@code
 * DurationTrendApiIT}; these pin the parts that don't need a database.
 */
class DurationTrendHandlerTest {

  // ── validateN ─────────────────────────────────────────────────────────────

  @Test
  void validateN_acceptsPositiveInRange() {
    assertEquals(1, DurationTrendHandler.validateN(1));
    assertEquals(30, DurationTrendHandler.validateN(30));
    assertEquals(
        DurationTrendDao.MAX_BUILDS, DurationTrendHandler.validateN(DurationTrendDao.MAX_BUILDS));
  }

  @Test
  void validateN_zeroOrNegative_throws400() {
    ApiBadRequestException ex0 =
        assertThrows(ApiBadRequestException.class, () -> DurationTrendHandler.validateN(0));
    assertTrue(ex0.getMessage().contains("'n'"), "error message names the offending param");
    assertThrows(ApiBadRequestException.class, () -> DurationTrendHandler.validateN(-1));
    assertThrows(ApiBadRequestException.class, () -> DurationTrendHandler.validateN(-9999));
  }

  @Test
  void validateN_aboveMax_throws400() {
    int over = DurationTrendDao.MAX_BUILDS + 1;
    ApiBadRequestException ex =
        assertThrows(ApiBadRequestException.class, () -> DurationTrendHandler.validateN(over));
    assertTrue(
        ex.getMessage().contains(Integer.toString(DurationTrendDao.MAX_BUILDS)),
        "error message reports the cap");
  }

  // ── DTO ms → s conversion ───────────────────────────────────────────────────

  @Test
  void from_convertsMillisToSecondsPreservingSubSecond() {
    Instant ts = Instant.parse("2026-06-09T10:00:00Z");
    DurationTrendPointDto dto =
        DurationTrendPointDto.from(new DurationTrendRow(ts, 1500L, "SUCCESS"));
    assertEquals(ts, dto.ts());
    assertEquals(1.5, dto.durationS(), 1e-9, "1500ms → 1.5s, no precision loss");
    assertEquals("SUCCESS", dto.status());
  }

  @Test
  void from_zeroDurationIsZeroSeconds() {
    DurationTrendPointDto dto =
        DurationTrendPointDto.from(new DurationTrendRow(Instant.EPOCH, 0L, "FAILED"));
    assertEquals(0.0, dto.durationS(), 1e-9);
    assertEquals("FAILED", dto.status(), "status carried through verbatim");
  }
}

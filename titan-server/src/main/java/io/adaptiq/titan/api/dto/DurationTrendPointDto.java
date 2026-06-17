package io.adaptiq.titan.api.dto;

import io.adaptiq.titan.store.DurationTrendDao.DurationTrendRow;
import java.time.Instant;

/**
 * One point of a job's build-duration trend — wire shape for {@code GET
 * /api/v1/jobs/{jobId}/duration-trend?n=30} (closes #1096).
 *
 * <ul>
 *   <li>{@code ts} — when the build finished (ISO-8601). Used as the x-axis ordering key; the list
 *       is server-ordered oldest→newest so the client never re-sorts.
 *   <li>{@code durationS} — wall-clock build duration in <strong>seconds</strong> (the engine
 *       stores milliseconds; we divide by 1000 at the boundary because the sparkline's "30d"
 *       framing reads in seconds, not ms). Carries sub-second precision as a double.
 *   <li>{@code status} — the build's terminal status ({@code SUCCESS / FAILED / UNSTABLE}) so the
 *       UI can annotate pass vs fail per point.
 * </ul>
 *
 * <p>Field names are camelCase ({@code durationS}) to match every other DTO + the OpenAPI-derived
 * TypeScript types in this codebase; the issue's {@code duration_s} sketch is illustrative.
 */
public record DurationTrendPointDto(Instant ts, double durationS, String status) {

  /** Map a DAO row to the wire DTO, converting milliseconds → seconds. */
  public static DurationTrendPointDto from(DurationTrendRow row) {
    return new DurationTrendPointDto(row.ts(), row.durationMs() / 1000.0, row.status());
  }
}

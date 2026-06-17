package io.adaptiq.titan.api.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Response for {@code GET /api/v1/jobs/{jobId}/stats} (closes #775).
 *
 * <p>Per-job analytics over a sliding time window — used by the {@code /jobs/{id}} Stats panel to
 * answer "is this job getting flakier?" and "is its duration degrading?". Numbers are computed over
 * the {@code titan.builds} table; all timestamps are interpreted in UTC.
 *
 * <ul>
 *   <li>{@code totalBuilds} — every build queued in the window, including still-RUNNING ones.
 *   <li>{@code failedBuilds} — subset of {@code totalBuilds} with terminal status {@code FAILED}.
 *   <li>{@code failureRate} — {@code failedBuilds / totalBuilds}, in {@code [0.0, 1.0]}. Zero when
 *       there are no builds in the window (UI treats zero-builds as an empty placeholder, not as
 *       "healthy").
 *   <li>{@code p50DurationMs} / {@code p95DurationMs} — duration percentiles computed in PostgreSQL
 *       via {@code percentile_cont} <strong>only over completed builds</strong> (status in {@code
 *       SUCCESS, FAILED, ABORTED, UNSTABLE} AND {@code duration_ms IS NOT NULL}). Returns {@code
 *       null} (NOT zero) when no completed build exists in the window — zero would be
 *       indistinguishable from "every build was instant" and would lie on the UI tile.
 *   <li>{@code dailyBuckets} — exactly {@code windowDays} entries, one per UTC calendar day,
 *       oldest-first. Days with no builds are present with zero counts so the sparkline's x-axis is
 *       dense (the UI shouldn't have to guess which days are missing).
 *   <li>{@code window} — echoes the request param ({@code 7d|30d|90d}) so the UI doesn't have to
 *       remember what it asked for.
 * </ul>
 */
public record JobStatsDto(
    long totalBuilds,
    long failedBuilds,
    double failureRate,
    Long p50DurationMs,
    Long p95DurationMs,
    List<DailyBucketDto> dailyBuckets,
    String window) {

  /**
   * One row of the daily-bucket sparkline. {@code day} is an ISO-8601 calendar date (no time, no
   * zone); the server bucketed by {@code DATE(queued_at AT TIME ZONE 'UTC')} so the UI doesn't have
   * to make assumptions about the server's local time.
   */
  public record DailyBucketDto(LocalDate day, long totalBuilds, long failedBuilds) {}
}

package io.adaptiq.titan.api.dto;

import java.util.List;

/**
 * Response for {@code GET /api/v1/jobs/{jobId}/stage-timings?n=30} (closes #1095).
 *
 * <p>Per-stage duration percentiles + per-build samples over the last {@code n} finished builds —
 * powers the "Stage Timing" panel on the pipeline detail page. SREs read this to answer "is this
 * build slower than usual?" in one glance.
 *
 * <ul>
 *   <li>{@code n} — the requested window (clamped server-side to {@code [1, 100]}).
 *   <li>{@code buildsConsidered} — how many finished builds the percentile actually saw. May be
 *       less than {@code n} for a fresh job. Zero means "not enough history yet" — the UI renders
 *       its empty state for this case.
 *   <li>{@code stages} — one entry per distinct stage display-name with at least one completed
 *       sample in the window. Ordered server-side by descending p50 so the slowest stages float to
 *       the top of the UI without any client-side sorting.
 * </ul>
 *
 * <p>Empty contract: a fresh job (zero finished builds) returns {@code buildsConsidered=0} and
 * {@code stages=[]}. The endpoint never 404s on a job that exists but has no history — that would
 * conflate "job missing" with "job idle".
 */
public record StageTimingsDto(int n, long buildsConsidered, List<StageTimingDto> stages) {

  /**
   * One stage's distribution. Percentile fields are {@link Long} (not primitive) so a future
   * sample-count of zero can be expressed as {@code null} without misrendering as {@code 0 ms}. In
   * practice the DAO omits zero-sample stages, but the wire shape stays honest.
   *
   * <p>{@code samples} is ordered oldest→newest (by build_number ascending). Each sample carries
   * its {@code buildId} so the UI can navigate to that build's detail on click — closes the
   * acceptance criterion "click on a bar → navigate to the build-detail of that build".
   */
  public record StageTimingDto(
      String stageName,
      long sampleCount,
      Long p50Ms,
      Long p95Ms,
      Long p99Ms,
      Long minMs,
      Long maxMs,
      List<StageSampleDto> samples) {}

  /** One historical sample of a stage's duration. */
  public record StageSampleDto(long buildId, int buildNumber, long durationMs, String status) {}
}

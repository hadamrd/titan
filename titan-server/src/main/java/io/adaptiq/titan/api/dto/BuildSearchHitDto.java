package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.BuildSearchHitRow;
import java.time.Instant;

/**
 * API response DTO for a single full-text-search hit ({@code GET /api/v1/builds/search}, #1083).
 *
 * <p>Distinct from {@link BuildDto} so the SEARCH wire-shape can evolve independently from the LIST
 * wire-shape. The list view caches a fat row (parameters / trigger-meta / failure summary) — the
 * search view caches a narrow row plus a server-rendered snippet.
 *
 * <p>{@code snippet} is HTML containing {@code <mark>} elements around matched tokens, as produced
 * by Postgres' {@code ts_headline()}. The string is XML-escaped before the {@code <mark>} tags are
 * applied by Postgres — see {@code BuildSearchDao} — so the UI can render it via {@code
 * dangerouslySetInnerHTML} without re-introducing user-controlled HTML.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BuildSearchHitDto(
    long id,
    long jobId,
    String jobFullName,
    int buildNumber,
    String status,
    Instant queuedAt,
    @Nullable String triggeredBy,
    @Nullable String snippet) {

  public static BuildSearchHitDto from(BuildSearchHitRow r) {
    return new BuildSearchHitDto(
        r.id,
        r.jobId,
        r.jobFullName,
        r.buildNumber,
        r.status,
        r.queuedAt,
        r.triggeredBy,
        r.snippet);
  }
}

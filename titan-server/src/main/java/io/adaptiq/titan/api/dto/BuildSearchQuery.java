package io.adaptiq.titan.api.dto;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;

/**
 * Bound, validated query record passed from {@code BuildSearchApi} to {@code BuildSearchDao}
 * (#1083). The HTTP layer normalises raw user input — uppercasing statuses, parsing the since
 * cutoff, capping limit — so the DAO can bind values directly into a parameterised JDBI statement.
 *
 * <p>{@code q} is the only required field; an empty {@code q} is rejected at the HTTP layer (the
 * endpoint is search-by-query, not list-everything-via-search).
 */
public record BuildSearchQuery(
    String q,
    /**
     * Optional job full-name glob — supports {@code *} as the only wildcard, mapped to SQL {@code
     * LIKE} {@code %}. Null = no job filter.
     */
    @Nullable String jobGlob,
    /** Optional status whitelist (already uppercased / validated). Empty = no filter. */
    List<String> status,
    /** Optional ISO-8601 cutoff. Null = no filter. */
    @Nullable Instant since,
    int limit,
    int offset) {}

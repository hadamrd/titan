package io.adaptiq.titan.api.dto;

import java.util.List;

/**
 * Paginated response envelope for {@code GET /api/v1/builds/search} (#1083). Same shape contract as
 * {@link BuildsPage} so the UI's pagination component stays uniform.
 */
public record BuildSearchPage(List<BuildSearchHitDto> items, int total, int offset, int limit) {}

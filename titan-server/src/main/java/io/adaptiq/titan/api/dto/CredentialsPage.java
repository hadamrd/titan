package io.adaptiq.titan.api.dto;

import java.util.List;

/**
 * Paginated list of credentials returned by {@code GET /api/v1/credentials}. Pagination is simple
 * offset/limit, matching {@link JobsPage}.
 */
public record CredentialsPage(List<CredentialDto> items, int total, int offset, int limit) {}

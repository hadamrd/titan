package io.adaptiq.titan.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code POST /api/v1/me/tokens} (closes #434).
 *
 * <p><strong>The {@code token} field is the ONLY place the plaintext token is ever returned over
 * the wire.</strong> The {@code GET /api/v1/me/tokens} list endpoint returns {@link
 * PersonalAccessTokenDto} (no plaintext, just the public prefix). Clients MUST capture this value
 * at generation time — there is no recovery path.
 *
 * <p>{@code scopes} (closes #500): echoes back the validated, normalised scope list — useful for
 * UIs that want to confirm what was actually persisted. {@code null} = legacy "inherit all".
 *
 * <p>{@code jobPattern} (closes #1082): echoes back the validated job-name glob (or {@code null}
 * for no restriction) so the post-creation reveal panel can confirm exactly what was minted.
 *
 * <p>NOTE: no {@code @JsonInclude(NON_NULL)}. Nulls MUST serialise as JSON null so the SPA receives
 * explicit {@code null} (not undefined) and {@code scopes === null} checks behave correctly.
 */
public record PersonalAccessTokenCreatedDto(
    long id,
    String name,
    String prefix,
    String token,
    Instant createdAt,
    List<String> scopes,
    String jobPattern) {}

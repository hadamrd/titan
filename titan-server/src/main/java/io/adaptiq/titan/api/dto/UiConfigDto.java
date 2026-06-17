package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response body for {@code GET /api/v1/system/ui-config} — runtime UI bootstrap configuration
 * (closes #898 server-side).
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code oidc} — OIDC client settings the SPA hands to {@code UserManager}.
 *   <li>{@code publicUrl} — externally-reachable base URL of this Titan rig (no trailing slash).
 *       Mirrors {@code titan.public-url}; useful to the UI for absolute-URL construction (e.g.
 *       webhooks, share links).
 * </ul>
 *
 * <p>{@code @PermitAll} — this endpoint MUST work before login; it's the boot endpoint that tells
 * the SPA HOW to auth.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UiConfigDto(OidcConfigDto oidc, String publicUrl) {}

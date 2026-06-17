package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OIDC client-side configuration block returned by {@code GET /api/v1/system/ui-config} (closes
 * #898 server-side).
 *
 * <p>The titan-ui SPA fetches this at boot — BEFORE instantiating the {@code oidc-client-ts} {@code
 * UserManager} — so a single, rig-agnostic UI bundle can target any titan-server. Prior to #898
 * these values were baked into the Vite bundle via {@code VITE_OIDC_*} env vars at build time,
 * which meant one image per rig and silent failures when a {@code --build-arg} was forgotten.
 *
 * <ul>
 *   <li>{@code authority} — the OIDC issuer the browser hits (Keycloak realm URL); maps to {@code
 *       titan.ui.oidc-authority} (falls back to {@code quarkus.oidc.auth-server-url}).
 *   <li>{@code clientId} — public OIDC client id; maps to {@code titan.ui.oidc-client-id} (default
 *       {@code "titan-ui"}).
 *   <li>{@code redirectUri} — {@code {publicUrl}/login/callback}.
 *   <li>{@code postLogoutRedirectUri} — {@code {publicUrl}/login}.
 * </ul>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record OidcConfigDto(
    String authority, String clientId, String redirectUri, String postLogoutRedirectUri) {}

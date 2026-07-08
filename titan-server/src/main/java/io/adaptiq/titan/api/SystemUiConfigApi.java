package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.OidcConfigDto;
import io.adaptiq.titan.api.dto.ProblemJson;
import io.adaptiq.titan.api.dto.UiConfigDto;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Jakarta REST resource: {@code GET /api/v1/system/ui-config} — runtime UI bootstrap config
 * endpoint (closes #898 server-side).
 *
 * <p><strong>Why.</strong> Prior to #898 the titan-ui bundle baked OIDC + API URLs at Vite build
 * time via {@code VITE_OIDC_*} / {@code VITE_TITAN_*} env vars. That meant one image per rig and
 * silent breakage when a {@code --build-arg} was forgotten (caught live on 2026-05-25). This
 * endpoint lets a single rig-agnostic SPA bundle ask the server for the rig-specific OIDC settings
 * at boot, BEFORE instantiating {@code UserManager}.
 *
 * <p><strong>Auth.</strong> {@code @PermitAll} — this is the endpoint that tells the SPA HOW to
 * auth. Requiring auth would be a chicken-and-egg block. The exposed values (issuer URL, public
 * client id, redirect URIs, public base URL) are not secrets — they're all visible in the browser
 * the moment a user clicks "Login" anyway.
 *
 * <p><strong>Config sources.</strong>
 *
 * <ul>
 *   <li>{@code oidc.authority} — {@code titan.ui.oidc-authority} when set, otherwise falls back to
 *       {@code quarkus.oidc.auth-server-url}. The override exists because in a k8s rig the
 *       in-cluster {@code auth-server-url} may differ from the public issuer the browser must
 *       reach.
 *   <li>{@code oidc.clientId} — {@code titan.ui.oidc-client-id} (default {@code "titan-ui"}). The
 *       UI is a public SPA client, separate from the {@code titan-server} bearer-token service
 *       client.
 *   <li>{@code oidc.redirectUri} — derived: {@code {uiPublicUrl}/login/callback}, where {@code
 *       uiPublicUrl} is {@code titan.ui.public-url} when set, else {@code titan.public-url}. The
 *       override exists for rigs where the SPA is served from a DIFFERENT origin than titan-server
 *       (e.g. the local rig: SPA on {@code :5180}, server on {@code :18080}) — the OIDC redirect
 *       must land on the SPA origin, which is also what the Keycloak client's redirect-URI
 *       allowlist contains (issue #38).
 *   <li>{@code oidc.postLogoutRedirectUri} — derived: {@code {uiPublicUrl}/login}.
 *   <li>{@code publicUrl} — {@code titan.public-url} (same property used by the GitHub status
 *       reporter for absolute target URLs). Deliberately NOT the UI override: the SPA uses this for
 *       server-reachable URLs such as GitHub App webhook endpoints.
 * </ul>
 *
 * <p>A blank/missing {@code titan.public-url} or unresolvable OIDC authority is a deployment
 * misconfig — we return a 500 {@code problem+json} rather than silently emitting empty strings (the
 * #898 failure mode we're explicitly fixing).
 */
@Path("/api/v1/system/ui-config")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@PermitAll
public class SystemUiConfigApi {

  /** Default OIDC client id for the SPA when no override is set — public client in Keycloak. */
  static final String DEFAULT_UI_CLIENT_ID = "titan-ui";

  private final Optional<String> publicUrl;
  private final Optional<String> uiPublicUrlOverride;
  private final String oidcClientId;
  private final Optional<String> uiOidcAuthorityOverride;
  private final Optional<String> oidcAuthServerUrl;

  @Inject
  SystemUiConfigApi(
      // Optional, not String: a blank/missing titan.public-url is a deployment misconfig we
      // surface at request time as a 500 problem+json — NOT at boot. Injecting it as a required
      // String would fail Quarkus' ConfigRecorder validation when the operator forgets to set it,
      // taking down the whole server (and the /api/v1/info endpoint with it) instead of giving the
      // SRE a clear error pointing at the missing key.
      @ConfigProperty(name = "titan.public-url") Optional<String> publicUrl,
      // UI-origin override for the OIDC redirect URIs (issue #38). titan.public-url is consumed
      // by MANY server-origin features (SCM status links, webhook callbacks, notification
      // deep-links) and cannot be repointed at the SPA origin on split-origin rigs. This key
      // affects ONLY the derived redirectUri/postLogoutRedirectUri; unset = same-origin rigs
      // keep the titan.public-url behaviour.
      @ConfigProperty(name = "titan.ui.public-url") Optional<String> uiPublicUrlOverride,
      @ConfigProperty(name = "titan.ui.oidc-client-id", defaultValue = DEFAULT_UI_CLIENT_ID)
          String oidcClientId,
      @ConfigProperty(name = "titan.ui.oidc-authority") Optional<String> uiOidcAuthorityOverride,
      @ConfigProperty(name = "quarkus.oidc.auth-server-url") Optional<String> oidcAuthServerUrl) {
    this.publicUrl = publicUrl;
    this.uiPublicUrlOverride = uiPublicUrlOverride;
    this.oidcClientId = oidcClientId;
    this.uiOidcAuthorityOverride = uiOidcAuthorityOverride;
    this.oidcAuthServerUrl = oidcAuthServerUrl;
  }

  @GET
  public UiConfigDto uiConfig() {
    String pub = normalisePublicUrl(publicUrl.orElse(null));
    // Redirect URIs must land on the ORIGIN THE SPA IS SERVED FROM (it is also the origin the
    // Keycloak client allowlists). On split-origin rigs that differs from titan.public-url.
    String uiPub =
        uiPublicUrlOverride
            .filter(s -> !s.isBlank())
            .map(s -> trimTrailingSlash(s.trim()))
            .orElse(pub);
    String authority = resolveAuthority();
    return new UiConfigDto(
        new OidcConfigDto(authority, oidcClientId, uiPub + "/login/callback", uiPub + "/login"),
        pub);
  }

  /** Pick the public-issuer override, else the configured auth-server-url, else fail loudly. */
  private String resolveAuthority() {
    String authority =
        uiOidcAuthorityOverride
            .filter(s -> !s.isBlank())
            .orElseGet(() -> oidcAuthServerUrl.filter(s -> !s.isBlank()).orElse(null));
    if (authority == null) {
      throw problem(
          "neither 'titan.ui.oidc-authority' nor 'quarkus.oidc.auth-server-url' is configured"
              + " — UI cannot bootstrap OIDC");
    }
    return trimTrailingSlash(authority);
  }

  private static String normalisePublicUrl(String raw) {
    if (raw == null || raw.isBlank()) {
      throw problem("'titan.public-url' is not configured — UI cannot bootstrap OIDC");
    }
    return trimTrailingSlash(raw.trim());
  }

  private static String trimTrailingSlash(String s) {
    int end = s.length();
    while (end > 0 && s.charAt(end - 1) == '/') {
      end--;
    }
    return s.substring(0, end);
  }

  /** Build a 500 {@code application/problem+json} response for a missing config property. */
  private static WebApplicationException problem(String detail) {
    return new WebApplicationException(
        Response.status(500)
            .type("application/problem+json")
            .entity(ProblemJson.internalError(detail))
            .build());
  }
}

/**
 * OIDC client configuration, sourced from titan-server's runtime config
 * endpoint (closes antipattern #898). NO `import.meta.env.VITE_*` reads —
 * those static-replaced at build time and forced one-image-per-rig.
 *
 * Tokens are stored in sessionStorage (not localStorage) to limit the XSS
 * blast-radius — tokens disappear when the tab closes.
 */
import { WebStorageStateStore, type UserManagerSettings } from 'oidc-client-ts'
import { loadRuntimeConfig } from '../runtimeConfig'

/**
 * Resolve the OIDC settings by awaiting the server-supplied runtime config.
 * The AuthProvider calls this exactly once at boot, before instantiating
 * {@link UserManager}.
 */
export async function loadOidcSettings(): Promise<UserManagerSettings> {
  const cfg = await loadRuntimeConfig()
  return {
    authority: cfg.oidc.authority,
    client_id: cfg.oidc.clientId,
    redirect_uri: cfg.oidc.redirectUri,
    post_logout_redirect_uri: cfg.oidc.postLogoutRedirectUri,
    response_type: 'code',
    scope: 'openid profile email',
    // sessionStorage — NOT localStorage. XSS-survivable tokens are out of
    // scope for now; a stolen token only lives until the tab closes.
    userStore:
      typeof window !== 'undefined'
        ? new WebStorageStateStore({ store: window.sessionStorage })
        : undefined,
    // Authorization-Code + PKCE is the default for response_type=code, but
    // be explicit so a future config sweep can't accidentally regress.
    loadUserInfo: true,
    automaticSilentRenew: false, // deferred — tracked in follow-up issue
  }
}

/**
 * Runtime UI configuration — fetched ONCE at app boot from titan-server.
 *
 * Closes antipattern #898: previously the OIDC issuer / API base URL / public
 * URL were `VITE_*` envs that Vite static-replaced into the bundle at build
 * time. That meant one image per rig (local / k3s-demo / staging / prod), and
 * an ad-hoc `docker build` that forgot a `--build-arg` silently shipped a
 * bundle that authenticated against nothing.
 *
 * Now: titan-server exposes `GET /api/v1/system/ui-config` (PermitAll) and the
 * SPA fetches it before the AuthProvider instantiates `UserManager`. One image
 * deployable to N rigs.
 *
 * Contract (server-agent owns the endpoint):
 *
 *   200 application/json
 *   {
 *     "oidc": {
 *       "authority": "https://titan.test.example.com/realms/titan-dev",
 *       "clientId": "titan-ui",
 *       "redirectUri": "https://titan.test.example.com/login/callback",
 *       "postLogoutRedirectUri": "https://titan.test.example.com/login"
 *     },
 *     "publicUrl": "https://titan.test.example.com"
 *   }
 *
 * The API base URL is NOT in the contract — the SPA is served by the same
 * nginx that proxies `/api` → titan-server, so same-origin (`""`) is the only
 * sensible value for V1. Cross-origin deployments would need a separate brief.
 */

export interface RuntimeOidcConfig {
  authority: string
  clientId: string
  redirectUri: string
  postLogoutRedirectUri: string
}

export interface RuntimeConfig {
  oidc: RuntimeOidcConfig
  publicUrl: string
}

const CONFIG_URL = '/api/v1/system/ui-config'

let cached: RuntimeConfig | null = null
let inFlight: Promise<RuntimeConfig> | null = null

/**
 * Fetch the runtime config from the server. Memoises the resolved value so
 * repeated calls during the React tree's startup don't trigger duplicate
 * round-trips. On failure, the promise rejects AND the in-flight cache is
 * cleared so a retry (e.g. from the boot error screen) re-fetches.
 */
export function loadRuntimeConfig(): Promise<RuntimeConfig> {
  if (cached !== null) return Promise.resolve(cached)
  if (inFlight !== null) return inFlight

  inFlight = (async () => {
    const res = await fetch(CONFIG_URL, {
      headers: { Accept: 'application/json' },
      credentials: 'same-origin',
    })
    if (!res.ok) {
      throw new Error(
        `Failed to load runtime config from ${CONFIG_URL}: HTTP ${res.status}`,
      )
    }
    const body = (await res.json()) as RuntimeConfig
    // Defensive validation — a misconfigured server returning {} would
    // otherwise blow up much later inside oidc-client-ts with a cryptic error.
    if (
      !body ||
      !body.oidc ||
      typeof body.oidc.authority !== 'string' ||
      typeof body.oidc.clientId !== 'string' ||
      typeof body.oidc.redirectUri !== 'string' ||
      typeof body.oidc.postLogoutRedirectUri !== 'string'
    ) {
      throw new Error(
        `Runtime config from ${CONFIG_URL} is missing required oidc.* fields`,
      )
    }
    cached = body
    return body
  })()

  inFlight.catch(() => {
    // Clear in-flight on failure so the retry button can re-trigger.
    inFlight = null
  })

  return inFlight
}

/**
 * Synchronous accessor for code paths that have already awaited
 * {@link loadRuntimeConfig} (transitively through the AuthProvider boot).
 * Throws if called before the config has resolved — that would be a logic
 * bug, since the AuthProvider gates the entire routed tree on the fetch.
 */
export function getRuntimeConfig(): RuntimeConfig {
  if (cached === null) {
    throw new Error(
      'getRuntimeConfig() called before loadRuntimeConfig() resolved — ' +
        'this should only be reached from inside the routed tree, which is ' +
        'gated on the AuthProvider boot.',
    )
  }
  return cached
}

/**
 * Test-only: reset the cache. Production code MUST NOT call this — there's
 * no scenario where the config changes during a single page lifetime.
 */
export function __resetRuntimeConfigForTests(): void {
  cached = null
  inFlight = null
}

/**
 * auth-v3 — Keycloak login helpers for the v3 Titan-only rig.
 *
 * The SPA does Authorization Code + PKCE against the local Keycloak
 * (`http://localhost:8081/realms/titan-dev`, client `titan-server` per
 * titan-ui/src/auth/oidcConfig.ts but overridden via VITE_OIDC_* on the rig).
 *
 * Two helpers:
 *   - `loginViaKeycloak(page)` — drive the browser through the OIDC redirect,
 *     fill the Keycloak form, return when the SPA's Overview is reachable.
 *   - `fetchBearerToken(...)` — direct token-endpoint grant (Direct Access
 *     Grants enabled on the dev client) for API-only setup steps.
 */
import type { Page } from '@playwright/test'
import { expect } from '@playwright/test'

const DEFAULT_KEYCLOAK = 'http://localhost:8081'
const DEFAULT_REALM = 'titan-dev'
const DEFAULT_CLIENT = 'titan-ui'
// Dedicated ROPC client. Direct-grants is enabled ONLY on this client so the
// SPA client (`titan-ui`) keeps PKCE without a password-grant bypass. See
// rig/local/keycloak/realm-titan-dev.json and issue #375.
const DEFAULT_DIRECT_GRANT_CLIENT = 'titan-e2e'
const DEFAULT_USER = 'dev'
const DEFAULT_PASSWORD = 'dev'

export interface AuthEnv {
  uiBaseUrl: string
  keycloakUrl: string
  realm: string
  /** Client used for the browser PKCE flow (the SPA's own client). */
  clientId: string
  /** Client used for the ROPC / direct-grants token fetch (e2e-only). */
  directGrantClientId: string
  username: string
  password: string
}

export function authEnv(): AuthEnv {
  return {
    uiBaseUrl: process.env.TITAN_UI_URL ?? 'http://localhost:5180',
    keycloakUrl: process.env.TITAN_KEYCLOAK_URL ?? DEFAULT_KEYCLOAK,
    realm: process.env.TITAN_KEYCLOAK_REALM ?? DEFAULT_REALM,
    clientId: process.env.TITAN_KEYCLOAK_CLIENT ?? DEFAULT_CLIENT,
    directGrantClientId:
      process.env.TITAN_KEYCLOAK_DIRECT_GRANT_CLIENT ?? DEFAULT_DIRECT_GRANT_CLIENT,
    username: process.env.TITAN_DEV_USER ?? DEFAULT_USER,
    password: process.env.TITAN_DEV_PASSWORD ?? DEFAULT_PASSWORD,
  }
}

/**
 * Drive the OIDC authorization-code flow: navigate to the SPA, click "Log in"
 * (or follow the auto-redirect), fill Keycloak's hosted login form, and wait
 * for the SPA to land back at the post-login route.
 *
 * Deterministic — no setTimeout. Uses Playwright waiters keyed on the form and
 * the post-redirect URL.
 */
export async function loginViaKeycloak(page: Page, env: AuthEnv = authEnv()): Promise<void> {
  await page.goto(env.uiBaseUrl + '/')

  // The SPA may either auto-redirect to Keycloak (unauthenticated guard) or
  // render the /login route. Either way the Keycloak login page is identified
  // by its `id="kc-form-login"` form.
  await page.waitForURL((url) => {
    return url.host.includes('localhost:8081') || url.pathname.startsWith('/login')
  })

  // If we're on the SPA /login route, click the "Sign in" button — it kicks
  // off the OIDC redirect to Keycloak.
  if (page.url().includes('/login') && !page.url().includes('localhost:8081')) {
    const signin = page.getByRole('button', { name: /sign in|log in/i }).first()
    if (await signin.isVisible().catch(() => false)) {
      await signin.click()
    }
  }

  // Wait for Keycloak login form.
  await page.waitForSelector('#kc-form-login', { timeout: 15_000 })
  await page.fill('#username', env.username)
  await page.fill('#password', env.password)
  await Promise.all([
    page.waitForURL(
      (url) => url.host === new URL(env.uiBaseUrl).host && !url.pathname.startsWith('/login'),
      { timeout: 20_000 },
    ),
    page.click('#kc-login'),
  ])

  // Sanity: a successful redirect lands us back on the SPA — Sidebar must be
  // mounted (it's in __root.tsx behind the auth guard). Use `.first()` because
  // recent layout work added a `data-sidebar` attribute on <html>, which would
  // otherwise trip strict-mode (two matches: <html data-sidebar> + <aside>).
  await expect(page.locator('aside.sidebar, nav').first()).toBeVisible({ timeout: 10_000 })
}

/**
 * Fetch a bearer token straight from Keycloak's token endpoint via Direct
 * Access Grants. Used by `titan-api-v3.ts` for API-only setup steps that
 * shouldn't pay the cost of a browser flow.
 *
 * The `titan-dev` realm's dedicated `titan-e2e` client has Direct Access
 * Grants enabled for the dev user (see rig/local/keycloak/realm-titan-dev.json
 * and issue #375). The SPA's own `titan-ui` client deliberately keeps direct
 * grants disabled so the PKCE flow isn't weakened.
 */
export async function fetchBearerToken(env: AuthEnv = authEnv()): Promise<string> {
  const tokenUrl = `${env.keycloakUrl}/realms/${env.realm}/protocol/openid-connect/token`
  const body = new URLSearchParams({
    grant_type: 'password',
    client_id: env.directGrantClientId,
    username: env.username,
    password: env.password,
    scope: 'openid profile email',
  })
  const res = await fetch(tokenUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  })
  if (!res.ok) {
    const text = await res.text()
    throw new Error(`Keycloak token grant failed (${res.status}): ${text}`)
  }
  const payload = (await res.json()) as { access_token?: string }
  if (!payload.access_token) {
    throw new Error('Keycloak token response missing access_token')
  }
  return payload.access_token
}

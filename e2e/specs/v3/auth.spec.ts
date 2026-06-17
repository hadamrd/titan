/**
 * auth — explicit OIDC happy-path + edge-case coverage for the v3 SPA.
 *
 * Pre-req: `task dev:titan` is up (Keycloak realm titan-dev, dev/dev user,
 * Quarkus server seeded). Guards three bug classes from PR #343:
 *
 *   - OIDC issuer mismatch — after login the bearer must work against
 *     `/api/v1/jobs` (server validates `iss`). We assert 200 + a non-empty
 *     items array so a silent issuer-mismatch 401 is caught here.
 *   - Login-route regression (#343) — `/login/callback?code=...` must
 *     render the LoginCallbackPage ("Completing sign-in…"), NOT the
 *     LoginPage card.
 *   - Already-authenticated redirect — visiting /login while signed in
 *     must honour the `?redirect=...` query and bounce to the requested
 *     route, not strand the user on the sign-in card.
 *
 * Deterministic: every wait is a Playwright waiter, never setTimeout.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()

test.describe('v3 auth', () => {
  test('happy path: anonymous user → Keycloak → SPA, bearer works against /api/v1/jobs', async ({
    page,
  }) => {
    // Step 1: anonymous root must redirect to /login (the SPA's beforeLoad
    // guard throws redirect({to:'/login', search:{redirect:...}})).
    await page.goto(ENV.uiBaseUrl + '/')
    await page.waitForURL(
      (url) => url.host.includes('localhost:8081') || url.pathname.startsWith('/login'),
      { timeout: 15_000 },
    )

    // The guard sets ?redirect=... on /login. Tolerate either the SPA's
    // /login route or an immediate auto-redirect to Keycloak.
    if (page.url().includes('/login') && !page.url().includes('localhost:8081')) {
      expect(page.url()).toMatch(/redirect=/)
    }

    // Step 2: drive the Keycloak flow via the shared fixture.
    await loginViaKeycloak(page, ENV)

    // Step 3: we must end up back inside the SPA (sidebar visible — root
    // layout). loginViaKeycloak already asserted that, but pin it here so
    // a future fixture change doesn't silently weaken this spec.
    await expect(page.locator('aside.sidebar, nav').first()).toBeVisible({ timeout: 10_000 })

    // Step 4: pull the access token from sessionStorage and hit the API. The
    // oidc-client-ts UserManager stores under `oidc.user:<authority>:<client>`.
    // We don't hard-code the authority — find the first matching key.
    const token = await page.evaluate(() => {
      for (let i = 0; i < sessionStorage.length; i++) {
        const key = sessionStorage.key(i)
        if (key && key.startsWith('oidc.user:') && key.endsWith(':titan-ui')) {
          const raw = sessionStorage.getItem(key)
          if (!raw) continue
          try {
            const parsed = JSON.parse(raw) as { access_token?: string; expires_at?: number }
            if (parsed.access_token) {
              return {
                token: parsed.access_token,
                expiresAt: parsed.expires_at ?? 0,
                key,
              }
            }
          } catch {
            // fall through
          }
        }
      }
      return null
    })

    expect(token, 'sessionStorage missing oidc.user:*:titan-ui after login').not.toBeNull()
    expect(token!.token.length).toBeGreaterThan(20)
    // Token must not already be expired.
    const nowSec = Math.floor(Date.now() / 1000)
    expect(
      token!.expiresAt,
      `access_token already expired (expires_at=${token!.expiresAt}, now=${nowSec})`,
    ).toBeGreaterThan(nowSec)

    // Step 5: bearer round-trip via the browser context. If the server's
    // OIDC issuer override is wrong (PR #343 bug #4), this 401s here.
    const apiBase = ENV.uiBaseUrl
    const apiResp = await page.evaluate(
      async ({ url, bearer }) => {
        const res = await fetch(url, {
          headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
        })
        const body = res.ok ? await res.json() : null
        return { status: res.status, body }
      },
      { url: `${apiBase}/api/v1/jobs?limit=5`, bearer: token!.token },
    )

    expect(
      apiResp.status,
      `API call returned ${apiResp.status} — likely OIDC issuer mismatch (PR #343 bug #4)`,
    ).toBe(200)
    // Seed data: titan-dev rig is meant to ship at least one job.
    expect(apiResp.body, 'expected an items array from /api/v1/jobs').toBeTruthy()
    const items = (apiResp.body as { items?: unknown[] }).items
    expect(Array.isArray(items), 'response.items should be an array').toBe(true)
    expect(items!.length, 'expected seed jobs (titan-dev realm) — got 0').toBeGreaterThan(0)
  })

  test('already-signed-in: /login?redirect=/builds auto-redirects to /builds', async ({ page }) => {
    // Establish a session first.
    await loginViaKeycloak(page, ENV)

    // Now nav to /login with a redirect target — the SPA should bounce.
    await page.goto(ENV.uiBaseUrl + '/login?redirect=/builds')

    await page.waitForURL(
      (url) => url.pathname === '/builds' && url.host === new URL(ENV.uiBaseUrl).host,
      { timeout: 5_000 },
    )
    expect(new URL(page.url()).pathname).toBe('/builds')
  })

  test('callback route: /login/callback renders LoginCallbackPage (not LoginPage card)', async ({
    page,
  }) => {
    // Visit the callback URL directly with an obviously-invalid code. We are
    // NOT asserting a successful exchange — only that the route resolves to
    // the LoginCallbackPage component. PR #343 regression collapsed this onto
    // the LoginPage card (no <Outlet/> in login.tsx).
    await page.goto(ENV.uiBaseUrl + '/login/callback?code=invalid')

    // The LoginCallbackPage renders either "Completing sign-in…" or an
    // alert with the exchange error. Either is acceptable — both prove the
    // callback component mounted. The wrong outcome is the LoginPage card
    // with its "Sign in to Titan" heading.
    const callbackTextVisible = await Promise.race([
      page
        .getByText(/completing sign-in/i)
        .first()
        .waitFor({ state: 'visible', timeout: 8_000 })
        .then(() => true)
        .catch(() => false),
      page
        .getByRole('alert')
        .first()
        .waitFor({ state: 'visible', timeout: 8_000 })
        .then(() => true)
        .catch(() => false),
    ])

    // Diagnostic: capture what DID render if neither selector matched.
    if (!callbackTextVisible) {
      const heading = await page
        .getByRole('heading', { name: /sign in to titan/i })
        .first()
        .isVisible()
        .catch(() => false)
      expect(
        heading,
        '/login/callback rendered the LoginPage card — PR #343 regression (#3) is back',
      ).toBe(false)
    }
    expect(callbackTextVisible).toBe(true)
  })
})

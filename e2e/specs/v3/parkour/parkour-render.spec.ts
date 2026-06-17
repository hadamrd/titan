/**
 * parkour-render — every v3 route is console-clean under happy + adversarial
 * conditions (issue #1061, acceptance criteria 1, 2, 3, 4).
 *
 * Three variants of each route:
 *
 *   1. happy        — load the route on the regular seeded rig; assert zero
 *                     console errors on first paint AND after a 2s settle.
 *                     This is the floor: a route that fails here is the
 *                     #1037 class of bug (broken on the basic rig).
 *
 *   2. adversarial  — the parkour seed has injected long-string + null-field
 *                     + churn rows into Postgres. Reload the route and run
 *                     the same console-clean assertion. A route that fails
 *                     here is the #1036 class of bug (broken on a "weird"
 *                     row the golden specs never exercised).
 *
 *   3. flaky-net    — install a `page.route('**', ...)` 5% abort injector
 *                     before navigation. The page must EITHER render
 *                     gracefully (loading/error UI, no console error) OR
 *                     recover within one polling tick. A page that crashes
 *                     here is the "I forgot to handle the rejected promise"
 *                     class — the user sees a white screen on any network
 *                     blip.
 *
 * Tagged @parkour so the runbook in docs/ops/runbooks/parkour-smoke.md can
 * select this suite with `--grep @parkour`.
 *
 * Why not split each variant into its own file: the variants are
 * matrix-multiplied with the route list. Pulling them apart would either
 * triple the file count or duplicate the route table — both worse than the
 * nested describe blocks here.
 */
import { test, expect } from '@playwright/test'
import type { Route, Request } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../../fixtures/auth-v3'
import { attachConsoleGuard, settle } from '../../../lib/console-guard'
import {
  cleanParkourSeed,
  seedAdversarialRows,
  type ParkourSeed,
} from '../../../fixtures/parkour'

const ENV = authEnv()

/**
 * Every authenticated top-level route the parkour suite covers. Mirrors the
 * acceptance-criteria list in #1061 but ALSO routes that exist in the SPA
 * that the brief lumped under one path (e.g. /workers/events).
 *
 * Routes that need a row ID are filled in by `routesNeedingId()` below.
 */
const STATIC_ROUTES = [
  '/',
  '/builds',
  '/queue',
  '/workers',
  '/jobs',
  '/pipelines',
  '/approvals',
  '/audit',
  '/admin/users',
  '/profile',
  '/profile#tokens',
  '/settings',
] as const

test.describe('@parkour render — every v3 route is console-clean', () => {
  let seed: ParkourSeed | null = null

  test.beforeAll(async () => {
    // Best-effort: seeding the rig requires Postgres on localhost:5432. If
    // the rig isn't local (e.g. running parkour against a remote rig), the
    // adversarial-row variant skips itself instead of hard-failing.
    try {
      seed = await seedAdversarialRows()
    } catch (e) {
      console.warn('[parkour] seed failed — adversarial-row variants will skip:', e)
      seed = null
    }
  })

  test.afterAll(async () => {
    if (seed) {
      await cleanParkourSeed(seed).catch((e) =>
        console.warn('[parkour] cleanup failed (harmless):', e),
      )
    }
  })

  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
  })

  // -------- variant 1: happy ------------------------------------------------
  test.describe('happy path (default seeded rig)', () => {
    for (const path of STATIC_ROUTES) {
      test(`route ${path} renders without console errors`, async ({ page }) => {
        const guard = attachConsoleGuard(page)
        const resp = await page.goto(ENV.uiBaseUrl + path, {
          waitUntil: 'domcontentloaded',
        })
        expect(resp, `no response for ${path}`).not.toBeNull()
        expect(resp!.status(), `${path} returned HTTP ${resp!.status()}`).toBeLessThan(400)
        await settle(page, 2_000)
        guard.assertClean(`happy ${path}`)
      })
    }
  })

  // -------- variant 2: adversarial rows -------------------------------------
  test.describe('adversarial rows (long unicode + null fields + churn)', () => {
    for (const path of STATIC_ROUTES) {
      test(`route ${path} survives adversarial DB rows`, async ({ page }) => {
        test.skip(seed === null, 'parkour seed unavailable (remote rig?)')
        const guard = attachConsoleGuard(page)
        await page.goto(ENV.uiBaseUrl + path, { waitUntil: 'domcontentloaded' })
        await settle(page, 2_000)
        guard.assertClean(`adversarial ${path}`)
      })
    }
  })

  // -------- variant 3: flaky network (5% abort) -----------------------------
  test.describe('flaky network (5% random abort)', () => {
    for (const path of STATIC_ROUTES) {
      test(`route ${path} survives 5% request abort`, async ({ page }) => {
        // Deterministic-ish PRNG keyed off the route so re-runs are
        // reproducible. Math.random would give a different abort pattern
        // every time and make a flaky test impossible to diagnose.
        let seedN = 0
        for (const ch of path) seedN = (seedN * 31 + ch.charCodeAt(0)) >>> 0
        const rand = () => {
          seedN = (seedN * 1103515245 + 12345) >>> 0
          return (seedN >>> 16) / 0x10000
        }

        // Don't abort the auth round-trip — Keycloak is already in our cookie
        // and aborting /realms/* would mask the test as an auth failure.
        // Don't abort the SPA shell either; the abort must hit XHR/fetch.
        await page.route('**', (route: Route, req: Request) => {
          const url = req.url()
          if (/\/realms\/|\.js$|\.css$|\.html$|\.svg$|\.woff|\.png/.test(url)) {
            return route.continue()
          }
          if (req.resourceType() === 'document') {
            return route.continue()
          }
          if (rand() < 0.05) {
            return route.abort()
          }
          return route.continue()
        })

        const guard = attachConsoleGuard(page)
        const resp = await page
          .goto(ENV.uiBaseUrl + path, { waitUntil: 'domcontentloaded' })
          .catch(() => null)
        // The SHELL must load — only XHR is allowed to fail. If even the
        // document was aborted (rare given the rand() distribution + the
        // resourceType guard above), retry once.
        if (!resp) {
          const retry = await page.goto(ENV.uiBaseUrl + path, {
            waitUntil: 'domcontentloaded',
          })
          expect(retry!.status(), `${path} shell unreachable`).toBeLessThan(400)
        }
        await settle(page, 2_000)
        // Drop the route handler so afterEach + the next test aren't
        // polluted by 5% aborts on cleanup traffic.
        await page.unroute('**').catch(() => {})
        guard.assertClean(`flaky ${path}`)
      })
    }
  })
})

// ───────────────────────────────────────────────────────────────────────────
// Meta-test — proves the parkour suite WOULD catch the bug it claims to.
//
// Acceptance criterion #5 from the issue: "a deliberately-broken DTO (omit
// `scopes` on /me/tokens, like the #1036 regression) should make the suite
// RED." We can't actually break the production DTO inside a test, but we
// CAN install a network intercept that returns a broken /me/tokens response
// and assert that the resulting page would have failed our console-guard.
//
// If this test fails, the parkour suite has lost its teeth — the
// console-guard or the FORBIDDEN_RENDERED_TEXT list isn't picking up the
// canonical regression class.
// ───────────────────────────────────────────────────────────────────────────
test.describe('@parkour meta — guard catches the #1036 regression class', () => {
  test('broken /me/tokens DTO (no scopes field) is caught by the guard', async ({
    page,
  }) => {
    await loginViaKeycloak(page, ENV)
    // Intercept /me/tokens and return a row missing the `scopes` field —
    // exactly the regression that crashed /profile#tokens in PR #1036.
    await page.route(/\/api\/v1\/me\/tokens(\?|$)/, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          {
            id: 'pat_meta_test',
            name: 'broken-token',
            // scopes field deliberately omitted
            createdAt: new Date().toISOString(),
            lastUsedAt: null,
          },
        ]),
      }),
    )

    const guard = attachConsoleGuard(page)
    await page.goto(ENV.uiBaseUrl + '/profile#tokens', {
      waitUntil: 'domcontentloaded',
    })
    await settle(page, 2_000)
    const observed = guard.errors()
    // We expect at LEAST one console error here. If the UI was robust to a
    // missing scopes field (which it should be post-#1037), this assertion
    // verifies our intercept actually fired; tweak the intercept rather
    // than relaxing the contract.
    // The body text also gets a check — even if the JS doesn't throw, a
    // ".map of undefined" usually surfaces as "undefined" or [object Object].
    const bodyText = await page.evaluate(() => document.body.innerText)
    const garbageInBody = /\[object Object\]|\bundefined\b/.test(bodyText)
    const sawConsoleError = observed.length > 0

    expect(
      sawConsoleError || garbageInBody,
      `parkour guards no longer catch the #1036 regression class — ` +
        `no console errors AND no garbage text after we forced a tokens DTO ` +
        `without 'scopes'. Either the guard list is too narrow or the ` +
        `intercept didn't fire (check /api/v1/me/tokens path).`,
    ).toBe(true)
    await page.unroute(/\/api\/v1\/me\/tokens(\?|$)/).catch(() => {})
  })
})

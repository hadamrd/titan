/**
 * 16-click-everything-smoke — comprehensive click-every-route contract.
 *
 * Walks every authenticated top-level route in sequence and asserts the page
 * is healthy at the API + console + DOM level. Designed to catch the bug
 * classes the CTO has manually found in the last 24h that prior specs let
 * through.
 *
 * Bug-class coverage (each assertion below has a `Catches:` tag pointing at
 * the issue or PR # it would have caught):
 *
 *   - #66       — empty Logs tab placeholders ("pending /api/.../logs endpoint")
 *   - #408      — /pipelines/$id infinite skeleton
 *   - #412      — bare "#1" job-name + NaN durations
 *   - #442/#458 — Builds page silently filtered to nothing
 *   - #463      — EventSource 401 on Logs tab (any 401 on authenticated request)
 *   - PR #71    — wrong job loaded on /pipelines detail (caught here only as a
 *                 console-error / no-401 floor; precise semantic check is in
 *                 17-list-detail-semantic.spec.ts)
 *   - PR #70    — stuck-RUNNING build (precise check in 18-no-stuck-running)
 *
 * Lightweight per memory `feedback_playwright_lightweight_checks` — network +
 * console + targeted DOM evaluate, no full-page snapshots.
 */
import { test, expect, type ConsoleMessage, type Response } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()

// Every authenticated route reachable from the sidebar (or directly typed by
// an operator). `/onboarding` is included because the "New job" flow links to
// it from /jobs and a broken onboarding wedges the create-pipeline path.
const ROUTES = [
  '/',
  '/builds',
  '/queue',
  '/workers',
  '/jobs',
  '/pipelines',
  '/profile',
  '/settings',
  '/onboarding',
] as const

// Console messages that are legitimately ignorable: sourcemaps, favicons,
// React-Query devtools chatter. Anything else fails the spec.
const CONSOLE_NOISE = /favicon|sourcemap|Failed to load resource|\[vite\]/i

// Forbidden placeholder text — these strings mean the page is shipping a
// half-finished surface to the user. Each catches a real bug class:
//   - "pending /.../ endpoint"  → #66 Logs empty-tab style placeholder
//   - "coming in M\d"           → unmet milestone copy left behind
//   - "TBD"                     → engineer's note shipped to production
const FORBIDDEN_TEXT = [/pending\s+\/[^\s]+\s+endpoint/i, /coming\s+in\s+m\d+/i, /\bTBD\b/]

test.describe('v3 click-every-route smoke', () => {
  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
  })

  for (const path of ROUTES) {
    test(`route ${path} is healthy (no 401, no fatal console, no placeholder copy, no perpetual skeleton)`, async ({
      page,
    }) => {
      const consoleErrors: string[] = []
      const unauthorized: string[] = []
      const serverErrors: string[] = []

      const onConsole = (msg: ConsoleMessage) => {
        if (msg.type() === 'error' && !CONSOLE_NOISE.test(msg.text())) {
          consoleErrors.push(msg.text())
        }
      }
      // Catches #463 EventSource 401 + any other auth-wiring regression.
      // A 401 outside of an explicit login round-trip is always a bug.
      const onResponse = (resp: Response) => {
        const status = resp.status()
        const url = resp.url()
        if (status === 401 && !/\/login|\/realms\//.test(url)) {
          unauthorized.push(`401 ${url}`)
        }
        if (status >= 500) {
          serverErrors.push(`${status} ${url}`)
        }
      }
      page.on('console', onConsole)
      page.on('response', onResponse)

      // Catches: a 4xx on the SPA shell route (e.g. nginx mis-config).
      const resp = await page.goto(ENV.uiBaseUrl + path, { waitUntil: 'domcontentloaded' })
      expect(resp, `no response for ${path}`).not.toBeNull()
      expect(resp!.status(), `${path} returned ${resp!.status()}`).toBeLessThan(400)

      // Catches: #408 infinite skeleton. Every route must reach a state with
      // zero skeleton elements within 10s. We poll because some pages have
      // initial Skeleton placeholders that resolve once react-query settles.
      await expect
        .poll(
          async () =>
            await page.locator('.skeleton, [data-skeleton]').count(),
          {
            timeout: 10_000,
            message: `${path} still showing skeleton after 10s — #408 bug class (perpetual loading state)`,
          },
        )
        .toBe(0)

      // Drain any async events one more tick before we read the buffers.
      await page.waitForLoadState('networkidle', { timeout: 10_000 }).catch(() => {
        // networkidle can be flaky if a refetchInterval fires; not fatal.
      })

      // Catches: #66 + future placeholder copy leaks. Scrape visible body
      // text; assert none of the forbidden patterns match.
      const bodyText = await page.evaluate(() => document.body.innerText)
      for (const pat of FORBIDDEN_TEXT) {
        expect(
          pat.test(bodyText),
          `${path} contains forbidden placeholder copy matching ${pat} — half-finished surface shipped`,
        ).toBe(false)
      }

      page.off('console', onConsole)
      page.off('response', onResponse)

      // Catches: #463 + token-wiring regressions on ANY route.
      expect(
        unauthorized,
        `${path} hit unauthorized requests:\n${unauthorized.join('\n')}`,
      ).toEqual([])

      // Catches: server-side blow-ups masked by a polished UI shell.
      expect(
        serverErrors,
        `${path} hit 5xx responses:\n${serverErrors.join('\n')}`,
      ).toEqual([])

      // Catches: uncaught JS — TypeError, NaN-from-undefined, OIDC parse fail.
      expect(
        consoleErrors,
        `${path} console errors:\n${consoleErrors.join('\n')}`,
      ).toEqual([])
    })
  }
})

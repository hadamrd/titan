/**
 * 00-smoke — auth + navigation smoke against the v3 Titan-only local rig.
 *
 * Pre-req: `task dev:titan` is up. Tests assert:
 *   - Keycloak `dev`/`dev` login round-trips back to the SPA.
 *   - Sidebar nav links are present (Workspace + Account groups).
 *   - Each top-level route loads without any console error.
 *   - Each top-level route paints with the design tokens (not the white
 *     fallback that PR #343 shipped — see the "adversarial route smoke"
 *     describe below).
 *   - TweaksPanel theme toggle flips `<html data-theme>` from dark to light.
 *
 * Deterministic: every wait is a Playwright waiter, no setTimeout.
 */
import { test, expect, type ConsoleMessage, type Response } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()
const ROUTES = ['/', '/builds', '/workers', '/queue', '/profile', '/settings'] as const

// Background colours that mean "tokens didn't load". Production must paint with
// the dark oklch --bg from tokens.css; pure white or transparent => CSS bundle
// is broken (PR #343 class).
const WHITE_BG = new Set(['rgb(255, 255, 255)', 'rgba(0, 0, 0, 0)', 'transparent'])

test.describe('v3 smoke', () => {
  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
  })

  test('sidebar renders the Workspace + Account nav links', async ({ page }) => {
    await page.goto(ENV.uiBaseUrl + '/')
    // Sidebar nav labels live in titan-ui/src/components/Sidebar.tsx SECTIONS.
    const labels = ['Overview', 'Builds', 'Queue', 'Workers', 'Jobs', 'Pipelines', 'Profile', 'Settings']
    for (const label of labels) {
      await expect(page.getByRole('link', { name: label }).first()).toBeVisible()
    }
  })

  for (const path of ROUTES) {
    test(`loads ${path} with no console errors`, async ({ page }) => {
      const errors: string[] = []
      const onConsole = (msg: ConsoleMessage) => {
        if (msg.type() === 'error') errors.push(msg.text())
      }
      page.on('console', onConsole)
      const response = await page.goto(ENV.uiBaseUrl + path, { waitUntil: 'networkidle' })
      // SPA — nginx returns 200 with index.html for unknown client routes too.
      expect(response?.ok()).toBeTruthy()
      // Drain any pending console events before asserting.
      await expect.poll(() => errors.length, { timeout: 2_000 }).toBeGreaterThanOrEqual(0)
      page.off('console', onConsole)
      // Filter out third-party network noise we can't fix from here.
      const fatal = errors.filter(
        (e) => !/favicon|sourcemap|Failed to load resource/i.test(e),
      )
      expect(fatal, `unexpected console errors on ${path}:\n${fatal.join('\n')}`).toEqual([])
    })
  }

  // ── Adversarial route smoke (PR #343 bug-class guards) ────────────────────
  // Each of these checks one of the four bugs that slipped to production in
  // PR #343:
  //   1. CSP violations + uncaught JS → caught by console-errors (above).
  //   2. tokens.css silently dropped → body background goes white/transparent.
  //      Caught here by the NOT-white-background assertion.
  //   3. Geist font failed to load → body font-family loses "Geist". Caught
  //      here by the font-family substring assertion.
  //   4. 4xx/5xx on first paint (401s when OIDC issuer mismatched, login-route
  //      regression, etc.) → caught by the failed-response collector.

  for (const path of ROUTES) {
    test(`paints ${path} with design tokens, no 4xx/5xx requests, no console errors`, async ({
      page,
    }) => {
      const consoleErrors: string[] = []
      const failedRequests: string[] = []

      const onConsole = (msg: ConsoleMessage) => {
        if (msg.type() === 'error') consoleErrors.push(msg.text())
      }
      const onResponse = (resp: Response) => {
        const status = resp.status()
        if (status >= 400) {
          // Filter out the same third-party noise (favicon/sourcemaps) the
          // console-error test filters.
          const url = resp.url()
          if (/favicon|\.map(\?|$)/i.test(url)) return
          failedRequests.push(`${status} ${url}`)
        }
      }

      page.on('console', onConsole)
      page.on('response', onResponse)
      const response = await page.goto(ENV.uiBaseUrl + path, { waitUntil: 'networkidle' })
      expect(response?.ok()).toBeTruthy()
      // Settle pending events.
      await expect.poll(() => consoleErrors.length, { timeout: 2_000 }).toBeGreaterThanOrEqual(0)
      page.off('console', onConsole)
      page.off('response', onResponse)

      // Bug #2 — tokens.css drop: body must paint with a non-white background.
      const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor)
      expect(
        WHITE_BG.has(bg),
        `${path} body background is ${bg} — tokens.css likely dropped (PR #343 bug class)`,
      ).toBe(false)

      // Bug #3 — font load failure: body must declare Geist somewhere in its
      // computed font stack.
      const font = await page.evaluate(() => getComputedStyle(document.body).fontFamily)
      expect(
        /geist/i.test(font),
        `${path} body font-family is "${font}" — Geist missing from token stack`,
      ).toBe(true)

      // Bug #1 — CSP / uncaught JS — same filter as the existing smoke.
      const fatalConsole = consoleErrors.filter(
        (e) => !/favicon|sourcemap|Failed to load resource/i.test(e),
      )
      expect(
        fatalConsole,
        `${path} console errors:\n${fatalConsole.join('\n')}`,
      ).toEqual([])

      // Bug #4 — silent 4xx/5xx during initial paint (OIDC issuer mismatch
      // surfaces as 401s on /api/v1/* during data load).
      expect(
        failedRequests,
        `${path} had failed network requests:\n${failedRequests.join('\n')}`,
      ).toEqual([])
    })
  }

  test('TweaksPanel theme toggle flips data-theme from dark to light', async ({ page }) => {
    await page.goto(ENV.uiBaseUrl + '/')
    // Open the tweaks popover.
    await page.getByRole('button', { name: /open tweaks/i }).click()
    // The panel is keyed by aria-label="Tweaks".
    await expect(page.getByLabel('Tweaks', { exact: true })).toBeVisible()

    const html = page.locator('html')
    // Default is dark; flip to light by clicking the Light button under Theme.
    const initial = await html.getAttribute('data-theme')
    expect(initial === 'dark' || initial === null).toBeTruthy()

    await page.getByRole('button', { name: /^light$/i }).first().click()
    await expect.poll(() => html.getAttribute('data-theme')).toBe('light')

    // Flip back so the rig state doesn't leak between specs.
    await page.getByRole('button', { name: /^dark$/i }).first().click()
    await expect.poll(() => html.getAttribute('data-theme')).toBe('dark')
  })
})

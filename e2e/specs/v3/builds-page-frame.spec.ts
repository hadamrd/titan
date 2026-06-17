/**
 * builds-page-frame — #1187 acceptance guard for the /builds framing pass.
 *
 * EPIC #1185 · axis:ux-quality. /builds used to render a lonely list floating
 * in a 1440px void. This spec pins the framing + summary contract that fixed
 * the "empty feel":
 *
 *   1. The shared PageHeader is present — a single dominant <h1> "Builds".
 *   2. Exactly ONE primary action ("New build") is visible in the header (H5).
 *   3. The at-a-glance summary strip (passing / failed / running) is visible.
 *   4. The dense build table renders real rows (not a spinner-in-void).
 *
 * House style (memory: Playwright Lightweight Checks): verify via DOM/network/
 * console signals, not heavy full-page snapshots. We attach a console guard and
 * assert zero failed (non-noise) network requests rather than pixel-diffing.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { attachConsoleGuard, settle } from '../../lib/console-guard'

const ENV = authEnv()

test.describe('@golden /builds page frame (#1187)', () => {
  test('header + primary action + summary + rows render cleanly', async ({ page }) => {
    const guard = attachConsoleGuard(page)

    // Track failed network responses (4xx/5xx) — a framed page must not be
    // built on a broken fetch. Ignore the well-known noise (aborts, favicon).
    const failedRequests: string[] = []
    page.on('response', (res) => {
      const status = res.status()
      const url = res.url()
      if (status >= 400 && !/favicon|sourcemap|\.map$/.test(url)) {
        failedRequests.push(`${status} ${url}`)
      }
    })

    await loginViaKeycloak(page, ENV)
    await page.goto(ENV.uiBaseUrl + '/builds', { waitUntil: 'domcontentloaded' })
    await settle(page)

    // 1. Single dominant page title (PageHeader → <h1>).
    const heading = page.getByRole('heading', { level: 1, name: 'Builds' })
    await expect(heading).toBeVisible()

    // 2. Exactly one primary action — the "New build" CTA in the header.
    const primary = page.getByTestId('builds-new-build')
    await expect(primary).toBeVisible()
    await expect(page.locator('.btn-primary')).toHaveCount(1)

    // 3. The at-a-glance summary strip is present with all three tiles.
    await expect(page.getByTestId('builds-summary')).toBeVisible()
    await expect(page.getByTestId('builds-summary-passing')).toBeVisible()
    await expect(page.getByTestId('builds-summary-failed')).toBeVisible()
    await expect(page.getByTestId('builds-summary-running')).toBeVisible()

    // 4. The dense table renders at least one real row (data view, not void).
    await expect(page.locator('.build-row').first()).toBeVisible({ timeout: 10_000 })

    // Lightweight health checks: no failed requests, no console errors.
    expect(failedRequests, `failed requests:\n${failedRequests.join('\n')}`).toEqual([])
    guard.assertClean('/builds')
  })

  test('summary + table reflow at 768px with no horizontal scroll', async ({ page }) => {
    await page.setViewportSize({ width: 768, height: 1024 })
    await loginViaKeycloak(page, ENV)
    await page.goto(ENV.uiBaseUrl + '/builds', { waitUntil: 'domcontentloaded' })
    await settle(page)

    await expect(page.getByTestId('builds-summary')).toBeVisible()

    // No horizontal scroll introduced by the new frame/summary at narrow width
    // (UX chart H7): the document is not wider than the viewport.
    const overflow = await page.evaluate(() => {
      const el = document.documentElement
      return el.scrollWidth - el.clientWidth
    })
    expect(overflow, `page overflows horizontally by ${overflow}px at 768`).toBeLessThanOrEqual(1)
  })
})

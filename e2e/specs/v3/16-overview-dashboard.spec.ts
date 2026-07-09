/**
 * 16-overview-dashboard — UX guard for the /overview redesign (#1189).
 *
 * Audit verdict (#138): PROMOTED to @golden as-is. The spec is read-only
 * (owns no rows, mutates nothing — trivially ownership-clean), deterministic
 * (no sleeps, viewport/DOM invariants only), and covers a surface the new
 * data-golden spec (63-overview-stats) deliberately does not: layout + error
 * hygiene rather than data truth. The pair splits the overview contract:
 * spec 63 proves the numbers are real, this spec proves the frame holds.
 *
 * Lightweight checks only (per feedback_playwright_lightweight_checks): we
 * verify layout invariants via the DOM / viewport, NOT a full visual snapshot.
 *
 * Asserts the hard constraints the redesign must keep:
 *   - H7: no horizontal scroll at 768px (the page must reflow, not overflow).
 *   - H5: exactly one dominant primary action (.btn-primary). On a fresh
 *     instance the OnboardingWelcomeCard owns the single primary; once builds
 *     exist the header CTA does — either way the count is exactly one.
 *   - The three required regions are present: metric grid, Recent activity,
 *     Top failing jobs.
 *   - Zero uncaught page errors on load.
 */
import { test, expect, type ConsoleMessage } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()

// @golden sits BEFORE the parenthetical: dev/rig-smoke/golden-count.sh greps
// `describe\([^)]*@golden`, so a `)` ahead of the tag would drop this spec
// from the golden floor.
test.describe('Overview dashboard — UX invariants @golden (#1189, promoted by #138)', () => {
  test('reflows at 768px with one primary action and the three regions', async ({
    page,
  }) => {
    const pageErrors: Error[] = []
    const consoleErrors: ConsoleMessage[] = []
    page.on('pageerror', (e) => pageErrors.push(e))
    page.on('console', (m) => {
      if (m.type() === 'error') consoleErrors.push(m)
    })

    await loginViaKeycloak(page, ENV)
    const navResp = await page.goto(`${ENV.uiBaseUrl}/`)
    expect(navResp?.status(), 'overview route HTTP status').toBeLessThan(400)

    // The framed page wrapper must mount (H1).
    await expect(page.getByTestId('overview-page')).toBeVisible({ timeout: 15_000 })

    // ── The three required regions are present. ──────────────────────────────
    await expect(page.getByTestId('overview-metrics')).toBeVisible()
    await expect(page.getByTestId('overview-recent-activity')).toBeVisible()
    await expect(page.getByTestId('top-failing-jobs')).toBeVisible()
    // Four metric tiles in the grid.
    await expect(page.getByTestId('kpi-jobs')).toBeVisible()
    await expect(page.getByTestId('kpi-builds')).toBeVisible()
    await expect(page.getByTestId('kpi-fail-rate')).toBeVisible()
    await expect(page.getByTestId('kpi-workers')).toBeVisible()

    // ── H5: exactly one dominant primary action. ─────────────────────────────
    await expect(page.locator('.btn-primary')).toHaveCount(1)

    // ── H7: no horizontal scroll at 768px. Check the scroll container, not a
    // pixel snapshot — overflow shows up as scrollWidth > clientWidth. ────────
    await page.setViewportSize({ width: 768, height: 1024 })
    // Let the responsive grid settle.
    await expect(page.getByTestId('overview-metrics')).toBeVisible()
    const overflow = await page.evaluate(() => {
      const el = document.scrollingElement ?? document.documentElement
      // +1 tolerance for sub-pixel rounding.
      return el.scrollWidth - el.clientWidth
    })
    expect(overflow, `horizontal overflow at 768px was ${overflow}px`).toBeLessThanOrEqual(1)

    // ── No uncaught errors on load. ──────────────────────────────────────────
    expect(
      pageErrors,
      pageErrors.length === 0 ? '' : `page errors:\n${pageErrors.map((e) => e.message).join('\n')}`,
    ).toEqual([])
    expect(
      consoleErrors,
      consoleErrors.length === 0
        ? ''
        : `console errors:\n${consoleErrors.map((m) => m.text()).join('\n')}`,
    ).toEqual([])
  })
})

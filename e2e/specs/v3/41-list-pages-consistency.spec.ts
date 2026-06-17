/**
 * 41-list-pages-consistency — the four operator list pages (#1190) must feel
 * like one product: workers / queue / approvals / audit each render the shared
 * page frame (PageHeader title) + a shared DataTable, with zero console errors
 * and no horizontal overflow at 1440px or 768px.
 *
 * Lightweight per memory `feedback_playwright_lightweight_checks`: verify via
 * DOM / console / evaluate, NOT full-page snapshots.
 */
import { test, expect, type ConsoleMessage } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()

const ROUTES = [
  { path: '/workers', title: /workers/i },
  { path: '/queue', title: /build queue/i },
  { path: '/approvals', title: /approvals/i },
  { path: '/audit', title: /audit log/i },
] as const

const WIDTHS = [
  { name: '1440px', width: 1440, height: 900 },
  { name: '768px', width: 768, height: 1024 },
] as const

test.describe('list pages consistency (#1190)', () => {
  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
  })

  for (const { path, title } of ROUTES) {
    test(`${path} shows the shared frame + a DataTable, no console errors`, async ({ page }) => {
      const errors: string[] = []
      page.on('console', (m: ConsoleMessage) => {
        if (m.type() === 'error') errors.push(m.text())
      })
      page.on('pageerror', (e) => errors.push(String(e)))

      await page.goto(ENV.uiBaseUrl + path, { waitUntil: 'domcontentloaded' })

      // Shared PageHeader title (H1 / H8).
      await expect(page.getByRole('heading', { name: title }).first()).toBeVisible({ timeout: 10_000 })

      // Wait for the loading skeleton to resolve so we read a settled state.
      await expect
        .poll(async () => page.locator('.tt-skel-row').count(), { timeout: 10_000 })
        .toBe(0)

      // The shared DataTable primitive renders a `table.tt` once one of the
      // four states resolves to populated/empty (error would also be fine, but
      // the seeded rig has data on these routes).
      const hasTable = await page.locator('table.tt').count()
      const hasState = await page.locator('.tt-empty').count()
      expect(hasTable + hasState, `${path} rendered neither a DataTable nor a shared state panel`).toBeGreaterThan(0)

      // Console must be clean (drift-class regressions surface here).
      expect(errors, `${path} logged console errors:\n${errors.join('\n')}`).toEqual([])
    })

    for (const { name, width, height } of WIDTHS) {
      test(`${path} has no horizontal overflow at ${name}`, async ({ page }) => {
        await page.setViewportSize({ width, height })
        await page.goto(ENV.uiBaseUrl + path, { waitUntil: 'domcontentloaded' })
        await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 10_000 })

        // The page (documentElement) must not scroll horizontally — tables
        // scroll inside their `.list-table-wrap` container instead (H7).
        const overflow = await page.evaluate(() => {
          const el = document.documentElement
          return el.scrollWidth - el.clientWidth
        })
        expect(overflow, `${path} overflows horizontally by ${overflow}px at ${name}`).toBeLessThanOrEqual(1)
      })
    }
  }
})

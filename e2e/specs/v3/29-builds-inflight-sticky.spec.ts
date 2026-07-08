/**
 * 29-builds-inflight-sticky — regression guard for #917.
 *
 * On the /builds default `tab=all` view, in-flight (non-terminal) builds MUST
 * render above the terminal "History" section so a single RUNNING build is
 * never buried under a wall of SUCCESS rows. When zero in-flight builds
 * match the view, the "In flight" heading is suppressed and history renders
 * as it did pre-#917 (no orphan separator, no empty header).
 *
 * Strategy (#59 — spec-ownership rule, see e2e/README.md): each test creates
 * its OWN engine-inert job + build rows carrying a unique search marker in
 * both `triggered_by` (matched by the server-side ILIKE search) and
 * `display_name` (matched by the client-side filter), then drives the page
 * through `/builds?q=<marker>` so the partition logic under test
 * (partitionBuilds + the showSplit gate, both q-independent) sees exactly the
 * rows this spec owns. Pre-#59 this spec flipped a foreign build to RUNNING
 * (and never restored it) for the happy path, and force-ABORTed EVERY
 * non-terminal build in the database for the empty case — with restore SQL
 * that always failed ("inconsistent types deduced for parameter $2"),
 * permanently poisoning any spec sharing the run.
 *
 * Tagged @golden — must stay green for V1.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { deleteOwnedJob, seedOwnedJobWithBuilds } from '../../fixtures/seed-v3'

const ENV = authEnv()

/** Unique lowercase-alnum marker — URL-safe and ILIKE-safe. */
function marker(label: string): string {
  return `e2ein29${label}${Date.now()}${Math.floor(Math.random() * 1_000_000)}`
}

test.describe('@golden /builds — in-flight sticky section (#917)', () => {
  test('in-flight runs render above the History section', async ({ page }) => {
    // Own rows: one RUNNING + one SUCCESS build, both tagged with the marker.
    const mark = marker('a')
    const seed = await seedOwnedJobWithBuilds(`e2e-inflight29-${mark}`, [
      { status: 'SUCCESS', displayName: mark, triggeredBy: mark },
      { status: 'RUNNING', displayName: mark, triggeredBy: mark },
    ])

    try {
      await loginViaKeycloak(page, ENV)
      await page.goto(`${ENV.uiBaseUrl}/builds?q=${mark}`, {
        waitUntil: 'domcontentloaded',
      })

      const inflightHeading = page.getByTestId('builds-section-inflight')
      const historyHeading = page.getByTestId('builds-section-history')

      await expect(inflightHeading).toBeVisible({ timeout: 10_000 })
      await expect(inflightHeading).toContainText(/in flight/i)
      await expect(historyHeading).toBeVisible()
      await expect(historyHeading).toContainText(/history/i)

      // DOM order: In flight precedes History.
      const order = await page.evaluate(() => {
        const inflight = document.querySelector('[data-testid="builds-section-inflight"]')
        const history = document.querySelector('[data-testid="builds-section-history"]')
        if (!inflight || !history) return -1
        // Node.DOCUMENT_POSITION_FOLLOWING = 4 → history follows inflight.
        return inflight.compareDocumentPosition(history) & 4 ? 1 : 0
      })
      expect(order, 'history heading must follow in-flight heading in DOM').toBe(1)

      // Our RUNNING row sits inside the inflight list (not under history).
      const inflightList = page.getByTestId('builds-list-inflight')
      await expect(inflightList).toBeVisible()
      const inflightRunningCount = await inflightList
        .locator('.build-row[data-status="RUNNING"], .build-row[data-status="QUEUED"]')
        .count()
      expect(inflightRunningCount, 'in-flight section must contain a RUNNING/QUEUED row')
        .toBeGreaterThan(0)

      // And belt-and-braces: history list does NOT carry a non-terminal row.
      const historyList = page.getByTestId('builds-list-history')
      const historyRunningCount = await historyList
        .locator('.build-row[data-status="RUNNING"], .build-row[data-status="QUEUED"]')
        .count()
      expect(historyRunningCount, 'history must not contain RUNNING/QUEUED rows').toBe(0)
    } finally {
      await deleteOwnedJob(seed.jobId)
    }
  })

  test('empty in-flight case omits the heading + separator cleanly', async ({ page }) => {
    // Own rows: ONLY terminal builds behind the marker — zero in-flight rows
    // match the view, so the page must fall back to the single un-split list.
    // No foreign build is aborted (pre-#59 this test ABORTed every RUNNING
    // build in the database and its restore never worked).
    const mark = marker('e')
    const seed = await seedOwnedJobWithBuilds(`e2e-inflight29-${mark}`, [
      { status: 'SUCCESS', displayName: mark, triggeredBy: mark },
      { status: 'ABORTED', displayName: mark, triggeredBy: mark },
    ])

    try {
      await loginViaKeycloak(page, ENV)
      await page.goto(`${ENV.uiBaseUrl}/builds?q=${mark}`, {
        waitUntil: 'domcontentloaded',
      })

      // Wait for the page to load actual rows (or empty state).
      await page.waitForSelector(
        '[data-testid="builds-list"], [data-testid="builds-empty"], [data-testid="builds-empty-filtered"]',
        { timeout: 10_000 },
      )

      // Neither the in-flight heading nor the history heading should render —
      // the page falls back to the single un-split list.
      await expect(page.getByTestId('builds-section-inflight')).toHaveCount(0)
      await expect(page.getByTestId('builds-section-history')).toHaveCount(0)

      // The single-list mode renders our terminal rows.
      const singleList = page.getByTestId('builds-list')
      await expect(singleList).toBeVisible()
      const rowCount = await singleList.locator('.build-row').count()
      expect(rowCount, 'single list must render the seeded terminal builds')
        .toBeGreaterThan(0)
      const nonTerminalCount = await singleList
        .locator('.build-row[data-status="RUNNING"], .build-row[data-status="QUEUED"]')
        .count()
      expect(nonTerminalCount, 'no non-terminal row may match this view').toBe(0)
    } finally {
      await deleteOwnedJob(seed.jobId)
    }
  })
})

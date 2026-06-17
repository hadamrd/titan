/**
 * 29-builds-inflight-sticky — regression guard for #917.
 *
 * On the /builds default `tab=all` view, in-flight (non-terminal) builds MUST
 * render above the terminal "History" section so a single RUNNING build is
 * never buried under a wall of SUCCESS rows. When zero in-flight builds
 * exist, the "In flight" heading is suppressed and history renders as it did
 * pre-#917 (no orphan separator, no empty header).
 *
 * The rig's seed-data.sh leaves at least one titan-ui RUNNING build, so the
 * happy-path assertion runs without needing extra seeding. To assert the
 * empty case, we flip every in-flight build to ABORTED for the duration of
 * the second test and restore the previous state in `afterAll`.
 *
 * Tagged @golden — must stay green for V1.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { pgClient } from '../../fixtures/seed-v3'

const ENV = authEnv()

test.describe('@golden /builds — in-flight sticky section (#917)', () => {
  test('in-flight runs render above the History section', async ({ page }) => {
    // Make sure at least one build is non-terminal (the seed normally leaves
    // titan-ui#1 in RUNNING; flip the first non-terminal row if not).
    const client = pgClient()
    await client.connect()
    try {
      const res = await client.query<{ count: string }>(
        `SELECT count(*)::text AS count FROM titan.builds
         WHERE status IN ('RUNNING','QUEUED')`,
      )
      const n = Number(res.rows[0]!.count)
      if (n === 0) {
        // Flip the most-recent terminal build to RUNNING for this test.
        await client.query(
          `UPDATE titan.builds SET status = 'RUNNING', finished_at = NULL
           WHERE id = (SELECT id FROM titan.builds ORDER BY id DESC LIMIT 1)`,
        )
      }
    } finally {
      await client.end()
    }

    await loginViaKeycloak(page, ENV)
    await page.goto(ENV.uiBaseUrl + '/builds', { waitUntil: 'domcontentloaded' })

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

    // At least one RUNNING/QUEUED row sits inside the inflight list (not under
    // history). We assert via the per-section list testids.
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
  })

  test('empty in-flight case omits the heading + separator cleanly', async ({ page }) => {
    // Snapshot the current non-terminal ids so we can restore them after.
    const client = pgClient()
    await client.connect()
    const snapshot: Array<{ id: number; status: string; finishedAt: string | null }> = []
    try {
      const res = await client.query<{
        id: string
        status: string
        finished_at: string | null
      }>(
        `SELECT id::text AS id, status, finished_at::text AS finished_at
           FROM titan.builds WHERE status IN ('RUNNING','QUEUED')`,
      )
      for (const r of res.rows) {
        snapshot.push({
          id: Number(r.id),
          status: r.status,
          finishedAt: r.finished_at,
        })
      }
      // Park every non-terminal build as ABORTED for the duration of this test.
      await client.query(
        `UPDATE titan.builds SET status = 'ABORTED', finished_at = COALESCE(finished_at, NOW())
         WHERE status IN ('RUNNING','QUEUED')`,
      )
    } finally {
      await client.end()
    }

    try {
      await loginViaKeycloak(page, ENV)
      await page.goto(ENV.uiBaseUrl + '/builds', { waitUntil: 'domcontentloaded' })

      // Wait for the page to load actual rows (or empty state).
      await page.waitForSelector(
        '[data-testid="builds-list"], [data-testid="builds-empty"], [data-testid="builds-empty-filtered"]',
        { timeout: 10_000 },
      )

      // Neither the in-flight heading nor the history heading should render —
      // the page falls back to the single un-split list.
      await expect(page.getByTestId('builds-section-inflight')).toHaveCount(0)
      await expect(page.getByTestId('builds-section-history')).toHaveCount(0)

      // The single-list mode still renders.
      const singleList = page.getByTestId('builds-list')
      // It is possible (rare) that flipping everything to ABORTED leaves no
      // rows on the page when paginated; accept either single-list visible or
      // empty-state — but DO NOT accept a partial-render with a stray header.
      const singleListCount = await singleList.count()
      if (singleListCount === 0) {
        // Empty state — acceptable.
        const emptyVisible =
          (await page.getByTestId('builds-empty').count()) +
          (await page.getByTestId('builds-empty-filtered').count())
        expect(emptyVisible).toBeGreaterThan(0)
      } else {
        await expect(singleList).toBeVisible()
      }
    } finally {
      // Restore the snapshot so downstream specs that depend on a running
      // build (cancel / golden-path) keep working.
      const restore = pgClient()
      await restore.connect()
      try {
        for (const row of snapshot) {
          await restore.query(
            `UPDATE titan.builds SET status = $2,
                                     finished_at = CASE WHEN $2 IN ('RUNNING','QUEUED')
                                                        THEN NULL ELSE finished_at END
             WHERE id = $1`,
            [row.id, row.status],
          )
        }
      } finally {
        await restore.end()
      }
    }
  })
})

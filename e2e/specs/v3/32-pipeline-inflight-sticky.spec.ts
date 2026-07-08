/**
 * 32-pipeline-inflight-sticky — regression guard for #927 (mirror of #917).
 *
 * On the per-pipeline page (/pipelines/$pipelineId), the "Recent builds" card
 * MUST render any non-terminal (RUNNING/QUEUED) build above the terminal
 * "History" section so a single in-flight run isn't buried under SUCCESS
 * rows. Empty in-flight = no headers / separator (no orphan UI).
 *
 * Strategy (#59 — spec-ownership rule, see e2e/README.md): each test creates
 * its OWN engine-inert job + build rows in the exact status mix it needs and
 * deletes exactly those rows afterwards. Pre-#59 this spec picked whatever
 * job had the most recent builds and force-flipped/ABORTed builds belonging
 * to OTHER specs via direct SQL — and its restore SQL was broken
 * ("inconsistent types deduced for parameter $2"), so the mutation was never
 * undone and poisoned the rest of the run.
 *
 * Tagged @golden — must stay green for V1.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { deleteOwnedJob, seedOwnedJobWithBuilds } from '../../fixtures/seed-v3'

const ENV = authEnv()

function runTag(): string {
  return `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
}

test.describe('@golden /pipelines/$id — in-flight sticky section (#927)', () => {
  test('in-flight runs render above the History section', async ({ page }) => {
    // Own job: two terminal builds + one RUNNING build. Nothing else in the
    // rig is read or written — the page under test is per-job.
    const seed = await seedOwnedJobWithBuilds(`e2e-inflight32-${runTag()}`, [
      { status: 'SUCCESS' },
      { status: 'FAILED' },
      { status: 'RUNNING' },
    ])

    try {
      await loginViaKeycloak(page, ENV)
      await page.goto(`${ENV.uiBaseUrl}/pipelines/${seed.jobId}`, {
        waitUntil: 'domcontentloaded',
      })

      const inflightHeading = page.getByTestId('pipeline-section-inflight')
      const historyHeading = page.getByTestId('pipeline-section-history')

      await expect(inflightHeading).toBeVisible({ timeout: 10_000 })
      await expect(inflightHeading).toContainText(/in flight/i)
      await expect(historyHeading).toBeVisible()
      await expect(historyHeading).toContainText(/history/i)

      // DOM order: In flight precedes History.
      const order = await page.evaluate(() => {
        const inflight = document.querySelector(
          '[data-testid="pipeline-section-inflight"]',
        )
        const history = document.querySelector(
          '[data-testid="pipeline-section-history"]',
        )
        if (!inflight || !history) return -1
        return inflight.compareDocumentPosition(history) & 4 ? 1 : 0
      })
      expect(order, 'history heading must follow in-flight heading in DOM').toBe(1)

      // The in-flight list contains exactly our RUNNING row.
      const inflightList = page.getByTestId('pipeline-recent-inflight')
      await expect(inflightList).toBeVisible()
      const inflightRunningCount = await inflightList
        .locator('.row[data-status="RUNNING"], .row[data-status="QUEUED"]')
        .count()
      expect(
        inflightRunningCount,
        'in-flight section must contain a RUNNING/QUEUED row',
      ).toBeGreaterThan(0)

      // Belt-and-braces: history list does NOT carry a non-terminal row.
      const historyList = page.getByTestId('pipeline-recent-history')
      const historyListCount = await historyList.count()
      if (historyListCount > 0) {
        const historyRunningCount = await historyList
          .locator('.row[data-status="RUNNING"], .row[data-status="QUEUED"]')
          .count()
        expect(
          historyRunningCount,
          'history must not contain RUNNING/QUEUED rows',
        ).toBe(0)
      }
    } finally {
      await deleteOwnedJob(seed.jobId)
    }
  })

  test('empty in-flight case omits the heading + separator cleanly', async ({
    page,
  }) => {
    // Own job with ONLY terminal builds — the empty in-flight case needs no
    // mutation of anyone else's rows because the section is per-job.
    const seed = await seedOwnedJobWithBuilds(`e2e-inflight32e-${runTag()}`, [
      { status: 'SUCCESS' },
      { status: 'ABORTED' },
    ])

    try {
      await loginViaKeycloak(page, ENV)
      await page.goto(`${ENV.uiBaseUrl}/pipelines/${seed.jobId}`, {
        waitUntil: 'domcontentloaded',
      })

      // Wait for the recent-builds list (or empty state) to render.
      await page.waitForSelector(
        '[data-testid="pipeline-recent-list"], [data-testid="pipeline-recent-history"], .empty',
        { timeout: 10_000 },
      )

      // Neither header may appear when no row is in-flight.
      await expect(page.getByTestId('pipeline-section-inflight')).toHaveCount(0)
      await expect(page.getByTestId('pipeline-section-history')).toHaveCount(0)

      // The un-split single list renders our terminal rows.
      const singleList = page.getByTestId('pipeline-recent-list')
      await expect(singleList).toBeVisible()
      const rowCount = await singleList.locator('.row').count()
      expect(rowCount, 'single list must render the terminal builds').toBeGreaterThan(0)
    } finally {
      await deleteOwnedJob(seed.jobId)
    }
  })
})

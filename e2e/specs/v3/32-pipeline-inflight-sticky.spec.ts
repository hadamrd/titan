/**
 * 32-pipeline-inflight-sticky — regression guard for #927 (mirror of #917).
 *
 * On the per-pipeline page (/pipelines/$pipelineId), the "Recent builds" card
 * MUST render any non-terminal (RUNNING/QUEUED) build above the terminal
 * "History" section so a single in-flight run isn't buried under SUCCESS
 * rows. Empty in-flight = no headers / separator (no orphan UI).
 *
 * Strategy: pick a job that has at least one build; ensure exactly one of
 * its builds is non-terminal (flip if needed); navigate to /pipelines/{id}
 * and assert section ordering + row partitioning. The empty case parks every
 * non-terminal build owned by the same job as ABORTED for the test duration
 * and restores afterwards.
 *
 * Tagged @golden — must stay green for V1.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { pgClient } from '../../fixtures/seed-v3'

const ENV = authEnv()

async function pickJobWithBuilds(): Promise<{ jobId: number }> {
  const client = pgClient()
  await client.connect()
  try {
    const res = await client.query<{ job_id: string }>(
      `SELECT job_id::text AS job_id
         FROM titan.builds
         GROUP BY job_id
         ORDER BY MAX(id) DESC
         LIMIT 1`,
    )
    if (res.rows.length === 0) {
      throw new Error('no jobs with builds in titan.builds — seed-data missing')
    }
    return { jobId: Number(res.rows[0]!.job_id) }
  } finally {
    await client.end()
  }
}

test.describe('@golden /pipelines/$id — in-flight sticky section (#927)', () => {
  test('in-flight runs render above the History section', async ({ page }) => {
    const { jobId } = await pickJobWithBuilds()

    // Ensure the chosen job has at least one non-terminal build. If it has
    // none, flip its most-recent build to RUNNING for this test.
    const client = pgClient()
    await client.connect()
    try {
      const res = await client.query<{ count: string }>(
        `SELECT count(*)::text AS count FROM titan.builds
           WHERE job_id = $1 AND status IN ('RUNNING','QUEUED')`,
        [jobId],
      )
      const n = Number(res.rows[0]!.count)
      if (n === 0) {
        await client.query(
          `UPDATE titan.builds SET status = 'RUNNING', finished_at = NULL
             WHERE id = (SELECT id FROM titan.builds
                          WHERE job_id = $1
                          ORDER BY id DESC LIMIT 1)`,
          [jobId],
        )
      }
    } finally {
      await client.end()
    }

    await loginViaKeycloak(page, ENV)
    await page.goto(`${ENV.uiBaseUrl}/pipelines/${jobId}`, {
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

    // The in-flight list contains at least one RUNNING/QUEUED row.
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
  })

  test('empty in-flight case omits the heading + separator cleanly', async ({
    page,
  }) => {
    const { jobId } = await pickJobWithBuilds()

    // Snapshot the non-terminal rows for this job + park them as ABORTED.
    const client = pgClient()
    await client.connect()
    const snapshot: Array<{ id: number; status: string }> = []
    try {
      const res = await client.query<{ id: string; status: string }>(
        `SELECT id::text AS id, status FROM titan.builds
           WHERE job_id = $1 AND status IN ('RUNNING','QUEUED')`,
        [jobId],
      )
      for (const r of res.rows) {
        snapshot.push({ id: Number(r.id), status: r.status })
      }
      await client.query(
        `UPDATE titan.builds
            SET status = 'ABORTED',
                finished_at = COALESCE(finished_at, NOW())
          WHERE job_id = $1 AND status IN ('RUNNING','QUEUED')`,
        [jobId],
      )
    } finally {
      await client.end()
    }

    try {
      await loginViaKeycloak(page, ENV)
      await page.goto(`${ENV.uiBaseUrl}/pipelines/${jobId}`, {
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
    } finally {
      // Restore so downstream specs that rely on this job's running build
      // (cancel / golden-path) keep working.
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

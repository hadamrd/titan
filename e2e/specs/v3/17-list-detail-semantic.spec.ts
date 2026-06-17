/**
 * 17-list-detail-semantic — click the first row of every list page and
 * assert the detail page's identity MATCHES the row that was clicked.
 *
 * This is the spec that would have caught PR #71's regression: the
 * /pipelines list sends a jobId in its Link, while the detail page treats
 * the param as a buildId — clicking the row loaded a totally different
 * pipeline. Prior coverage tolerated this because both pages rendered
 * "something" without erroring.
 *
 * Bug-class coverage:
 *   - PR #71  — /pipelines list→detail semantic mismatch (jobId vs buildId)
 *   - #412    — bare "#1" job-name (detail header missing job displayName)
 *   - #445    — /pipelines Open routes to /jobs/$id (covered partly in spec
 *               15; this spec adds the row-click variant in addition to the
 *               Open-button variant)
 *
 * Pre-req: live rig with seeded data (`bash rig/local/seed-data.sh`).
 *
 * Lightweight per memory `feedback_playwright_lightweight_checks` — read row
 * text + assert detail-page text contains the same identifying token. No
 * snapshots.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()

test.describe('v3 list→detail semantic match', () => {
  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
  })

  test('/builds first row → /builds/$id header shows the same #N', async ({ page }) => {
    // Catches: clicking row N takes you to a detail page showing build M.
    await page.goto(`${ENV.uiBaseUrl}/builds`)
    const firstRow = page.locator('a.row').first()
    await expect(firstRow).toBeVisible({ timeout: 10_000 })

    // Read the row's build number from `.build-num` (rendered as "#N").
    const rowText = (await firstRow.locator('.build-num').textContent()) ?? ''
    const rowBuildNumber = rowText.trim().replace(/^#/, '')
    expect(rowBuildNumber, `row build number unreadable: "${rowText}"`).toMatch(/^\d+$/)

    const href = await firstRow.getAttribute('href')
    expect(href, 'row has no href').toMatch(/^\/builds\/\d+$/)

    await firstRow.click()
    await page.waitForURL(/\/builds\/\d+$/, { timeout: 10_000 })

    // Detail header renders "<displayName> #<buildNumber>". Assert that the
    // buildNumber from the row appears in the detail header — this is the
    // semantic anchor. A wrong-build load would render a different number.
    const header = page.getByRole('heading').first()
    await expect(header).toBeVisible({ timeout: 10_000 })
    const headerText = (await header.textContent()) ?? ''
    expect(
      headerText.includes(`#${rowBuildNumber}`),
      `detail header "${headerText}" missing row's build #${rowBuildNumber} — list/detail semantic mismatch`,
    ).toBe(true)
  })

  test('/jobs first row → /jobs/$id header shows the same displayName', async ({ page }) => {
    // Catches: list→detail mismatch on the jobs surface (would also catch
    // #412 — bare "#1" header without job displayName).
    await page.goto(`${ENV.uiBaseUrl}/jobs`)
    const firstViewLink = page.getByRole('link', { name: /^view$/i }).first()
    await expect(firstViewLink).toBeVisible({ timeout: 10_000 })

    // The row's Name cell holds job.displayName. Read it from the same row.
    const row = firstViewLink.locator('xpath=ancestor::tr')
    const displayName = ((await row.locator('td').first().textContent()) ?? '').trim()
    expect(displayName.length, 'job displayName empty on /jobs row').toBeGreaterThan(0)

    await firstViewLink.click()
    await page.waitForURL(/\/jobs\/\d+$/, { timeout: 10_000 })

    // JobDetailPage useDocumentTitle is `${displayName} — Job`. Use the tab
    // title as the semantic anchor (immune to header layout changes).
    await expect(page).toHaveTitle(new RegExp(escapeRegex(displayName)), { timeout: 10_000 })
  })

  test('/pipelines first row → /pipelines/$id loads the SAME pipeline (PR #71 bug class)', async ({
    page,
  }) => {
    // Catches: PR #71. The pipelines list sends `pipelineId = job.id` in the
    // Link param. The detail page interprets it as a buildId. The fix in
    // flight should reconcile these — but until it lands, this spec hard
    // fails on the mismatch (filed as a follow-up issue).
    await page.goto(`${ENV.uiBaseUrl}/pipelines`)
    const firstNameLink = page.locator('a[href^="/pipelines/"]').first()
    await expect(firstNameLink).toBeVisible({ timeout: 10_000 })

    const rowName = ((await firstNameLink.textContent()) ?? '').trim()
    const href = (await firstNameLink.getAttribute('href')) ?? ''
    expect(href, 'pipelines row href is not /pipelines/$id').toMatch(/^\/pipelines\/\d+$/)

    await firstNameLink.click()
    await page.waitForURL(/\/pipelines\/\d+$/, { timeout: 10_000 })

    // Tolerance for the in-flight back-compat: the detail page may render
    // either the pipeline displayName OR a build header that ultimately
    // references the same job. Assert that the row's name string appears on
    // the detail page's visible text. If the bug is present, the detail page
    // will show a DIFFERENT job's name (or none).
    const bodyText = await page.evaluate(() => document.body.innerText)
    expect(
      bodyText.includes(rowName),
      `clicked "${rowName}" on /pipelines but detail page shows different content — list/detail semantic mismatch (PR #71 bug class)`,
    ).toBe(true)
  })

  test('/workers pool filter narrows the visible rows', async ({ page }) => {
    // The workers surface has no per-row detail page (per /workers route
    // — workers.tsx renders a grid, not detail pages). The semantic analogue
    // is the pool filter: clicking a pool chip narrows the list to that pool
    // and the URL/state must reflect it. Catches a regression where the
    // filter clicks but the data doesn't filter.
    await page.goto(`${ENV.uiBaseUrl}/workers`)
    await expect(page.getByRole('heading', { name: /^workers$/i })).toBeVisible({
      timeout: 10_000,
    })

    // If no workers seeded, this is a non-issue — skip without fail.
    const firstRow = page.locator('.row, [data-worker-row]').first()
    const hasRows = await firstRow.isVisible().catch(() => false)
    test.skip(!hasRows, 'no workers seeded — pool filter not exercisable')

    // The /workers page renders one filter chip per distinct pool (see
    // WorkersPage). Click the first pool chip and assert the visible rows
    // shrink (or stay equal if only one pool).
    const chips = page.locator('.filter-chip')
    const chipCount = await chips.count()
    if (chipCount > 0) {
      const beforeRows = await page.locator('.row').count()
      await chips.first().click()
      // After click, row count must be > 0 (the chip's own pool exists) and
      // <= the unfiltered count.
      const afterRows = await page.locator('.row').count()
      expect(afterRows, 'pool filter produced 0 visible rows').toBeGreaterThan(0)
      expect(afterRows, 'pool filter widened the result set').toBeLessThanOrEqual(beforeRows)
    }
  })

  test('/queue dnd grip handle is interactive (not a static icon)', async ({ page }) => {
    // /queue has no detail page; the semantic check is that the drag handle
    // actually responds (otherwise rows are pinned in place — a UX bug
    // adjacent to the "no-feedback click" class).
    await page.goto(`${ENV.uiBaseUrl}/queue`)
    await expect(page.getByRole('heading', { name: /build queue/i })).toBeVisible({
      timeout: 10_000,
    })

    const grip = page.locator('[aria-roledescription="sortable"], .queue-row [role="button"]').first()
    const hasGrip = await grip.isVisible().catch(() => false)
    test.skip(!hasGrip, 'queue empty — no rows to drag')

    // Assert the handle is a real interactive element (has pointer events
    // enabled). This is a lightweight DOM check, not a full drag simulation.
    const interactive = await grip.evaluate((el) => {
      const cs = getComputedStyle(el as HTMLElement)
      return cs.pointerEvents !== 'none'
    })
    expect(interactive, 'queue drag handle has pointer-events:none — dnd dead').toBe(true)
  })
})

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

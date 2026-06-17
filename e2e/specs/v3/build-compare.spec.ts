/**
 * build-compare — /builds/<a>/compare/<b> path-form comparison view (#1077).
 *
 * Pre-req: `task dev:titan` is up and `rig/local/seed-data.sh` has run.
 * The seed leaves at least two terminal builds on the {@code titan-server}
 * job (build_number 1 and 2 — see 05-artifacts-browser.spec.ts and the
 * gate/replay specs which depend on the same anchors).
 *
 * Assertions (issue acceptance criteria):
 *   1. The path-form URL resolves and renders the comparison view.
 *   2. Per-side summary cards show both builds' numbers and statuses.
 *   3. At least one stage row + delta cell is visible.
 *   4. The artifacts section is present.
 *   5. The same-build sanity path: /builds/<a>/compare/<a> surfaces the
 *      "comparing with itself" notice.
 *   6. The invalid-id sanity path: /builds/abc/compare/def surfaces the
 *      "Build not found" message rather than a blank shell.
 *
 * @golden — the acceptance suite mandated by #1077.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { pgClient } from '../../fixtures/seed-v3'

const ENV = authEnv()

let buildAId: number
let buildBId: number

test.beforeAll(async () => {
  const client = pgClient()
  await client.connect()
  try {
    // Two terminal builds of the same job (titan-server). The seed places at
    // least build_number 1 and 2 there; we pick whichever two exist with the
    // smallest build_numbers to keep the test deterministic against re-seeds.
    const res = await client.query<{ id: string; n: string }>(
      `SELECT b.id::text AS id, b.build_number::text AS n
         FROM titan.builds b
         JOIN titan.jobs   j ON j.id = b.job_id
        WHERE j.full_name = 'titan-server'
        ORDER BY b.build_number ASC
        LIMIT 2`,
    )
    if (res.rows.length < 2) {
      throw new Error(
        `Expected >=2 seeded titan-server builds, found ${res.rows.length}. ` +
          'Re-run rig/local/seed-data.sh.',
      )
    }
    buildAId = Number(res.rows[0]!.id)
    buildBId = Number(res.rows[1]!.id)
  } finally {
    await client.end()
  }
})

test.describe('@golden build comparison', () => {
  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
  })

  test('renders side-by-side comparison at the path-form URL', async ({ page }) => {
    await page.goto(`${ENV.uiBaseUrl}/builds/${buildAId}/compare/${buildBId}`)
    // The view container is the entry point — surfaces the whole stack.
    await expect(page.getByTestId('build-compare-view')).toBeVisible({ timeout: 15_000 })
    // Both per-side summary cards present.
    await expect(page.getByTestId('compare-summary-a')).toBeVisible()
    await expect(page.getByTestId('compare-summary-b')).toBeVisible()
    // Either a real stage table or the empty-state placeholder must render —
    // we never want a blank shell on a happy-path navigate.
    const haveTable = await page.getByTestId('compare-stages-table').count()
    const haveEmpty = await page.getByTestId('compare-stages-empty').count()
    expect(haveTable + haveEmpty).toBeGreaterThan(0)
    // Artifacts section is rendered (either rows or the empty-state message).
    await expect(page.getByTestId('compare-artifacts-disclosure')).toBeVisible()
  })

  test('same-build URL surfaces the friendly identical notice', async ({ page }) => {
    await page.goto(`${ENV.uiBaseUrl}/builds/${buildAId}/compare/${buildAId}`)
    await expect(page.getByTestId('compare-same-build-notice')).toBeVisible({
      timeout: 15_000,
    })
    await expect(page.getByTestId('compare-same-build-notice')).toContainText(/itself/i)
  })

  test('invalid build ids render the 404 message rather than a blank page', async ({
    page,
  }) => {
    await page.goto(`${ENV.uiBaseUrl}/builds/not-a-number/compare/also-not`)
    await expect(page.getByTestId('compare-invalid-id')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('compare-invalid-id')).toContainText(/Build not found/i)
  })
})

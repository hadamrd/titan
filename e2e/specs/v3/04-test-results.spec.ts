/**
 * 04-test-results — TestResultsPanel render + filter behaviour.
 *
 * Pre-req: `task dev:titan` is up (the rig's seed-data.sh inserts the
 * test_result rows on titan-server build #2 — 5 PASSED, 2 FAILED, 1 SKIPPED).
 *
 * Assertions:
 *   - Counter chips render the seeded counts (5 passed / 2 failed / 1 skipped).
 *   - "All" filter widens the list back to 8 rows.
 *   - "Failed" filter narrows the list to exactly 2 rows.
 *   - Clicking the expand affordance on a failed row reveals its
 *     failure_message (mirrors the <pre id="test-fail-{id}"> contract in
 *     TestResultsPanel.tsx).
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { pgClient } from '../../fixtures/seed-v3'

const ENV = authEnv()

let buildId: number

test.beforeAll(async () => {
  // Resolve the anchor build (titan-server build_number=2) and assert the
  // seed actually landed — surfaces a clearer failure than the UI assertions
  // would if seed-data.sh wasn't run.
  const client = pgClient()
  await client.connect()
  try {
    const res = await client.query<{ id: string; n: string }>(
      `SELECT b.id::text AS id,
              (SELECT count(*) FROM titan.test_result t WHERE t.build_id = b.id)::text AS n
         FROM titan.builds b
         JOIN titan.jobs   j ON j.id = b.job_id
        WHERE j.full_name = 'titan-server' AND b.build_number = 2`,
    )
    if (res.rows.length === 0) {
      throw new Error('Anchor build titan-server#2 not found — did seed-data.sh run?')
    }
    const row = res.rows[0]!
    if (Number(row.n) !== 8) {
      throw new Error(
        `Expected 8 seeded test_result rows on titan-server#2, found ${row.n}. ` +
          'Re-run rig/local/seed-data.sh against a clean rig.',
      )
    }
    buildId = Number(row.id)
  } finally {
    await client.end()
  }
})

test('renders test counter chips and filters failed-only', async ({ page }) => {
  await loginViaKeycloak(page, ENV)
  await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)

  // Switch to the Tests tab — the BuildDetailPage default tab is Pipeline.
  await page.getByRole('tab', { name: /^tests$/i }).click()

  // ── Counter chips (passed/failed/skipped) ────────────────────────────────
  // TestResultsPanel.tsx Counter sets aria-label="${count} ${label}".
  await expect(page.getByLabel('5 passed')).toBeVisible({ timeout: 10_000 })
  await expect(page.getByLabel('2 failed')).toBeVisible()
  await expect(page.getByLabel('1 skipped')).toBeVisible()

  // ── Row counting helper — sniff the test-row grid inside the panel. ──────
  // The panel auto-defaults to the "failed" filter when failed > 0, so first
  // assert that view (2 rows), then flip to All (8), then back to Failed.
  const rows = page.locator('.test-row')

  // Default landing view is Failed (panel auto-selects it when failures > 0).
  await expect(rows).toHaveCount(2)

  // ── "All" filter widens to 8 rows ────────────────────────────────────────
  await page.getByRole('button', { name: /^all$/i, pressed: false }).click()
  await expect(rows).toHaveCount(8)

  // ── "Failed" filter narrows back to 2 rows ───────────────────────────────
  await page.getByRole('button', { name: /^failed/i, pressed: false }).click()
  await expect(rows).toHaveCount(2)

  // ── Expand a failed row → its failure_message becomes visible ────────────
  // The expand button has aria-label "Expand failure details" and the failed
  // row itself is also clickable (see TestResultsPanel.tsx — both call
  // onToggle, and the inner button's onClick stopPropagation()s the row).
  // We click the row directly to avoid event-handler races between the two
  // overlapping click targets.
  const firstFailedRow = page.locator('.test-row').first()
  await firstFailedRow.click()

  // After expanding, the <pre id="test-fail-{rowId}"> renders the seeded
  // failure-message text. The button's aria-expanded should flip to "true".
  const expandBtn = firstFailedRow.getByRole('button', { name: /failure details/i })
  await expect(expandBtn).toHaveAttribute('aria-expanded', 'true', { timeout: 5_000 })

  // The seeded failure messages contain known marker substrings (see the
  // VALUES list in rig/local/seed-data.sh). Match the first failure's
  // assertion text — either GateTest or WorkerTest depending on row order.
  const expanded = page.locator('pre[id^="test-fail-"]').first()
  await expect(expanded).toBeVisible()
  await expect(expanded).toContainText(/AssertionError|AssertionFailedError/)
})

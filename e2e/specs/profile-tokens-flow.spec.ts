/**
 * profile-tokens-flow — end-to-end coverage for the #1186 /profile redesign.
 *
 * This is the integration-layer proof the review (#1195) asked for: the unit
 * tests pin the four data states in isolation, but the issue's test matrix
 * requires a Playwright spec that, against the live rig:
 *
 *   1. deep-links to /profile#tokens and confirms hash → section sync (so the
 *      preserved deep links still select the right tab),
 *   2. confirms the tokens table is FRAMED — never hugging the top-left dead
 *      zone at 1440px (H1),
 *   3. creates a token through the UI, confirms the reveal-once card appears
 *      (PR #464/#526/#1082 behaviour preserved verbatim),
 *   4. revokes it and confirms the row leaves the table,
 *   5. captures the 1440px + 768px screenshots referenced by the PR body
 *      (populated + empty), and
 *   6. asserts NO horizontal PAGE scroll at 768px — the table scrolls inside
 *      its own container instead (H7).
 *
 * The empty / error states are captured deterministically by intercepting the
 * tokens GET so we don't have to destroy the shared rig's seeded rows to render
 * them. The create / reveal / revoke flow runs against the REAL API.
 */
import { expect, test } from '@playwright/test'
import { authEnv, fetchBearerToken, loginViaKeycloak } from '../fixtures/auth-v3'
import { deleteToken } from '../fixtures/pat'

const ENV = authEnv()
const TOKENS_API = '**/api/v1/me/tokens'

/** Assert the document does not scroll horizontally (H7). */
async function assertNoHorizontalPageScroll(
  page: import('@playwright/test').Page,
  label: string,
) {
  const overflow = await page.evaluate(() => {
    const el = document.scrollingElement ?? document.documentElement
    // +1 tolerance for sub-pixel rounding across browsers.
    return el.scrollWidth - el.clientWidth
  })
  expect(overflow, `${label}: page must not scroll horizontally`).toBeLessThanOrEqual(1)
}

test.describe('Profile / Access tokens — full flow + responsive (#1186)', () => {
  test('deep-link, framed table, create → reveal → revoke, 1440 + 768 screenshots', async ({
    page,
  }) => {
    const createdName = `e2e-1186-flow-${Date.now()}`
    let createdId: string | null = null

    await loginViaKeycloak(page, ENV)

    // ── 1. deep-link hash → section sync ───────────────────────────────
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`${ENV.uiBaseUrl}/profile#tokens`)

    // The shared SectionCard frame (H8) and the tokens table must mount.
    await expect(page.getByTestId('section-card')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('section-tokens')).toBeVisible()
    await expect(page.getByTestId('pat-list')).toBeVisible({ timeout: 10_000 })
    // The deep-linked tab is the active one (aria-current=page).
    await expect(page.getByTestId('nav-tokens')).toHaveAttribute('aria-current', 'page')

    // A different deep link selects a different tab — hash sync is real.
    await page.goto(`${ENV.uiBaseUrl}/profile#security`)
    await expect(page.getByTestId('section-security')).toBeVisible()
    await expect(page.getByTestId('nav-security')).toHaveAttribute('aria-current', 'page')
    await page.goto(`${ENV.uiBaseUrl}/profile#tokens`)
    await expect(page.getByTestId('pat-list')).toBeVisible({ timeout: 10_000 })

    // ── 2. H1: framed, not hugging the top-left dead zone at 1440px ─────
    const tableBox = await page.getByTestId('pat-list').boundingBox()
    expect(tableBox, 'tokens table has a layout box').not.toBeNull()
    expect(
      tableBox!.x,
      `tokens table left edge (${tableBox!.x}px) must be framed, not hugging x<32px`,
    ).toBeGreaterThanOrEqual(32)

    // ── 3. create a token through the UI → reveal-once card ────────────
    await page.getByTestId('pat-name-input').fill(createdName)
    const [createResp] = await Promise.all([
      page.waitForResponse(
        (r) => /\/api\/v1\/me\/tokens$/.test(r.url()) && r.request().method() === 'POST',
        { timeout: 10_000 },
      ),
      page.getByTestId('pat-generate-btn').click(),
    ])
    expect(createResp.ok(), 'POST /me/tokens succeeded').toBeTruthy()
    createdId = String(((await createResp.json()) as { id: string | number }).id)

    const reveal = page.getByTestId('token-reveal')
    await expect(reveal, 'reveal-once card appears after create').toBeVisible()
    await expect(page.getByTestId('token-secret')).not.toBeEmpty()

    // The new row is in the table.
    const row = page
      .locator('[data-testid="token-row"]')
      .filter({ has: page.locator(`td:has-text("${createdName}")`) })
    await expect(row).toBeVisible()

    // 1440px populated screenshot for the PR body.
    await test.info().attach('profile-tokens-1440-populated.png', {
      body: await page.screenshot({ fullPage: true }),
      contentType: 'image/png',
    })

    // ── 4. revoke it via the themed dialog → row leaves the table ──────
    await page.getByTestId('token-reveal').getByRole('button', { name: /copied it/i }).click()
    await row.getByRole('button', { name: /Revoke/i }).click()
    await expect(page.getByRole('alertdialog')).toBeVisible()
    await Promise.all([
      page.waitForResponse(
        (r) =>
          /\/api\/v1\/me\/tokens\//.test(r.url()) && r.request().method() === 'DELETE',
        { timeout: 10_000 },
      ),
      page.getByTestId('revoke-token-dialog-confirm').click(),
    ])
    createdId = null // server-side gone; teardown no longer required
    await expect
      .poll(async () => row.isVisible().catch(() => false), { timeout: 10_000 })
      .toBe(false)

    // ── 5. empty-state screenshots (deterministic via route mock) ──────
    // We don't revoke the shared rig's other seeded tokens, so force the empty
    // state by intercepting the list GET — this is presentation-only.
    await page.route(TOKENS_API, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: '[]',
        })
        return
      }
      await route.continue()
    })
    await page.goto(`${ENV.uiBaseUrl}/profile#tokens`)
    await expect(page.getByTestId('pat-list-empty')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('pat-list-empty')).toContainText(/no tokens yet/i)
    // The one dominant primary action is still present in the empty state (H5).
    await expect(page.getByTestId('pat-generate-btn')).toBeVisible()

    await test.info().attach('profile-tokens-1440-empty.png', {
      body: await page.screenshot({ fullPage: true }),
      contentType: 'image/png',
    })

    // ── 6. 768px: no horizontal PAGE scroll, table scrolls in-container ─
    await page.setViewportSize({ width: 768, height: 1024 })
    await expect(page.getByTestId('pat-list-empty')).toBeVisible()
    await assertNoHorizontalPageScroll(page, '768px empty')
    await test.info().attach('profile-tokens-768-empty.png', {
      body: await page.screenshot({ fullPage: true }),
      contentType: 'image/png',
    })

    // Drop the mock and re-check the real (populated) page at 768px: the page
    // still must not scroll horizontally — the overflow lives in the table's
    // own scroll container (H7).
    await page.unroute(TOKENS_API)
    await page.goto(`${ENV.uiBaseUrl}/profile#tokens`)
    await expect(page.getByTestId('pat-list')).toBeVisible({ timeout: 10_000 })
    await assertNoHorizontalPageScroll(page, '768px populated')
    // The table's own scroll container is what overflows, not the page.
    const scroll = page.getByTestId('pat-table-scroll')
    await expect(scroll).toBeVisible()
    await test.info().attach('profile-tokens-768-populated.png', {
      body: await page.screenshot({ fullPage: true }),
      contentType: 'image/png',
    })

    // Best-effort teardown if a failure skipped the in-flow revoke.
    if (createdId !== null) {
      const bearer = await fetchBearerToken(ENV)
      await deleteToken(createdId, bearer, ENV).catch(() => {})
    }
  })

  test('forced 500 on the tokens query renders a retry affordance, not a blank header (sad path)', async ({
    page,
  }) => {
    await loginViaKeycloak(page, ENV)

    // Force the list GET to error before the page mounts.
    await page.route(TOKENS_API, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ title: 'boom', status: 500 }),
        })
        return
      }
      await route.continue()
    })

    await page.goto(`${ENV.uiBaseUrl}/profile#tokens`)

    // Error terminal state with an explicit retry — NOT an infinite spinner and
    // NOT a bare table header (H4 sad path).
    const errorBox = page.getByTestId('pat-list-error')
    await expect(errorBox).toBeVisible({ timeout: 10_000 })
    await expect(errorBox).toContainText(/retry/i)
    await expect(page.getByTestId('pat-list-skel')).toHaveCount(0)

    // Clicking retry re-issues the GET (which we let succeed this time).
    await page.unroute(TOKENS_API)
    await Promise.all([
      page.waitForResponse(
        (r) => /\/api\/v1\/me\/tokens$/.test(r.url()) && r.request().method() === 'GET',
        { timeout: 10_000 },
      ),
      errorBox.getByText(/retry/i).click(),
    ])
    await expect(page.getByTestId('pat-list')).toBeVisible({ timeout: 10_000 })

    // The page must not scroll horizontally even in the error state at 768px.
    await page.setViewportSize({ width: 768, height: 1024 })
    await assertNoHorizontalPageScroll(page, '768px error-recovered')
  })
})

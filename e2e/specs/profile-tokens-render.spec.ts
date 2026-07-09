/**
 * profile-tokens-render — render-time smoke for /profile#tokens (#1039).
 *
 * PR #1036 fixed a TypeError crash (Array.map on undefined .scopes) that the
 * server's @JsonInclude(NON_NULL) made impossible to catch in unit tests
 * with default DTO shapes. This spec exercises the real wire format: it
 * seeds two PATs via the real REST API — one WITHOUT a scopes field and
 * one WITH explicit scopes — then renders the page and asserts the table
 * shows both rows with ZERO console errors and ZERO unhandled page errors.
 */
import { test, expect, type ConsoleMessage } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../fixtures/auth-v3'
import { seedMixedScopeTokens, MIXED_SCOPE_TOKEN_SCOPES } from '../fixtures/pat'

const ENV = authEnv()

test.describe('Profile / Access tokens — mixed-scope render', () => {
  let seeded: Awaited<ReturnType<typeof seedMixedScopeTokens>>

  test.beforeAll(async () => {
    seeded = await seedMixedScopeTokens()
  })

  test.afterAll(async () => {
    if (seeded) await seeded.cleanup()
  })

  test('renders both scoped + unscoped tokens with zero console errors', async ({ page }) => {
    const pageErrors: Error[] = []
    const consoleErrors: ConsoleMessage[] = []
    page.on('pageerror', (e) => pageErrors.push(e))
    page.on('console', (m) => {
      if (m.type() === 'error') consoleErrors.push(m)
    })

    await loginViaKeycloak(page, ENV)

    // Capture the /api/v1/me/tokens fetch the page makes on mount.
    const tokensRespPromise = page.waitForResponse(
      (r) =>
        r.url().includes('/api/v1/me/tokens') &&
        r.request().method() === 'GET',
      { timeout: 15_000 },
    )
    const navResp = await page.goto(`${ENV.uiBaseUrl}/profile#tokens`)
    expect(navResp?.status(), 'profile route HTTP status').toBeLessThan(400)
    const tokensResp = await tokensRespPromise
    expect(tokensResp.ok(), `/api/v1/me/tokens GET status was ${tokensResp.status()}`).toBeTruthy()

    // The token table must mount and contain both seeded rows.
    const list = page.getByTestId('pat-list')
    await expect(list).toBeVisible({ timeout: 10_000 })

    const noScopesRow = page
      .locator('[data-testid="token-row"]')
      .filter({ has: page.locator(`td:has-text("${seeded.noScopes.name}")`) })
    const withScopesRow = page
      .locator('[data-testid="token-row"]')
      .filter({ has: page.locator(`td:has-text("${seeded.withScopes.name}")`) })

    await expect(noScopesRow, 'unscoped token row visible').toBeVisible()
    await expect(withScopesRow, 'scoped token row visible').toBeVisible()

    // The unscoped row renders the "all (legacy)" sentinel rather than crashing
    // on undefined .scopes. This is the regression #1036 fixed. NOTE: in the
    // current PatTable markup the sentinel <span> deliberately carries NO
    // data-testid (only the scoped branch renders `token-scopes`), so the
    // assertion is scoped to the row, not the testid. Same oracle: the
    // sentinel text is visible in THIS token's row.
    await expect(noScopesRow).toContainText(/all \(legacy\)/i)

    // The scoped row renders each scope as a badge — we don't pin badge text
    // beyond it appearing somewhere in the cell. The strings come from the
    // fixture's exported constant (legal PatScopes.ALLOWED role names, #109)
    // so spec and seed can never drift apart again.
    const scopedCell = withScopesRow.getByTestId('token-scopes')
    for (const scope of MIXED_SCOPE_TOKEN_SCOPES) {
      await expect(scopedCell).toContainText(scope)
    }

    // The hard regression assertion: NO console errors, NO uncaught exceptions.
    // We allow benign warnings/info; an error-level message is a fail.
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

  // #1201 + #1186: the tokens tab reads as a deliberate framed card (no
  // left-of-centre dead zone) and the always-empty "Last used" column is
  // dropped. In the #1186 redesign the framing Card is the shared `SectionCard`
  // shell (H8) that wraps every tab; `pat-card` is its body content. Captures
  // the 1440px screenshot referenced in the PR.
  test('renders a framed card with no "Last used" column at 1440px', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await loginViaKeycloak(page, ENV)
    await page.goto(`${ENV.uiBaseUrl}/profile#tokens`)

    // The shared SectionCard shell is the deliberate `.card` frame (H8).
    const card = page.getByTestId('section-card')
    await expect(card).toBeVisible({ timeout: 10_000 })
    await expect(card).toHaveClass(/(^|\s)card(\s|$)/)

    // The tokens body + table mount inside the frame.
    await expect(page.getByTestId('pat-card')).toBeVisible()
    await expect(page.getByTestId('pat-list')).toBeVisible({ timeout: 10_000 })

    // Hard negative: the always-empty "Last used" column header is gone.
    await expect(
      page.getByRole('columnheader', { name: /last used/i }),
    ).toHaveCount(0)

    // Artifact used in the PR body — captures the tightened 1440px frame.
    await card.screenshot({ path: 'test-results/profile-tokens-1440.png' })
  })
})

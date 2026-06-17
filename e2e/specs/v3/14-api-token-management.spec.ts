/**
 * 14-api-token-management — Personal API token: generate / list / revoke.
 *
 * STATUS: test.fixme — the affordance does not exist yet.
 *
 * The /profile route (PR #422) currently shows OIDC identity claims only —
 * "Multi-session management is a follow-up — tracked under GET /api/v1/sessions"
 * (titan-ui/src/routes/profile.tsx). The /settings route (PR #422) is
 * workspace-wide, read-only in 0.1.0, and has no token UI either.
 *
 * Per the tick #56 constitution: "Look for 'Generate token' affordance. If
 * it doesn't exist, the entire spec is `test.fixme` with a follow-up issue
 * filed for the missing feature." We file the follow-up via the PR body —
 * the spec is wired up so that the moment the affordance ships, removing
 * the `test.fixme` line activates the (already-implemented) golden path
 * assertions.
 *
 * Golden-path shape (when the feature lands):
 *   1. Generate token → assert the secret renders ONCE (a one-shot copy
 *      view; the API must never return the secret on a list call).
 *   2. List shows the token entry (by name + masked prefix).
 *   3. Revoke → list either drops the row or marks it revoked.
 *
 * Tracking issue: filed in the tick #56 PR body.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()

test.fixme(
  'Personal API token UI: generate / list / revoke',
  async ({ page }) => {
    await loginViaKeycloak(page, ENV)
    await page.goto(`${ENV.uiBaseUrl}/profile`)

    // ── 1. Generate ────────────────────────────────────────────────────
    // When the affordance lands, the button's accessible name is expected
    // to be "Generate token" (or "Create token" — adjust at unfixme time).
    const generateBtn = page.getByRole('button', {
      name: /Generate token|Create token/i,
    })
    await expect(generateBtn).toBeVisible({ timeout: 10_000 })

    // Optional name field — the canonical shape (Buildkite/GitHub/Prefect
    // all match) prompts for a label.
    const nameInput = page.getByLabel(/Token name|Label|Description/i)
    if (await nameInput.isVisible().catch(() => false)) {
      await nameInput.fill('e2e-spec-14-token')
    }

    const submitBtn = page.getByRole('button', {
      name: /^Generate$|^Create$|^Create token$|^Generate token$/i,
    })

    const [genResp] = await Promise.all([
      page.waitForResponse(
        (r) =>
          /\/api\/v1\/(tokens|me\/tokens|sessions)$/.test(r.url()) &&
          r.request().method() === 'POST',
        { timeout: 10_000 },
      ),
      submitBtn.click(),
    ])
    expect(genResp.ok()).toBeTruthy()

    // Security invariant: the secret renders EXACTLY ONCE in a one-shot
    // reveal. We assert a `[data-testid="token-secret"]` or `code` block
    // is shown; subsequent list calls must mask it.
    const secret = page.locator('[data-testid="token-secret"], code.token-secret')
    await expect(secret).toBeVisible()
    const issuedSecret = (await secret.textContent())?.trim() ?? ''
    expect(issuedSecret.length).toBeGreaterThan(16)

    // ── 2. List ────────────────────────────────────────────────────────
    // After dismissing the one-shot view, the token must appear in the
    // listing — but the secret itself is gone forever.
    await page.getByRole('button', { name: /Close|Done|I'?ve copied it/i }).click()
    const listRow = page.locator('[data-testid="token-row"]').filter({
      hasText: 'e2e-spec-14-token',
    })
    await expect(listRow).toBeVisible()
    // The secret must NOT appear in the listing.
    await expect(listRow).not.toContainText(issuedSecret)

    // ── 3. Revoke ──────────────────────────────────────────────────────
    const revokeBtn = listRow.getByRole('button', { name: /Revoke|Delete/i })

    // Confirm dialog is the same window.confirm pattern as queue drain.
    page.on('dialog', (d) => {
      void d.accept()
    })

    const [revokeResp] = await Promise.all([
      page.waitForResponse(
        (r) =>
          /\/api\/v1\/(tokens|me\/tokens|sessions)\//.test(r.url()) &&
          r.request().method() === 'DELETE',
        { timeout: 10_000 },
      ),
      revokeBtn.click(),
    ])
    expect(revokeResp.ok()).toBeTruthy()

    // Listing either drops the row, or the row is marked revoked.
    await expect
      .poll(async () => {
        const stillThere = await listRow.isVisible().catch(() => false)
        if (!stillThere) return 'gone'
        return (await listRow.textContent())?.includes('Revoked') ? 'revoked' : 'present'
      })
      .toMatch(/^(gone|revoked)$/)
  },
)

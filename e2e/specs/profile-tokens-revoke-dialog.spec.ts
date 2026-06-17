/**
 * profile-tokens-revoke-dialog — themed-modal pinning for the revoke flow (#1039).
 *
 * PR #1037 swapped the revoke confirmation from native window.confirm() to
 * the themed ConfirmDialog (role="alertdialog"). This spec pins:
 *   - clicking Revoke opens role=alertdialog, NOT a native dialog
 *   - Cancel closes the dialog without firing the DELETE
 *   - Confirm fires the DELETE and the row reflects revoked state
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../fixtures/auth-v3'
import { seedMixedScopeTokens } from '../fixtures/pat'

const ENV = authEnv()

test.describe('Profile / Access tokens — revoke uses themed ConfirmDialog', () => {
  let seeded: Awaited<ReturnType<typeof seedMixedScopeTokens>>

  test.beforeAll(async () => {
    seeded = await seedMixedScopeTokens({ prefixLabel: 'e2e-1039-rev' })
  })

  test.afterAll(async () => {
    if (seeded) await seeded.cleanup()
  })

  test('Cancel closes the dialog without firing DELETE; Confirm fires it', async ({ page }) => {
    // If the page ever fell back to native window.confirm, this listener
    // would auto-dismiss it and the test would still drive forward — that
    // would hide a regression. We REJECT native dialogs by failing the test
    // explicitly if one ever fires.
    let nativeDialogFired = false
    page.on('dialog', async (d) => {
      nativeDialogFired = true
      // Dismiss so the page isn't stuck if something does surface a native one.
      await d.dismiss().catch(() => {})
    })

    // Track DELETE calls so we can prove Cancel does NOT fire one.
    const deleteUrls: string[] = []
    page.on('request', (r) => {
      if (r.method() === 'DELETE' && r.url().includes('/api/v1/me/tokens/')) {
        deleteUrls.push(r.url())
      }
    })

    await loginViaKeycloak(page, ENV)
    await page.goto(`${ENV.uiBaseUrl}/profile#tokens`)
    await expect(page.getByTestId('pat-list')).toBeVisible({ timeout: 10_000 })

    const targetName = seeded.withScopes.name
    const row = page
      .locator('[data-testid="token-row"]')
      .filter({ has: page.locator(`td:has-text("${targetName}")`) })
    await expect(row).toBeVisible()

    // ── Open the dialog ────────────────────────────────────────────────
    await row.getByRole('button', { name: /Revoke/i }).click()
    const dialog = page.getByRole('alertdialog')
    await expect(dialog, 'themed ConfirmDialog opens with role=alertdialog').toBeVisible()
    await expect(dialog).toHaveAttribute('aria-modal', 'true')
    await expect(dialog).toContainText(targetName)

    // ── Cancel ─────────────────────────────────────────────────────────
    await page.getByTestId('revoke-token-dialog-cancel').click()
    await expect(dialog).not.toBeVisible()
    expect(deleteUrls, 'Cancel must not fire a DELETE').toEqual([])

    // ── Re-open + Confirm ──────────────────────────────────────────────
    await row.getByRole('button', { name: /Revoke/i }).click()
    await expect(page.getByRole('alertdialog')).toBeVisible()

    const [deleteResp] = await Promise.all([
      page.waitForResponse(
        (r) =>
          /\/api\/v1\/me\/tokens\//.test(r.url()) && r.request().method() === 'DELETE',
        { timeout: 10_000 },
      ),
      page.getByTestId('revoke-token-dialog-confirm').click(),
    ])
    expect(deleteResp.ok()).toBeTruthy()

    // Row is either removed or marked Revoked.
    await expect
      .poll(async () => {
        const stillThere = await row.isVisible().catch(() => false)
        if (!stillThere) return 'gone'
        const status = await row.getAttribute('data-token-status')
        return status === 'revoked' ? 'revoked' : 'present'
      }, { timeout: 10_000 })
      .toMatch(/^(gone|revoked)$/)

    // Hard regression check: a native confirm() would have surfaced as a
    // page.on('dialog') event. The themed modal must NOT trigger that.
    expect(nativeDialogFired, 'no native confirm() dialog should fire').toBe(false)
  })
})

/**
 * 20-new-job-flow — golden path: SRE creates a job from /jobs via the dialog.
 *
 * Closes #512 (UI follow-up to #511 / POST /api/v1/jobs). Drives the dialog
 * end-to-end against the live rig:
 *   1. Land on /jobs (Keycloak login already handled by the fixture).
 *   2. Click "New job" → dialog opens.
 *   3. Fill a unique fullName + accept the default pipeline script.
 *   4. Submit → 201 → router navigates to /jobs/$newId.
 *
 * The fullName is namespaced with Date.now() so re-runs don't 409. The new job
 * detail page renders the displayName the server materialised from fullName.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()

test.describe('v3 new-job dialog', () => {
  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
  })

  test('creating a job via the New-job dialog navigates to /jobs/<newId>', async ({ page }) => {
    await page.goto(ENV.uiBaseUrl + '/jobs')
    await expect(page.getByRole('heading', { name: /^jobs$/i })).toBeVisible()

    // Open the dialog.
    await page.getByTestId('new-job-open').click()
    await expect(page.getByRole('dialog', { name: /new job/i })).toBeVisible()

    // Unique name per run so a re-run never 409s on this rig.
    const fullName = `e2e/new-job-${Date.now()}`
    await page.getByTestId('new-job-fullname').fill(fullName)

    // Default pipeline script is pre-filled and valid; accept it.
    const submit = page.getByTestId('new-job-submit')
    await expect(submit).toBeEnabled()
    await submit.click()

    // Backend returns 201 → router navigates to /jobs/<newId>.
    await page.waitForURL(/\/jobs\/\d+$/, { timeout: 15_000 })
    // Job detail page renders the job name somewhere on screen.
    await expect(page.getByText(fullName, { exact: false })).toBeVisible({ timeout: 10_000 })
  })

  test('400 from invalid YAML renders inline below the textarea (NOT in a toast)', async ({
    page,
  }) => {
    await page.goto(ENV.uiBaseUrl + '/jobs')
    await page.getByTestId('new-job-open').click()
    await expect(page.getByRole('dialog', { name: /new job/i })).toBeVisible()

    await page.getByTestId('new-job-fullname').fill(`e2e/invalid-${Date.now()}`)
    // Replace the default script with something the server-side TitanYamlParser rejects.
    await page.getByTestId('new-job-script').fill('this: is: not: a: pipeline')

    await page.getByTestId('new-job-submit').click()
    // Inline error appears under the textarea; dialog stays open.
    await expect(page.getByTestId('new-job-script-error')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('dialog', { name: /new job/i })).toBeVisible()
  })
})

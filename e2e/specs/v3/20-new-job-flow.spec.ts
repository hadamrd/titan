/**
 * 20-new-job-flow — golden path: SRE creates a job from the list page dialog.
 *
 * Closes #512 (UI follow-up to #511 / POST /api/v1/jobs). Drives the dialog
 * end-to-end against the live rig:
 *   1. Land on /jobs — the legacy route 301s to /pipelines (design 66 renamed
 *      the jobs vocabulary to "pipelines"; routes/jobs/index.tsx is a
 *      redirect). Entering via /jobs keeps the redirect covered.
 *   2. Click "New job" → dialog opens.
 *   3. Fill a unique fullName + accept the default pipeline script.
 *   4. Submit → 201 → router navigates to /pipelines/$newId
 *      (NewJobDialog.tsx navigates to '/pipelines/$pipelineId').
 *
 * (#118 drive-by: the spec pre-dated the design-66 rename and still asserted
 * the "Jobs" heading + /jobs/$id navigation — it had been failing on trunk.)
 *
 * The fullName is namespaced with Date.now() so re-runs don't 409. The new job
 * detail page renders the displayName the server materialised from fullName.
 *
 * Teardown (#118): the dialog-created jobs used to have NO teardown — every
 * run leaked one `e2e/new-job-<ts>` row (and, if the 400 guard ever
 * regressed, an `e2e/invalid-<ts>` row too). afterAll sweeps BOTH name
 * patterns via safeDeleteJobCascade. Pattern-scoped deletion is own-rows
 * safe here because this spec is the only writer of either prefix (grep
 * `e2e/new-job-` / `e2e/invalid-` across e2e/ — no other creator), and the
 * sweep doubles as cleanup of litter left by earlier revisions of this spec.
 */
import { test, expect, request as pwRequest } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { pgClient } from '../../fixtures/seed-v3'
import { safeDeleteJobCascade } from '../../fixtures/teardown-v3'

const ENV = authEnv()

test.describe('v3 new-job dialog', () => {
  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
  })

  // Own APIRequestContext — the test-scoped `request` fixture is not available
  // in afterAll (same shape as 59-cron's safety-net teardown).
  test.afterAll(async () => {
    test.setTimeout(180_000)
    const client = pgClient()
    await client.connect()
    let jobIds: number[] = []
    try {
      const res = await client.query<{ id: string }>(
        `SELECT id::text AS id FROM titan.jobs
          WHERE full_name LIKE 'e2e/new-job-%' OR full_name LIKE 'e2e/invalid-%'`,
      )
      jobIds = res.rows.map((r) => Number(r.id))
    } finally {
      await client.end()
    }
    if (jobIds.length === 0) return

    const ctx = await pwRequest.newContext()
    try {
      for (const jobId of jobIds) {
        const result = await safeDeleteJobCascade(ctx, jobId)
        if (!result.deleted) {
          console.warn(
            `[20-new-job] teardown left job ${jobId} in place ` +
              `(leftoverBuildIds=${JSON.stringify(result.leftoverBuildIds)}) — see teardown-v3.ts`,
          )
        }
      }
    } finally {
      await ctx.dispose()
    }
  })

  test('creating a job via the New-job dialog navigates to /pipelines/<newId>', async ({
    page,
  }) => {
    await page.goto(ENV.uiBaseUrl + '/jobs')
    // Legacy /jobs must land on /pipelines (design 66 redirect).
    await page.waitForURL(/\/pipelines$/, { timeout: 15_000 })
    await expect(page.getByRole('heading', { name: /^pipelines$/i })).toBeVisible()

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

    // Backend returns 201 → router navigates to /pipelines/<newId>.
    await page.waitForURL(/\/pipelines\/\d+$/, { timeout: 15_000 })
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

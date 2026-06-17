/**
 * 11-cancel-running-build — Admin cancels a RUNNING build mid-flight.
 *
 * Pre-req: `task dev:titan` is up (seed-data.sh ran, so titan-ui has a
 * RUNNING build per PR #430).
 *
 * Affordance under test: the "Cancel" button on /builds/$id (PR #412 —
 * rendered only when `!terminal`, i.e. status NOT in
 * SUCCESS/FAILED/ABORTED/UNSTABLE — see titan-ui/src/routes/builds/$buildId.tsx).
 *
 * Endpoint exercised: POST /api/v1/builds/{id}/cancel — BuildDetailApi
 * delegates to BuildAbortService, which flips the build to ABORTED and
 * terminalises every in-flight flow_node (titan-server/.../flow/BuildAbortService.java).
 *
 * Assertions (lightweight — per the Playwright-Lightweight-Checks rule we
 * watch the network call, not a full snapshot):
 *   1. Cancel button is visible while the build is RUNNING.
 *   2. Clicking it fires POST /api/v1/builds/{id}/cancel and the server
 *      returns 2xx (the API returns 202 on success per BuildDetailApi).
 *   3. The build row in titan.builds is now ABORTED.
 *
 * Teardown: reset the build status back to RUNNING so downstream specs
 * (gate-approval, replay, etc.) that key off the seeded running build keep
 * passing. We deliberately do NOT re-run seed-data.sh — it's idempotent
 * and would skip because rows already exist.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { findRunningUiBuildId, readBuildStatus, setBuildStatus } from '../../fixtures/seed-v3'

const ENV = authEnv()

let buildId: number

test.beforeAll(async () => {
  buildId = await findRunningUiBuildId()
})

test.afterEach(async () => {
  // Restore the seed invariant: titan-ui has a RUNNING build for other specs.
  await setBuildStatus(buildId, 'RUNNING')
})

test('Cancel button aborts a running build via POST /cancel', async ({ page }) => {
  await loginViaKeycloak(page, ENV)
  await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)

  // Page-header cancel button — accessible name is literal "Cancel" while
  // the build is non-terminal. The same button renders as "Cancelling…"
  // while the mutation is pending, so we key off the initial name.
  const cancelBtn = page.getByRole('button', { name: /^Cancel$/ })
  await expect(cancelBtn).toBeVisible({ timeout: 10_000 })
  await expect(cancelBtn).toBeEnabled()

  // Fire the click and capture the API response in parallel.
  const [resp] = await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().includes(`/api/v1/builds/${buildId}/cancel`) &&
        r.request().method() === 'POST',
      { timeout: 10_000 },
    ),
    cancelBtn.click(),
  ])
  expect(resp.ok()).toBeTruthy()
  // BuildDetailApi returns 202 on success.
  expect(resp.status()).toBe(202)

  // DB read-back — BuildAbortService synchronously updates titan.builds.
  await expect
    .poll(() => readBuildStatus(buildId), { timeout: 10_000 })
    .toBe('ABORTED')
})

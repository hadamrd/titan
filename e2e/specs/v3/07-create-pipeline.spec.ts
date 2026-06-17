/**
 * 07-create-pipeline — onboarding wizard golden path (no backend job-create API yet).
 *
 * The CTO wants the "first 90 seconds" creation journey covered: an SRE lands,
 * opens the create-flow, fills repo URL → trigger → YAML → submits.
 *
 * Backend reality (per titan-ui/src/routes/onboarding.tsx javadoc):
 *   "A 'create job from URL + YAML' endpoint does NOT exist in 0.1.0."
 * Onboarding step 4 therefore triggers a build on an existing job instead.
 * The /jobs and /pipelines list pages also explicitly say "Create a job via
 * the API to get started" — there is NO "New job" button in the sidebar/UI.
 *
 * What this spec validates:
 *   - The onboarding wizard renders + is reachable from /onboarding.
 *   - Each of the 4 steps (Repo, Trigger, Pipeline, Run) advances under the
 *     SRE's actions.
 *   - The final "Run first build" action, when a seeded job exists (the PR #430
 *     fleet has 3+ jobs), triggers a build and navigates the SRE to
 *     /builds/<newId> — which is the closest behaviour the 0.1.0 product
 *     offers to "I created a pipeline, take me to its first build".
 *
 * Steps that DON'T exist in 0.1.0 are documented as `test.fixme` with the
 * follow-up gap so the inventory captures what's still missing:
 *   - "New job" button in the Jobs/Pipelines list pages (none exists).
 *   - Backend POST /api/v1/jobs accepting repoUrl + pipelineYaml.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()

test.describe('v3 create-pipeline (onboarding wizard)', () => {
  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
  })

  test('a "New job / Create pipeline" CTA on /jobs is not yet shipped', async ({ page }) => {
    // Hard rule from the brief: "If a 'New job' button doesn't exist, locate
    // where it WOULD be + assert empty state with a test.fixme."
    //
    // The Jobs index renders a page-header + a Table (when populated) or an
    // empty card. There's NO "New job" / "Create" button anywhere on the page.
    // Follow-up: design/?? — surface a CTA that opens /onboarding.
    await page.goto(ENV.uiBaseUrl + '/jobs')
    await expect(page.getByRole('heading', { name: /^jobs$/i })).toBeVisible()

    const newJobBtn = page.getByRole('button', { name: /new job|create pipeline|create job/i })
    const exists = (await newJobBtn.count()) > 0
    test.fixme(
      !exists,
      "no 'New job / Create pipeline' CTA on /jobs — follow-up: add a button that links to /onboarding",
    )
    // If it ever lands, click it and assert it navigates to /onboarding.
    await newJobBtn.first().click()
    await page.waitForURL(/\/onboarding$/)
  })

  test('onboarding wizard advances Repo → Trigger → Pipeline → Run', async ({ page }) => {
    await page.goto(ENV.uiBaseUrl + '/onboarding')
    await expect(
      page.getByRole('heading', { name: /let.+s run your first build/i }),
    ).toBeVisible({ timeout: 10_000 })

    // Step 1: Repo. Type a Git URL → Continue.
    await page.getByLabel('Git URL').fill('https://github.com/hadamrd/titan')
    await page.getByRole('button', { name: /^continue$/i }).click()

    // Step 2: Trigger. Default is "Run on demand"; just Continue.
    await expect(page.getByRole('heading', { name: /when should it run/i })).toBeVisible()
    // The radio group is keyed by name="trigger-kind"; "demand" is preselected.
    const demandRadio = page.locator('input[type=radio][name=trigger-kind][value=demand]')
    await expect(demandRadio).toBeChecked()
    await page.getByRole('button', { name: /^continue$/i }).click()

    // Step 3: Pipeline. Default mode is "default" (the lint/build/test YAML).
    await expect(page.getByRole('heading', { name: /step 3 — pipeline/i })).toBeVisible()
    const defaultRadio = page.locator('input[type=radio][name=yaml-mode][value=default]')
    await expect(defaultRadio).toBeChecked()
    await page.getByRole('button', { name: /^continue$/i }).click()

    // Step 4: Run.
    await expect(page.getByRole('heading', { name: /^step 4 — run it$/i })).toBeVisible()
    // The repo URL we typed in step 1 should be echoed back as confirmation.
    await expect(page.getByText('https://github.com/hadamrd/titan')).toBeVisible()
  })

  test('Run first build on an existing job navigates to /builds/<newId>', async ({ page }) => {
    // PR #430 seeds 6 enabled jobs (titan-server, titan-ui, titan-hello,
    // titan-server-ci, titan-ui-ci, integration-tests). useJobs(0,1) returns
    // the first by full_name ASC — alphabetically that's `integration-tests`
    // (enabled=true per seed-data.sh L640-648), so the wizard's
    // canTriggerBackend is true.
    await page.goto(ENV.uiBaseUrl + '/onboarding')
    // Skip straight to the run step by walking the wizard quickly.
    await page.getByLabel('Git URL').fill('https://github.com/example/repo')
    await page.getByRole('button', { name: /^continue$/i }).click()
    await page.getByRole('button', { name: /^continue$/i }).click()
    await page.getByRole('button', { name: /^continue$/i }).click()

    // The "Run first build" button is only enabled if a job exists.
    const runBtn = page.getByRole('button', { name: 'Run first build' })
    await expect(runBtn).toBeVisible()

    // If, for any reason, no enabled job is present, the button is disabled +
    // a fallback snippet shows instead. In that case we mark fixme so the
    // wizard's "no jobs" path is at least recorded.
    const disabled = await runBtn.isDisabled()
    test.fixme(
      disabled,
      'wizard run button disabled — no enabled job on the rig (re-run rig/local/seed-data.sh)',
    )

    // Click + assert we land on a /builds/<id> route. The trigger mutation
    // navigates on success via onSuccess → navigate({to:'/builds/$buildId'}).
    await runBtn.click()
    await page.waitForURL(/\/builds\/\d+$/, { timeout: 15_000 })
    // Build-detail header reads "Builds / #<n>" — same anchor used by spec 06.
    await expect(page.getByRole('heading', { name: /builds.*#/i })).toBeVisible({
      timeout: 10_000,
    })
  })
})

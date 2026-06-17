/**
 * 08-configure-trigger — trigger configuration on an existing job.
 *
 * The SRE's daily-driver journey includes "I need to change how this job is
 * triggered" — flip on-demand → cron, edit a cron spec, or attach a GitHub
 * webhook. In 0.1.0 the wizard at /onboarding picks a trigger kind at job
 * creation, but there is NO post-create edit UI on /jobs/$id.
 *
 * What this spec validates:
 *   - The job-detail page (/jobs/$id) for a seeded job renders the job's
 *     summary (full name, folder, status, last-updated) — the same surface a
 *     trigger-edit affordance would attach to.
 *   - The current read-only display is documented + the gap is captured.
 *
 * Steps that DON'T exist in 0.1.0 are documented as `test.fixme`:
 *   - An "Edit triggers" / "Configure" button on the job-detail page.
 *   - A read-only display of the job's currently-configured triggers
 *     (pipeline.yml `triggers:` section in the YAML or DB-stored config).
 *
 * Anchor: seed-data.sh seeds `titan-hello` with a manual on-demand pipeline.
 * The PR #430 fleet adds `titan-server-ci` etc. We pick the first one we find
 * via the API and walk to its detail page.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { pgClient } from '../../fixtures/seed-v3'

const ENV = authEnv()

let seededJobId: number

test.beforeAll(async () => {
  // Pick the first enabled seeded job; the URL pattern is /jobs/$jobId.
  // titan-hello is created by seed-data.sh L462+; it's always present after seed.
  const client = pgClient()
  await client.connect()
  try {
    const res = await client.query<{ id: string }>(
      `SELECT id::text AS id FROM titan.jobs WHERE full_name='titan-hello' LIMIT 1`,
    )
    const row = res.rows[0]
    if (!row) {
      throw new Error("seed-data.sh hasn't run — titan-hello job missing")
    }
    seededJobId = Number(row.id)
  } finally {
    await client.end()
  }
})

test.describe('v3 configure-trigger', () => {
  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
  })

  test('job-detail page renders job summary affordance for trigger config', async ({ page }) => {
    await page.goto(`${ENV.uiBaseUrl}/jobs/${seededJobId}`)

    // The job-detail header shows the displayName.
    await expect(page.getByText('Titan Hello').first()).toBeVisible({ timeout: 10_000 })
    // Read-only summary fields (full name + folder + status + updated).
    await expect(page.getByText('Full name')).toBeVisible()
    await expect(page.getByText('Status')).toBeVisible()
    // The job's full_name is rendered in mono.
    await expect(page.getByText('titan-hello', { exact: true })).toBeVisible()
  })

  test('"Edit triggers" affordance is not yet shipped on /jobs/$id', async ({ page }) => {
    await page.goto(`${ENV.uiBaseUrl}/jobs/${seededJobId}`)

    // The only button on the job-detail page today is "Trigger build". No
    // "Edit triggers" / "Configure" / "Settings" affordance exists.
    const editBtn = page.getByRole('button', {
      name: /edit trigger|configure trigger|edit job|configure job|settings/i,
    })
    const exists = (await editBtn.count()) > 0
    test.fixme(
      !exists,
      "no trigger-edit UI on /jobs/$id — follow-up: surface a 'Configure triggers' button + drawer/modal",
    )
    await editBtn.first().click()
  })

  test('read-only trigger display is not yet shipped on /jobs/$id', async ({ page }) => {
    await page.goto(`${ENV.uiBaseUrl}/jobs/${seededJobId}`)

    // Even without an edit UI, the SRE should see WHAT triggers are configured
    // (cron spec, github webhook URL, manual). Today nothing on the page
    // surfaces this; the trigger config lives in titan.jobs.pipeline_script as
    // YAML — never parsed for display.
    const triggersHeading = page.getByText(/^triggers$/i)
    const triggersCard = page.locator('[data-testid="triggers-section"]')
    const found =
      (await triggersHeading.count()) > 0 || (await triggersCard.count()) > 0
    test.fixme(
      !found,
      "no read-only triggers display on /jobs/$id — follow-up: parse pipeline_script.triggers + render a chip per trigger",
    )
    await expect(triggersHeading.first().or(triggersCard.first())).toBeVisible()
  })

  test('the manual "Trigger build" button is reachable + clickable on /jobs/$id', async ({
    page,
  }) => {
    // What IS shipped: a manual on-demand trigger button. We assert the
    // affordance exists + is enabled (job seeded as enabled=true).
    await page.goto(`${ENV.uiBaseUrl}/jobs/${seededJobId}`)

    const trigger = page.getByRole('button', { name: /trigger build/i })
    await expect(trigger).toBeVisible({ timeout: 10_000 })
    await expect(trigger).toBeEnabled()
  })
})

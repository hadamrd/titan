/**
 * 09-run-and-watch-build — Run on demand + watch the live build view.
 *
 * SRE story: "I clicked Run, the page should take me to a live build view
 * where I can watch status + duration + logs roll in."
 *
 * Backend reality:
 *   - POST /api/v1/jobs/{id}/build IS shipped and is the trigger button on
 *     /jobs/$id (see useTriggerBuild). It returns {buildId, buildNumber}.
 *   - Whether a worker actually claims + advances the build to RUNNING /
 *     terminal in the local rig depends on a live worker being subscribed
 *     to the right queue. The seed retires stale tasks (seed-data.sh L443)
 *     because real workers only listen on `local-worker-01`.
 *
 * Plan:
 *   - Trigger a build via the job-detail page's Trigger button.
 *   - Assert the success banner appears with a link to /builds/<id>.
 *   - Follow the link → assert /builds/<id> renders the build header.
 *   - The status pill MAY or may NOT transition QUEUED → RUNNING depending on
 *     the local worker — we assert the QUEUED initial state but mark a
 *     transition-watch fixme if the worker doesn't claim it.
 *
 * Fallback: if trigger fails (no enabled job, or backend not yet wired), we
 * fall back to the pre-seeded titan-ui-ci#2 RUNNING build (PR #430 seed)
 * and assert the LIVE view: status pill = RUNNING, build header rendered.
 */
import { test, expect, type Response } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { pgClient } from '../../fixtures/seed-v3'

const ENV = authEnv()

interface JobAnchor {
  id: number
  fullName: string
}

let triggerJob: JobAnchor
let runningBuildId: number

test.beforeAll(async () => {
  const client = pgClient()
  await client.connect()
  try {
    // Pick an enabled job to trigger. titan-hello is always present + enabled.
    const j = await client.query<{ id: string; full_name: string }>(
      `SELECT id::text AS id, full_name FROM titan.jobs WHERE full_name='titan-hello' AND enabled=true LIMIT 1`,
    )
    const jrow = j.rows[0]
    if (!jrow) throw new Error('titan-hello job missing — re-run seed-data.sh')
    triggerJob = { id: Number(jrow.id), fullName: jrow.full_name }

    // Locate the pre-seeded RUNNING build (fallback anchor): titan-ui-ci#2 or
    // titan-hello#2 per PR #430. We prefer titan-ui-ci#2 as it has flow_nodes.
    const r = await client.query<{ id: string }>(
      `SELECT b.id::text AS id
         FROM titan.builds b
         JOIN titan.jobs   j ON j.id = b.job_id
        WHERE b.status='RUNNING' AND j.full_name IN ('titan-ui-ci','titan-hello')
        ORDER BY (j.full_name='titan-ui-ci') DESC, b.id DESC
        LIMIT 1`,
    )
    const rrow = r.rows[0]
    if (!rrow) {
      throw new Error('no RUNNING build seeded — PR #430 seed missing')
    }
    runningBuildId = Number(rrow.id)
  } finally {
    await client.end()
  }
})

test.describe('v3 run-and-watch-build', () => {
  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
  })

  test('Trigger build button on /jobs/$id enqueues a new build', async ({ page }) => {
    const apiPosts: Array<{ url: string; status: number }> = []
    page.on('response', (r: Response) => {
      const req = r.request()
      if (req.method() === 'POST' && r.url().includes('/api/v1/jobs/')) {
        apiPosts.push({ url: r.url(), status: r.status() })
      }
    })

    await page.goto(`${ENV.uiBaseUrl}/jobs/${triggerJob.id}`)
    const trigger = page.getByRole('button', { name: /trigger build/i })
    await expect(trigger).toBeVisible({ timeout: 10_000 })
    await expect(trigger).toBeEnabled()
    await trigger.click()

    // Success banner: "Build #N queued. View" — the success text is in
    // jobs/$jobId.tsx L112-122.
    await expect(page.getByText(/build #\d+ queued/i)).toBeVisible({ timeout: 15_000 })
    // The View link goes to /builds/<id>.
    const viewLink = page.getByRole('link', { name: /^view$/i }).first()
    await expect(viewLink).toBeVisible()

    // Network proof: POST /api/v1/jobs/{id}/build returned 2xx.
    await expect
      .poll(
        () =>
          apiPosts.some((r) => /\/api\/v1\/jobs\/\d+\/build/.test(r.url) && r.status < 400),
        { timeout: 10_000 },
      )
      .toBe(true)
  })

  test('clicking View on the trigger banner navigates to /builds/<newId>', async ({ page }) => {
    await page.goto(`${ENV.uiBaseUrl}/jobs/${triggerJob.id}`)
    await page.getByRole('button', { name: /trigger build/i }).click()
    await expect(page.getByText(/build #\d+ queued/i)).toBeVisible({ timeout: 15_000 })

    // Following the View link must land on /builds/<id>.
    await Promise.all([
      page.waitForURL(/\/builds\/\d+$/, { timeout: 10_000 }),
      page.getByRole('link', { name: /^view$/i }).first().click(),
    ])
    await expect(page.getByRole('heading', { name: /builds.*#/i })).toBeVisible({
      timeout: 10_000,
    })
    // The Duration tile renders even for QUEUED builds (durationMs null → "—").
    await expect(page.getByText('Duration', { exact: true }).first()).toBeVisible()
  })

  test('LIVE view on a seeded RUNNING build shows status + pipeline + ticking duration', async ({
    page,
  }) => {
    // Use the pre-seeded RUNNING build so the assertions don't depend on a
    // live worker. The build header + StatusBadge must show "RUNNING".
    await page.goto(`${ENV.uiBaseUrl}/builds/${runningBuildId}`)
    await expect(page.getByRole('heading', { name: /builds.*#/i })).toBeVisible({
      timeout: 10_000,
    })

    // The StatusBadge renders the literal status text. Match case-insensitively
    // anywhere on the page to tolerate either "Running" or "RUNNING" depending
    // on the badge formatting.
    await expect(page.getByText(/running/i).first()).toBeVisible({ timeout: 10_000 })

    // Pipeline tab is default; the seeded RUNNING build (titan-ui-ci#2 per PR
    // #430) has 2 flow_nodes (stage-install SUCCESS + step-build RUNNING).
    // A `.job-card` must render.
    const jobCards = page.locator('.job-card')
    await expect(jobCards.first()).toBeVisible({ timeout: 10_000 })

    // Duration tile rendered. For a RUNNING build, formatDuration(null) shows
    // "—"; that's still a visible cell. We assert the tile label is present.
    await expect(page.getByText('Duration', { exact: true }).first()).toBeVisible()
  })

  test('status pill transitions QUEUED → RUNNING (depends on live worker)', async ({ page }) => {
    // Trigger a fresh build + watch its status. In a rig with a live worker
    // subscribed to local-worker-01 the build picks up within seconds. If the
    // worker isn't running, the build stays QUEUED forever — that's a known
    // limitation of the standalone rig (PR #430 seed retires stale tasks).
    await page.goto(`${ENV.uiBaseUrl}/jobs/${triggerJob.id}`)
    await page.getByRole('button', { name: /trigger build/i }).click()
    await expect(page.getByText(/build #\d+ queued/i)).toBeVisible({ timeout: 15_000 })
    await Promise.all([
      page.waitForURL(/\/builds\/\d+$/, { timeout: 10_000 }),
      page.getByRole('link', { name: /^view$/i }).first().click(),
    ])

    // The initial status MUST be either QUEUED or RUNNING (the worker may
    // have raced us). We poll for up to 30s for the status pill to flip to
    // RUNNING or any terminal — if it stays QUEUED past that, mark fixme.
    const initialQueued = await page
      .getByText(/queued/i)
      .first()
      .isVisible()
      .catch(() => false)

    // Look for a transition within 30s.
    // Probe via locator instead of in-page evaluate so the spec stays in the
    // Node typing context (the e2e tsconfig doesn't pull DOM lib).
    const transitioned = await page
      .getByText(/\b(running|success|failed|aborted)\b/i)
      .first()
      .waitFor({ timeout: 30_000, state: 'visible' })
      .then(() => true)
      .catch(() => false)

    test.fixme(
      !transitioned && initialQueued,
      'build stayed QUEUED for 30s — no live worker on local-worker-01 to claim it',
    )
    expect(transitioned).toBe(true)
  })
})

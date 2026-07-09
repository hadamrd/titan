/**
 * jobs-sparklines — the inline "Duration trend (30d)" column on the /jobs
 * (→ /pipelines) index (issue #1096; promoted to @golden by the #138 audit).
 *
 * Audit verdict (#138): REPLACED. The pre-standards revision depended on
 * `rig/local/seed-data.sh` having run, grabbed "the first row" of whatever
 * foreign jobs happened to exist, and probed THAT job's duration-trend
 * endpoint — so it asserted nothing about known data (any array shape
 * passed) and failed on a clean rig. This revision follows the
 * spec-ownership rule (e2e/README, #59): it seeds its OWN job with a known
 * build history, asserts the sparkline + endpoint against exactly those
 * rows, and deletes them afterwards. Zero litter, no foreign-row coupling.
 *
 * What is asserted:
 *   - The "Duration trend (30d)" column header renders on the index.
 *   - OUR seeded job's row renders a real SVG sparkline (`<path>` stroke),
 *     from the page-level bulk recent-builds fetch (#650 — no N+1 fan-out).
 *   - GET /api/v1/jobs/<ourId>/duration-trend?n=30 returns 200 and EXACTLY
 *     our seeded builds (count, status, positive durations) — the endpoint
 *     is measured against known data, not just a JSON shape.
 *   - No /api/v1 request 401s during the page visit (bearer wiring).
 *
 * Seeding: `seedOwnedJobWithBuilds` (engine-inert rows — no task_queue rows,
 * the worker never touches them) with 4 finished SUCCESS builds carrying
 * durations. 4 ≥ 2 = the Sparkline's minimum to draw a line, and 0 failures
 * keeps this job out of the top-failing surface exercised by spec 63.
 *
 * Bug classes this spec catches:
 *   - The column header "Duration trend (30d)" missing entirely.
 *   - The duration-trend endpoint 401/404/500-ing (handler not registered,
 *     RBAC misconfigured, or SQL broken) or returning rows that don't match
 *     the job's real build history.
 *   - A job WITH history rendering the em-dash empty state (feature no-op).
 */
import { test, expect, type Response } from '@playwright/test'
import { authEnv, loginViaKeycloak, fetchBearerToken } from '../../fixtures/auth-v3'
import { deleteOwnedJob, seedOwnedJobWithBuilds, type OwnedJobSeed } from '../../fixtures/seed-v3'

const ENV = authEnv()

/** 4 finished SUCCESS builds — enough history for a real sparkline line (≥2). */
const SEEDED_BUILDS = 4

// @golden sits BEFORE the parenthetical: dev/rig-smoke/golden-count.sh greps
// `describe\([^)]*@golden`, so a `)` ahead of the tag would drop this spec
// from the golden floor.
test.describe('v3 /jobs duration-trend sparklines @golden (#1096, re-baselined by #138)', () => {
  let seed: OwnedJobSeed | undefined

  test.afterEach(async () => {
    // Ownership teardown — engine-inert rows, so the direct delete is safe
    // (no task_queue rows can exist for them; see seed-v3.ts).
    if (seed) {
      await deleteOwnedJob(seed.jobId)
      seed = undefined
    }
  })

  test('the seeded job renders a real sparkline and its endpoint returns exactly the seeded history', async ({
    page,
    request,
  }) => {
    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
    seed = await seedOwnedJobWithBuilds(
      `e2e-sparkline-${runTag}`,
      Array.from({ length: SEEDED_BUILDS }, () => ({ status: 'SUCCESS' })),
    )

    const responses: Array<{ url: string; status: number }> = []
    page.on('response', (r: Response) => {
      if (r.url().includes('/api/v1/')) responses.push({ url: r.url(), status: r.status() })
    })

    await loginViaKeycloak(page, ENV)

    // /jobs redirects to /pipelines (design 66 vocabulary); the duration-trend
    // column lives on that index.
    await page.goto(`${ENV.uiBaseUrl}/jobs`)
    await expect(page.getByRole('heading', { name: /^pipelines$/i })).toBeVisible({
      timeout: 5_000,
    })
    await expect(
      page.getByRole('columnheader', { name: /duration trend \(30d\)/i }),
    ).toBeVisible({ timeout: 5_000 })

    // OUR row must render — the index pages by full_name with limit=50, and
    // per-run-unique `e2e-*` names keep the fleet small on a zero-litter rig.
    await expect(
      page.getByTestId(`job-row-${seed.jobId}`),
      `job-row-${seed.jobId} (${seed.fullName}) not on the index — either the seed failed or ` +
        `the rig carries >50 jobs and the row fell off page 1 (a litter violation worth chasing).`,
    ).toBeVisible({ timeout: 10_000 })

    // A job WITH ≥2 finished durations must render the real SVG sparkline —
    // the em-dash empty state here would mean the feature is a no-op against
    // real data (the pre-#138 revision could not catch this per-job).
    const trendCell = page.getByTestId(`job-row-${seed.jobId}-duration-trend`)
    await expect(
      trendCell,
      `job ${seed.jobId} has ${SEEDED_BUILDS} finished builds with durations but rendered ` +
        `no duration-trend sparkline (empty state or missing cell).`,
    ).toBeVisible({ timeout: 10_000 })
    await expect
      .poll(async () => trendCell.locator('svg path').count(), {
        timeout: 10_000,
        message: `job ${seed.jobId} sparkline rendered no SVG <path> stroke`,
      })
      .toBeGreaterThan(0)

    // AC endpoint measured against KNOWN data: exactly our seeded builds.
    const token = await fetchBearerToken(ENV)
    const res = await request.get(
      `${ENV.uiBaseUrl}/api/v1/jobs/${seed.jobId}/duration-trend?n=30`,
      { headers: { Authorization: `Bearer ${token}` } },
    )
    expect(res.status(), 'duration-trend endpoint must be 200, never 401/404/500').toBe(200)
    const body = (await res.json()) as Array<{ ts: string; durationS: number; status: string }>
    expect(Array.isArray(body), 'duration-trend body must be a JSON array').toBe(true)
    expect(
      body.length,
      `duration-trend must return exactly the ${SEEDED_BUILDS} seeded finished builds, ` +
        `got ${body.length}: ${JSON.stringify(body)}`,
    ).toBe(SEEDED_BUILDS)
    for (const p of body) {
      expect(Number.isFinite(Date.parse(p.ts)), `point ts="${p.ts}" not ISO`).toBe(true)
      expect(p.durationS, `point durationS must be positive: ${JSON.stringify(p)}`).toBeGreaterThan(0)
      expect(p.status, 'every seeded build is SUCCESS').toBe('SUCCESS')
    }

    // No 401 anywhere during the page visit (shared bearer wiring).
    const got401 = responses.find((r) => r.status === 401)
    expect(got401, `401 on ${got401?.url} — auth wiring broken`).toBeUndefined()
  })
})

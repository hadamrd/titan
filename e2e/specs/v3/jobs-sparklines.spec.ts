/**
 * jobs-sparklines — the inline "Duration trend (30d)" column on the /jobs
 * (→ /pipelines) index (issue #1096).
 *
 * Acceptance criterion under test: "sparklines visible on /jobs index".
 *
 * The index renders the trend from the page-level bulk recent-builds fetch
 * (#650) — the same source as the status sparkline — so it does NOT fan out a
 * per-row duration-trend request (no N+1). We therefore split coverage:
 *   - the page proves the column + a real SVG sparkline render from bulk data;
 *   - a direct API probe proves the AC endpoint
 *     GET /api/v1/jobs/<id>/duration-trend?n=30 returns 200 + the right shape.
 *
 * Bug classes this spec catches:
 *   - The column header "Duration trend (30d)" missing entirely.
 *   - The duration-trend endpoint 401/404/500-ing (handler not registered,
 *     RBAC misconfigured, or SQL broken).
 *   - No row ever rendering an actual sparkline SVG for a job with history.
 *
 * Pre-req: `task dev:titan && bash rig/local/seed-data.sh`.
 */
import { test, expect, type Response } from '@playwright/test'
import { authEnv, loginViaKeycloak, fetchBearerToken } from '../../fixtures/auth-v3'

const ENV = authEnv()

test.describe('v3 /jobs duration-trend sparklines (#1096)', () => {
  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
  })

  test('the "Duration trend (30d)" column renders and its endpoint returns 200', async ({
    page,
    request,
  }) => {
    const responses: Array<{ url: string; status: number }> = []
    page.on('response', (r: Response) => {
      if (r.url().includes('/api/v1/')) responses.push({ url: r.url(), status: r.status() })
    })

    // /jobs redirects to /pipelines (design 66 vocabulary); the duration-trend
    // column lives on that index.
    await page.goto(`${ENV.uiBaseUrl}/jobs`)
    await expect(page.getByRole('heading', { name: /^pipelines$/i })).toBeVisible({
      timeout: 5_000,
    })
    await expect(
      page.getByRole('columnheader', { name: /duration trend \(30d\)/i }),
    ).toBeVisible({ timeout: 5_000 })

    // A row must exist so we can probe its single-job endpoint.
    const firstRow = page
      .locator('[data-testid^="job-row-"][data-testid$="-last-build"]')
      .first()
    await expect(firstRow).toBeVisible({ timeout: 10_000 })
    const rowTestId = await firstRow.getAttribute('data-testid')
    const jobId = rowTestId?.match(/job-row-(\d+)-last-build/)?.[1]
    expect(jobId, 'could not read a job id from the index').toBeTruthy()

    // AC endpoint: GET /api/v1/jobs/<id>/duration-trend?n=30 → 200 + array shape.
    const token = await fetchBearerToken(ENV)
    const res = await request.get(
      `${ENV.uiBaseUrl}/api/v1/jobs/${jobId}/duration-trend?n=30`,
      { headers: { Authorization: `Bearer ${token}` } },
    )
    expect(res.status(), 'duration-trend endpoint must be 200, never 401/404/500').toBe(200)
    const body = (await res.json()) as Array<{ ts: string; durationS: number; status: string }>
    expect(Array.isArray(body), 'duration-trend body must be a JSON array').toBe(true)
    for (const p of body) {
      expect(typeof p.ts).toBe('string')
      expect(typeof p.durationS).toBe('number')
      expect(typeof p.status).toBe('string')
    }

    // No 401 anywhere during the page visit (shared bearer wiring).
    const got401 = responses.find((r) => r.status === 401)
    expect(got401, `401 on ${got401?.url} — auth wiring broken`).toBeUndefined()
  })

  test('at least one row renders a duration-trend sparkline SVG or its empty state', async ({
    page,
  }) => {
    await page.goto(`${ENV.uiBaseUrl}/jobs`)
    await expect(page.getByRole('heading', { name: /^pipelines$/i })).toBeVisible({
      timeout: 5_000,
    })

    // Each row renders ONE of: the sparkline (data-testid …-duration-trend) or
    // the empty em-dash (…-duration-trend-empty). The feature is "visible" iff a
    // settled cell exists.
    const cell = page.locator(
      '[data-testid$="-duration-trend"], [data-testid$="-duration-trend-empty"]',
    )
    await expect(cell.first()).toBeVisible({ timeout: 10_000 })

    // A seeded job with build history must render an actual SVG sparkline (a real
    // <path> stroke), not only em-dashes — otherwise the feature is a no-op
    // against real data.
    await expect
      .poll(
        async () => page.locator('[data-testid$="-duration-trend"] svg path').count(),
        { timeout: 10_000, message: 'no row rendered a real sparkline SVG path' },
      )
      .toBeGreaterThan(0)
  })
})

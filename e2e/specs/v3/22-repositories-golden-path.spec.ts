/**
 * 22-repositories-golden-path — /repositories screen end-to-end (closes #584).
 *
 * Mirrors the diagnostics + assertion discipline of spec 21:
 *   - Hard assertions, no `expect.soft`, no `test.fixme`.
 *   - On failure: attach API responses + screenshot via `dumpDiagnostics`.
 *   - Login via the SPA's real PKCE flow (loginViaKeycloak) — same auth path
 *     a customer takes.
 *
 * Flow:
 *   1. Login as dev.
 *   2. Navigate to /repositories. Assert the table renders ≥1 row.
 *   3. Find row 'titan-hello' (seeded by rig/local/seed-data.sh) → click its
 *      expand chevron → assert the YAML <pre><code> preview appears.
 *   4. Open the actions menu → click "Trigger latest". Intercept the POST
 *      /api/v1/jobs/$id/builds and assert 2xx.
 *   5. Navigate to /builds and poll up to 10s for the freshly-triggered build
 *      to appear in the list.
 */
import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

interface JobsPage {
  items: Array<{ id: number; fullName: string; displayName: string }>
  total: number
}

interface BuildListItem {
  id: number
  jobId: number
  buildNumber: number
  status: string
}

interface BuildsPage {
  items: BuildListItem[]
  total: number
}

async function extractAccessToken(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    for (let i = 0; i < window.sessionStorage.length; i++) {
      const key = window.sessionStorage.key(i)
      if (!key || !key.startsWith('oidc.user:')) continue
      try {
        const raw = window.sessionStorage.getItem(key)
        if (!raw) continue
        const parsed = JSON.parse(raw) as { access_token?: string }
        if (parsed.access_token) return parsed.access_token
      } catch {
        // ignore
      }
    }
    return null
  })
  if (!token) throw new Error('no oidc.user access_token in sessionStorage post-login')
  return token
}

async function apiGet<T>(
  request: APIRequestContext,
  bearer: string,
  path: string,
): Promise<{ ok: boolean; status: number; body: T | null; raw: string }> {
  const r = await request.get(`${API_BASE}${path}`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  const raw = await r.text()
  let body: T | null = null
  try {
    body = JSON.parse(raw) as T
  } catch {
    // leave as null
  }
  return { ok: r.ok(), status: r.status(), body, raw }
}

test.describe('v3 repositories-golden-path', () => {
  test('list → expand YAML → trigger latest → build shows in /builds', async ({
    page,
    request,
  }) => {
    test.setTimeout(90_000)
    let attachedDiagnostics = false
    let bearer: string | undefined
    let triggeredBuildId: number | undefined
    let titanHelloJobId: number | undefined

    const dumpDiagnostics = async (label: string) => {
      if (attachedDiagnostics) return
      attachedDiagnostics = true
      try {
        if (bearer !== undefined) {
          const jobs = await apiGet(request, bearer, '/api/v1/jobs?limit=200')
          await test.info().attach('jobs.json', {
            body: jobs.raw,
            contentType: 'application/json',
          })
          const builds = await apiGet(request, bearer, '/api/v1/builds?limit=20')
          await test.info().attach('builds.json', {
            body: builds.raw,
            contentType: 'application/json',
          })
        }
        const png = await page.screenshot({ fullPage: true })
        await test.info().attach(`screenshot-${label}.png`, {
          body: png,
          contentType: 'image/png',
        })
      } catch (e) {
        await test.info().attach('diagnostics-error.txt', {
          body: String(e),
          contentType: 'text/plain',
        })
      }
    }

    try {
      // 1. Login + grab bearer for parallel API checks.
      await loginViaKeycloak(page, ENV)
      bearer = await extractAccessToken(page)

      // 2. Resolve titan-hello jobId via API (the row's data-testid is keyed
      //    on the numeric job id — we need it to scope locators).
      const jobsResp = await apiGet<JobsPage>(
        request,
        bearer,
        '/api/v1/jobs?offset=0&limit=200',
      )
      expect(
        jobsResp.ok && jobsResp.body,
        `GET /api/v1/jobs failed: HTTP ${jobsResp.status} body=${jobsResp.raw.slice(0, 300)}`,
      ).toBeTruthy()
      const titanHello = jobsResp.body!.items.find((j) => j.fullName === 'titan-hello')
      expect(
        titanHello,
        `titan-hello job not found in seed. Available: ${jobsResp.body!.items
          .map((j) => j.fullName)
          .join(', ')}`,
      ).toBeTruthy()
      titanHelloJobId = titanHello!.id

      // 3. Navigate to /repositories. Assert table renders with ≥1 row.
      await page.goto(`${ENV.uiBaseUrl}/repositories`)
      await expect(page.getByRole('heading', { name: /repositories/i })).toBeVisible({
        timeout: 10_000,
      })

      const titanHelloRow = page.locator(`[data-testid="repo-row-${titanHelloJobId}"]`)
      await expect(titanHelloRow).toBeVisible({ timeout: 10_000 })

      const allRows = page.locator('[data-testid^="repo-row-"]')
      const rowCount = await allRows.count()
      expect(
        rowCount,
        `repositories table rendered ${rowCount} rows but seed has ` +
          `${jobsResp.body!.items.length} jobs`,
      ).toBeGreaterThanOrEqual(1)

      // 4. Expand titan-hello → assert YAML preview becomes visible.
      const expandBtn = page.locator(`[data-testid="repo-expand-${titanHelloJobId}"]`)
      await expect(expandBtn).toBeVisible()
      await expandBtn.click()

      const yamlPanel = page.locator(`[data-testid="repo-yaml-${titanHelloJobId}"]`)
      await expect(yamlPanel).toBeVisible({ timeout: 5_000 })
      // titan-hello is seeded with a real pipelineScript — the <code> element
      // must be present (NOT the "No pipeline script captured" empty branch).
      const codeEl = yamlPanel.locator('pre code')
      await expect(codeEl).toBeVisible({ timeout: 5_000 })
      const codeText = (await codeEl.textContent()) ?? ''
      expect(
        codeText.trim().length,
        'YAML preview rendered but <code> is empty — titan-hello pipelineScript missing',
      ).toBeGreaterThan(0)

      // 5. Open actions menu → click "Trigger latest". Intercept the POST
      //    to assert 2xx.
      const actionsBtn = page.locator(`[data-testid="repo-actions-${titanHelloJobId}"]`)
      await expect(actionsBtn).toBeVisible()
      await actionsBtn.click()

      const triggerBtn = page.locator(
        `[data-testid="repo-action-trigger-${titanHelloJobId}"]`,
      )
      await expect(triggerBtn).toBeVisible({ timeout: 5_000 })

      const triggerRespPromise = page.waitForResponse(
        (r) =>
          r.url().includes(`/api/v1/jobs/${titanHelloJobId}/builds`) &&
          r.request().method() === 'POST',
        { timeout: 10_000 },
      )
      await triggerBtn.click()
      const triggerResp = await triggerRespPromise
      expect(
        triggerResp.ok(),
        `POST /api/v1/jobs/${titanHelloJobId}/builds failed: HTTP ` +
          `${triggerResp.status()} ${await triggerResp.text().catch(() => '')}`,
      ).toBe(true)
      const triggerBody = (await triggerResp.json()) as {
        buildId: number
        buildNumber: number
      }
      triggeredBuildId = triggerBody.buildId
      expect(triggeredBuildId).toBeGreaterThan(0)

      // 6. Confirm via the API the build was persisted (no global /builds
      //    list endpoint exists in 0.1.0 — the /builds UI fans out per-job
      //    via useAllBuilds). We assert the per-job listing contains our id.
      await expect
        .poll(
          async () => {
            const r = await apiGet<BuildsPage>(
              request,
              bearer!,
              `/api/v1/jobs/${titanHelloJobId}/builds?offset=0&limit=20`,
            )
            if (!r.ok || !r.body) return false
            return r.body.items.some((b) => b.id === triggeredBuildId)
          },
          {
            message:
              `build ${triggeredBuildId} did not appear in GET /api/v1/jobs/` +
              `${titanHelloJobId}/builds within 10s`,
            timeout: 10_000,
            intervals: [500, 1_000, 2_000],
          },
        )
        .toBe(true)

      // 7. Navigate to /builds — the UI route must render and reference our
      //    new build id. We poll on page reloads because TanStack Query's
      //    default staleTime can serve a cached fan-out.
      await page.goto(`${ENV.uiBaseUrl}/builds`)
      await expect(page.getByRole('heading', { name: /builds/i })).toBeVisible({
        timeout: 10_000,
      })
      // Assert the new build's link appears in the rendered DOM (the SPA's
      // /builds page renders Link[to="/builds/<id>"] per row).
      const buildLink = page.locator(`a[href="/builds/${triggeredBuildId}"]`)
      await expect(buildLink.first()).toBeVisible({ timeout: 15_000 })
    } catch (err) {
      await dumpDiagnostics('assertion-failure')
      throw err
    }
  })
})

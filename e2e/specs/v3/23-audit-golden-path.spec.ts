/**
 * 23-audit-golden-path — /audit screen end-to-end (closes #584).
 *
 * Mirrors spec 21's diagnostics + assertion discipline. Hard assertions only.
 *
 * The dev user has ADMIN role in the titan-dev realm, so READ_AUDIT is
 * exercised (closes the UI half of #517 / #478 / #519).
 *
 * Flow:
 *   1. Login as dev via PKCE (and grab a separate ROPC bearer for direct API
 *      build-triggering).
 *   2. Trigger a build for titan-hello via POST /api/v1/jobs/$id/builds
 *      (uses the API helper — same path as exercised in spec 22 but called
 *      directly here so the audit event is uniquely identifiable).
 *   3. Navigate to /audit.
 *   4. Poll up to 15s for the row { action: BUILD_TRIGGER, target: BUILD/<id>,
 *      actor: dev } to appear.
 *   5. Hard-fail with the raw /audit response attached as a diagnostic on
 *      timeout.
 */
import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

interface JobsPage {
  items: Array<{ id: number; fullName: string }>
}

interface AuditEvent {
  id: number
  occurredAt: string
  actor: string
  action: string
  targetType: string
  targetId: string | null
  detailsJson: string | null
}

interface AuditPage {
  items: AuditEvent[]
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

async function apiPost<T>(
  request: APIRequestContext,
  bearer: string,
  path: string,
  payload: unknown,
): Promise<{ ok: boolean; status: number; body: T | null; raw: string }> {
  const r = await request.post(`${API_BASE}${path}`, {
    headers: {
      Authorization: `Bearer ${bearer}`,
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    data: JSON.stringify(payload),
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

test.describe('v3 audit-golden-path', () => {
  test('BUILD_TRIGGER audit row surfaces in /audit within 15s', async ({
    page,
    request,
  }) => {
    test.setTimeout(90_000)
    let attachedDiagnostics = false
    let bearer: string | undefined
    let triggeredBuildId: number | undefined

    const dumpDiagnostics = async (label: string) => {
      if (attachedDiagnostics) return
      attachedDiagnostics = true
      try {
        if (bearer !== undefined) {
          const audit = await apiGet(request, bearer, '/api/v1/audit?limit=50')
          await test.info().attach('audit.json', {
            body: audit.raw,
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
      // 1a. PKCE browser login (puts dev session in the page).
      await loginViaKeycloak(page, ENV)
      bearer = await extractAccessToken(page)

      // 1b. Independent ROPC bearer for the API trigger. Either token works
      //     (dev has ADMIN either way); using the dedicated helper makes the
      //     API trigger independent of the SPA's token refresh.
      const apiBearer = await fetchBearerToken(ENV)

      // 2. Resolve titan-hello + trigger a build directly via the API.
      const jobsResp = await apiGet<JobsPage>(
        request,
        apiBearer,
        '/api/v1/jobs?offset=0&limit=200',
      )
      expect(
        jobsResp.ok && jobsResp.body,
        `GET /api/v1/jobs failed: HTTP ${jobsResp.status} body=${jobsResp.raw.slice(0, 300)}`,
      ).toBeTruthy()
      const titanHello = jobsResp.body!.items.find((j) => j.fullName === 'titan-hello')
      expect(titanHello, 'titan-hello job not seeded').toBeTruthy()

      const triggerResp = await apiPost<{ buildId: number; buildNumber: number }>(
        request,
        apiBearer,
        `/api/v1/jobs/${titanHello!.id}/builds`,
        {},
      )
      expect(
        triggerResp.ok && triggerResp.body,
        `POST /jobs/${titanHello!.id}/builds failed: HTTP ${triggerResp.status} ` +
          `body=${triggerResp.raw.slice(0, 300)}`,
      ).toBeTruthy()
      triggeredBuildId = triggerResp.body!.buildId
      expect(triggeredBuildId).toBeGreaterThan(0)

      // 3. Navigate to /audit (admin-only — dev has ADMIN).
      await page.goto(`${ENV.uiBaseUrl}/audit`)
      await expect(page.getByRole('heading', { name: /audit log/i })).toBeVisible({
        timeout: 10_000,
      })

      // Defensive: the 403 empty-state must NOT appear for dev.
      await expect(page.locator('[data-testid="audit-error-403"]')).not.toBeVisible()

      // 4. Poll /audit via the API for our row. We poll the API (rather than
      //    DOM-scrape) because the page may paginate the row off the first
      //    page once enough actions accrue; the API with a tight `since`
      //    window is the canonical oracle. We then ALSO assert the row is
      //    in the rendered table (it should be — fresh trigger means it's
      //    the newest row, which lands on page 1 of a 50-row pager).
      await expect
        .poll(
          async () => {
            const r = await apiGet<AuditPage>(request, bearer!, '/api/v1/audit?limit=50')
            if (!r.ok || !r.body) return false
            return r.body.items.some(
              (e) =>
                e.action === 'BUILD_TRIGGER' &&
                e.targetType === 'BUILD' &&
                e.targetId === String(triggeredBuildId) &&
                e.actor === 'dev',
            )
          },
          {
            message:
              `audit row { action: BUILD_TRIGGER, target: BUILD/${triggeredBuildId}, ` +
              `actor: dev } not present in GET /api/v1/audit within 15s`,
            timeout: 15_000,
            intervals: [500, 1_000, 2_000],
          },
        )
        .toBe(true)

      // 5. The row must also render in the table (UI/API agreement). Look up
      //    the event id via the API, then assert the corresponding
      //    audit-row-<id> testid is present after a reload (forces TanStack
      //    Query refetch).
      const finalAudit = await apiGet<AuditPage>(
        request,
        bearer!,
        '/api/v1/audit?limit=50',
      )
      expect(finalAudit.ok && finalAudit.body).toBeTruthy()
      const matchingEvent = finalAudit.body!.items.find(
        (e) =>
          e.action === 'BUILD_TRIGGER' &&
          e.targetType === 'BUILD' &&
          e.targetId === String(triggeredBuildId) &&
          e.actor === 'dev',
      )
      expect(matchingEvent, 'matching event vanished from API between poll and read').toBeTruthy()

      await page.reload()
      await expect(page.getByRole('heading', { name: /audit log/i })).toBeVisible({
        timeout: 10_000,
      })
      const row = page.locator(`[data-testid="audit-row-${matchingEvent!.id}"]`)
      await expect(row).toBeVisible({ timeout: 10_000 })

      // The action chip on that row must be BUILD_TRIGGER (sanity — guards
      // against an id-collision misread of the API response).
      const actionChip = page.locator(`[data-testid="audit-action-${matchingEvent!.id}"]`)
      await expect(actionChip).toHaveText('BUILD_TRIGGER')
    } catch (err) {
      await dumpDiagnostics('assertion-failure')
      throw err
    }
  })
})

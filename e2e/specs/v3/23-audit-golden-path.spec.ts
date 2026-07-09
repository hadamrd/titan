/**
 * 23-audit-golden-path @golden — /audit screen end-to-end (closes #584).
 *
 * Mirrors spec 21's diagnostics + assertion discipline. Hard assertions only.
 *
 * The dev user has ADMIN role in the titan-dev realm, so READ_AUDIT is
 * exercised (closes the UI half of #517 / #478 / #519).
 *
 * Hermeticity (#123 golden promotion): the spec used to trigger a build on
 * the SEEDED `titan-hello` job and never cleaned it up — one leaked build
 * (+ flow_nodes/logs/task rows) per run, silently reshaping the shared
 * job's history. It now creates its OWN per-run job (#59 ownership rule),
 * triggers the build there, and tears everything down via
 * `safeDeleteJobCascade` in a finally. The audit row itself is append-only
 * by design and stays — that's the audit log's contract, not litter.
 *
 * Flow:
 *   1. Login as dev via PKCE (and grab a separate ROPC bearer for direct API
 *      job-creation + build-triggering).
 *   2. Create a per-run job, then trigger a build via
 *      POST /api/v1/jobs/$id/builds so the audit event is uniquely
 *      identifiable by the fresh buildId.
 *   3. Navigate to /audit.
 *   4. Poll up to 15s for the row { action: BUILD_TRIGGER, target: BUILD/<id>,
 *      actor: dev } to appear.
 *   5. Hard-fail with the raw /audit response attached as a diagnostic on
 *      timeout.
 *   6. finally: cancel-safe cascade delete of the per-run job (zero litter).
 */
import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken, loginViaKeycloak } from '../../fixtures/auth-v3'
import { safeDeleteJobCascade } from '../../fixtures/teardown-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

// Minimal valid pipeline — the build only needs to EXIST (BUILD_TRIGGER is
// audited at accept-time); it does not need to produce interesting output.
const MIN_YAML = 'stages:\n  - stage: audit-probe\n    steps:\n      - sh: echo audit probe\n'

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

test.describe('v3 audit-golden-path @golden', () => {
  test('BUILD_TRIGGER audit row surfaces in /audit within 15s', async ({
    page,
    request,
  }) => {
    test.setTimeout(120_000)
    let attachedDiagnostics = false
    let bearer: string | undefined
    let jobId: number | undefined
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

      // 2. Create a per-run job (own rows — #59 ownership rule; the old
      //    titan-hello trigger leaked one build per run onto the shared
      //    seeded job), then trigger a build on IT via the API.
      const runTag = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`
      const createResp = await apiPost<{ id: number }>(request, apiBearer, '/api/v1/jobs', {
        fullName: `e2e/audit-${runTag}`,
        displayName: 'e2e audit-golden-path',
        pipelineScript: MIN_YAML,
        enabled: true,
      })
      expect(
        createResp.status === 201 && createResp.body,
        `POST /api/v1/jobs failed: HTTP ${createResp.status} body=${createResp.raw.slice(0, 300)}`,
      ).toBeTruthy()
      jobId = createResp.body!.id
      expect(jobId).toBeGreaterThan(0)

      const triggerResp = await apiPost<{ buildId: number; buildNumber: number }>(
        request,
        apiBearer,
        `/api/v1/jobs/${jobId}/builds`,
        {},
      )
      expect(
        triggerResp.ok && triggerResp.body,
        `POST /jobs/${jobId}/builds failed: HTTP ${triggerResp.status} ` +
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
    } finally {
      // 6. Zero litter: the per-run job + its triggered build (which may
      //    still be RUNNING — safeDeleteJobCascade cancels it first and
      //    never deletes under a leased task row, #59).
      if (jobId !== undefined) {
        const result = await safeDeleteJobCascade(request, jobId).catch((e) => {
          console.warn(`[23-audit] teardown of job ${jobId} failed: ${String(e)}`)
          return null
        })
        if (result && !result.deleted) {
          console.warn(
            `[23-audit] job ${jobId} left rows behind: builds ${result.leftoverBuildIds.join(',')}`,
          )
        }
      }
    }
  })
})

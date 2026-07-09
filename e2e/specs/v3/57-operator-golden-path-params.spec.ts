/**
 * 57-operator-golden-path-params — the CRO's broken core flow, locked as a gate.
 *
 * Why this spec exists (closes #1263):
 *   "Run a parameterized pipeline from the UI" broke in production and "dozens
 *   of e2e tests" never caught it, because those were component-unit tests with
 *   MOCKED APIs — they asserted the seed shape, not the real operator journey on
 *   a running system. The most recent surfacing was a MODAL-TRAP bug: the params
 *   modal opened but could not be dismissed (Escape did nothing) and/or
 *   submission silently failed, so an operator could not trigger a parameterized
 *   build at all.
 *
 *   `#1260` proved a *shell-based* round-trip. THIS spec is the *UI operator
 *   path* against the running rig with REAL Keycloak OIDC — the exact path that
 *   broke — and is tagged `@golden` so it blocks merges if it regresses.
 *
 * The full operator journey, one spec:
 *   1. Real Keycloak PKCE login as the roled `dev` user (no mocked APIs, no
 *      direct-grant bypass for the browser flow).
 *   2. Seed a SMALL, real, runnable parameterized pipeline via the API (two
 *      declared params + one `sh` step that echoes them). Created fresh per run
 *      and deleted in `finally` so the rig stays clean — mirrors spec 27.
 *   3. /pipelines list renders REAL pipelines for the roled user — assert ≥1 row
 *      and the API agrees (no 403, no empty-state). Guards the roled-user-403
 *      regression. Then reach the job the way a real operator does at ANY job
 *      count (#85): Ctrl/⌘+K palette search on the unique job name (server-side
 *      ?search=, #693) → pipeline detail page. The list is paginated, so
 *      anchoring on the fresh job's page-1 row was litter-dependent (137+ jobs
 *      on the long-running rig pushed it off page 1 — the #85 red).
 *   4. Click the pipeline's Run/Trigger → the params modal opens showing EVERY
 *      declared param, labeled. (AC#3)
 *   5. MODAL-TRAP GUARD: press Escape → the modal is gone AND the page is
 *      interactive again (the exact bug just fixed). (AC#4, adversarial)
 *   6. Re-open → fill the params → confirm → a build QUEUES; capture the trigger
 *      POST response carrying buildId/buildNumber. (AC#4)
 *   7. The build appears in /builds and transitions QUEUED→RUNNING→SUCCESS,
 *      verified by polling GET /api/v1/builds/$id to terminal (≤120s). (AC#5)
 *   8. /builds/$id shows LOGS present (≥1 line, not "No log output yet"); the
 *      params USED match what we submitted — asserted DIRECTLY on the typed
 *      build-detail Parameters surface (#1266): the section lists each key=value
 *      the build ran with, and `BuildDto.parametersUsed` carries the same pairs
 *      on the wire. This replaces the old build-log-echo proxy. (AC#6)
 *
 * A second, adversarial test proves a build with NO params shows NO Parameters
 * section (and the detail page does not crash).
 *   9. CONSOLE-ERROR GUARD: every route visited is watched for `console.error`
 *      and uncaught `pageerror`; the test fails if any fire even on the happy
 *      path. (AC#7, adversarial)
 *
 * On any failure, attach a screenshot + the build/nodes/logs JSON so diagnosis
 * is one-shot — mirrors the failure-attachment pattern in
 * 21-golden-path-real-build.spec.ts.
 */
import { test, expect, type Page, type APIRequestContext, type ConsoleMessage } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

// Benign console noise that is never a product bug. Matches the allow-list used
// by 16-click-everything-smoke.spec.ts so the two guards can't drift.
const CONSOLE_NOISE = /favicon|sourcemap|Failed to load resource|\[vite\]/i

// The smallest real parameterized pipeline: two declared params covering the
// text + choice input renderers, and ONE sh step that echoes the resolved
// values. Echoing the params is what lets us prove — from the build log — that
// the values we SUBMITTED were the values the build USED.
const PARAM_PIPELINE_YAML = `parameters:
  - name: GREETING
    type: string
    default: hello
    description: greeting word echoed by the build
  - name: MODE
    type: choice
    default: dev
    choices: [dev, prod]
stages:
  - stage: greet
    steps:
      - sh: echo "GREETING=\${{ params.GREETING }} MODE=\${{ params.MODE }}"
`

const TERMINAL = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'CANCELLED', 'UNSTABLE', 'FAILURE'])

interface JobsPage {
  items: Array<{ id: number; fullName: string; displayName: string }>
  total: number
}

interface BuildDto {
  id: number
  jobId: number
  buildNumber: number
  status: string
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

async function pollBuildUntilTerminal(
  request: APIRequestContext,
  bearer: string,
  buildId: number,
  timeoutMs: number,
): Promise<BuildDto> {
  const start = Date.now()
  let last: BuildDto | null = null
  let lastRaw = ''
  while (Date.now() - start < timeoutMs) {
    const r = await apiGet<BuildDto>(request, bearer, `/api/v1/builds/${buildId}`)
    lastRaw = r.raw
    if (r.ok && r.body) {
      last = r.body
      if (TERMINAL.has(last.status)) return last
    }
    await new Promise((res) => setTimeout(res, 1_000))
  }
  throw new Error(
    `build ${buildId} did not reach terminal status in ${timeoutMs}ms. ` +
      `Last status=${last?.status ?? 'unknown'}. Last body=${lastRaw.slice(0, 500)}`,
  )
}

test.describe('v3 operator-golden-path-params @golden', () => {
  test('login → list pipelines → params modal (dismiss + submit) → build SUCCESS → logs + params used', async ({
    page,
    request,
  }) => {
    test.setTimeout(180_000)

    // The job is created via the API and torn down in `finally`. Declared here
    // so the cleanup block can see them after any mid-test throw.
    let bearer: string | undefined
    let jobId: number | undefined
    let buildId: number | undefined
    const jobFullName = `e2e-params-golden-${Date.now()}`

    // ── Console-error guard (AC#7). Buffer every console.error + pageerror
    //    across the whole journey; assert empty at the end. A console error on
    //    ANY visited route is a product bug, even when the happy path passes.
    const consoleErrors: string[] = []
    const pageErrors: string[] = []
    const onConsole = (msg: ConsoleMessage) => {
      if (msg.type() === 'error' && !CONSOLE_NOISE.test(msg.text())) {
        consoleErrors.push(msg.text())
      }
    }
    const onPageError = (err: Error) => {
      pageErrors.push(err.message)
    }
    page.on('console', onConsole)
    page.on('pageerror', onPageError)

    let attachedDiagnostics = false
    const dumpDiagnostics = async (label: string) => {
      if (attachedDiagnostics) return
      attachedDiagnostics = true
      try {
        if (buildId !== undefined && bearer !== undefined) {
          const build = await apiGet(request, bearer, `/api/v1/builds/${buildId}`)
          await test.info().attach(`build-${buildId}.json`, {
            body: build.raw,
            contentType: 'application/json',
          })
          const nodes = await apiGet(request, bearer, `/api/v1/builds/${buildId}/nodes`)
          await test.info().attach(`nodes-${buildId}.json`, {
            body: nodes.raw,
            contentType: 'application/json',
          })
          const logs = await apiGet(request, bearer, `/api/v1/builds/${buildId}/logs`)
          await test.info().attach(`logs-${buildId}.txt`, {
            body: logs.raw.slice(0, 1000),
            contentType: 'text/plain',
          })
        }
        const png = await page.screenshot({ fullPage: true })
        await test.info().attach(`screenshot-${label}-${buildId ?? 'pre'}.png`, {
          body: png,
          contentType: 'image/png',
        })
        if (consoleErrors.length || pageErrors.length) {
          await test.info().attach('console-errors.txt', {
            body: `console.error:\n${consoleErrors.join('\n')}\n\npageerror:\n${pageErrors.join('\n')}`,
            contentType: 'text/plain',
          })
        }
      } catch (e) {
        await test.info().attach('diagnostics-error.txt', {
          body: String(e),
          contentType: 'text/plain',
        })
      }
    }

    try {
      // 1. Real Keycloak PKCE login as the roled `dev` user.
      await loginViaKeycloak(page, ENV)
      bearer = await extractAccessToken(page)

      // 2. Seed the small real parameterized pipeline via the API.
      const jobCreate = await request.post(`${API_BASE}/api/v1/jobs`, {
        headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
        data: {
          fullName: jobFullName,
          displayName: 'E2E params golden-path',
          pipelineScript: PARAM_PIPELINE_YAML,
          enabled: true,
        },
      })
      const jobRaw = await jobCreate.text()
      expect(
        jobCreate.status(),
        `POST /api/v1/jobs HTTP ${jobCreate.status()} body=${jobRaw.slice(0, 600)}`,
      ).toBe(201)
      jobId = (JSON.parse(jobRaw) as { id: number }).id
      expect(jobId).toBeGreaterThan(0)

      // Sanity: the server actually parsed our declared params. This is the
      // exact list the modal must render — if it's empty the modal won't open.
      const declared = await apiGet<Array<{ name: string; type: string }>>(
        request,
        bearer,
        `/api/v1/jobs/${jobId}/parameters`,
      )
      expect(
        declared.ok && Array.isArray(declared.body) && declared.body.length === 2,
        `GET /jobs/${jobId}/parameters did not return the 2 declared params: ` +
          `HTTP ${declared.status} body=${declared.raw.slice(0, 300)}`,
      ).toBe(true)

      // 3. /pipelines list renders REAL pipelines for the roled user (AC#2).
      //    Assert the API is non-empty + 2xx (no 403) AND the DOM shows rows.
      const jobsApi = await apiGet<JobsPage>(request, bearer, '/api/v1/jobs?offset=0&limit=200')
      expect(
        jobsApi.ok && jobsApi.body !== null,
        `GET /api/v1/jobs failed for roled user — HTTP ${jobsApi.status} ` +
          `body=${jobsApi.raw.slice(0, 300)} (roled-user-403 regression?)`,
      ).toBe(true)
      expect(
        jobsApi.body!.items.length,
        'roled user sees 0 pipelines from the API — empty-state / 403 regression',
      ).toBeGreaterThan(0)

      await page.goto(`${ENV.uiBaseUrl}/pipelines`)
      // The list shell renders rows for the roled user. NOTE (#85): the list
      // is paginated (useJobs limit=50) and on a long-running rig 137+ jobs
      // accumulate, so the fresh job's row is routinely BEYOND page 1 — we
      // must not anchor on `job-row-<id>` here. The list assertions stay
      // count-independent; the job itself is reached via search below.
      await expect(page.getByText('No pipelines yet', { exact: false })).not.toBeVisible()
      const rowCount = await page.locator('[data-testid^="job-row-"]').count()
      expect(rowCount, 'pipelines list rendered 0 rows in the DOM').toBeGreaterThan(0)

      // 3b. Navigate to the job the way a real operator does at ANY job count
      // (#85): Ctrl/⌘+K command palette → type the unique job name → the
      // server-side `?search=` filter (#693) returns it regardless of list
      // pagination → select the hit → land on the pipeline detail page. The
      // detail page's Run button drives the SAME param-aware trigger flow as
      // the list row (shared useParamAwareTrigger — the #1208 anti-drift
      // seam), so every modal oracle below is unchanged.
      await page.keyboard.press('ControlOrMeta+k')
      const cmdkInput = page.getByTestId('cmdk-input')
      await expect(cmdkInput, 'Ctrl+K did not open the command palette').toBeVisible({
        timeout: 10_000,
      })
      await cmdkInput.fill(jobFullName)
      const paletteHit = page.getByTestId(`cmdk-job-${jobId}`)
      await expect(
        paletteHit,
        `command-palette search for '${jobFullName}' never surfaced job ${jobId} — ` +
          `the operator search path must find a job at ANY job count (#85)`,
      ).toBeVisible({ timeout: 15_000 })
      await paletteHit.click()

      const triggerBtn = page.getByTestId('pipeline-detail-trigger-btn')
      await expect(
        triggerBtn,
        `pipeline detail page for job ${jobId} did not render its Run button after palette navigation`,
      ).toBeVisible({ timeout: 15_000 })

      // 4. Click Trigger → params modal opens showing EVERY declared param (AC#3).
      await triggerBtn.click()
      const modal = page.locator('[data-testid="trigger-params-backdrop"] [role="dialog"]')
      await expect(modal).toBeVisible({ timeout: 10_000 })
      // Each declared param input present + labeled.
      const greetingInput = page.getByTestId('trigger-param-GREETING-input')
      const modeInput = page.getByTestId('trigger-param-MODE-input')
      await expect(greetingInput).toBeVisible()
      await expect(modeInput).toBeVisible()
      // exact:true — the param hint ("greeting word echoed by the build",
      // #trigger-param-GREETING-hint) also substring-matches 'GREETING'
      // case-insensitively, tripping strict mode (#77).
      await expect(modal.getByText('GREETING', { exact: true })).toBeVisible()
      await expect(modal.getByText('MODE', { exact: true })).toBeVisible()
      // Bonus oracle: the declared description renders as the field hint.
      await expect(modal.locator('#trigger-param-GREETING-hint')).toHaveText(
        'greeting word echoed by the build',
      )

      // 5. MODAL-TRAP GUARD (AC#4, adversarial): Escape dismisses the modal AND
      //    the page is interactive again. This is the exact bug #1263 fixed.
      await page.keyboard.press('Escape')
      await expect(modal).toBeHidden({ timeout: 5_000 })
      // Page is interactive: the trigger button is clickable again (not trapped
      // behind a stuck backdrop). Re-clicking it re-opens the modal cleanly.
      await expect(page.locator('[data-testid="trigger-params-backdrop"]')).toHaveCount(0)
      await expect(triggerBtn).toBeEnabled()

      // 6. Re-open → fill the params → submit → build QUEUES (AC#4). Capture the
      //    trigger POST response (it carries buildId + buildNumber).
      await triggerBtn.click()
      await expect(modal).toBeVisible({ timeout: 10_000 })
      await greetingInput.fill('world') // override the default 'hello'

      const triggerRespPromise = page.waitForResponse(
        (r) =>
          r.url().includes(`/api/v1/jobs/${jobId}/builds`) && r.request().method() === 'POST',
        { timeout: 15_000 },
      )
      await page.getByTestId('trigger-params-confirm').click()
      const triggerResp = await triggerRespPromise
      expect(
        triggerResp.ok(),
        `trigger POST failed: HTTP ${triggerResp.status()} ${await triggerResp.text().catch(() => '')}`,
      ).toBe(true)
      const triggerBody = (await triggerResp.json()) as { buildId: number; buildNumber: number }
      buildId = triggerBody.buildId
      expect(buildId, 'trigger response carried no buildId').toBeGreaterThan(0)
      expect(triggerBody.buildNumber).toBeGreaterThan(0)

      // 7. The build appears in /builds and runs to SUCCESS (AC#5).
      await page.goto(`${ENV.uiBaseUrl}/builds`)
      await expect(
        page.locator(`a[href$="/builds/${buildId}"]`).first(),
        'the queued build never appeared in the /builds list',
      ).toBeVisible({ timeout: 15_000 })

      const finalBuild = await pollBuildUntilTerminal(request, bearer, buildId, 120_000)
      expect(
        finalBuild.status,
        `build ${buildId} reached terminal status ${finalBuild.status}, expected SUCCESS`,
      ).toBe('SUCCESS')

      // 8. /builds/$id shows logs present (AC#6).
      await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)
      await expect(page.locator('[data-testid="build-detail-v3"]')).toBeVisible({
        timeout: 10_000,
      })
      // Header status pill reflects the API's terminal verdict.
      await expect(page.locator('[data-testid="build-verdict-badge"]')).toBeVisible({
        timeout: 10_000,
      })

      const logsTab = page.getByRole('tab', { name: /^logs$/i })
      if (await logsTab.isVisible().catch(() => false)) {
        await logsTab.click()
      }
      await expect(page.getByText('No log output yet.')).not.toBeVisible({ timeout: 15_000 })
      const logLines = page.locator('.log-line')
      await expect(logLines.first()).toBeVisible({ timeout: 15_000 })
      expect(
        await logLines.count(),
        'Logs tab rendered 0 lines for a build that ran — log linkage broken',
      ).toBeGreaterThan(0)

      // 8b. Params USED match what we submitted (AC#6) — asserted DIRECTLY on the
      //     typed build-detail surface (#1266), no longer via the build-log echo.
      //     The detail page renders a Parameters section listing each key=value
      //     the build ran with; the section shows our submitted override
      //     (GREETING=world) and the MODE default (dev).
      const paramsSection = page.locator('[data-testid="build-parameters-section"]')
      await expect(
        paramsSection,
        'build-detail Parameters section did not render for a parameterized build',
      ).toBeVisible({ timeout: 10_000 })
      const greetingRow = page.getByTestId('build-param-GREETING')
      const modeRow = page.getByTestId('build-param-MODE')
      await expect(greetingRow).toBeVisible()
      await expect(greetingRow).toContainText('world') // the override we submitted
      await expect(modeRow).toBeVisible()
      await expect(modeRow).toContainText('dev') // the choice default

      // 8c. The typed API surface agrees — `parametersUsed` is on the detail DTO
      //     and carries the persisted pairs (the UI is not lying about the wire).
      const detail = await apiGet<{ parametersUsed?: Record<string, string> }>(
        request,
        bearer,
        `/api/v1/builds/${buildId}`,
      )
      expect(detail.ok, `GET /builds/${buildId} failed: HTTP ${detail.status}`).toBe(true)
      expect(
        detail.body?.parametersUsed,
        `BuildDto.parametersUsed missing/empty on the detail payload: ${detail.raw.slice(0, 400)}`,
      ).toMatchObject({ GREETING: 'world', MODE: 'dev' })

      // 9. Console-error guard (AC#7): no console.error / pageerror on any route.
      expect(
        consoleErrors,
        `console.error fired during the operator journey:\n${consoleErrors.join('\n')}`,
      ).toEqual([])
      expect(
        pageErrors,
        `uncaught pageerror fired during the operator journey:\n${pageErrors.join('\n')}`,
      ).toEqual([])
    } catch (err) {
      await dumpDiagnostics('assertion-failure')
      throw err
    } finally {
      page.off('console', onConsole)
      page.off('pageerror', onPageError)
      // Cleanup: best-effort, never throws past the test boundary.
      if (bearer && jobId) {
        await request
          .delete(`${API_BASE}/api/v1/jobs/${jobId}`, {
            headers: { Authorization: `Bearer ${bearer}` },
          })
          .catch(() => null)
      }
    }
  })

  // ── Adversarial: a build with NO params shows NO Parameters section (#1266).
  //    Proves the section is hidden ENTIRELY (no empty card) and the detail page
  //    does not crash when `parametersUsed` is absent from the DTO.
  test('no-params build → build-detail renders NO Parameters section and does not crash', async ({
    page,
    request,
  }) => {
    test.setTimeout(60_000)

    let bearer: string | undefined
    let jobId: number | undefined
    let buildId: number | undefined
    const jobFullName = `e2e-noparams-${Date.now()}`
    // The smallest real pipeline with ZERO declared params.
    const NO_PARAM_YAML = `stages:
  - stage: greet
    steps:
      - sh: echo "no params here"
`

    const pageErrors: string[] = []
    const onPageError = (err: Error) => pageErrors.push(err.message)
    page.on('pageerror', onPageError)

    try {
      await loginViaKeycloak(page, ENV)
      bearer = await extractAccessToken(page)

      const jobCreate = await request.post(`${API_BASE}/api/v1/jobs`, {
        headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
        data: {
          fullName: jobFullName,
          displayName: 'E2E no-params',
          pipelineScript: NO_PARAM_YAML,
          enabled: true,
        },
      })
      expect(jobCreate.status(), await jobCreate.text()).toBe(201)
      jobId = (JSON.parse(await jobCreate.text()) as { id: number }).id

      // Trigger via the API with no params — we only need the build row to exist;
      // the Parameters projection is independent of build progress.
      const trigger = await request.post(`${API_BASE}/api/v1/jobs/${jobId}/builds`, {
        headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
        data: {},
      })
      expect(trigger.ok(), `trigger failed: HTTP ${trigger.status()} ${await trigger.text()}`).toBe(
        true,
      )
      buildId = (JSON.parse(await trigger.text()) as { buildId: number }).buildId
      expect(buildId).toBeGreaterThan(0)

      // Detail page renders (no crash) and the Parameters section is ABSENT.
      await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)
      await expect(page.locator('[data-testid="build-detail-v3"]')).toBeVisible({
        timeout: 10_000,
      })
      await expect(page.locator('[data-testid="build-parameters-section"]')).toHaveCount(0)

      // The typed DTO omits the field too (JsonInclude.NON_NULL).
      const detail = await apiGet<{ parametersUsed?: unknown }>(
        request,
        bearer,
        `/api/v1/builds/${buildId}`,
      )
      expect(detail.ok).toBe(true)
      expect(detail.body?.parametersUsed ?? null).toBeNull()

      expect(pageErrors, `uncaught pageerror on the no-params detail page`).toEqual([])
    } finally {
      page.off('pageerror', onPageError)
      if (bearer && jobId) {
        await request
          .delete(`${API_BASE}/api/v1/jobs/${jobId}`, {
            headers: { Authorization: `Bearer ${bearer}` },
          })
          .catch(() => null)
      }
    }
  })
})

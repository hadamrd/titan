/**
 * live-log-stream — proves the build-detail Logs tab streams LIVE while a
 * build is RUNNING, on the rendered DOM, end-to-end against the local rig
 * (issue #1264).
 *
 * WHY THIS SPEC EXISTS
 *   The SSE plumbing (server `BuildLogsSse`, client `streamBuildLogs`, the
 *   `useLogStream` hook, `TerminalConsole`) was wired but UNVERIFIED e2e — there
 *   was no test proving lines append INCREMENTALLY before the build is terminal
 *   (vs. only dumping on completion). A regression that broke live streaming,
 *   left the indicator stuck on `connecting`, or threw in the log panel would
 *   ship green. This spec closes that gap.
 *
 * WHAT IT ASSERTS (issue #1264 acceptance)
 *   1. Live append while RUNNING — the rendered `.log-line` count GROWS across
 *      successive observations while the build status is RUNNING.
 *   2. Terminal stops the stream — at SUCCESS the verdict badge renders SUCCESS
 *      and the SSE indicator settles (never stuck in `connecting`/spinner).
 *   3. Adversarial — ZERO `pageerror` and zero app-origin console errors for the
 *      whole run.
 *
 * FAIL-LOUD: a missing rig / unreachable Keycloak / a build that never reaches
 * a RUNNING window is an EXPLICIT failure naming the surface — never a silent
 * green.
 */
import * as fs from 'node:fs'
import * as path from 'node:path'
import { randomBytes } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { authEnv, fetchBearerToken, loginViaKeycloak } from '../fixtures/auth-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const TERMINAL = new Set(['SUCCESS', 'FAILED', 'FAILURE', 'ABORTED', 'UNSTABLE', 'ERROR'])
const BUILD_TIMEOUT_MS = Number(process.env.TITAN_GOLDEN_PATH_TIMEOUT_MS ?? 90_000)

interface JobCreateResp { id: number }
interface BuildTriggerResp { buildId: number }
interface BuildDto { id: number; status: string }

function pipelineYaml(fixture: string): string {
  const here = path.dirname(fileURLToPath(import.meta.url))
  return fs.readFileSync(
    path.resolve(here, '..', 'pipelines', fixture, 'titan-pipeline.yml'),
    'utf8',
  )
}

async function createJob(api: APIRequestContext, bearer: string, fullName: string, fixture: string): Promise<number> {
  const resp = await api.post(`${API_BASE}/api/v1/jobs`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: {
      fullName,
      displayName: `live-log-stream #1264 — ${fixture}`,
      pipelineScript: pipelineYaml(fixture),
      configJson: JSON.stringify({ triggers: [] }),
      enabled: true,
    },
  })
  expect(resp.status(), `job create for ${fixture}: ${await resp.text()}`).toBe(201)
  return (JSON.parse(await resp.text()) as JobCreateResp).id
}

async function triggerBuild(api: APIRequestContext, bearer: string, jobId: number): Promise<number> {
  const r = await api.post(`${API_BASE}/api/v1/jobs/${jobId}/builds`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: {},
  })
  expect(r.status(), `build trigger ${await r.text()}`).toBeLessThan(300)
  const body = JSON.parse(await r.text()) as BuildTriggerResp
  expect(body.buildId, 'trigger returned no buildId').toBeGreaterThan(0)
  return body.buildId
}

async function buildStatus(api: APIRequestContext, bearer: string, buildId: number): Promise<string> {
  const r = await api.get(`${API_BASE}/api/v1/builds/${buildId}`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  if (!r.ok()) return 'UNKNOWN'
  return (JSON.parse(await r.text()) as BuildDto).status
}

async function renderedLineCount(page: Page): Promise<number> {
  return page.locator('.log-line').count()
}

test.describe('live-log-stream UI @1264', () => {
  test.describe.configure({ mode: 'serial' })

  let bearer: string
  const runTag = randomBytes(4).toString('hex')

  test.beforeAll(async () => {
    bearer = await fetchBearerToken(ENV)
    expect(bearer.length, 'Keycloak returned an empty bearer — is the rig up?').toBeGreaterThan(10)
  })

  test('logs append live while RUNNING, then the stream stops and the build renders SUCCESS', async ({
    request,
    page,
  }) => {
    test.setTimeout(BUILD_TIMEOUT_MS + 90_000)

    // ── Adversarial: no uncaught errors anywhere in the run ──────────────────
    const pageErrors: string[] = []
    page.on('pageerror', (e) => pageErrors.push(String(e)))
    const consoleErrors: string[] = []
    page.on('console', (msg) => {
      if (msg.type() !== 'error') return
      const text = msg.text()
      // Filter out resource-load noise that isn't an app-origin runtime error
      // (favicon, a token-refresh 401 during transition, ResizeObserver chatter).
      if (/favicon|ResizeObserver|Failed to load resource/i.test(text)) return
      consoleErrors.push(text)
    })

    const jobId = await createJob(request, bearer, `live-log-${runTag}`, 'live-log-stream')
    const buildId = await triggerBuild(request, bearer, jobId)

    // Open the rendered build-detail page as fast as possible so we catch the
    // RUNNING window — the fixture paces ~6 lines over ~6s.
    await loginViaKeycloak(page, ENV)
    await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)
    await expect(
      page.locator('[data-testid="build-detail-v3"]'),
      `v3 build-detail shell did not render for build ${buildId}`,
    ).toBeVisible({ timeout: 20_000 })

    // Make sure the Logs tab is active (it is by default, but be explicit).
    const logsTab = page.getByRole('tab', { name: /^logs$/i })
    if (await logsTab.count() > 0) await logsTab.click()

    // ── 1. Live append while RUNNING: sample the rendered line count and prove
    //       it grows across successive observations while status is RUNNING. ──
    const samples: number[] = []
    let sawRunning = false
    const deadline = Date.now() + BUILD_TIMEOUT_MS
    let status = await buildStatus(request, bearer, buildId)
    while (Date.now() < deadline && !TERMINAL.has(status)) {
      if (status === 'RUNNING') {
        sawRunning = true
        samples.push(await renderedLineCount(page))
      }
      await page.waitForTimeout(700)
      status = await buildStatus(request, bearer, buildId)
    }

    expect(
      sawRunning,
      'never observed a RUNNING window — the rig or worker did not start the build; cannot prove live streaming',
    ).toBe(true)

    const maxWhileRunning = samples.length > 0 ? Math.max(...samples) : 0
    const minWhileRunning = samples.length > 0 ? Math.min(...samples) : 0
    expect(
      maxWhileRunning,
      `rendered 0 log lines during the RUNNING window (samples=${JSON.stringify(samples)}) — SSE did not stream live`,
    ).toBeGreaterThan(0)
    // Growth: the count must increase across the RUNNING window (incremental
    // append, not a single terminal dump).
    expect(
      maxWhileRunning,
      `log-line count did not grow while RUNNING (samples=${JSON.stringify(samples)}) — lines did not append incrementally`,
    ).toBeGreaterThan(minWhileRunning)

    // ── 2. Terminal stops the stream + SUCCESS renders ───────────────────────
    expect(TERMINAL.has(status), `build ${buildId} never reached a terminal status; last=${status}`).toBe(true)
    expect(status, `live-log-stream fixture exits 0 — expected SUCCESS, got ${status}`).toBe('SUCCESS')

    const badge = page.locator('[data-testid="build-verdict-badge"]')
    await expect(badge, 'verdict badge absent from header').toBeVisible({ timeout: 15_000 })
    await expect
      .poll(async () => badge.getAttribute('data-status'), { timeout: 20_000 })
      .toBe('SUCCESS')

    // The SSE indicator must SETTLE — never stuck spinning on `connecting`.
    const sseIndicator = page.locator('[data-testid="sse-state"]')
    if (await sseIndicator.count() > 0) {
      await expect
        .poll(async () => sseIndicator.getAttribute('data-sse-state'), { timeout: 20_000 })
        .not.toBe('connecting')
    }

    // Final rendered count reflects the full log (>= what we saw mid-stream).
    const finalCount = await renderedLineCount(page)
    expect(finalCount, 'final rendered line count regressed below the RUNNING-window peak').toBeGreaterThanOrEqual(
      maxWhileRunning,
    )

    // ── 3. Adversarial: zero uncaught errors for the whole run ───────────────
    expect(pageErrors, `uncaught pageerror(s) during live streaming: ${pageErrors.join(' | ')}`).toEqual([])
    expect(consoleErrors, `app-origin console error(s) during live streaming: ${consoleErrors.join(' | ')}`).toEqual([])
  })
})

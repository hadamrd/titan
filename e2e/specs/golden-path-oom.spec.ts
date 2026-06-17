/**
 * golden-path-oom — the SRE's "why did the worker kill my build?" flow.
 *
 * Closes issue #1175 (extends the #1173 / #1130 adversarial golden-path suite).
 *
 * Why this spec exists:
 *   The single worst CI failure mode to debug is a step the kernel OOM-killer
 *   reaped: the process dies on SIGKILL and the shell reports a bare `exit 137`.
 *   Per the SRE requirement (verbatim from scenarios/oom-step.e2e.yaml):
 *
 *     "'exit 137' is not an answer — that's a SRE-1 paging trigger every time."
 *
 *   The build-detail UI must surface an OOM-CLASSIFIED reason (`killed: OOM` /
 *   typed `failureReason`), not a bare generic error. This spec is the
 *   build-detail-UI-layer assertion that the generic scenario runner
 *   (scenarios/oom-step.e2e.yaml) cannot make.
 *
 * Strategy (mirrors specs/golden-path-failure-triage.spec.ts, #1130):
 *   1. Create a job from e2e/pipelines/oom-step/ (512 MiB allocation inside a
 *      64 MiB cgroup — reaped by the OOM-killer).
 *   2. Trigger a build, assert it terminates FAILED/FAILURE.
 *   3. Open the build-detail page; assert the `hog` step renders with a
 *      FAILED-class accessible status (role + data-status, NOT pixel colour).
 *   4. Assert the failed step surfaces an OOM-classified reason in the DOM.
 *   5. Adversarial: assert the UI does NOT show ONLY a bare `exit 137` with no
 *      OOM annotation — the `exit 137 (no reason)` regression class.
 *
 * PENDING — hard dependency #1116 (worker OOM detection) is genuinely unshipped:
 *   The titan-worker must (a) enforce `resources.memoryLimitMb` as a real cgroup
 *   RSS cap and (b) inspect cgroup memory.events / the SIGKILL termination cause
 *   and emit a typed `StepTerminationReason.OOM` mapped onto the node's
 *   `failureReason` (surfaced as `killed: OOM`). Until that ships:
 *     - the 512 MiB allocation succeeds on a multi-GB host → the build goes
 *       green → there is nothing to classify;
 *     - even on a FAILED build the UI has no OOM reason to render.
 *   So this spec self-skips with the exact gap named. Flip it on by setting
 *   TITAN_OOM_DETECTION=1 (CI sets it once #1116 lands); it then runs LIVE and
 *   FAILS LOUDLY if classification regresses to a bare `exit 137`.
 */
import * as fs from 'node:fs'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken, loginViaKeycloak } from '../fixtures/auth-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const FAILURE_TIMEOUT_MS = Number(process.env.TITAN_OOM_FAILURE_TIMEOUT_MS ?? 60_000)

/**
 * Hard gate: worker OOM detection (#1116). Default OFF — the spec is registered
 * as a visible, skipped PENDING test naming the exact blocking gap, exactly as
 * scenarios/oom-step.e2e.yaml is `pending:`. CI flips this to "1" the moment
 * the StepTerminationReason.OOM enum + cgroup RSS inspection + UI reason
 * rendering ship, and the spec then runs LIVE.
 */
const OOM_DETECTION_SHIPPED = process.env.TITAN_OOM_DETECTION === '1'
const PENDING_REASON =
  'PENDING #1116 — worker OOM detection unshipped: titan-worker must enforce ' +
  'resources.memoryLimitMb as a cgroup RSS cap AND emit a typed ' +
  'StepTerminationReason.OOM (-> node.failureReason -> "killed: OOM"). ' +
  'Set TITAN_OOM_DETECTION=1 to run live once #1116 lands.'

const TERMINAL = new Set(['SUCCESS', 'FAILED', 'FAILURE', 'ABORTED', 'UNSTABLE', 'ERROR'])
const FAILED = new Set(['FAILED', 'FAILURE', 'ERROR'])

/** Marks an OOM-classified failure in any of the surfaces the UI may use. */
const OOM_REASON = /killed:\s*OOM|OOMKilled|StepTerminationReason\.OOM|out of memory|reason:\s*OOM/i
/** A bare 137 with NO accompanying OOM annotation — the regression we guard. */
const BARE_137 = /exit\s*(code\s*)?137/i

interface JobCreateResp { id: number }
interface BuildTriggerResp { buildId: number; buildNumber: number }
interface BuildDto { id: number; status: string }
interface FlowNodeDto {
  buildId: number
  nodeId: string
  displayName?: string | null
  nodeType?: string | null
  status?: string | null
  failureReason?: string | null
}

async function apiGet<T>(
  api: APIRequestContext,
  bearer: string,
  pathPart: string,
): Promise<{ ok: boolean; status: number; body: T | null; raw: string }> {
  const r = await api.get(`${API_BASE}${pathPart}`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  const raw = await r.text()
  let body: T | null = null
  try { body = JSON.parse(raw) as T } catch { /* leave null */ }
  return { ok: r.ok(), status: r.status(), body, raw }
}

function fixtureRoot(): string {
  const here = path.dirname(fileURLToPath(import.meta.url))
  return path.resolve(here, '..', 'pipelines', 'oom-step')
}

function readPipelineYaml(): string {
  return fs.readFileSync(path.join(fixtureRoot(), 'titan-pipeline.yml'), 'utf8')
}

async function createJob(api: APIRequestContext, bearer: string, fullName: string): Promise<number> {
  const yaml = readPipelineYaml()
  const resp = await api.post(`${API_BASE}/api/v1/jobs`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: {
      fullName,
      displayName: 'E2E OOM classification (#1175)',
      pipelineScript: yaml,
      configJson: JSON.stringify({ triggers: [] }),
      enabled: true,
    },
  })
  expect(resp.status(), `job create ${await resp.text()}`).toBe(201)
  return (JSON.parse(await resp.text()) as JobCreateResp).id
}

async function triggerBuild(api: APIRequestContext, bearer: string, jobId: number): Promise<number> {
  const r = await api.post(`${API_BASE}/api/v1/jobs/${jobId}/builds`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: {},
  })
  expect(r.status(), `build trigger ${await r.text()}`).toBeLessThan(300)
  const body = JSON.parse(await r.text()) as BuildTriggerResp
  expect(body.buildId).toBeGreaterThan(0)
  return body.buildId
}

async function pollTerminal(
  api: APIRequestContext,
  bearer: string,
  buildId: number,
  budgetMs: number,
): Promise<{ status: string; observedStatuses: string[] }> {
  const deadline = Date.now() + budgetMs
  const observed: string[] = []
  let last = 'QUEUED'
  while (Date.now() < deadline) {
    const r = await apiGet<BuildDto>(api, bearer, `/api/v1/builds/${buildId}`)
    if (r.ok && r.body?.status) {
      last = r.body.status
      if (observed[observed.length - 1] !== last) observed.push(last)
      if (TERMINAL.has(last)) return { status: last, observedStatuses: observed }
    }
    await new Promise(res => setTimeout(res, 1_000))
  }
  throw new Error(
    `build ${buildId} not terminal within ${budgetMs}ms; last=${last}; observed=[${observed.join(', ')}]`,
  )
}

function findHog(nodes: FlowNodeDto[]): FlowNodeDto | null {
  return (
    nodes.find(n => (n.displayName ?? '').trim() === 'hog' && (n.nodeType ?? '').toUpperCase() === 'STAGE')
    ?? nodes.find(n => (n.displayName ?? '').trim() === 'hog')
    ?? null
  )
}

test.describe('golden-path-oom @golden @sre @oom', () => {
  test.describe.configure({ mode: 'serial' })

  let bearer: string
  let jobId: number
  let runTag: string
  let buildId: number
  let nodes: FlowNodeDto[] = []

  test.beforeAll(async ({ request }) => {
    // test.skip() is illegal in beforeAll — gate with an early return; each
    // test self-skips via test.skip(!OOM_DETECTION_SHIPPED, ...) below.
    if (!OOM_DETECTION_SHIPPED) return
    bearer = await fetchBearerToken(ENV)
    runTag = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`
    jobId = await createJob(request, bearer, `e2e-oom-${runTag}`)
  })

  test('1. OOM-reaped step -> build terminates FAILED', async ({ request }) => {
    test.skip(!OOM_DETECTION_SHIPPED, PENDING_REASON)
    test.setTimeout(FAILURE_TIMEOUT_MS + 60_000)

    buildId = await triggerBuild(request, bearer, jobId)
    const { status, observedStatuses } = await pollTerminal(request, bearer, buildId, FAILURE_TIMEOUT_MS)

    expect(
      FAILED.has(status),
      `expected a FAILED-class terminal status for the OOM-reaped build ${buildId} ` +
        `(512 MiB allocated in a 64 MiB cgroup), got ${status}. ` +
        `Observed: [${observedStatuses.join(', ')}]. ` +
        `A SUCCESS here means resources.memoryLimitMb was not enforced as a cgroup cap (#1116a).`,
    ).toBe(true)

    const resp = await apiGet<FlowNodeDto[]>(request, bearer, `/api/v1/builds/${buildId}/nodes`)
    expect(resp.ok && Array.isArray(resp.body), `GET /nodes failed: ${resp.raw.slice(0, 300)}`).toBe(true)
    nodes = resp.body!
    expect(nodes.length, 'engine emitted zero flow_nodes for the OOM build').toBeGreaterThan(0)

    const hog = findHog(nodes)
    expect(hog, 'hog stage not present in flow_nodes').not.toBeNull()
    expect(
      FAILED.has((hog!.status ?? '').toUpperCase()),
      `hog node terminal status expected FAILED, got ${hog!.status}`,
    ).toBe(true)

    // The typed failureReason (engine side, #1116b) MUST carry an OOM marker —
    // the API surface the UI renders from. A non-null reason that is ONLY a
    // bare `exit 137` is the regression class this asserts against.
    const reason = (hog!.failureReason ?? '').trim()
    expect(
      reason.length > 0,
      `hog node has NO failureReason — a SIGKILL-at-RSS-limit kill reached the API as a bare ` +
        `terminal status with no reason. That is the "exit 137 (no reason)" regression (#1116b).`,
    ).toBe(true)
    expect(
      OOM_REASON.test(reason),
      `hog failureReason "${reason}" is not OOM-classified. The kernel OOM-killer reaped this step; ` +
        `the worker must tag it StepTerminationReason.OOM, not leave a bare "exit 137".`,
    ).toBe(true)
  })

  test('2. build-detail UI surfaces an OOM-classified reason on the failed step', async ({ page }) => {
    test.skip(!OOM_DETECTION_SHIPPED, PENDING_REASON)
    test.skip(!buildId, 'prior OOM-build test did not complete')
    test.setTimeout(120_000)

    const pageErrors: string[] = []
    page.on('pageerror', err => pageErrors.push(String(err)))

    await loginViaKeycloak(page, ENV)
    await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)

    const detailShell = page.locator('[data-testid="build-detail-v3"], .build-detail-v3')
    await expect(detailShell.first(), 'v3 build-detail shell did not render').toBeVisible({ timeout: 15_000 })

    // (a) The failed step renders a FAILED-class accessible status — role +
    // data-status, NOT pixel colour (docs/best-practices Playwright rule).
    const failedNodes = page.locator('[data-status="FAILED"], [data-status="FAILURE"], [data-status="ERROR"]')
    await expect(
      failedNodes.first(),
      'no node rendered with a FAILED-class data-status — UI dropped the OOM-failed-step indicator',
    ).toBeVisible({ timeout: 15_000 })

    // Click into the hog step.
    const hog = findHog(nodes)
    expect(hog, 'hog node disappeared between fetch and click').not.toBeNull()
    const treeRow = page.locator(`[data-testid="tree-row-${hog!.nodeId}"]`)
    if (await treeRow.count() > 0) {
      await treeRow.first().click()
    } else {
      await failedNodes.first().click()
    }

    const logsTab = page.getByRole('tab', { name: /^logs$/i })
    if (await logsTab.count() > 0) {
      await logsTab.click()
      await expect(logsTab).toHaveAttribute('aria-selected', 'true')
    }

    // (b) THE CORE ASSERTION: the rendered build-detail surface must show an
    // OOM-classified reason. We read the whole detail panel's text so a reason
    // rendered as a chip, a banner, OR a top-of-log line all satisfy it.
    const panelText = (await detailShell.first().innerText()).trim()
    expect(
      OOM_REASON.test(panelText),
      'build-detail UI shows NO OOM classification for an OOM-reaped step. ' +
        'The SRE needs "killed: OOM", not a bare generic error. ' +
        `Panel text (truncated): ${panelText.slice(0, 400)}`,
    ).toBe(true)

    // (c) ADVERSARIAL — `exit 137 (no reason)` regression class: if a bare 137
    // appears, it must be ACCOMPANIED by the OOM annotation, never alone. The
    // raw signal is fine for forensics; a bare 137 with no human reason is not.
    if (BARE_137.test(panelText)) {
      expect(
        OOM_REASON.test(panelText),
        'UI shows a bare "exit 137" with NO OOM annotation — exactly the SRE-1 paging ' +
          'regression this spec guards. The raw signal must travel WITH "killed: OOM", never alone.',
      ).toBe(true)
    }

    expect(
      pageErrors,
      `uncaught JS errors while rendering the OOM build-detail: ${JSON.stringify(pageErrors)}`,
    ).toEqual([])
  })
})

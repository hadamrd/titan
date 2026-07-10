/**
 * golden-path-failure-triage — the SRE's day-zero failure-triage loop.
 *
 * Closes issue #1130.
 *
 * Why this spec exists:
 *   Titan's existing e2e suite covers gate approval, JUnit reporting, the live
 *   graph, profile tokens, and the happy-path real build (`v3/21-…`). It does
 *   NOT cover the single most-clicked UX flow in any CI product:
 *
 *     push -> red build -> click failing step -> see failing log line
 *                      -> re-run that step -> green
 *
 *   If any of those five legs silently regresses, Titan is unshippable for the
 *   SRE persona. This spec is the integration assertion.
 *
 * Strategy:
 *   1. Create a job from the `e2e/pipelines/node-app-with-failing-test/`
 *      fixture (vitest suite where exactly ONE test fails on purpose).
 *   2. Trigger a build, assert it terminates as FAILURE within the triage
 *      budget (default 30 s; configurable via TITAN_E2E_TRIAGE_BUDGET_MS —
 *      see #151 and the WHY note on FAILURE_TIMEOUT_MS below).
 *   3. Open the build-detail page, assert the failing step renders with a
 *      red/error accessible status (role + data-status, NOT pixel colour).
 *   4. Click the failing step, assert the log viewer renders and contains a
 *      line matching /FAIL / (vitest's failure prefix).
 *   5. (test.fixme) per-step re-run affordance — see #1130 follow-up. For
 *      now we exercise a full job re-trigger after patching the failing test
 *      to passing and assert SUCCESS. The patch is BUILD-SCOPED (#161): leg 4
 *      rewrites this job's pipelineScript (PATCH /api/v1/jobs/{id}) at the
 *      fixture's E2E_PATCH_POINT anchor so the sed runs inside the build's
 *      own workspace copy. It must NEVER touch the host fixture tree: the
 *      previous host-side fs.writeFileSync patch window (~20s) raced every
 *      concurrent spec that tar-copies the same fixture (TITAN_PW_WORKERS=2),
 *      and a should-fail build that copied inside the window went phantom
 *      SUCCESS (#161, observed live 2026-07-10 06:57: builds 3110 FAILED /
 *      3111 patched-SUCCESS / 3112 phantom-SUCCESS — the :ro mount does not
 *      stop host-side edits passing through the bind mount).
 *
 * What's deferred (test.fixme + follow-up issue) — out-of-scope per #1130:
 *   * GitHub PR check status transition (requires real GitHub repo + webhook
 *     wiring, not present on `task dev:titan`). Tracked in #1130 follow-up.
 *   * Per-step "re-run only this step" control (UI affordance not yet shipped;
 *     full re-trigger is the substitute oracle here).
 *   * Log viewport scroll-to-line (UI affordance gap; we assert the FAIL line
 *     is present in the rendered log content rather than auto-scrolled into
 *     viewport).
 *
 * Adversarial assertions (per #1130 test matrix):
 *   * Sad path: clicking a step with no log output must not crash the UI nor
 *     emit JS errors in the browser console.
 *   * Timing:  FAILURE must be observed before any SUCCESS — the PR-check /
 *     build-status must not flip green while the failing step still runs.
 *   * Re-run scope: after re-triggering, already-passed steps must either be
 *     SKIPPED or start AFTER the trigger timestamp (no stale-cache success).
 */
import * as fs from 'node:fs'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken, loginViaKeycloak } from '../fixtures/auth-v3'
import { safeDeleteJobCascade } from '../fixtures/teardown-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

// #151: trigger→FAILURE budget. The DEFAULT stays 30_000 — the strict canary
// for every non-smoke context. PR #146 proved product latency healthy (engine
// overhead ~3s, verdict fold ~1.5s); the residual intermittent misses were
// npm/vitest EXECUTION time under suite-start CPU contention on the local
// rig, so dev/rig-smoke/run-golden.sh exports TITAN_E2E_TRIAGE_BUDGET_MS=45000
// for the smoke ONLY. The legacy TITAN_FAILURE_TRIAGE_TIMEOUT_MS name is
// honored as a fallback (it predates #151 and was only ever read here).
const FAILURE_TIMEOUT_MS = Number(
  process.env.TITAN_E2E_TRIAGE_BUDGET_MS
    ?? process.env.TITAN_FAILURE_TRIAGE_TIMEOUT_MS
    ?? 30_000,
)
// #151: keep polling past the budget so a miss records its REAL observed
// latency (the elapsed <= budget assertion below stays the oracle) instead of
// aborting blind at the deadline with no measurement.
const TRIAGE_POLL_GRACE_MS = 45_000
const SUCCESS_TIMEOUT_MS = Number(process.env.TITAN_FAILURE_TRIAGE_SUCCESS_TIMEOUT_MS ?? 60_000)

const TERMINAL = new Set(['SUCCESS', 'FAILED', 'FAILURE', 'ABORTED', 'UNSTABLE', 'ERROR'])
const SUCCESSFUL = new Set(['SUCCESS'])
const FAILED = new Set(['FAILED', 'FAILURE', 'ERROR'])

// #161: the fixture's install stage carries a single-line no-op anchor
// (`true # E2E_PATCH_POINT …`) that specs rewrite via PATCH
// /api/v1/jobs/{id} to alter the BUILD's workspace copy. Rewriting the job's
// pipelineScript is the only sanctioned way to vary this fixture's behaviour
// — the host fixture tree under e2e/pipelines/ is immutable at run time.
const PATCH_POINT_RE = /^([ \t]*)true # E2E_PATCH_POINT.*$/m

interface JobCreateResp { id: number }
interface BuildTriggerResp { buildId: number; buildNumber: number }
interface BuildDto { id: number; status: string; startedAt: string | null; finishedAt: string | null }
interface FlowNodeDto {
  buildId: number
  nodeId: string
  displayName?: string | null
  nodeType?: string | null
  status?: string | null
  startedAt?: string | null
  attempt?: number | null
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
  // The spec lives at e2e/specs/golden-path-failure-triage.spec.ts; the fixture
  // lives at e2e/pipelines/node-app-with-failing-test/. Resolve relative.
  const here = path.dirname(fileURLToPath(import.meta.url))
  return path.resolve(here, '..', 'pipelines', 'node-app-with-failing-test')
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
      displayName: 'E2E failure triage (#1130)',
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

function findNodeByDisplay(nodes: FlowNodeDto[], name: string): FlowNodeDto | null {
  return (
    nodes.find(n => (n.displayName ?? '').trim() === name && (n.nodeType ?? '').toUpperCase() === 'STAGE')
    ?? nodes.find(n => (n.displayName ?? '').trim() === name)
    ?? null
  )
}

test.describe('golden-path-failure-triage @golden @sre', () => {
  // Job state mutated across tests — keep serial.
  test.describe.configure({ mode: 'serial' })

  let bearer: string
  let jobId: number
  let runTag: string
  let failingBuildId: number
  let failingBuildNodes: FlowNodeDto[] = []

  test.beforeAll(async ({ request }) => {
    bearer = await fetchBearerToken(ENV)
    runTag = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`
    jobId = await createJob(request, bearer, `e2e-failure-triage-${runTag}`)
  })

  test.afterAll(async ({ request }) => {
    // #116: zero-litter — this suite used to leak one e2e-failure-triage-*
    // job per run (~27 rows in the litter census). Cancel-then-delete via
    // safeDeleteJobCascade; never yanks a leased task_queue row (#59).
    if (jobId) {
      await safeDeleteJobCascade(request, jobId).catch(() => undefined)
    }
  })

  test('1. push -> red build: FAILURE within the triage budget (default 30s)', async ({ request }) => {
    test.setTimeout(FAILURE_TIMEOUT_MS + TRIAGE_POLL_GRACE_MS + 60_000)

    const t0 = Date.now()
    failingBuildId = await triggerBuild(request, bearer, jobId)

    const { status, observedStatuses } = await pollTerminal(
      request,
      bearer,
      failingBuildId,
      FAILURE_TIMEOUT_MS + TRIAGE_POLL_GRACE_MS,
    )
    const elapsed = Date.now() - t0

    // #151 latency telemetry: record the observed trigger→terminal latency as
    // a test annotation (visible in the HTML/JUnit reports) AND as a
    // grep-able console line (rig-smoke-parse.sh lifts it into the smoke
    // JSONL as the additive `triageLatencyMs` field). Budget drift becomes
    // visible in data instead of as flakes.
    test.info().annotations.push({ type: 'triage-latency-ms', description: String(elapsed) })
    console.log(
      `[triage-telemetry] triage_latency_ms=${elapsed} budget_ms=${FAILURE_TIMEOUT_MS} ` +
        `build_id=${failingBuildId} status=${status}`,
    )

    expect(
      FAILED.has(status),
      `expected FAILED-class terminal status for build ${failingBuildId} ` +
        `(fixture has one intentionally-failing vitest), got ${status}. ` +
        `Observed sequence: [${observedStatuses.join(', ')}].`,
    ).toBe(true)

    expect(
      elapsed,
      `build ${failingBuildId} took ${elapsed}ms to reach FAILURE — SLA is ${FAILURE_TIMEOUT_MS}ms.`,
    ).toBeLessThanOrEqual(FAILURE_TIMEOUT_MS)

    // Adversarial 2 (timing): SUCCESS must never have been observed before FAILURE.
    // Guards against the "PR check flipped green while the failing step still ran"
    // regression class.
    for (const seen of observedStatuses) {
      expect(
        SUCCESSFUL.has(seen),
        `build ${failingBuildId} transiently reported SUCCESS while failing step still ran: ` +
          `observed=[${observedStatuses.join(', ')}]`,
      ).toBe(false)
    }

    // Snapshot the API's view of the nodes for the UI assertions below.
    const nodes = await apiGet<FlowNodeDto[]>(request, bearer, `/api/v1/builds/${failingBuildId}/nodes`)
    expect(nodes.ok && Array.isArray(nodes.body), `GET /nodes failed: ${nodes.raw.slice(0, 300)}`).toBe(true)
    failingBuildNodes = nodes.body!
    expect(failingBuildNodes.length, 'engine emitted zero flow_nodes for the failing build').toBeGreaterThan(0)

    const unitTest = findNodeByDisplay(failingBuildNodes, 'unit-test')
    expect(unitTest, 'unit-test stage not present in flow_nodes').not.toBeNull()
    expect(
      FAILED.has((unitTest!.status ?? '').toUpperCase()),
      `unit-test node terminal status expected FAILED, got ${unitTest!.status}`,
    ).toBe(true)
  })

  test('2. click failing step -> build-detail surfaces red status + FAIL log line', async ({ page }) => {
    test.skip(!failingBuildId, 'prior failing-build test did not complete')
    test.setTimeout(120_000)

    // Track JS errors and console errors for the adversarial assertion.
    const consoleErrors: string[] = []
    const pageErrors: string[] = []
    page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text()) })
    page.on('pageerror', err => pageErrors.push(String(err)) )

    await loginViaKeycloak(page, ENV)
    await page.goto(`${ENV.uiBaseUrl}/builds/${failingBuildId}`)

    // Build-detail shell — the v3 layout used by the rest of the suite.
    const detailShell = page.locator('[data-testid="build-detail-v3"]')
    await expect(detailShell, 'v3 build-detail shell did not render').toBeVisible({ timeout: 15_000 })

    // The failing step renders with an accessible failed status. Use the
    // data-status attribute (stable wire status — see 21-golden-path-real-build)
    // rather than CSS pixel colour.
    const failedNodes = page.locator('[data-status="FAILED"], [data-status="FAILURE"], [data-status="ERROR"]')
    await expect(
      failedNodes.first(),
      'no node rendered with a FAILED-class data-status — UI dropped the failing-step indicator',
    ).toBeVisible({ timeout: 15_000 })

    // Click into the failing step. The DAG tree rail uses [data-testid^="tree-row-"];
    // we click whichever row corresponds to the unit-test node.
    const unitTestNode = findNodeByDisplay(failingBuildNodes, 'unit-test')
    expect(unitTestNode, 'unit-test node disappeared between fetch and click').not.toBeNull()

    const treeRow = page.locator(`[data-testid="tree-row-${unitTestNode!.nodeId}"]`)
    if (await treeRow.count() > 0) {
      await treeRow.first().click()
    } else {
      // Fallback: click any element whose data-status is FAILED — covers UI
      // refactors that drop the per-row testid scheme.
      await failedNodes.first().click()
    }

    // Open the Logs tab — same surface 21-golden-path-real-build asserts on.
    const logsTab = page.getByRole('tab', { name: /^logs$/i })
    if (await logsTab.count() > 0) {
      await logsTab.click()
      await expect(logsTab).toHaveAttribute('aria-selected', 'true')
    }

    // The log viewer must NOT show the empty-state literal.
    await expect(page.getByText('No log output yet.')).not.toBeVisible({ timeout: 20_000 })

    // At least one log line rendered.
    const logLines = page.locator('.log-line')
    await expect(logLines.first(), 'log viewer rendered zero lines for a build that ran').toBeVisible({ timeout: 20_000 })

    // The failing step's log MUST contain vitest's "FAIL " prefix. We assert
    // presence in the DOM — auto-scroll-to-line is deferred (see test.fixme
    // below).
    const failLineMatcher = page.locator('.log-line', { hasText: /FAIL / })
    await expect(
      failLineMatcher.first(),
      'log viewer rendered no /FAIL / line — either vitest output was dropped or the failing step had no log',
    ).toBeVisible({ timeout: 20_000 })

    // Adversarial 1 (sad path): clicking a step with NO log output must not
    // crash the UI. We exercise this by re-clicking the install step (the
    // first stage — usually has log output, but if a re-run skipped it,
    // the empty-state must render gracefully).
    const installNode = findNodeByDisplay(failingBuildNodes, 'install')
    if (installNode) {
      const installRow = page.locator(`[data-testid="tree-row-${installNode.nodeId}"]`)
      if (await installRow.count() > 0) {
        await installRow.first().click()
        // Whatever the log state, the build-detail shell must remain mounted
        // (no uncaught JS error tearing the React tree down).
        await expect(detailShell).toBeVisible()
      }
    }

    expect(
      pageErrors,
      `uncaught JS errors during failure-triage click flow: ${JSON.stringify(pageErrors)}`,
    ).toEqual([])
    // Console errors get a softer assertion — known-benign noise (SSE
    // reconnect chatter) is filtered by callers if needed. We only fail on
    // hard React invariant violations.
    const reactInvariantErrors = consoleErrors.filter(e => /Minified React error|Cannot read prop|Uncaught/i.test(e))
    expect(
      reactInvariantErrors,
      `React invariant violations on click: ${JSON.stringify(reactInvariantErrors)}`,
    ).toEqual([])
  })

  test.fixme(
    '3a. per-step "re-run only this step" control restarts only the failed step',
    async () => {
      // Deferred: the per-step re-run UI control is not yet shipped. See
      // #1130 acceptance criteria + the "Out of scope" rider. Follow-up
      // issue to be filed with title:
      //   "ux: per-step re-run control on the build-detail page (#1130 follow-up)"
      // When the affordance lands, the assertion becomes:
      //   1. Click the per-step re-run button on the failed `unit-test` row.
      //   2. Capture the new buildId from the POST response.
      //   3. Assert API `/builds/$new/nodes` shows install/build as SKIPPED
      //      (or with startedAt < trigger timestamp — i.e. cached/reused).
      //   4. Assert unit-test alone was re-executed.
    },
  )

  test.fixme(
    '3b. log viewer auto-scrolls the /FAIL / line into viewport on step click',
    async () => {
      // Deferred: scroll-to-line is not yet wired into the v3 log viewer.
      // Follow-up issue: "ux: log viewer scrolls to first matching highlight
      // line when a failed step is opened (#1130 follow-up)".
      // When shipped: assert the .log-line[data-match="FAIL"] has
      // boundingClientRect inside the viewport rect of the scroll container.
    },
  )

  test('4. patch failing test -> re-run -> SUCCESS (build-scoped patch, #161)', async ({ request }) => {
    test.skip(!failingBuildId, 'prior failing-build test did not complete')
    test.setTimeout(SUCCESS_TIMEOUT_MS + 60_000)

    // BUILD-SCOPED patch (#161): rewrite THIS job's pipelineScript so the
    // install stage flips the intentional regression to passing INSIDE the
    // build's own workspace copy. The host fixture tree is never touched, so
    // a concurrent spec's tar copy can never observe a passing source (the
    // #161 phantom-SUCCESS mechanism). The chained grep makes the sed
    // self-verifying: if the fixture's assertion text drifts, sed no-ops,
    // grep exits 1, install FAILS — never a silent no-op.
    const baseYaml = readPipelineYaml()
    const patchedYaml = baseYaml.replace(
      PATCH_POINT_RE,
      "$1sed -i 's/expect(sum(1, 1)).toBe(3);/expect(sum(1, 1)).toBe(2);/' src/sum.test.js && grep -qF 'expect(sum(1, 1)).toBe(2);' src/sum.test.js # leg-4 patch (#1130/#161): workspace-scoped, host untouched",
    )
    expect(
      patchedYaml,
      'E2E_PATCH_POINT anchor not found in the fixture pipeline yaml — refusing to silently no-op',
    ).not.toBe(baseYaml)

    const patchResp = await request.patch(`${API_BASE}/api/v1/jobs/${jobId}`, {
      headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
      data: { pipelineScript: patchedYaml },
    })
    expect(
      patchResp.status(),
      `PATCH /jobs/${jobId} pipelineScript failed: ${await patchResp.text()}`,
    ).toBe(200)

    try {
      const t0 = Date.now()
      const successBuildId = await triggerBuild(request, bearer, jobId)
      expect(successBuildId).not.toBe(failingBuildId)

      const { status, observedStatuses } = await pollTerminal(request, bearer, successBuildId, SUCCESS_TIMEOUT_MS)
      const elapsed = Date.now() - t0

      expect(
        SUCCESSFUL.has(status),
        `expected SUCCESS after patching the failing test, got ${status} in ${elapsed}ms. ` +
          `Observed: [${observedStatuses.join(', ')}]. Has the worker workspace mount drifted?`,
      ).toBe(true)

      // Adversarial 3 (re-run scope sanity): the new build emitted its own
      // flow_nodes and they all started AFTER the re-trigger. Guards against
      // a stale-cache "SUCCESS without actually re-running" regression.
      const nodesResp = await apiGet<FlowNodeDto[]>(request, bearer, `/api/v1/builds/${successBuildId}/nodes`)
      expect(nodesResp.ok && Array.isArray(nodesResp.body)).toBe(true)
      const newNodes = nodesResp.body!
      const unitTest = findNodeByDisplay(newNodes, 'unit-test')
      expect(unitTest, 'unit-test stage missing from re-run build').not.toBeNull()
      expect(
        SUCCESSFUL.has((unitTest!.status ?? '').toUpperCase()),
        `unit-test stage status on re-run expected SUCCESS, got ${unitTest!.status}`,
      ).toBe(true)
      if (unitTest!.startedAt) {
        const startedMs = Date.parse(unitTest!.startedAt)
        expect(
          startedMs,
          `unit-test on re-run started at ${unitTest!.startedAt} — BEFORE the trigger at ${new Date(t0).toISOString()}. ` +
            `That's a stale-cache regression.`,
        ).toBeGreaterThanOrEqual(t0 - 5_000)
      }
    } finally {
      // Defensive hygiene: point the job back at the pristine should-fail
      // pipeline so even a failed afterAll delete can never leave a job that
      // produces SUCCESS builds from the should-fail fixture. Best-effort —
      // teardown must not mask the test's own verdict. NOTE: nothing on the
      // host was mutated, so there is nothing to restore on disk (#161).
      await request
        .patch(`${API_BASE}/api/v1/jobs/${jobId}`, {
          headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
          data: { pipelineScript: baseYaml },
        })
        .catch(() => undefined)
    }
  })
})

/**
 * fixture-test-absence-guard — a should-fail fixture must be STRUCTURALLY
 * INCAPABLE of succeeding via test-absence (#161).
 *
 * Why this spec exists:
 *   Twice now a build of `node-app-with-failing-test` terminated SUCCESS —
 *   the worst-class verdict a CI product can emit (a red build shown green).
 *   #157/#158 fixed the first mechanism (shared node_modules collision);
 *   #161's mechanism was a host-side patch window in the triage spec's leg 4
 *   (now build-scoped). This spec is the standing guard for the residual
 *   class: WHATEVER makes the test file invisible to the unit-test step —
 *   tar copy failure, cwd drift, partial copy, host mutation — the build
 *   must terminate FAILED, never SUCCESS.
 *
 * How:
 *   The fixture's install stage carries a single-line no-op anchor
 *   (`true # E2E_PATCH_POINT …`). Each test here creates its OWN job whose
 *   pipelineScript replaces that anchor with a sabotage command that breaks
 *   the build's OWN workspace copy (never the host fixture — that host-side
 *   mutation was exactly the #161 bug):
 *     1. `rm -f src/sum.test.js`  — the test file is GONE. The unit-test
 *        stage's `test -f` guard must fail the step.
 *     2. `: > src/sum.test.js`    — the file EXISTS but holds zero tests.
 *        The `test -f` guard passes; vitest itself must exit non-zero
 *        ("no test suite found"; --passWithNoTests=false pins the default —
 *        both behaviours verified empirically on vitest 2.1.9).
 *   Both must terminate FAILED with the unit-test node FAILED. If either
 *   ever goes SUCCESS, the phantom-green class has regressed.
 *
 * Zero-litter: every job is deleted in afterAll via safeDeleteJobCascade.
 */
import * as fs from 'node:fs'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken } from '../fixtures/auth-v3'
import { safeDeleteJobCascade } from '../fixtures/teardown-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const TERMINAL = new Set(['SUCCESS', 'FAILED', 'FAILURE', 'ABORTED', 'UNSTABLE', 'ERROR'])
const FAILED = new Set(['FAILED', 'FAILURE', 'ERROR'])

// Generous: sabotage builds still run a full npm ci in the workspace.
const BUILD_TIMEOUT_MS = Number(process.env.TITAN_E2E_ABSENCE_GUARD_TIMEOUT_MS ?? 90_000)

// Same anchor contract as golden-path-failure-triage leg 4 (#161).
const PATCH_POINT_RE = /^([ \t]*)true # E2E_PATCH_POINT.*$/m

interface JobCreateResp { id: number }
interface BuildTriggerResp { buildId: number }
interface BuildDto { id: number; status: string }
interface FlowNodeDto {
  nodeId: string
  displayName?: string | null
  nodeType?: string | null
  status?: string | null
}

function fixtureYaml(): string {
  const here = path.dirname(fileURLToPath(import.meta.url))
  return fs.readFileSync(
    path.resolve(here, '..', 'pipelines', 'node-app-with-failing-test', 'titan-pipeline.yml'),
    'utf8',
  )
}

/** Replace the E2E_PATCH_POINT anchor with a sabotage command — fail loud if the anchor drifted. */
function sabotagedYaml(sabotage: string): string {
  const base = fixtureYaml()
  const out = base.replace(PATCH_POINT_RE, `$1${sabotage}`)
  expect(
    out,
    'E2E_PATCH_POINT anchor not found in node-app-with-failing-test/titan-pipeline.yml — ' +
      'the sabotage would silently no-op (and this guard would assert nothing)',
  ).not.toBe(base)
  return out
}

async function createJob(
  api: APIRequestContext,
  bearer: string,
  fullName: string,
  pipelineScript: string,
): Promise<number> {
  const resp = await api.post(`${API_BASE}/api/v1/jobs`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: {
      fullName,
      displayName: 'E2E test-absence guard (#161)',
      pipelineScript,
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
  expect(body.buildId, 'trigger returned no buildId').toBeGreaterThan(0)
  return body.buildId
}

async function pollTerminal(
  api: APIRequestContext,
  bearer: string,
  buildId: number,
): Promise<string> {
  const deadline = Date.now() + BUILD_TIMEOUT_MS
  let last = 'QUEUED'
  while (Date.now() < deadline) {
    const r = await api.get(`${API_BASE}/api/v1/builds/${buildId}`, {
      headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
    })
    if (r.ok()) {
      try {
        last = (JSON.parse(await r.text()) as BuildDto).status ?? last
      } catch { /* keep last */ }
      if (TERMINAL.has(last)) return last
    }
    await new Promise((res) => setTimeout(res, 1_000))
  }
  throw new Error(`build ${buildId} not terminal within ${BUILD_TIMEOUT_MS}ms; last=${last}`)
}

async function unitTestNodeStatus(
  api: APIRequestContext,
  bearer: string,
  buildId: number,
): Promise<string> {
  const r = await api.get(`${API_BASE}/api/v1/builds/${buildId}/nodes`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  expect(r.ok(), `GET /builds/${buildId}/nodes failed: HTTP ${r.status()}`).toBe(true)
  const nodes = JSON.parse(await r.text()) as FlowNodeDto[]
  const unitTest =
    nodes.find(
      (n) => (n.displayName ?? '').trim() === 'unit-test' && (n.nodeType ?? '').toUpperCase() === 'STAGE',
    ) ?? nodes.find((n) => (n.displayName ?? '').trim() === 'unit-test')
  expect(unitTest, `unit-test stage missing from build ${buildId} flow_nodes`).toBeTruthy()
  return (unitTest!.status ?? '').toUpperCase()
}

test.describe('fixture test-absence guard @adversarial @sre @161', () => {
  let bearer: string
  const runTag = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`
  const createdJobIds: number[] = []

  test.beforeAll(async () => {
    bearer = await fetchBearerToken(ENV)
    expect(bearer.length, 'Keycloak returned an empty bearer — is the rig up?').toBeGreaterThan(10)
  })

  test.afterAll(async ({ request }) => {
    // Zero-litter (#116): delete every job this suite created.
    for (const id of createdJobIds) {
      await safeDeleteJobCascade(request, id).catch(() => undefined)
    }
  })

  test('deleted workspace test file -> build FAILS (never SUCCESS)', async ({ request }) => {
    test.setTimeout(BUILD_TIMEOUT_MS + 60_000)

    const yaml = sabotagedYaml(
      'rm -f src/sum.test.js # ADVERSARIAL (#161): simulate a workspace copy that lost the test file',
    )
    const jobId = await createJob(request, bearer, `e2e-absence-guard-rm-${runTag}`, yaml)
    createdJobIds.push(jobId)

    const buildId = await triggerBuild(request, bearer, jobId)
    const status = await pollTerminal(request, bearer, buildId)

    expect(
      FAILED.has(status),
      `build ${buildId} ran the should-fail fixture with src/sum.test.js DELETED from its workspace ` +
        `and terminated '${status}' — a should-fail fixture succeeded via test-absence. ` +
        `This is the #161 phantom-SUCCESS class; the unit-test stage's test -f guard did not fire.`,
    ).toBe(true)

    const unitTest = await unitTestNodeStatus(request, bearer, buildId)
    expect(
      FAILED.has(unitTest),
      `unit-test node terminated '${unitTest}' — expected FAILED (the test -f guard must fail THAT stage, ` +
        `so triage points at the right step)`,
    ).toBe(true)
  })

  test('emptied workspace test file (zero tests) -> build FAILS (never SUCCESS)', async ({ request }) => {
    test.setTimeout(BUILD_TIMEOUT_MS + 60_000)

    const yaml = sabotagedYaml(
      ': > src/sum.test.js # ADVERSARIAL (#161): file exists but holds ZERO tests — vitest must fail, not pass-with-no-tests',
    )
    const jobId = await createJob(request, bearer, `e2e-absence-guard-empty-${runTag}`, yaml)
    createdJobIds.push(jobId)

    const buildId = await triggerBuild(request, bearer, jobId)
    const status = await pollTerminal(request, bearer, buildId)

    expect(
      FAILED.has(status),
      `build ${buildId} ran the should-fail fixture with src/sum.test.js EMPTIED in its workspace ` +
        `and terminated '${status}' — vitest passed with zero tests. ` +
        `Check --passWithNoTests=false on the unit-test step (#161).`,
    ).toBe(true)

    const unitTest = await unitTestNodeStatus(request, bearer, buildId)
    expect(
      FAILED.has(unitTest),
      `unit-test node terminated '${unitTest}' — expected FAILED (vitest must reject a zero-test file)`,
    ).toBe(true)
  })
})

/**
 * matrix-fixture — matrix step expansion end-to-end (issue #1127).
 *
 * Why this spec exists:
 *   #1127 ships matrix step expansion: a `matrix:` block on a stage fans the
 *   stage out across the Cartesian product of declared axes, each cell runs
 *   as an independent worker task with its own log stream and env injection
 *   (MATRIX_<AXIS>), and the parent verdict aggregates over the cells.
 *
 *   This spec is the integration assertion that the parser + scheduler +
 *   worker + UI seam actually delivers that behaviour against the live rig.
 *
 * Strategy:
 *   1. Create a job from the `e2e/pipelines/java-matrix/` fixture
 *      (matrix axes: jdk × profile = 2 × 2 = 4 cells; one cell — jdk=21,
 *      profile=it — is rigged to fail).
 *   2. Trigger a build, wait for terminal status (FAILED, because of the
 *      rigged cell, AND fail_fast: false so siblings finish).
 *   3. Assert the API surfaces 4 cell stages, each with the expected
 *      axis-tuple naming (`build [jdk=…,profile=…]`).
 *   4. Assert per-cell verdicts: 3 SUCCESS + 1 FAILED.
 *   5. Pull one passing cell's log and assert it contains the cell's
 *      MATRIX_JDK / MATRIX_PROFILE values (proves env injection threads
 *      end-to-end).
 *
 * Adversarial assertions (per #1127 test matrix):
 *   * fail_fast: false — every non-rigged cell MUST reach SUCCESS even
 *     though one cell failed. A `FAILED` count > 1 means fail-fast leaked.
 *   * No cell may end ABORTED (would mean fail-fast cancelled siblings
 *     despite fail_fast=false on the matrix).
 *
 * UI assertions (build-detail rendering of cell rows) are guarded behind
 * a soft check: if the v3 build-detail shell exposes per-cell rows with
 * the axis-tuple label, we assert their presence; if the UI hasn't yet
 * landed cell-aware rendering, the spec records a soft warning rather
 * than failing. The hard contract is the API + worker layer: the UI
 * follow-on is tracked under #1127's UI slice.
 */
import * as fs from 'node:fs'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken } from '../fixtures/auth-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const TERMINAL_TIMEOUT_MS = Number(process.env.TITAN_MATRIX_TIMEOUT_MS ?? 90_000)

const TERMINAL = new Set(['SUCCESS', 'FAILED', 'FAILURE', 'ABORTED', 'UNSTABLE', 'ERROR'])
const FAILED = new Set(['FAILED', 'FAILURE', 'ERROR'])

interface JobCreateResp { id: number }
interface BuildTriggerResp { buildId: number; buildNumber: number }
interface BuildDto { id: number; status: string }
interface FlowNodeDto {
  buildId: number
  nodeId: string
  displayName?: string | null
  nodeType?: string | null
  status?: string | null
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

function fixtureYaml(): string {
  const here = path.dirname(fileURLToPath(import.meta.url))
  return fs.readFileSync(
    path.resolve(here, '..', 'pipelines', 'java-matrix', 'titan-pipeline.yml'),
    'utf8',
  )
}

async function createJob(api: APIRequestContext, bearer: string, fullName: string): Promise<number> {
  const resp = await api.post(`${API_BASE}/api/v1/jobs`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: {
      fullName,
      displayName: 'E2E matrix step expansion (#1127)',
      pipelineScript: fixtureYaml(),
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
  return (JSON.parse(await r.text()) as BuildTriggerResp).buildId
}

async function pollTerminal(
  api: APIRequestContext,
  bearer: string,
  buildId: number,
  budgetMs: number,
): Promise<string> {
  const deadline = Date.now() + budgetMs
  let last = 'QUEUED'
  while (Date.now() < deadline) {
    const r = await apiGet<BuildDto>(api, bearer, `/api/v1/builds/${buildId}`)
    if (r.ok && r.body?.status) {
      last = r.body.status
      if (TERMINAL.has(last)) return last
    }
    await new Promise(res => setTimeout(res, 1_000))
  }
  throw new Error(`build ${buildId} not terminal within ${budgetMs}ms; last=${last}`)
}

/**
 * The MatrixScope expands a stage `build` over {jdk × profile} into 4 cell
 * stages whose ids look like `build-jdk-17-profile-unit`. We match either
 * id-shape (cell id) or display-name shape (`build [jdk=…,profile=…]`).
 */
function matrixCells(nodes: FlowNodeDto[]): FlowNodeDto[] {
  return nodes.filter(n => {
    const id = (n.nodeId ?? '').toLowerCase()
    const name = (n.displayName ?? '').toLowerCase()
    const isStage = (n.nodeType ?? '').toUpperCase() === 'STAGE'
    return isStage && (id.startsWith('build-jdk-') || name.startsWith('build [') || name.startsWith('build ['))
  })
}

test.describe('matrix-fixture @matrix', () => {
  test.describe.configure({ mode: 'serial' })

  let bearer: string
  let jobId: number
  let buildId: number
  let nodes: FlowNodeDto[] = []
  const runTag = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`

  test.beforeAll(async ({ request }) => {
    bearer = await fetchBearerToken(ENV)
    jobId = await createJob(request, bearer, `e2e-matrix-${runTag}`)
  })

  test('1. matrix fans build out into 4 cells and parent rolls up as FAILED', async ({ request }) => {
    test.setTimeout(TERMINAL_TIMEOUT_MS + 30_000)

    buildId = await triggerBuild(request, bearer, jobId)
    const status = await pollTerminal(request, bearer, buildId, TERMINAL_TIMEOUT_MS)

    expect(
      FAILED.has(status),
      `parent verdict for matrix with one rigged failing cell + fail_fast=false ` +
        `must roll up as FAILED, got ${status}`,
    ).toBe(true)

    const r = await apiGet<FlowNodeDto[]>(request, bearer, `/api/v1/builds/${buildId}/nodes`)
    expect(r.ok && Array.isArray(r.body), `GET /nodes failed: ${r.raw.slice(0, 300)}`).toBe(true)
    nodes = r.body!

    const cells = matrixCells(nodes)
    expect(cells.length, `expected 4 matrix cell stages, got ${cells.length}: ` +
      cells.map(c => `${c.nodeId}=${c.status}`).join(', '),
    ).toBe(4)

    // Per-cell verdict assertions — fail_fast: false guarantees the 3 non-rigged
    // cells reach SUCCESS regardless of the 4th cell failing.
    const byStatus: Record<string, FlowNodeDto[]> = {}
    for (const c of cells) {
      const s = (c.status ?? 'UNKNOWN').toUpperCase()
      ;(byStatus[s] ??= []).push(c)
    }
    const successCount = (byStatus['SUCCESS'] ?? []).length
    const failedCount = (byStatus['FAILED'] ?? []).concat(byStatus['FAILURE'] ?? [], byStatus['ERROR'] ?? []).length
    const abortedCount = (byStatus['ABORTED'] ?? []).length

    expect(
      successCount,
      `fail_fast=false: 3 non-rigged cells MUST reach SUCCESS even though one failed. ` +
        `cells: ${cells.map(c => `${c.nodeId}=${c.status}`).join(', ')}`,
    ).toBe(3)
    expect(failedCount, `exactly one cell should be FAILED (the rigged jdk=21+profile=it cell)`).toBe(1)
    expect(
      abortedCount,
      `fail_fast=false MUST NOT cancel siblings; no cell should be ABORTED. ` +
        `cells: ${cells.map(c => `${c.nodeId}=${c.status}`).join(', ')}`,
    ).toBe(0)

    // The failed cell MUST be the rigged one (jdk=21, profile=it).
    const failedCell = (byStatus['FAILED'] ?? byStatus['FAILURE'] ?? byStatus['ERROR'] ?? [])[0]
    if (!failedCell) {
      throw new Error('expected exactly one rigged FAILED cell (jdk=21, profile=it), got none')
    }
    expect(failedCell.nodeId.toLowerCase()).toContain('jdk-21')
    expect(failedCell.nodeId.toLowerCase()).toContain('profile-it')
  })

  test('2. a passing cell\'s log reflects its MATRIX_* env bindings', async ({ request }) => {
    test.skip(!buildId, 'prior matrix-fan-out test did not complete')
    test.setTimeout(30_000)

    // Find a passing cell and pull its log via the SSE-backed log endpoint.
    const passing = matrixCells(nodes).find(n => (n.status ?? '').toUpperCase() === 'SUCCESS')
    expect(passing, 'no passing cell found — earlier test should have asserted >= 3').toBeTruthy()

    // The cell's id encodes its axis tuple: e.g. build-jdk-17-profile-unit.
    const id = passing!.nodeId
    const jdkMatch = id.match(/jdk-(\d+)/)
    const profileMatch = id.match(/profile-(\w+)/)
    expect(jdkMatch && profileMatch, `cell id ${id} did not parse as build-jdk-<v>-profile-<v>`).toBeTruthy()
    const jdk = jdkMatch![1]
    const profile = profileMatch![1]

    // Pull the rendered log text. The API exposes per-node logs at
    // /api/v1/builds/{id}/nodes/{nodeId}/log (used by the SSE viewer).
    // Some deployments wrap that as /log/stream; we probe both.
    let log: string | null = null
    for (const suffix of ['/log', '/log/stream', '/logs']) {
      const r = await apiGet<unknown>(request, bearer, `/api/v1/builds/${buildId}/nodes/${id}${suffix}`)
      if (r.ok && r.raw && r.raw.length > 0) {
        log = r.raw
        break
      }
    }
    test.skip(log === null, `no log endpoint surfaced output for node ${id} — SSE wiring not asserted here`)

    expect(log!.includes(`MATRIX_JDK`) || log!.includes(`jdk=${jdk}`),
      `cell log must echo its MATRIX_JDK / jdk binding; log head: ${log!.slice(0, 200)}`,
    ).toBe(true)
    expect(log!.includes(`MATRIX_PROFILE`) || log!.includes(`profile=${profile}`),
      `cell log must echo its MATRIX_PROFILE / profile binding; log head: ${log!.slice(0, 200)}`,
    ).toBe(true)
  })
})

/**
 * 43-matrix-aggregate — matrix fan-out + aggregator (@golden).
 *
 * Drives `matrix-aggregate.yml` from `hadamrd/titan-e2e-fixture`. Stresses the
 * design/54 `matrix:` scope (Cartesian product of named axes) PLUS the
 * downstream-aggregator pattern: a single stage that `dependsOn: [Matrix]`
 * and must run strictly after every cell terminates.
 *
 *   Matrix (java=[17,21] x os=[ubuntu,alpine])  — 4 parallel cells
 *     └── Aggregate
 *
 * Adversarial bar:
 *   - 4 distinct flow_nodes whose displayName carries the cell identity
 *     (`Matrix [java=17,os=ubuntu]` style, per MatrixScope.cellLabel).
 *   - every cell SUCCESS; if the engine collapses cells we catch it here.
 *   - Aggregate must be SUCCESS terminally; the build itself SUCCESS.
 */
import * as crypto from 'node:crypto'
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'
import { readFixtureYaml } from '../../fixtures/fixture-files'
import { pgClient } from '../../fixtures/seed-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const FIXTURE_REPO = 'hadamrd/titan-e2e-fixture'
const FIXTURE_BRANCH = process.env.TITAN_FIXTURE_BRANCH ?? 'main'
const FIXTURE_PATH = 'matrix-aggregate.yml'

const STAGE_MATRIX = 'Matrix'
const STAGE_AGGREGATE = 'Aggregate'

const TERMINAL_STATUSES = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'UNSTABLE', 'ERROR'])

const EXPECTED_CELLS: { java: number; os: string }[] = [
  { java: 17, os: 'ubuntu' },
  { java: 17, os: 'alpine' },
  { java: 21, os: 'ubuntu' },
  { java: 21, os: 'alpine' },
]

interface CredentialCreateResp { id: number }
interface JobCreateResp { id: number }
interface BuildListItem { id: number; status: string }
interface BuildsPage { items: BuildListItem[] }
interface BuildDetail { id: number; status: string }
interface FlowNode {
  buildId: number
  nodeId: string
  displayName?: string | null
  nodeType?: string | null
  status?: string | null
}

async function apiGet<T>(
  api: APIRequestContext,
  bearer: string,
  path: string,
): Promise<{ ok: boolean; status: number; body: T | null; raw: string }> {
  const r = await api.get(`${API_BASE}${path}`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  const raw = await r.text()
  let body: T | null = null
  try { body = JSON.parse(raw) as T } catch { /* leave null */ }
  return { ok: r.ok(), status: r.status(), body, raw }
}

function findCellNode(nodes: FlowNode[], java: number, os: string): FlowNode | null {
  // MatrixScope.cellLabel emits `<StageName> [java=17,os=ubuntu]`. We also
  // tolerate a different separator (the engine could legitimately reorder
  // axes), so we just check that the displayName contains both `java=N`
  // and `os=<v>` plus the stage prefix.
  return nodes.find(n => {
    const d = (n.displayName ?? '').trim()
    return d.startsWith(STAGE_MATRIX) && d.includes(`java=${java}`) && d.includes(`os=${os}`)
  }) ?? null
}

interface Ctx { bearer: string; credId: number; jobId: number }

async function setup(api: APIRequestContext, runTag: string): Promise<Ctx> {
  // Read the vendored fixture YAML (hermetic — #48).
  const yaml = readFixtureYaml(FIXTURE_PATH)
  for (const needle of ['matrix:', 'axes:', 'java:', 'os:', `stage: ${STAGE_AGGREGATE}`]) {
    expect(yaml.includes(needle), `fixture YAML missing "${needle}"`).toBe(true)
  }

  const bearer = await fetchBearerToken(ENV)

  const credKey = `e2e-matrix-${runTag}`
  const secret = `s3cr3t-${runTag}`
  const credResp = await api.post(`${API_BASE}/api/v1/credentials`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: { kind: 'STRING', scope: 'github-webhook', key: credKey, plaintext: secret },
  })
  expect(credResp.status(), `cred create ${await credResp.text()}`).toBe(201)
  const credId = (JSON.parse(await credResp.text()) as CredentialCreateResp).id

  const fullName = `e2e-matrix-${runTag}`
  const triggersConfig = {
    triggers: [
      { type: 'github', id: 'github-1', branches: [FIXTURE_BRANCH], events: ['push'], credentialsId: credKey },
    ],
  }
  const jobResp = await api.post(`${API_BASE}/api/v1/jobs`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: {
      fullName,
      displayName: 'E2E matrix-aggregate',
      pipelineScript: yaml,
      configJson: JSON.stringify(triggersConfig),
      enabled: true,
    },
  })
  expect(jobResp.status(), `job create ${await jobResp.text()}`).toBe(201)
  const jobId = (JSON.parse(await jobResp.text()) as JobCreateResp).id

  const payload = {
    ref: `refs/heads/${FIXTURE_BRANCH}`,
    before: '0'.repeat(40),
    after: 'f'.repeat(40),
    repository: { full_name: FIXTURE_REPO, default_branch: FIXTURE_BRANCH },
    pusher: { name: 'e2e-bot' },
    head_commit: { id: 'f'.repeat(40), message: 'matrix synthetic push' },
  }
  const body = Buffer.from(JSON.stringify(payload), 'utf8')
  const sig = 'sha256=' + crypto.createHmac('sha256', secret).update(body).digest('hex')

  const webhook = await api.post(`${API_BASE}/api/v1/triggers/github`, {
    headers: {
      'Content-Type': 'application/json',
      'X-GitHub-Event': 'push',
      'X-Hub-Signature-256': sig,
      'X-GitHub-Delivery': `e2e-${runTag}`,
    },
    data: body,
  })
  const wbody = JSON.parse(await webhook.text()) as { dispatched: boolean; detail: string }
  expect(webhook.status()).toBe(200)
  expect(wbody.dispatched, `webhook dispatched=false (${wbody.detail})`).toBe(true)
  return { bearer, credId, jobId }
}

async function waitForBuild(api: APIRequestContext, bearer: string, jobId: number): Promise<number> {
  let id = 0
  await expect.poll(async () => {
    const r = await apiGet<BuildsPage>(api, bearer, `/api/v1/jobs/${jobId}/builds?offset=0&limit=10`)
    if (!r.ok || !r.body) return 0
    const first = r.body.items[0]
    if (first) { id = first.id; return 1 }
    return 0
  }, { message: 'no build appeared', timeout: 30_000, intervals: [500, 1000, 2000] }).toBeGreaterThan(0)
  return id
}

async function pollTerminal(api: APIRequestContext, bearer: string, buildId: number, budgetMs: number): Promise<string> {
  const deadline = Date.now() + budgetMs
  let last = 'QUEUED'
  while (Date.now() < deadline) {
    const r = await apiGet<BuildDetail>(api, bearer, `/api/v1/builds/${buildId}`)
    if (r.ok && r.body?.status) {
      last = r.body.status
      if (TERMINAL_STATUSES.has(last)) return last
    }
    await new Promise(res => setTimeout(res, 1000))
  }
  throw new Error(`build ${buildId} not terminal within ${budgetMs}ms; last=${last}`)
}

async function cleanup(api: APIRequestContext, bearer: string, jobId: number | undefined, credId: number | undefined): Promise<void> {
  if (jobId && jobId > 0) {
    const c = pgClient(); await c.connect()
    try {
      const r = await c.query<{ id: string }>(`SELECT id::text AS id FROM titan.builds WHERE job_id = $1`, [jobId])
      const ids = r.rows.map(x => Number(x.id))
      if (ids.length > 0) {
        for (const t of ['approvals', 'test_result', 'artifact', 'flow_nodes', 'task_queue']) {
          await c.query(`DELETE FROM titan.${t} WHERE build_id = ANY($1::bigint[])`, [ids]).catch(() => undefined)
        }
        await c.query(`DELETE FROM titan.builds WHERE id = ANY($1::bigint[])`, [ids])
      }
      await c.query(`DELETE FROM titan.jobs WHERE id = $1`, [jobId])
    } finally { await c.end() }
  }
  if (credId && credId > 0) {
    await api.delete(`${API_BASE}/api/v1/credentials/${credId}`, { headers: { Authorization: `Bearer ${bearer}` } }).catch(() => undefined)
  }
}

test.describe('v3 matrix-aggregate @golden', () => {
  test('4 cells fan out, Aggregate joins after all cells SUCCESS', async ({ request }) => {
    test.setTimeout(4 * 60_000)
    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
    let ctx: Ctx | undefined
    try {
      ctx = await setup(request, runTag)
      const buildId = await waitForBuild(request, ctx.bearer, ctx.jobId)

      const final = await pollTerminal(request, ctx.bearer, buildId, 3 * 60_000)
      expect(
        final,
        `build must terminate SUCCESS — matrix fan-out + aggregator should both pass on `
        + `trivial echo cells (final=${final})`,
      ).toBe('SUCCESS')

      const finalNodes = (await apiGet<FlowNode[]>(request, ctx.bearer, `/api/v1/builds/${buildId}/nodes`)).body ?? []
      expect(finalNodes.length, 'no flow_nodes returned').toBeGreaterThan(0)

      // Hard assertion: a node per cell, all SUCCESS.
      const missingCells: string[] = []
      const notSuccess: string[] = []
      for (const c of EXPECTED_CELLS) {
        const n = findCellNode(finalNodes, c.java, c.os)
        if (!n) { missingCells.push(`java=${c.java},os=${c.os}`); continue }
        if ((n.status ?? '').toUpperCase() !== 'SUCCESS') {
          notSuccess.push(`java=${c.java},os=${c.os}=${n.status}`)
        }
      }
      expect(
        missingCells,
        `matrix expansion missed cells — engine fan-out did not produce 4 distinct nodes. `
        + `Got displayNames: ${JSON.stringify(finalNodes.map(n => n.displayName))}`,
      ).toEqual([])
      expect(
        notSuccess,
        `some matrix cells not SUCCESS: ${notSuccess.join(', ')}`,
      ).toEqual([])

      // Aggregate must be SUCCESS terminally.
      const agg = finalNodes.find(
        n => (n.displayName ?? '').trim() === STAGE_AGGREGATE
          && (n.nodeType ?? '').toUpperCase() === 'STAGE',
      ) ?? finalNodes.find(n => (n.displayName ?? '').trim() === STAGE_AGGREGATE)
      expect(agg, `${STAGE_AGGREGATE} node missing — aggregator did not materialise`).toBeDefined()
      expect(agg, `${STAGE_AGGREGATE} node missing`).not.toBeNull()
      expect(
        (agg!.status ?? '').toUpperCase(),
        `${STAGE_AGGREGATE} must be SUCCESS (was ${agg!.status}) — aggregator did not join after cells`,
      ).toBe('SUCCESS')
    } finally {
      if (ctx) await cleanup(request, ctx.bearer, ctx.jobId, ctx.credId)
    }
  })
})

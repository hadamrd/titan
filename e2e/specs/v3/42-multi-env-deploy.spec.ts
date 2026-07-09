/**
 * 42-multi-env-deploy — chained-gate stress walk (@golden).
 *
 * Drives `multi-env-deploy.yml` from `hadamrd/titan-e2e-fixture`. Two approval
 * gates in sequence; the spec proves the engine parks at EACH gate
 * independently and does NOT auto-advance past the second one after the
 * first has been approved.
 *
 *   Build -> SmokeTest -> DeployStaging Gate (1) -> DeployStaging ->
 *   IntegrationTest -> DeployProd Gate (2) -> DeployProd
 *
 * Adversarial bar: after approving gate-1, DeployStaging + IntegrationTest
 * must SUCCESS, then the build must park AGAIN with a fresh approval row;
 * DeployProd must NOT be SUCCESS before gate-2 is approved.
 *
 * Cleanup: per-run suffix on job + credential, finally{} truncates
 * approvals/flow_nodes/task_queue/builds/jobs.
 */
import * as crypto from 'node:crypto'
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'
import { readFixtureYaml } from '../../fixtures/fixture-files'
import { safeDeleteJobCascade } from '../../fixtures/teardown-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const FIXTURE_REPO = 'hadamrd/titan-e2e-fixture'
const FIXTURE_BRANCH = process.env.TITAN_FIXTURE_BRANCH ?? 'main'
const FIXTURE_PATH = 'multi-env-deploy.yml'

const GATE_1 = 'DeployStaging Gate'
const GATE_2 = 'DeployProd Gate'
const STAGE_DEPLOY_STAGING = 'DeployStaging'
const STAGE_INTEGRATION = 'IntegrationTest'
const STAGE_DEPLOY_PROD = 'DeployProd'

const TERMINAL_STATUSES = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'UNSTABLE', 'ERROR'])

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
interface ApprovalRow { id: number; buildId: number; status: string; prompt?: string }
interface ApprovalPage { items: ApprovalRow[] }
interface DecisionResponse { applied: boolean; status: string }

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

function findNode(nodes: FlowNode[], name: string): FlowNode | null {
  return (
    nodes.find(
      (n) => (n.displayName ?? '').trim() === name && (n.nodeType ?? '').toUpperCase() === 'STAGE',
    ) ??
    nodes.find(
      (n) => (n.displayName ?? '').trim() === name && (n.nodeType ?? '').toUpperCase() === 'GATE',
    ) ??
    nodes.find((n) => (n.displayName ?? '').trim() === name) ??
    null
  )
}

interface Ctx { bearer: string; credId: number; jobId: number }

async function setup(api: APIRequestContext, runTag: string): Promise<Ctx> {
  // Read the vendored fixture YAML (hermetic — #48).
  const yaml = readFixtureYaml(FIXTURE_PATH)
  for (const needle of [`gate: ${GATE_1}`, `gate: ${GATE_2}`, `stage: ${STAGE_DEPLOY_PROD}`]) {
    expect(yaml.includes(needle), `fixture YAML missing "${needle}"`).toBe(true)
  }

  const bearer = await fetchBearerToken(ENV)

  const credKey = `e2e-multi-env-${runTag}`
  const secret = `s3cr3t-${runTag}`
  const credResp = await api.post(`${API_BASE}/api/v1/credentials`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: { kind: 'STRING', scope: 'github-webhook', key: credKey, plaintext: secret },
  })
  expect(credResp.status(), `cred create ${await credResp.text()}`).toBe(201)
  const credId = (JSON.parse(await credResp.text()) as CredentialCreateResp).id
  // #83 sweep: from here on, a mid-setup failure (job create / webhook
  // dispatch) previously LEAKED the credential (and possibly the job) — the
  // caller's finally{} never sees a ctx when setup throws. Clean up what this
  // function created before rethrowing.
  let createdJobId: number | undefined
  try {
    const fullName = `e2e-multi-env-${runTag}`
    const triggersConfig = {
      triggers: [
        {
          type: 'github',
          id: 'github-1',
          branches: [FIXTURE_BRANCH],
          events: ['push'],
          credentialsId: credKey,
        },
      ],
    }
    const jobResp = await api.post(`${API_BASE}/api/v1/jobs`, {
      headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
      data: {
        fullName,
        displayName: 'E2E multi-env-deploy',
        pipelineScript: yaml,
        configJson: JSON.stringify(triggersConfig),
        enabled: true,
      },
    })
    expect(jobResp.status(), `job create ${await jobResp.text()}`).toBe(201)
    const jobId = (JSON.parse(await jobResp.text()) as JobCreateResp).id
    createdJobId = jobId

    const payload = {
      ref: `refs/heads/${FIXTURE_BRANCH}`,
      before: '0'.repeat(40),
      after: 'f'.repeat(40),
      repository: { full_name: FIXTURE_REPO, default_branch: FIXTURE_BRANCH },
      pusher: { name: 'e2e-bot' },
      head_commit: { id: 'f'.repeat(40), message: 'multi-env synthetic push' },
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
    expect(webhook.status(), `webhook ${webhook.status()}`).toBe(200)
    expect(wbody.dispatched, `webhook dispatched=false (${wbody.detail})`).toBe(true)

    return { bearer, credId, jobId }
  } catch (err) {
    await cleanup(api, bearer, createdJobId, credId)
    throw err
  }
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

/**
 * Wait for a pending approval row tied to this build whose prompt/name maps
 * to the expected gate stage. Returns the row plus the flow_nodes snapshot.
 *
 * We disambiguate gate-1 vs gate-2 by checking which prerequisite stages are
 * already SUCCESS on the snapshot — gate-2 can only legitimately park when
 * IntegrationTest is SUCCESS; gate-1 parks before SmokeTest's downstream.
 */
async function waitForPark(
  api: APIRequestContext,
  bearer: string,
  buildId: number,
  expectedUpstreamSuccess: string[],
  forbiddenSuccess: string[],
  budgetMs: number,
  label: string,
): Promise<{ approval: ApprovalRow; nodes: FlowNode[] }> {
  let found: ApprovalRow | null = null
  let foundNodes: FlowNode[] = []
  await expect.poll(async () => {
    const [buildResp, approvalsResp, nodesResp] = await Promise.all([
      apiGet<BuildDetail>(api, bearer, `/api/v1/builds/${buildId}`),
      apiGet<ApprovalPage>(api, bearer, `/api/v1/approvals?status=PENDING&limit=200`),
      apiGet<FlowNode[]>(api, bearer, `/api/v1/builds/${buildId}/nodes`),
    ])
    if (!buildResp.ok || !approvalsResp.ok || !nodesResp.ok) return false
    if (!approvalsResp.body || !Array.isArray(nodesResp.body)) return false
    const status = buildResp.body?.status ?? ''
    if (TERMINAL_STATUSES.has(status)) {
      throw new Error(
        `[${label}] build ${buildId} reached ${status} before parking — nodes: ` +
        JSON.stringify(nodesResp.body.map(n => ({ n: n.displayName, s: n.status }))),
      )
    }
    // Upstream prerequisites must already be SUCCESS.
    for (const name of expectedUpstreamSuccess) {
      const n = findNode(nodesResp.body, name)
      if (!n || (n.status ?? '').toUpperCase() !== 'SUCCESS') return false
    }
    // Forbidden stages must NOT be SUCCESS yet (would mean gate didn't gate).
    for (const name of forbiddenSuccess) {
      const n = findNode(nodesResp.body, name)
      if (n && (n.status ?? '').toUpperCase() === 'SUCCESS') {
        throw new Error(
          `[${label}] ${name} reached SUCCESS before the expected gate parked — auto-advance bug`,
        )
      }
    }
    const row = approvalsResp.body.items.find((a) => a.buildId === buildId) ?? null
    if (!row) return false
    found = row
    foundNodes = nodesResp.body
    return true
  }, {
    message: `[${label}] never parked within ${budgetMs}ms`,
    timeout: budgetMs,
    intervals: [1000, 2000, 3000],
  }).toBe(true)
  if (!found) throw new Error('unreachable')
  return { approval: found, nodes: foundNodes }
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
    // #65: migrated off the raw-cascade SQL teardown. safeDeleteJobCascade
    // (#59, fixtures/teardown-v3.ts) cancels any still-live build via the
    // public API, waits (bounded) for terminal status + CLAIMED/PROCESSING
    // task-lease drain, and only then deletes — never yanking a leased
    // task_queue row out from under the worker.
    await safeDeleteJobCascade(api, jobId)
  }
  if (credId && credId > 0) {
    await api.delete(`${API_BASE}/api/v1/credentials/${credId}`, { headers: { Authorization: `Bearer ${bearer}` } }).catch(() => undefined)
  }
}

test.describe('v3 multi-env-deploy @golden', () => {
  test('chained gates: park at gate-1 -> approve -> park at gate-2 -> approve -> SUCCESS', async ({ request }) => {
    test.setTimeout(5 * 60_000)
    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
    let ctx: Ctx | undefined
    try {
      ctx = await setup(request, runTag)
      const buildId = await waitForBuild(request, ctx.bearer, ctx.jobId)

      // ── Gate 1: park, with SmokeTest SUCCESS upstream and DeployStaging not yet run.
      const PARK_BUDGET = 90_000
      const park1 = await waitForPark(
        request, ctx.bearer, buildId,
        ['Build', 'SmokeTest'],
        [STAGE_DEPLOY_STAGING, STAGE_INTEGRATION, STAGE_DEPLOY_PROD],
        PARK_BUDGET,
        'gate-1',
      )
      expect(park1.approval.status).toBe('PENDING')

      // Approve gate-1.
      const a1 = await request.post(`${API_BASE}/api/v1/approvals/${park1.approval.id}/approve`, {
        headers: { Authorization: `Bearer ${ctx.bearer}`, 'Content-Type': 'application/json' },
        data: {},
      })
      expect(a1.status(), `approve-1 ${await a1.text()}`).toBe(200)
      const a1body = JSON.parse(await a1.text()) as DecisionResponse
      expect(a1body.applied).toBe(true)

      // ── Gate 2: must park again, with IntegrationTest SUCCESS upstream and DeployProd not run.
      const park2 = await waitForPark(
        request, ctx.bearer, buildId,
        [STAGE_DEPLOY_STAGING, STAGE_INTEGRATION],
        [STAGE_DEPLOY_PROD],
        PARK_BUDGET,
        'gate-2',
      )
      // Auto-advance guard: park2 must be a DIFFERENT approval row than park1.
      expect(
        park2.approval.id,
        `gate-2 approval id (${park2.approval.id}) equals gate-1 (${park1.approval.id}) — engine reused the row; smell`,
      ).not.toBe(park1.approval.id)

      // Approve gate-2.
      const a2 = await request.post(`${API_BASE}/api/v1/approvals/${park2.approval.id}/approve`, {
        headers: { Authorization: `Bearer ${ctx.bearer}`, 'Content-Type': 'application/json' },
        data: {},
      })
      expect(a2.status(), `approve-2 ${await a2.text()}`).toBe(200)

      // Drive to terminal SUCCESS.
      const final = await pollTerminal(request, ctx.bearer, buildId, 90_000)
      expect(final).toBe('SUCCESS')

      const finalNodes = (await apiGet<FlowNode[]>(request, ctx.bearer, `/api/v1/builds/${buildId}/nodes`)).body ?? []
      const prod = findNode(finalNodes, STAGE_DEPLOY_PROD)
      expect(prod, `${STAGE_DEPLOY_PROD} node missing post-approve`).not.toBeNull()
      expect(prod!.status, `${STAGE_DEPLOY_PROD} must be SUCCESS terminally (was ${prod!.status})`).toBe('SUCCESS')
    } finally {
      if (ctx) await cleanup(request, ctx.bearer, ctx.jobId, ctx.credId)
    }
  })
})

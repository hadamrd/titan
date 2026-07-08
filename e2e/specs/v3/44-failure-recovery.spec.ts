/**
 * 44-failure-recovery — retry-block + recovery (@golden).
 *
 * Drives `failure-recovery.yml` from `hadamrd/titan-e2e-fixture`. Exercises
 * the design/44 `retry:` policy: a step intentionally fails on attempts 1+2
 * and succeeds on attempt 3. The step's own exit code drives the retry
 * lifecycle; we assert the engine honours `retry: 3` and the build ends
 * SUCCESS, not FAILED.
 *
 *   Build -> FlakyTest (retry: 3) -> Report
 *   BackupReport (when: "false", SKIPPED in happy path — see below)
 *
 * ─── onFailure: not supported (yet) ──────────────────────────────────────────
 * The brief asked for a `BackupReport` stage that runs ONLY when FlakyTest
 * exceeds its retries. The engine has no `onFailure:` scope today, so the
 * fixture uses `when: "false"` as a placeholder and we mark the
 * "BackupReport runs on retry exhaustion" check `test.fixme` here. When the
 * engine ships a real `onFailure:` handler, swap the YAML's `when:` and
 * un-fixme the second test.
 *
 * Adversarial bar:
 *   - Build SUCCESS terminally.
 *   - FlakyTest SUCCESS (NOT FAILED) — retry policy actually recovered.
 *   - Report SUCCESS.
 *   - BackupReport SKIPPED / NOT_BUILT / never materialised (when: false).
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
const FIXTURE_PATH = 'failure-recovery.yml'

const STAGE_BUILD = 'Build'
const STAGE_FLAKY = 'FlakyTest'
const STAGE_REPORT = 'Report'
const STAGE_BACKUP = 'BackupReport'

const TERMINAL_STATUSES = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'UNSTABLE', 'ERROR'])
const SKIPPED_STATUSES = new Set(['SKIPPED', 'NOT_BUILT'])

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
  attempt?: number | null
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

function findNode(nodes: FlowNode[], name: string): FlowNode | null {
  return (
    nodes.find(
      (n) => (n.displayName ?? '').trim() === name && (n.nodeType ?? '').toUpperCase() === 'STAGE',
    ) ??
    nodes.find((n) => (n.displayName ?? '').trim() === name) ??
    null
  )
}

interface Ctx { bearer: string; credId: number; jobId: number }

async function setup(api: APIRequestContext, runTag: string): Promise<Ctx> {
  // Read the vendored fixture YAML (hermetic — #48).
  const yaml = readFixtureYaml(FIXTURE_PATH)
  for (const needle of [
    `stage: ${STAGE_FLAKY}`,
    'retry: 3',
    `stage: ${STAGE_BACKUP}`,
    'when: "false"',
  ]) {
    expect(yaml.includes(needle), `fixture YAML missing "${needle}"`).toBe(true)
  }

  const bearer = await fetchBearerToken(ENV)

  const credKey = `e2e-recover-${runTag}`
  const secret = `s3cr3t-${runTag}`
  const credResp = await api.post(`${API_BASE}/api/v1/credentials`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: { kind: 'STRING', scope: 'github-webhook', key: credKey, plaintext: secret },
  })
  expect(credResp.status(), `cred create ${await credResp.text()}`).toBe(201)
  const credId = (JSON.parse(await credResp.text()) as CredentialCreateResp).id

  const fullName = `e2e-recover-${runTag}`
  const triggersConfig = {
    triggers: [
      { type: 'github', id: 'github-1', branches: [FIXTURE_BRANCH], events: ['push'], credentialsId: credKey },
    ],
  }
  const jobResp = await api.post(`${API_BASE}/api/v1/jobs`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: {
      fullName,
      displayName: 'E2E failure-recovery',
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
    head_commit: { id: 'f'.repeat(40), message: 'recovery synthetic push' },
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

test.describe('v3 failure-recovery @golden', () => {
  test('retry: 3 — FlakyTest fails twice, succeeds on attempt 3, build SUCCESS', async ({ request }) => {
    test.setTimeout(4 * 60_000)
    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
    let ctx: Ctx | undefined
    try {
      ctx = await setup(request, runTag)
      const buildId = await waitForBuild(request, ctx.bearer, ctx.jobId)

      const final = await pollTerminal(request, ctx.bearer, buildId, 3 * 60_000)
      expect(
        final,
        `build must end SUCCESS — engine retry: 3 should recover a 2-fail-then-pass step `
        + `(final=${final}). If FAILED here, the retry policy was not honoured.`,
      ).toBe('SUCCESS')

      const finalNodes = (await apiGet<FlowNode[]>(request, ctx.bearer, `/api/v1/builds/${buildId}/nodes`)).body ?? []
      expect(finalNodes.length).toBeGreaterThan(0)

      const build = findNode(finalNodes, STAGE_BUILD)
      expect(build, `${STAGE_BUILD} missing`).not.toBeNull()
      expect(build!.status, `${STAGE_BUILD} must be SUCCESS (was ${build!.status})`).toBe('SUCCESS')

      const flaky = findNode(finalNodes, STAGE_FLAKY)
      expect(flaky, `${STAGE_FLAKY} missing`).not.toBeNull()
      expect(
        flaky!.status,
        `${STAGE_FLAKY} must be SUCCESS (was ${flaky!.status}) — retry policy did not recover`,
      ).toBe('SUCCESS')

      // Forensic assertion (issue #954): distinguish engine retry bug from
      // fixture bug. The fixture fails on attempts 1 + 2 and succeeds on
      // attempt 3, so the engine MUST have driven a STEP to attempt 3. There
      // is only one retry'd step in this pipeline (FlakyTest's `sh`), so we
      // assert the max attempt observed across STEP nodes is exactly 3. If the
      // engine ever stops surfacing `attempt` on the API, this guard short-
      // circuits to a soft skip rather than masking a real engine regression.
      const stepAttempts = finalNodes
        .filter((n) => (n.nodeType ?? '').toUpperCase() === 'STEP' && typeof n.attempt === 'number')
        .map((n) => n.attempt as number)
      if (stepAttempts.length > 0) {
        const maxAttempt = Math.max(...stepAttempts)
        expect(
          maxAttempt,
          `engine should have retried FlakyTest's step up to 3 (max STEP attempt=${maxAttempt}). `
          + `If 1, the fixture's retry counter is broken (issue #954). `
          + `If 2, the third retry never fired.`,
        ).toBe(3)
      }

      const report = findNode(finalNodes, STAGE_REPORT)
      expect(report, `${STAGE_REPORT} missing`).not.toBeNull()
      expect(
        report!.status,
        `${STAGE_REPORT} must be SUCCESS (was ${report!.status}) — downstream of recovered FlakyTest`,
      ).toBe('SUCCESS')

      // BackupReport (when: "false") MUST NOT be SUCCESS. Engines may
      // legitimately leave it materialised as SKIPPED/NOT_BUILT or not
      // materialise it at all. The bar is: status MUST NOT be SUCCESS.
      const backup = findNode(finalNodes, STAGE_BACKUP)
      if (backup !== null) {
        const s = (backup.status ?? '').toUpperCase()
        expect(
          s === '' || SKIPPED_STATUSES.has(s),
          `${STAGE_BACKUP} must be SKIPPED/NOT_BUILT (when: "false") but was ${backup.status}`,
        ).toBe(true)
      }
    } finally {
      if (ctx) await cleanup(request, ctx.bearer, ctx.jobId, ctx.credId)
    }
  })

  // Reserved for the day the engine ships a real `onFailure:` scope.
  // Today the engine has no failure-recovery handler — the fixture's
  // BackupReport uses `when: "false"` as a placeholder, so this assertion
  // would never observe a "run on retry exhaustion" event.
  // See PR body of dashboard-plugin#<this PR> for the engine follow-up.
  test.fixme(
    'BackupReport runs ONLY when FlakyTest exhausts retries (requires engine onFailure: scope)',
    async () => {
      // Intentionally empty — engine feature not yet shipped.
    },
  )
})

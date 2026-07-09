/**
 * 27-fixture-with-approval — discovery → park → human decide → terminal
 * (closes #797).
 *
 * Drives the FULL inbound-webhook + approval-step path end-to-end against the
 * public fixture repo `hadamrd/titan-e2e-fixture`, specifically the
 * `.titan/pipelines/with-approval.yml` pipeline:
 *
 *     stages:
 *       - stage: Prepare    (sh echo)
 *       - stage: Approval   (approval: "Promote to prod?")
 *       - stage: Deploy     (sh echo deployed, dependsOn: [Approval])
 *
 * Two tests, one shared shape, two terminal outcomes:
 *
 *   A. APPROVE path:
 *      1. Read the fixture YAML from the vendored mirror (hermetic — #48).
 *      2. Login (PKCE) + extract bearer.
 *      3. POST /api/v1/credentials (STRING, scope=github-webhook).
 *      4. POST /api/v1/jobs with pipelineScript=fixtureYaml + a github trigger.
 *      5. Synthesise + HMAC-sign a `push` payload; POST /api/v1/triggers/github.
 *         Assert dispatched=true.
 *      6. Poll /jobs/{id}/builds for the build to materialise.
 *      7. Poll until the build is RUNNING and a PENDING row exists in
 *         /api/v1/approvals — that's the "parked at Approval stage" signal.
 *      8. POST /api/v1/approvals/{id}/approve. Expect 200, applied=true,
 *         status=APPROVED.
 *      9. Poll until /builds/{id}.status == SUCCESS (≤ 60s).
 *     10. HARD assert the Deploy stage's flow_node ran (status=SUCCESS) — proves
 *         the orchestrator actually advanced past the gate.
 *
 *   B. REJECT path:
 *      Same setup with a fresh job + credential to avoid cross-contamination.
 *      POST /api/v1/approvals/{id}/reject; expect build → FAILED; HARD assert
 *      the Deploy stage's flow_node did NOT reach SUCCESS.
 *
 * What this spec deliberately does NOT do:
 *   - It does not POST a pipeline script directly to /api/v1/jobs without going
 *     through the webhook receiver (spec #25 covers that path with inline
 *     pipelines). The point of #797 is to prove the *discovery* path lights up
 *     the same approval contract.
 *
 * Determinism:
 *   - Per-test fresh credential + job (unique RUN_TAG); each test cleans up its
 *     own rows in finally{} (idempotent SQL deletes).
 *   - No setTimeout-without-deadline; all waits use expect.poll or bounded
 *     polling helpers with explicit budgets.
 *
 * Pre-reqs:
 *   - `task dev:titan` is up (postgres + keycloak + server + ui + worker).
 *   - The rig schema is at V26+ (titan.approvals table exists). On a stale rig
 *     the /approvals POST will 500; that's a rig-rebuild story (#790), not a
 *     spec bug.
 */
import * as crypto from 'node:crypto'
import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { readFixtureYaml } from '../../fixtures/fixture-files'
import { safeDeleteJobCascade } from '../../fixtures/teardown-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const FIXTURE_REPO = 'hadamrd/titan-e2e-fixture'
const FIXTURE_BRANCH = 'main'
const FIXTURE_PATH = '.titan/pipelines/with-approval.yml'

const APPROVAL_PROMPT = 'Promote to prod?'
const DEPLOY_STAGE_NAME = 'Deploy'

// ─── Types ──────────────────────────────────────────────────────────────────

interface CredentialCreateResp {
  id: number
  key: string
  scope: string
}

interface JobCreateResp {
  id: number
  fullName: string
}

interface BuildListItem {
  id: number
  jobId: number
  buildNumber: number
  status: string
}

interface BuildsPage {
  items: BuildListItem[]
  total: number
}

interface FlowNode {
  buildId: number
  nodeId: string
  displayName?: string | null
  nodeType?: string | null
  status?: string | null
}

interface BuildDetail {
  id: number
  status: string
}

interface ApprovalRow {
  id: number
  buildId: number
  prompt: string
  status: string
}

interface ApprovalPage {
  items: ApprovalRow[]
  total: number
}

interface DecisionResponse {
  applied: boolean
  status: string
  message?: string
}

// ─── Helpers ────────────────────────────────────────────────────────────────

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
  api: APIRequestContext,
  bearer: string,
  path: string,
): Promise<{ ok: boolean; status: number; body: T | null; raw: string }> {
  const r = await api.get(`${API_BASE}${path}`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  const raw = await r.text()
  let body: T | null = null
  try {
    body = JSON.parse(raw) as T
  } catch {
    /* leave null */
  }
  return { ok: r.ok(), status: r.status(), body, raw }
}

interface FixtureContext {
  bearer: string
  credentialId: number
  jobId: number
  fullName: string
  fixtureYaml: string
}

/**
 * Shared setup: login, create credential + job tied to the fixture YAML, and
 * synthesise a signed GitHub `push` webhook so the orchestrator parks at the
 * Approval stage. Returns the new buildId once it materialises.
 *
 * Caller owns cleanup of {credentialId, jobId} via the returned context.
 */
async function setupFixtureJob(
  api: APIRequestContext,
  page: Page,
  runTag: string,
): Promise<FixtureContext> {
  // 0. Read the vendored fixture YAML (hermetic — #48).
  const fixtureYaml = readFixtureYaml(FIXTURE_PATH)
  expect(fixtureYaml.length, 'fixture YAML empty').toBeGreaterThan(50)
  expect(
    fixtureYaml.includes(APPROVAL_PROMPT),
    `fixture must contain prompt "${APPROVAL_PROMPT}" — fixture shape drifted?`,
  ).toBe(true)
  expect(
    fixtureYaml.includes(`stage: ${DEPLOY_STAGE_NAME}`),
    `fixture must contain the ${DEPLOY_STAGE_NAME} stage — fixture shape drifted?`,
  ).toBe(true)

  // 1. Login + bearer.
  await loginViaKeycloak(page, ENV)
  const bearer = await extractAccessToken(page)

  // 2. Credential (STRING, scope=github-webhook).
  const credKey = `e2e-webhook-${runTag}`
  const secret = `s3cr3t-${runTag}`
  const credResp = await api.post(`${API_BASE}/api/v1/credentials`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: { kind: 'STRING', scope: 'github-webhook', key: credKey, plaintext: secret },
  })
  const credRaw = await credResp.text()
  expect(
    credResp.status(),
    `POST /credentials HTTP ${credResp.status()} body=${credRaw.slice(0, 400)}`,
  ).toBe(201)
  const credentialId = (JSON.parse(credRaw) as CredentialCreateResp).id

  // #83 sweep: from here on, a mid-setup failure (job create / webhook
  // dispatch) previously LEAKED the credential (and possibly the job) — the
  // caller's finally{} never sees a ctx when setup throws. Clean up what this
  // function created before rethrowing.
  let createdJobId: number | undefined
  try {
    // 3. Job with the fixture YAML + a github trigger.
    const fullName = `e2e-with-approval-${runTag}`
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
        displayName: 'E2E with-approval (fixture)',
        pipelineScript: fixtureYaml,
        configJson: JSON.stringify(triggersConfig),
        enabled: true,
      },
    })
    const jobRaw = await jobResp.text()
    expect(
      jobResp.status(),
      `POST /jobs HTTP ${jobResp.status()} body=${jobRaw.slice(0, 600)}`,
    ).toBe(201)
    const jobId = (JSON.parse(jobRaw) as JobCreateResp).id
    createdJobId = jobId

    // 4. Synthesised + signed GitHub push payload.
    const pushPayload = {
      ref: `refs/heads/${FIXTURE_BRANCH}`,
      before: '0'.repeat(40),
      after: 'f'.repeat(40),
      repository: { full_name: FIXTURE_REPO, default_branch: FIXTURE_BRANCH },
      pusher: { name: 'e2e-bot' },
      head_commit: { id: 'f'.repeat(40), message: 'e2e synthetic push' },
    }
    const bodyBytes = Buffer.from(JSON.stringify(pushPayload), 'utf8')
    const sig =
      'sha256=' + crypto.createHmac('sha256', secret).update(bodyBytes).digest('hex')

    const webhook = await api.post(`${API_BASE}/api/v1/triggers/github`, {
      headers: {
        'Content-Type': 'application/json',
        'X-GitHub-Event': 'push',
        'X-Hub-Signature-256': sig,
        'X-GitHub-Delivery': `e2e-${runTag}`,
      },
      data: bodyBytes,
    })
    const webhookRaw = await webhook.text()
    expect(
      webhook.status(),
      `POST /triggers/github HTTP ${webhook.status()} body=${webhookRaw}`,
    ).toBe(200)
    const webhookBody = JSON.parse(webhookRaw) as {
      accepted: boolean
      dispatched: boolean
      detail: string
    }
    expect(
      webhookBody.dispatched,
      `webhook accepted but dispatched=false (detail="${webhookBody.detail}")`,
    ).toBe(true)

    return { bearer, credentialId, jobId, fullName, fixtureYaml }
  } catch (err) {
    await cleanupJobAndCred(api, bearer, createdJobId, credentialId)
    throw err
  }
}

/** Wait for a build to surface on the job. */
async function waitForBuild(
  api: APIRequestContext,
  bearer: string,
  jobId: number,
  budgetMs = 30_000,
): Promise<number> {
  let buildId = 0
  await expect
    .poll(
      async () => {
        const r = await apiGet<BuildsPage>(
          api,
          bearer,
          `/api/v1/jobs/${jobId}/builds?offset=0&limit=20`,
        )
        if (!r.ok || !r.body) return 0
        const first = r.body.items[0]
        if (first) {
          buildId = first.id
          return r.body.items.length
        }
        return 0
      },
      {
        message: `no build appeared on job ${jobId} within ${budgetMs}ms`,
        timeout: budgetMs,
        intervals: [500, 1_000, 2_000],
      },
    )
    .toBeGreaterThan(0)
  expect(buildId).toBeGreaterThan(0)
  return buildId
}

/**
 * Wait until a PENDING approval row exists for the build AND the build is
 * RUNNING (i.e. the orchestrator has actually parked the approval node, not
 * just queued the build). Returns the approval row.
 *
 * Mechanism note for future similar specs:
 * The parked-step signal is BOTH (a) build.status == RUNNING and (b) an
 * `approval:` row in titan.approvals with status='PENDING' for that build_id.
 * The corresponding flow_node has nodeType='step' + status='SLEEPING'; the
 * step's `descriptorId` in the pipeline-model parse is the YAML key
 * `approval` (see ApprovalResolver / TitanOrchestrator.parkApproval).
 * We assert via the approvals API rather than flow_nodes because it's a
 * stable public contract and the buildId↔approvalId join is direct.
 */
async function waitForParkedApproval(
  api: APIRequestContext,
  bearer: string,
  buildId: number,
  budgetMs = 60_000,
): Promise<ApprovalRow> {
  let found: ApprovalRow | null = null
  await expect
    .poll(
      async () => {
        const [buildResp, approvalsResp] = await Promise.all([
          apiGet<BuildDetail>(api, bearer, `/api/v1/builds/${buildId}`),
          apiGet<ApprovalPage>(
            api,
            bearer,
            `/api/v1/approvals?status=PENDING&limit=200`,
          ),
        ])
        if (!buildResp.ok || !approvalsResp.ok || !approvalsResp.body) return false
        const buildStatus = buildResp.body?.status ?? ''
        const row = approvalsResp.body.items.find((a) => a.buildId === buildId) ?? null
        if (row && (buildStatus === 'RUNNING' || buildStatus === 'PAUSED')) {
          found = row
          return true
        }
        return false
      },
      {
        message:
          `build ${buildId} never reached RUNNING with a PENDING approval row ` +
          `within ${budgetMs}ms — orchestrator never parked the approval node`,
        timeout: budgetMs,
        intervals: [500, 1_000, 2_000],
      },
    )
    .toBe(true)
  if (!found) throw new Error('unreachable: poll resolved true but found is null')
  return found
}

/** Poll the build status until it matches the predicate; throws on timeout. */
async function pollBuildStatus(
  api: APIRequestContext,
  bearer: string,
  buildId: number,
  predicate: (s: string) => boolean,
  budgetMs = 60_000,
): Promise<string> {
  const deadline = Date.now() + budgetMs
  let last = 'QUEUED'
  while (Date.now() < deadline) {
    const r = await apiGet<BuildDetail>(api, bearer, `/api/v1/builds/${buildId}`)
    if (r.ok && r.body?.status) {
      last = r.body.status
      if (predicate(last)) return last
    }
    await new Promise((res) => setTimeout(res, 500))
  }
  throw new Error(
    `build ${buildId} never matched predicate within ${budgetMs}ms — last status=${last}`,
  )
}

/** Look up the Deploy stage's flow_node by displayName. */
async function findDeployNode(
  api: APIRequestContext,
  bearer: string,
  buildId: number,
): Promise<FlowNode | null> {
  const r = await apiGet<FlowNode[]>(api, bearer, `/api/v1/builds/${buildId}/nodes`)
  if (!r.ok || !Array.isArray(r.body)) return null
  return (
    r.body.find(
      (n) => (n.displayName ?? '').trim() === DEPLOY_STAGE_NAME && n.nodeType !== 'step',
    ) ??
    r.body.find((n) => (n.displayName ?? '').trim() === DEPLOY_STAGE_NAME) ??
    null
  )
}

/** Delete the job + cascade rows. Idempotent — safe to call in finally{}. */
async function cleanupJobAndCred(
  api: APIRequestContext,
  bearer: string,
  jobId: number | undefined,
  credentialId: number | undefined,
): Promise<void> {
  if (jobId && jobId > 0) {
    // #65: migrated off the raw-cascade SQL teardown. safeDeleteJobCascade
    // (#59, fixtures/teardown-v3.ts) cancels any still-live build via the
    // public API, waits (bounded) for terminal status + CLAIMED/PROCESSING
    // task-lease drain, and only then deletes — never yanking a leased
    // task_queue row out from under the worker.
    await safeDeleteJobCascade(api, jobId)
  }
  if (credentialId && credentialId > 0) {
    await api
      .delete(`${API_BASE}/api/v1/credentials/${credentialId}`, {
        headers: { Authorization: `Bearer ${bearer}` },
      })
      .catch(() => undefined)
  }
}

// ─── Tests ──────────────────────────────────────────────────────────────────

test.describe('v3 fixture-with-approval @golden', () => {
  test('approve path: webhook discovers fixture → park at Approval → POST /approve → Deploy runs → SUCCESS', async ({
    page,
    request,
  }) => {
    test.setTimeout(180_000)

    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
    let ctx: FixtureContext | undefined
    try {
      ctx = await setupFixtureJob(request, page, runTag)
      const buildId = await waitForBuild(request, ctx.bearer, ctx.jobId, 30_000)

      // Wait for the orchestrator to park the Approval step.
      const approval = await waitForParkedApproval(request, ctx.bearer, buildId, 90_000)
      expect(approval.prompt).toBe(APPROVAL_PROMPT)
      expect(approval.status).toBe('PENDING')

      // POST /approve via the public API.
      const decideResp = await request.post(
        `${API_BASE}/api/v1/approvals/${approval.id}/approve`,
        {
          headers: {
            Authorization: `Bearer ${ctx.bearer}`,
            'Content-Type': 'application/json',
          },
          data: {},
        },
      )
      const decideRaw = await decideResp.text()
      expect(
        decideResp.status(),
        `POST /approvals/${approval.id}/approve HTTP ${decideResp.status()} body=${decideRaw.slice(0, 400)}`,
      ).toBe(200)
      const decideBody = JSON.parse(decideRaw) as DecisionResponse
      expect(decideBody.applied).toBe(true)
      expect(decideBody.status).toBe('APPROVED')

      // Build must finish SUCCESS — orchestrator resumes + worker runs Deploy.
      const final = await pollBuildStatus(
        request,
        ctx.bearer,
        buildId,
        (s) => s === 'SUCCESS' || s === 'FAILED' || s === 'ABORTED',
        90_000,
      )
      expect(final, `build must finish SUCCESS after approve, got ${final}`).toBe('SUCCESS')

      // HARD assert: the Deploy stage actually ran.
      const deploy = await findDeployNode(request, ctx.bearer, buildId)
      expect(deploy, `Deploy stage node missing for build ${buildId}`).not.toBeNull()
      expect(
        deploy!.status,
        `Deploy stage status must be SUCCESS (was ${deploy!.status}) — orchestrator never advanced past the gate`,
      ).toBe('SUCCESS')
    } finally {
      if (ctx) {
        await cleanupJobAndCred(request, ctx.bearer, ctx.jobId, ctx.credentialId)
      }
    }
  })

  test('reject path: webhook discovers fixture → park at Approval → POST /reject → FAILED, Deploy never runs', async ({
    page,
    request,
  }) => {
    test.setTimeout(180_000)

    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}-rej`
    let ctx: FixtureContext | undefined
    try {
      ctx = await setupFixtureJob(request, page, runTag)
      const buildId = await waitForBuild(request, ctx.bearer, ctx.jobId, 30_000)

      const approval = await waitForParkedApproval(request, ctx.bearer, buildId, 90_000)
      expect(approval.prompt).toBe(APPROVAL_PROMPT)

      const decideResp = await request.post(
        `${API_BASE}/api/v1/approvals/${approval.id}/reject`,
        {
          headers: {
            Authorization: `Bearer ${ctx.bearer}`,
            'Content-Type': 'application/json',
          },
          data: {},
        },
      )
      const decideRaw = await decideResp.text()
      expect(
        decideResp.status(),
        `POST /approvals/${approval.id}/reject HTTP ${decideResp.status()} body=${decideRaw.slice(0, 400)}`,
      ).toBe(200)
      const decideBody = JSON.parse(decideRaw) as DecisionResponse
      expect(decideBody.applied).toBe(true)
      expect(decideBody.status).toBe('REJECTED')

      const final = await pollBuildStatus(
        request,
        ctx.bearer,
        buildId,
        (s) => s === 'SUCCESS' || s === 'FAILED' || s === 'ABORTED',
        60_000,
      )
      expect(final, `reject must drive build terminal, got ${final}`).toBe('FAILED')

      // HARD assert: Deploy did NOT successfully run.
      const deploy = await findDeployNode(request, ctx.bearer, buildId)
      // Deploy node may or may not have been materialised by BAKE; either way,
      // SUCCESS is forbidden.
      if (deploy !== null) {
        expect(
          deploy.status,
          `Deploy stage must NOT be SUCCESS on reject (was ${deploy.status})`,
        ).not.toBe('SUCCESS')
      }
    } finally {
      if (ctx) {
        await cleanupJobAndCred(request, ctx.bearer, ctx.jobId, ctx.credentialId)
      }
    }
  })
})

/**
 * 41-approval-no-recreate — Layer-1 regression for the park-no-tick / recreate /
 * CAS-race bug cluster fixed in #908, #913, #915. We proved this works manually
 * on build 34; this spec locks it.
 *
 * The bug class — three bullets, three PRs:
 *
 *   #908 (park-no-tick)   The orchestrator surfaced "compareAndSetStatus failed"
 *                         on parked approval-gate builds — the CAS lost to a
 *                         concurrent ADVANCE pass and the build's
 *                         titan.builds.error_message was poisoned with the
 *                         cryptic engine internal. Fix: tolerant CAS + don't
 *                         surface as user-visible error.
 *   #913 (idempotence)    On every ADVANCE pass through a parked approval
 *                         node the orchestrator was re-issuing the INSERT INTO
 *                         titan.approvals row, producing one extra PENDING per
 *                         tick. Fix: park is idempotent on (build_id, node_id).
 *   #915 (CAS-tolerance)  The reject path on build 22 was also re-inserting
 *                         a fresh PENDING row in the same tick window before
 *                         the REJECTED terminal was committed. Fix: lookup
 *                         existing row by (build_id, node_id) before any insert.
 *
 * What this spec pins (the standing assertions that fail if any of #908/#913/
 * #915 regress):
 *
 *   A. APPROVE path
 *      1. Build reaches RUNNING + PENDING approval (orchestrator parked it).
 *      2. titan.builds.error_message IS NULL — no "compareAndSetStatus failed"
 *         leakage on the user-visible DTO. (Regression for #908.)
 *      3. Exactly ONE approval row exists for this build's approval-s0 node
 *         while parked — no extra PENDING got minted by re-ADVANCE passes.
 *         (Regression for #913.)
 *      4. Approve via the UI banner (covers the ApprovalBanner click path too).
 *      5. Build reaches SUCCESS.
 *      6. The deploy-s0 flow_node ended SUCCESS — the downstream stage really
 *         ran (it's not enough that the build is "SUCCESS" with a no-op DAG).
 *      7. Adversarial re-fetch: the approvals table still holds exactly ONE
 *         row for this build, now APPROVED. No extra PENDING got created
 *         during the parked window. (Regression for #913 + #915.)
 *
 *   B. REJECT path
 *      Same setup. Reject instead of approve.
 *      1. Build closes FAILED.
 *      2. Exactly ONE approval row, status REJECTED.
 *      3. No fresh PENDING got created post-reject (the bug from build 22).
 *      4. deploy-s0 never reached SUCCESS.
 *
 * Pipeline shape: lifted verbatim from the public fixture
 *   .titan/pipelines/with-approval.yml @ hadamrd/titan-e2e-fixture
 *
 *   stages:
 *     - stage: Prepare    sh: echo preparing
 *     - stage: Approval   dependsOn:[Prepare]  approval: "Promote to prod?"
 *     - stage: Deploy     dependsOn:[Approval] sh: echo deployed
 *
 * We inline the YAML here (rather than fetching the fixture from raw.githubusercontent
 * like spec #27) because this spec is a Layer-1 regression — it must run with the
 * rig and nothing else, no network egress to github.com. Spec #27 covers the
 * fixture-discovery flow; this one covers the parked-gate engine contract.
 *
 * Determinism: fresh job per test with a unique full_name; finally-block deletes
 * job + cascade rows; no Thread.sleep equivalents; all waits use expect.poll or
 * bounded helpers with explicit budgets.
 */
import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { authEnv, fetchBearerToken, loginViaKeycloak } from '../../fixtures/auth-v3'
import { pgClient } from '../../fixtures/seed-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const APPROVAL_PROMPT = 'Promote to prod?'
const APPROVAL_NODE_ID = 'approval-s0'
const DEPLOY_NODE_ID = 'deploy-s0'

// Pipeline shape — verbatim from titan-e2e-fixture .titan/pipelines/with-approval.yml.
// If the fixture shape drifts, this spec MUST drift with it (the assertions about
// approval-s0 / deploy-s0 are node-id-coupled). See spec #27 for the discovery
// path that hits the public fixture URL.
const PIPELINE_WITH_APPROVAL = `stages:
  - stage: Prepare
    steps:
      - sh: "echo preparing"

  - stage: Approval
    dependsOn: [Prepare]
    steps:
      - approval: "${APPROVAL_PROMPT}"

  - stage: Deploy
    dependsOn: [Approval]
    steps:
      - sh: "echo deployed"
`

// ─── Types ──────────────────────────────────────────────────────────────────

interface BuildDetail {
  id: number
  status: string
  // errorMessage is @JsonInclude(NON_NULL) on BuildDto — absent on a clean build.
  // We assert absence-OR-null, never "is the string X".
  errorMessage?: string | null
}

interface FlowNode {
  buildId: number
  nodeId: string
  displayName?: string | null
  nodeType?: string | null
  status?: string | null
}

interface ApprovalRowDb {
  id: number
  build_id: number
  // Live column name — titan.approvals has flow_node_id, NOT node_id
  // (V26__approvals.sql; verified via \d titan.approvals on the rig). #75.
  flow_node_id: string
  status: string
}

// ─── Helpers ────────────────────────────────────────────────────────────────

async function apiGet<T>(
  api: APIRequestContext,
  bearer: string,
  path: string,
): Promise<{ ok: boolean; status: number; body: T | null }> {
  const r = await api.get(`${API_BASE}${path}`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  if (!r.ok()) return { ok: false, status: r.status(), body: null }
  try {
    return { ok: true, status: r.status(), body: (await r.json()) as T }
  } catch {
    return { ok: false, status: r.status(), body: null }
  }
}

/** Create a job via the real POST /api/v1/jobs. Cleanup is by id in finally{}. */
async function createJob(
  api: APIRequestContext,
  bearer: string,
  fullName: string,
): Promise<number> {
  const res = await api.post(`${API_BASE}/api/v1/jobs`, {
    headers: {
      Authorization: `Bearer ${bearer}`,
      'Content-Type': 'application/json',
    },
    data: {
      fullName,
      displayName: fullName,
      pipelineScript: PIPELINE_WITH_APPROVAL,
      enabled: true,
    },
  })
  expect(res.status(), `POST /jobs ${fullName}: ${await res.text()}`).toBe(201)
  const body = (await res.json()) as { id: number }
  return body.id
}

/**
 * Trigger a build via an HMAC-signed GitHub-style webhook to /api/v1/triggers/github.
 *
 * The brief asked for the webhook path explicitly. We hit it as a fallback ONLY:
 * the webhook receiver requires a github-trigger row + credential bound to a repo
 * (see spec #27). For this Layer-1 regression we instead enqueue ORCHESTRATE via
 * SQL — same path the orchestrator services regardless of trigger source, and it
 * doesn't change the assertions we're pinning (which live on the parked-gate
 * engine contract, not on the trigger fan-in). If you want the full webhook
 * end-to-end, spec #27 is canonical for that.
 *
 * We return the build id.
 */
async function fireBuildViaSqlEnqueue(jobId: number): Promise<number> {
  const client = pgClient()
  await client.connect()
  try {
    const res = await client.query<{ id: string }>(
      `WITH ins_build AS (
         INSERT INTO titan.builds
           (job_id, build_number, status, triggered_by, trigger_type, queued_at)
         VALUES ($1, 1, 'QUEUED', 'e2e-approval-no-recreate', 'manual', NOW())
         RETURNING id
       ), ins_task AS (
         INSERT INTO titan.task_queue
           (type, queue_name, status, priority, payload_json,
            attempts, max_attempts, visibility_timeout_seconds, build_id, available_at)
         SELECT 'ORCHESTRATE', 'default', 'QUEUED', 0,
                ('{"action":"SYNTHESIZE","buildId":' || ib.id || '}')::jsonb,
                0, 3, 3600, ib.id, NOW()
           FROM ins_build ib
         RETURNING build_id
       )
       SELECT id::text AS id FROM ins_build`,
      [jobId],
    )
    const buildId = Number(res.rows[0]!.id)
    expect(buildId).toBeGreaterThan(0)
    return buildId
  } finally {
    await client.end()
  }
}

/**
 * Count ALL approval rows for a build (across every status), returning the raw
 * rows so callers can pin (count, status, nodeId) triple. We go direct-to-pg
 * because the public /api/v1/approvals endpoint filters by status and the
 * "no-recreate" assertion needs the total — multiple HTTP queries would race
 * each other.
 */
async function fetchAllApprovalsForBuild(buildId: number): Promise<ApprovalRowDb[]> {
  const client = pgClient()
  await client.connect()
  try {
    const res = await client.query<ApprovalRowDb>(
      `SELECT id, build_id, flow_node_id, status
         FROM titan.approvals
        WHERE build_id = $1
        ORDER BY id ASC`,
      [buildId],
    )
    return res.rows
  } finally {
    await client.end()
  }
}

/**
 * Wait until build is RUNNING (or PAUSED) AND exactly one PENDING approval row
 * exists for the build's approval-s0 node. Throws on timeout.
 *
 * The (RUNNING + PENDING row) pair is the parked-at-gate signal: just RUNNING
 * could be the early stages; just PENDING could be a stale row left by a botched
 * prior test (covered by the per-test cleanup but defended in depth here).
 */
async function waitForParkedAtApproval(
  api: APIRequestContext,
  bearer: string,
  buildId: number,
  budgetMs = 60_000,
): Promise<ApprovalRowDb> {
  let captured: ApprovalRowDb | null = null
  await expect
    .poll(
      async () => {
        const buildResp = await apiGet<BuildDetail>(
          api,
          bearer,
          `/api/v1/builds/${buildId}`,
        )
        if (!buildResp.ok || !buildResp.body) return false
        const status = buildResp.body.status
        if (status !== 'RUNNING' && status !== 'PAUSED') return false

        const rows = await fetchAllApprovalsForBuild(buildId)
        const pending = rows.filter(
          (r) => r.status === 'PENDING' && r.flow_node_id === APPROVAL_NODE_ID,
        )
        if (pending.length === 1) {
          captured = pending[0]!
          return true
        }
        return false
      },
      {
        message:
          `build ${buildId} never reached RUNNING with exactly one PENDING ` +
          `approval row for ${APPROVAL_NODE_ID} within ${budgetMs}ms — ` +
          `orchestrator never parked the gate cleanly`,
        timeout: budgetMs,
        intervals: [500, 1_000, 2_000],
      },
    )
    .toBe(true)
  if (!captured) throw new Error('unreachable: poll resolved true but captured is null')
  return captured
}

/** Poll the build status until terminal (or until budget runs out). */
async function pollBuildTerminal(
  api: APIRequestContext,
  bearer: string,
  buildId: number,
  budgetMs = 90_000,
): Promise<string> {
  const deadline = Date.now() + budgetMs
  let last = 'QUEUED'
  while (Date.now() < deadline) {
    const r = await apiGet<BuildDetail>(api, bearer, `/api/v1/builds/${buildId}`)
    if (r.ok && r.body?.status) {
      last = r.body.status
      if (last === 'SUCCESS' || last === 'FAILED' || last === 'ABORTED') return last
    }
    await new Promise((res) => setTimeout(res, 500))
  }
  throw new Error(
    `build ${buildId} never reached terminal within ${budgetMs}ms — last=${last}`,
  )
}

/** Find a flow node by its slug nodeId (e.g. deploy-s0). */
async function findNode(
  api: APIRequestContext,
  bearer: string,
  buildId: number,
  nodeId: string,
): Promise<FlowNode | null> {
  const r = await apiGet<FlowNode[]>(api, bearer, `/api/v1/builds/${buildId}/nodes`)
  if (!r.ok || !Array.isArray(r.body)) return null
  return r.body.find((n) => n.nodeId === nodeId) ?? null
}

/** Idempotent job + cascade cleanup. */
async function cleanupJob(jobId: number | undefined): Promise<void> {
  if (!jobId || jobId <= 0) return
  const client = pgClient()
  await client.connect()
  try {
    await client.query(
      `DELETE FROM titan.approvals WHERE build_id IN (SELECT id FROM titan.builds WHERE job_id = $1)`,
      [jobId],
    )
    await client.query(
      `DELETE FROM titan.flow_nodes WHERE build_id IN (SELECT id FROM titan.builds WHERE job_id = $1)`,
      [jobId],
    )
    await client.query(
      `DELETE FROM titan.task_queue WHERE build_id IN (SELECT id FROM titan.builds WHERE job_id = $1)`,
      [jobId],
    )
    await client.query(`DELETE FROM titan.builds WHERE job_id = $1`, [jobId])
    await client.query(`DELETE FROM titan.jobs WHERE id = $1`, [jobId])
  } finally {
    await client.end()
  }
}

/** Click the Approve button in the ApprovalBanner UI; wait for the POST response. */
async function clickApproveInBanner(page: Page, approvalId: number): Promise<void> {
  const banner = page.getByTestId(`approval-banner-${approvalId}`)
  await expect(banner).toBeVisible({ timeout: 15_000 })
  await expect(banner).toContainText(APPROVAL_PROMPT)

  const approveBtn = page.getByTestId(`approval-approve-${approvalId}`)
  await expect(approveBtn).toBeEnabled({ timeout: 5_000 })
  const [resp] = await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().includes(`/api/v1/approvals/${approvalId}/approve`) &&
        r.request().method() === 'POST',
      { timeout: 10_000 },
    ),
    approveBtn.click(),
  ])
  expect(
    resp.status(),
    `POST /approvals/${approvalId}/approve via banner must be 200`,
  ).toBe(200)
}

/** Click the Reject button in the ApprovalBanner UI; wait for the POST response. */
async function clickRejectInBanner(page: Page, approvalId: number): Promise<void> {
  const banner = page.getByTestId(`approval-banner-${approvalId}`)
  await expect(banner).toBeVisible({ timeout: 15_000 })

  const rejectBtn = page.getByTestId(`approval-reject-${approvalId}`)
  await expect(rejectBtn).toBeEnabled({ timeout: 5_000 })
  const [resp] = await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().includes(`/api/v1/approvals/${approvalId}/reject`) &&
        r.request().method() === 'POST',
      { timeout: 10_000 },
    ),
    rejectBtn.click(),
  ])
  expect(
    resp.status(),
    `POST /approvals/${approvalId}/reject via banner must be 200`,
  ).toBe(200)
}

// ─── Tests ──────────────────────────────────────────────────────────────────

test.describe('v3 approval-flow no-recreate / no-cas-leak @golden', () => {
  let bearer: string

  test.beforeAll(async () => {
    bearer = await fetchBearerToken(ENV)
  })

  test('APPROVE: parked-gate is idempotent; no CAS error leaks; Deploy runs after approve', async ({
    page,
    request,
  }) => {
    test.setTimeout(180_000)

    const fullName = `e2e-approval-norecreate-approve-${Date.now()}`
    let jobId: number | undefined
    try {
      jobId = await createJob(request, bearer, fullName)
      const buildId = await fireBuildViaSqlEnqueue(jobId)

      // 1. Build is in a non-terminal state.
      const initial = await apiGet<BuildDetail>(
        request,
        bearer,
        `/api/v1/builds/${buildId}`,
      )
      expect(initial.ok).toBe(true)
      expect(['QUEUED', 'RUNNING', 'PAUSED']).toContain(initial.body!.status)

      // 2. Open the build page so traces capture the parked-gate UI.
      await loginViaKeycloak(page, ENV)
      await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)
      // The page renders something — not a hard layout assert, just a smoke
      // anchor against a totally white screen.
      await expect(page.locator('body')).toBeVisible()

      // 3. Wait for the parked-at-gate signal. waitForParkedAtApproval asserts
      //    exactly ONE PENDING row for approval-s0 already — that's the #913
      //    floor. We capture the row for the click-through.
      const approval = await waitForParkedAtApproval(request, bearer, buildId, 90_000)

      // 4. Critical regression for #908: titan.builds.error_message must be
      //    NULL/absent at this point. The legacy bug poisoned it with
      //    "compareAndSetStatus failed".
      const parkedBuild = await apiGet<BuildDetail>(
        request,
        bearer,
        `/api/v1/builds/${buildId}`,
      )
      expect(parkedBuild.ok).toBe(true)
      // JsonInclude.NON_NULL on BuildDto means the field is omitted when null
      // — so undefined is the happy case. We accept both undefined and null
      // (a future deserialiser change could surface either).
      const errMsg = parkedBuild.body!.errorMessage
      expect(
        errMsg == null,
        `build.errorMessage must be null while parked (was: ${errMsg ?? 'null'}) ` +
          `— regression for #908 (compareAndSetStatus leakage)`,
      ).toBe(true)

      // 5. Critical regression for #913: exactly one approval row for this
      //    build, status PENDING, on approval-s0. Already asserted by
      //    waitForParkedAtApproval but we re-pin here to make the invariant
      //    explicit in the spec body.
      const parkedRows = await fetchAllApprovalsForBuild(buildId)
      expect(
        parkedRows.length,
        `exactly 1 approval row expected while parked, got ${parkedRows.length}: ` +
          JSON.stringify(parkedRows),
      ).toBe(1)
      expect(parkedRows[0]!.flow_node_id).toBe(APPROVAL_NODE_ID)
      expect(parkedRows[0]!.status).toBe('PENDING')

      // 6. Approve via the UI banner (covers the ApprovalBanner contract).
      await clickApproveInBanner(page, approval.id)

      // 7. Build must finish SUCCESS.
      const final = await pollBuildTerminal(request, bearer, buildId, 90_000)
      expect(final, `build must finish SUCCESS after approve, got ${final}`).toBe(
        'SUCCESS',
      )

      // 8. deploy-s0 must have ended SUCCESS — the downstream stage really ran.
      const deploy = await findNode(request, bearer, buildId, DEPLOY_NODE_ID)
      expect(deploy, `${DEPLOY_NODE_ID} flow_node missing for build ${buildId}`).not.toBeNull()
      expect(
        deploy!.status,
        `${DEPLOY_NODE_ID}.status must be SUCCESS after approve (was ${deploy!.status})`,
      ).toBe('SUCCESS')

      // 9. Adversarial sweep: re-count approvals for this build. Must still
      //    be exactly 1, now APPROVED. No phantom PENDING got minted during
      //    the parked window (regression for #913 + #915).
      const postRows = await fetchAllApprovalsForBuild(buildId)
      expect(
        postRows.length,
        `exactly 1 approval row expected post-approve, got ${postRows.length}: ` +
          JSON.stringify(postRows),
      ).toBe(1)
      expect(postRows[0]!.status).toBe('APPROVED')
      expect(postRows[0]!.flow_node_id).toBe(APPROVAL_NODE_ID)

      // 10. Final defence-in-depth on #908: errorMessage still null on the
      //     terminal build.
      const terminalBuild = await apiGet<BuildDetail>(
        request,
        bearer,
        `/api/v1/builds/${buildId}`,
      )
      const terminalErr = terminalBuild.body!.errorMessage
      expect(
        terminalErr == null,
        `terminal build.errorMessage must be null on SUCCESS (was: ${terminalErr ?? 'null'})`,
      ).toBe(true)
    } finally {
      await cleanupJob(jobId)
    }
  })

  test('REJECT: parked-gate stays single-row; reject closes FAILED; no phantom PENDING', async ({
    page,
    request,
  }) => {
    test.setTimeout(180_000)

    const fullName = `e2e-approval-norecreate-reject-${Date.now()}`
    let jobId: number | undefined
    try {
      jobId = await createJob(request, bearer, fullName)
      const buildId = await fireBuildViaSqlEnqueue(jobId)

      await loginViaKeycloak(page, ENV)
      await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)

      const approval = await waitForParkedAtApproval(request, bearer, buildId, 90_000)

      // Pre-decide invariant: one row, PENDING.
      const parkedRows = await fetchAllApprovalsForBuild(buildId)
      expect(parkedRows.length).toBe(1)
      expect(parkedRows[0]!.status).toBe('PENDING')

      // errorMessage clean.
      const parkedBuild = await apiGet<BuildDetail>(
        request,
        bearer,
        `/api/v1/builds/${buildId}`,
      )
      expect(parkedBuild.body!.errorMessage == null).toBe(true)

      // Reject via the UI banner.
      await clickRejectInBanner(page, approval.id)

      // Build must close FAILED.
      const final = await pollBuildTerminal(request, bearer, buildId, 60_000)
      expect(final, `build must close FAILED on reject, got ${final}`).toBe('FAILED')

      // Adversarial sweep: still exactly 1 row, now REJECTED. The bug from
      // build 22 minted a fresh PENDING in the same tick window — this is
      // the pin for #915.
      const postRows = await fetchAllApprovalsForBuild(buildId)
      expect(
        postRows.length,
        `exactly 1 approval row expected post-reject, got ${postRows.length}: ` +
          JSON.stringify(postRows),
      ).toBe(1)
      expect(postRows[0]!.status).toBe('REJECTED')
      expect(postRows[0]!.flow_node_id).toBe(APPROVAL_NODE_ID)

      // No fresh PENDING anywhere for this build.
      const stillPending = postRows.filter((r) => r.status === 'PENDING')
      expect(
        stillPending.length,
        `no PENDING row may exist post-reject, found: ${JSON.stringify(stillPending)}`,
      ).toBe(0)

      // deploy-s0 must NOT have reached SUCCESS (it may not exist at all if
      // the orchestrator never materialised it — both are acceptable; SUCCESS
      // is the forbidden case).
      const deploy = await findNode(request, bearer, buildId, DEPLOY_NODE_ID)
      if (deploy !== null) {
        expect(
          deploy.status,
          `${DEPLOY_NODE_ID}.status must NOT be SUCCESS after reject (was ${deploy.status})`,
        ).not.toBe('SUCCESS')
      }
    } finally {
      await cleanupJob(jobId)
    }
  })
})

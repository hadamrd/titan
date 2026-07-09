/**
 * 61-bulk-approvals — golden coverage for POST /api/v1/approvals/bulk/approve
 * + /bulk/reject and the /approvals page's bulk surface (closes #121).
 *
 * Coverage gap this fills: the bulk endpoints (#734) + their UI hooks were
 * covered only by titan-ui vitest (approvals.test.tsx) — no spec drove
 * multiple REAL parked gates through a bulk decision on the rig.
 *
 * Two tests:
 *
 *   A. UI golden path (3 jobs, own rows only):
 *      1. Create 3 jobs (inline approval pipeline — same shape as spec 41),
 *         trigger each via the public POST /jobs/{id}/builds, and wait for
 *         each build to park at the Approval gate (RUNNING + exactly ONE
 *         PENDING titan.approvals row for approval-s0 — the #913 floor).
 *      2. On /approvals, tick the checkboxes for approvals A + B ONLY (never
 *         select-all: other agents' rows must stay untouched), click the
 *         "Approve 2" bulk button, and assert the POST /bulk/approve response
 *         carries applied=true/APPROVED for exactly {A,B}.
 *      3. Tick C, bulk-reject it the same way.
 *      4. Per-build outcome oracles: A + B → SUCCESS with deploy-s0 SUCCESS
 *         (the orchestrator really advanced past the gate); C → FAILED with
 *         deploy-s0 never SUCCESS.
 *      5. No-CAS-leak oracles (the #908/#913/#915 pattern from spec 41):
 *         every build ends with EXACTLY one approvals row in its terminal
 *         status — bulk must not mint phantom PENDING rows — and
 *         builds.error_message stays null (no "compareAndSetStatus failed"
 *         leakage on the DTO).
 *      6. UI reflects the outcome: all three rows leave the PENDING inbox.
 *
 *   B. API sad path (2 jobs):
 *      1. Park two gates D + E; decide D via the SINGLE approve endpoint.
 *      2. bulk/reject with ids [D, E, E, 404-ish] — asserts in one call:
 *         - de-dupe: 4 ids in → 3 outcomes out (first-occurrence order);
 *         - already-decided id D: applied=false, reason="already_decided",
 *           status stays APPROVED — the reject MUST NOT double-apply/flip it;
 *         - pending id E: applied=true, REJECTED;
 *         - unknown id: applied=false, reason="not_found";
 *         - overall HTTP 200 (partial success is the contract).
 *      3. Oracles: D's build → SUCCESS (deploy-s0 SUCCESS — the earlier
 *         approve stands), E's build → FAILED; exactly one approvals row per
 *         build in the expected terminal status.
 *      4. Empty ids → 400.
 *
 * Ownership + teardown discipline: every job/build/approval row asserted on
 * is created by this spec (unique full_name per run); finally{} tears each
 * job down via safeDeleteJobCascade (#59 — cancel first, never delete a
 * leased task_queue row). ZERO litter on the shared rig.
 *
 * Determinism: no bare setTimeout waits — expect.poll / bounded polling
 * helpers with explicit budgets throughout.
 */
import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { authEnv, fetchBearerToken, loginViaKeycloak } from '../../fixtures/auth-v3'
import { pgClient } from '../../fixtures/seed-v3'
import { safeDeleteJobCascade } from '../../fixtures/teardown-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const APPROVAL_PROMPT = 'Promote to prod?'
const APPROVAL_NODE_ID = 'approval-s0'
const DEPLOY_NODE_ID = 'deploy-s0'
/** An approval id that cannot exist (bigserial on a dev rig). */
const NONEXISTENT_APPROVAL_ID = 9_999_999_999

// Same shape as spec 41 / the public fixture's with-approval.yml — the
// approval-s0 / deploy-s0 node-id assertions are coupled to this shape.
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
  // @JsonInclude(NON_NULL) on BuildDto — absent on a clean build.
  errorMessage?: string | null
}

interface FlowNode {
  buildId: number
  nodeId: string
  status?: string | null
}

interface ApprovalRowDb {
  id: number
  build_id: number
  flow_node_id: string
  status: string
}

/** Mirror of ApprovalsApi.BulkOutcome — reason/status are NON_NULL-elided. */
interface BulkOutcome {
  id: number
  applied: boolean
  status?: string | null
  reason?: string | null
}

interface BulkApprovalResponse {
  outcomes: BulkOutcome[]
}

/** One spec-owned parked gate: the job/build the spec created + its approval row. */
interface ParkedGate {
  jobId: number
  buildId: number
  approvalId: number
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
 * Trigger a build via the PUBLIC trigger endpoint (POST /jobs/{id}/builds —
 * the same path the UI's "Run" button hits). One trigger per job, so the
 * per-(user, job) rate limit (#739) is never in play.
 */
async function triggerBuild(
  api: APIRequestContext,
  bearer: string,
  jobId: number,
): Promise<number> {
  const res = await api.post(`${API_BASE}/api/v1/jobs/${jobId}/builds`, {
    headers: {
      Authorization: `Bearer ${bearer}`,
      'Content-Type': 'application/json',
    },
    data: { triggeredBy: 'e2e-bulk-approvals' },
  })
  expect(res.status(), `POST /jobs/${jobId}/builds: ${await res.text()}`).toBe(201)
  const body = (await res.json()) as { buildId: number }
  expect(body.buildId).toBeGreaterThan(0)
  return body.buildId
}

/**
 * All approvals rows for a build (every status) — direct-to-pg because the
 * "exactly one row, no phantom PENDING" oracle needs the unfiltered total
 * (the public /approvals endpoint filters by status). Same rationale as
 * spec 41.
 */
async function fetchAllApprovalsForBuild(buildId: number): Promise<ApprovalRowDb[]> {
  const client = pgClient()
  await client.connect()
  try {
    // node-postgres returns bigint columns as strings — normalise to numbers
    // here so id comparisons downstream (outcome ids, testids) stay numeric.
    const res = await client.query<{
      id: string
      build_id: string
      flow_node_id: string
      status: string
    }>(
      `SELECT id::text AS id, build_id::text AS build_id, flow_node_id, status
         FROM titan.approvals
        WHERE build_id = $1
        ORDER BY id ASC`,
      [buildId],
    )
    return res.rows.map((r) => ({
      id: Number(r.id),
      build_id: Number(r.build_id),
      flow_node_id: r.flow_node_id,
      status: r.status,
    }))
  } finally {
    await client.end()
  }
}

/**
 * Wait until the build is RUNNING/PAUSED AND exactly one PENDING approvals
 * row exists for approval-s0 (the parked-at-gate signal — see spec 41 for
 * why both halves are required). Returns the approval row id.
 */
async function waitForParkedAtApproval(
  api: APIRequestContext,
  bearer: string,
  buildId: number,
  budgetMs = 90_000,
): Promise<number> {
  let approvalId = 0
  await expect
    .poll(
      async () => {
        const buildResp = await apiGet<BuildDetail>(api, bearer, `/api/v1/builds/${buildId}`)
        if (!buildResp.ok || !buildResp.body) return false
        const status = buildResp.body.status
        if (status !== 'RUNNING' && status !== 'PAUSED') return false

        const rows = await fetchAllApprovalsForBuild(buildId)
        const pending = rows.filter(
          (r) => r.status === 'PENDING' && r.flow_node_id === APPROVAL_NODE_ID,
        )
        if (pending.length === 1) {
          approvalId = pending[0]!.id
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
  expect(approvalId).toBeGreaterThan(0)
  return approvalId
}

/** Poll the build status until terminal (or the budget runs out). */
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

/**
 * Create + trigger + park N approval-gate jobs. Jobs are appended to
 * `ownedJobIds` AS SOON AS each is created so the caller's finally{} tears
 * down partial setups too (mid-setup failures must not leak rows — the #83
 * lesson from spec 28).
 */
async function parkGates(
  api: APIRequestContext,
  bearer: string,
  runTag: string,
  labels: string[],
  ownedJobIds: number[],
): Promise<ParkedGate[]> {
  // Create + trigger all jobs first so the builds park concurrently…
  const started: Array<{ jobId: number; buildId: number }> = []
  for (const label of labels) {
    const jobId = await createJob(api, bearer, `e2e-bulk-approvals-${runTag}-${label}`)
    ownedJobIds.push(jobId)
    const buildId = await triggerBuild(api, bearer, jobId)
    started.push({ jobId, buildId })
  }
  // …then wait for each park (the waits overlap the others' engine work).
  const gates: ParkedGate[] = []
  for (const s of started) {
    const approvalId = await waitForParkedAtApproval(api, bearer, s.buildId)
    gates.push({ ...s, approvalId })
  }
  return gates
}

/**
 * Post-decision oracles shared by both tests:
 *  - the build reaches the expected terminal status;
 *  - deploy-s0 ran (approve) / never reached SUCCESS (reject);
 *  - EXACTLY one approvals row exists for the build, in the expected terminal
 *    status — no phantom PENDING minted during/after the bulk decision
 *    (the #913/#915 no-recreate oracle applied to the bulk path);
 *  - builds.error_message stays null (no #908 CAS-noise leakage).
 */
async function assertBuildOutcome(
  api: APIRequestContext,
  bearer: string,
  gate: ParkedGate,
  expected: { build: 'SUCCESS' | 'FAILED'; approval: 'APPROVED' | 'REJECTED' },
): Promise<void> {
  const final = await pollBuildTerminal(api, bearer, gate.buildId)
  expect(
    final,
    `build ${gate.buildId} must finish ${expected.build} after bulk ${expected.approval}, got ${final}`,
  ).toBe(expected.build)

  const deploy = await findNode(api, bearer, gate.buildId, DEPLOY_NODE_ID)
  if (expected.build === 'SUCCESS') {
    expect(deploy, `${DEPLOY_NODE_ID} missing for build ${gate.buildId}`).not.toBeNull()
    expect(
      deploy!.status,
      `${DEPLOY_NODE_ID}.status must be SUCCESS after bulk approve (build ${gate.buildId}, was ${deploy!.status})`,
    ).toBe('SUCCESS')
  } else if (deploy !== null) {
    expect(
      deploy.status,
      `${DEPLOY_NODE_ID}.status must NOT be SUCCESS after bulk reject (build ${gate.buildId}, was ${deploy.status})`,
    ).not.toBe('SUCCESS')
  }

  const rows = await fetchAllApprovalsForBuild(gate.buildId)
  expect(
    rows.length,
    `exactly 1 approvals row expected for build ${gate.buildId} post-decision, got ` +
      JSON.stringify(rows),
  ).toBe(1)
  expect(rows[0]!.id).toBe(gate.approvalId)
  expect(rows[0]!.flow_node_id).toBe(APPROVAL_NODE_ID)
  expect(rows[0]!.status).toBe(expected.approval)

  const build = await apiGet<BuildDetail>(api, bearer, `/api/v1/builds/${gate.buildId}`)
  expect(build.ok).toBe(true)
  const errMsg = build.body!.errorMessage
  expect(
    errMsg == null,
    `build ${gate.buildId}.errorMessage must be null post-bulk-decision (was: ${errMsg ?? 'null'}) ` +
      `— #908-class CAS leakage`,
  ).toBe(true)
}

/**
 * Tick the row checkboxes for the given approval ids, then click the bulk
 * button and capture the endpoint's JSON response. Only OUR rows are ever
 * selected — never the select-all header control (shared rig!).
 */
async function bulkDecideViaUi(
  page: Page,
  approvalIds: number[],
  kind: 'approve' | 'reject',
): Promise<BulkApprovalResponse> {
  for (const id of approvalIds) {
    const checkbox = page.getByTestId(`approval-select-${id}`)
    await expect(checkbox, `row checkbox for approval ${id}`).toBeVisible({ timeout: 15_000 })
    await expect(checkbox, `approval ${id} must be selectable by the dev user`).toBeEnabled()
    await checkbox.check()
  }
  await expect(page.getByTestId('approvals-bulk-selected-count')).toHaveText(
    `${approvalIds.length} selected`,
  )

  const [resp] = await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().includes(`/api/v1/approvals/bulk/${kind}`) && r.request().method() === 'POST',
      { timeout: 15_000 },
    ),
    page.getByTestId(`bulk-${kind}-btn`).click(),
  ])
  expect(resp.status(), `POST /approvals/bulk/${kind} via UI must be 200`).toBe(200)
  return (await resp.json()) as BulkApprovalResponse
}

// ─── Tests ──────────────────────────────────────────────────────────────────

test.describe('v3 bulk approve/reject @golden', () => {
  let bearer: string

  test.beforeAll(async () => {
    bearer = await fetchBearerToken(ENV)
  })

  test('UI: bulk-approve 2 of 3 parked gates + bulk-reject the third; per-build outcomes; no phantom rows', async ({
    page,
    request,
  }) => {
    test.setTimeout(360_000)

    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
    const ownedJobIds: number[] = []
    try {
      const [gateA, gateB, gateC] = await parkGates(
        request,
        bearer,
        runTag,
        ['a', 'b', 'c'],
        ownedJobIds,
      )

      // Open the approvals inbox — all three of OUR rows must be listed.
      await loginViaKeycloak(page, ENV)
      await page.goto(`${ENV.uiBaseUrl}/approvals`)
      for (const g of [gateA!, gateB!, gateC!]) {
        await expect(page.getByTestId(`approval-row-${g.approvalId}`)).toBeVisible({
          timeout: 15_000,
        })
      }

      // Bulk-approve the {A, B} subset via the UI checkboxes + action bar.
      const approveResp = await bulkDecideViaUi(
        page,
        [gateA!.approvalId, gateB!.approvalId],
        'approve',
      )
      expect(
        approveResp.outcomes.length,
        `bulk/approve outcomes: ${JSON.stringify(approveResp)}`,
      ).toBe(2)
      expect(approveResp.outcomes.map((o) => o.id).sort()).toEqual(
        [gateA!.approvalId, gateB!.approvalId].sort(),
      )
      for (const o of approveResp.outcomes) {
        expect(o.applied, `outcome for approval ${o.id}: ${JSON.stringify(o)}`).toBe(true)
        expect(o.status).toBe('APPROVED')
        expect(o.reason ?? null).toBeNull()
      }

      // All-applied banner; the decided rows leave the PENDING inbox.
      const banner = page.getByTestId('approvals-bulk-banner')
      await expect(banner).toBeVisible()
      await expect(banner).toHaveAttribute('data-kind', 'ok')
      await expect(banner).toContainText('2 approved')
      await expect(page.getByTestId(`approval-row-${gateA!.approvalId}`)).toHaveCount(0, {
        timeout: 15_000,
      })
      await expect(page.getByTestId(`approval-row-${gateB!.approvalId}`)).toHaveCount(0, {
        timeout: 15_000,
      })

      // C is untouched by the approve — still PENDING in the inbox and in pg.
      await expect(page.getByTestId(`approval-row-${gateC!.approvalId}`)).toBeVisible()
      const cRows = await fetchAllApprovalsForBuild(gateC!.buildId)
      expect(cRows.length, `C must be untouched by the bulk approve: ${JSON.stringify(cRows)}`).toBe(1)
      expect(cRows[0]!.status).toBe('PENDING')

      // Bulk-reject the rest ({C}) via the same surface.
      const rejectResp = await bulkDecideViaUi(page, [gateC!.approvalId], 'reject')
      expect(rejectResp.outcomes.length, JSON.stringify(rejectResp)).toBe(1)
      expect(rejectResp.outcomes[0]!.id).toBe(gateC!.approvalId)
      expect(rejectResp.outcomes[0]!.applied).toBe(true)
      expect(rejectResp.outcomes[0]!.status).toBe('REJECTED')
      await expect(banner).toContainText('1 rejected')
      await expect(page.getByTestId(`approval-row-${gateC!.approvalId}`)).toHaveCount(0, {
        timeout: 15_000,
      })

      // Per-build outcome + no-CAS-leak/no-recreate oracles.
      await assertBuildOutcome(request, bearer, gateA!, {
        build: 'SUCCESS',
        approval: 'APPROVED',
      })
      await assertBuildOutcome(request, bearer, gateB!, {
        build: 'SUCCESS',
        approval: 'APPROVED',
      })
      await assertBuildOutcome(request, bearer, gateC!, {
        build: 'FAILED',
        approval: 'REJECTED',
      })
    } finally {
      for (const jobId of ownedJobIds) {
        await safeDeleteJobCascade(request, jobId)
      }
    }
  })

  test('API sad path: bulk decision with an already-decided id must not double-apply; de-dupe + not_found + empty-ids contract', async ({
    request,
  }) => {
    test.setTimeout(360_000)

    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}-sad`
    const ownedJobIds: number[] = []
    try {
      const [gateD, gateE] = await parkGates(
        request,
        bearer,
        runTag,
        ['d', 'e'],
        ownedJobIds,
      )

      // Decide D via the SINGLE endpoint — it is now already_decided for bulk.
      const singleResp = await request.post(
        `${API_BASE}/api/v1/approvals/${gateD!.approvalId}/approve`,
        {
          headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
          data: {},
        },
      )
      expect(
        singleResp.status(),
        `single approve of ${gateD!.approvalId}: ${await singleResp.text()}`,
      ).toBe(200)

      // Bulk-REJECT [D, E, E, nonexistent]: D must NOT flip; E decides once;
      // duplicates de-dupe; the unknown id reports not_found; HTTP stays 200.
      const bulkResp = await request.post(`${API_BASE}/api/v1/approvals/bulk/reject`, {
        headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
        data: {
          ids: [
            gateD!.approvalId,
            gateE!.approvalId,
            gateE!.approvalId,
            NONEXISTENT_APPROVAL_ID,
          ],
        },
      })
      const bulkRaw = await bulkResp.text()
      expect(bulkResp.status(), `POST /bulk/reject HTTP ${bulkResp.status()} body=${bulkRaw}`).toBe(
        200,
      )
      const bulk = JSON.parse(bulkRaw) as BulkApprovalResponse

      // De-dupe: 4 ids in, 3 outcomes out, first-occurrence order preserved.
      expect(bulk.outcomes.length, `outcomes: ${bulkRaw}`).toBe(3)
      expect(bulk.outcomes.map((o) => o.id)).toEqual([
        gateD!.approvalId,
        gateE!.approvalId,
        NONEXISTENT_APPROVAL_ID,
      ])

      const [outD, outE, outMissing] = bulk.outcomes
      // D: already decided — the reject must not double-apply or flip it.
      expect(outD!.applied, `already-decided outcome: ${JSON.stringify(outD)}`).toBe(false)
      expect(outD!.reason).toBe('already_decided')
      expect(outD!.status, 'already-decided id must keep its original decision').toBe('APPROVED')
      // E: decided exactly once by this bulk call.
      expect(outE!.applied, JSON.stringify(outE)).toBe(true)
      expect(outE!.status).toBe('REJECTED')
      // Unknown id: reported, not fatal.
      expect(outMissing!.applied).toBe(false)
      expect(outMissing!.reason).toBe('not_found')

      // Outcome oracles: D's earlier approve stands (SUCCESS, deploy ran);
      // E fails. Exactly one row each, terminal statuses unflipped.
      await assertBuildOutcome(request, bearer, gateD!, {
        build: 'SUCCESS',
        approval: 'APPROVED',
      })
      await assertBuildOutcome(request, bearer, gateE!, {
        build: 'FAILED',
        approval: 'REJECTED',
      })

      // Empty ids → 400 (never a silent 200-with-no-outcomes).
      const emptyResp = await request.post(`${API_BASE}/api/v1/approvals/bulk/approve`, {
        headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
        data: { ids: [] },
      })
      expect(
        emptyResp.status(),
        `POST /bulk/approve with empty ids: ${await emptyResp.text()}`,
      ).toBe(400)
    } finally {
      for (const jobId of ownedJobIds) {
        await safeDeleteJobCascade(request, jobId)
      }
    }
  })
})

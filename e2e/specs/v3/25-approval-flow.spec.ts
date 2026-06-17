/**
 * 25-approval-flow — end-to-end approval-step lifecycle against the v3 rig.
 *
 * Covers PR #720 (backend) + PR #726 (UI):
 *   - The orchestrator parks an `approval:` step + inserts a PENDING row.
 *   - The UI renders the inline ApprovalBanner on /builds/$id.
 *   - Approve → orchestrator resumes the node SUCCESS, build → SUCCESS.
 *   - Reject  → orchestrator resumes the node FAILED, build → FAILED.
 *   - Timeout (TimerSweepWorker) → row flips TIMED_OUT, build → FAILED.
 *
 * Plus the adversarial corners that protect the contract:
 *   - Unauthenticated decide → 401 (the @RolesAllowed gate is wired).
 *   - Double-decide → 409 (idempotency boundary).
 *
 * Pre-req: `task dev:titan` is up (postgres + keycloak + server + ui + worker).
 *          The dev/dev user has ADMIN, which bypasses the per-row approvers
 *          check (ApprovalService.decide) — that's how we approve/reject from
 *          the SPA without minting a second realm user.
 *
 * Determinism strategy:
 *   - Per-test, create a fresh job with a unique full_name (avoid 409 from a
 *     prior run) using the real POST /api/v1/jobs path (same shape as
 *     seed-data.sh). Then enqueue a build via direct SQL (mirrors
 *     dogfood-fire.sh). The orchestrator + worker run end-to-end.
 *   - Poll the API with bounded budgets; no setTimeout-loops without a
 *     hard deadline. No Thread.sleep equivalents.
 *   - Per-test cleanup deletes the job + cascade — no row pollution across
 *     reruns. (The fresh-name guards reruns as well.)
 */
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken, loginViaKeycloak } from '../../fixtures/auth-v3'
import { pgClient } from '../../fixtures/seed-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

// ─── Pipelines ──────────────────────────────────────────────────────────────
// Three pipelines: long-default-timeout (approve + reject paths reuse the same
// shape — only the human action differs), and short-5s-timeout (timeout path).
const APPROVAL_PROMPT = 'Deploy to prod?'

// Long timeout (default 24h) — the test always decides before this fires, so
// the timeout sweep never races the human action. `approvers: []` keeps the
// per-row authz check open; dev's ADMIN role bypasses anyway.
const PIPELINE_LONG = `stages:
  - stage: build
    steps:
      - sh: echo built
  - stage: deploy
    dependsOn: [build]
    steps:
      - approval:
          prompt: "${APPROVAL_PROMPT}"
          timeout: 24h
      - sh: echo deployed
`

// Short timeout — TimerSweepWorker is on a 5s cadence (see
// titan-server/src/main/java/io/adaptiq/titan/timer/TimerSweepWorker.java).
// 5s timeout + one sweep tick = ~10s wall before the row flips TIMED_OUT.
const PIPELINE_SHORT = `stages:
  - stage: build
    steps:
      - sh: echo built
  - stage: deploy
    dependsOn: [build]
    steps:
      - approval:
          prompt: "${APPROVAL_PROMPT}"
          timeout: 5s
      - sh: echo deployed
`

// ─── Test harness ───────────────────────────────────────────────────────────

interface CreatedJob {
  jobId: number
  fullName: string
}

interface CreatedBuild {
  buildId: number
  jobId: number
  fullName: string
}

/** Create a job via the real POST /api/v1/jobs path. Mirrors seed-data.sh. */
async function createJob(
  api: APIRequestContext,
  bearer: string,
  fullName: string,
  pipeline: string,
): Promise<CreatedJob> {
  const res = await api.post(`${API_BASE}/api/v1/jobs`, {
    headers: {
      Authorization: `Bearer ${bearer}`,
      'Content-Type': 'application/json',
    },
    data: {
      fullName,
      displayName: fullName,
      pipelineScript: pipeline,
      enabled: true,
    },
  })
  expect(res.status(), `POST /api/v1/jobs ${fullName}: ${await res.text()}`).toBe(201)
  const body = (await res.json()) as { id: number }
  return { jobId: body.id, fullName }
}

/**
 * Enqueue a build via direct SQL — mirrors rig/local/dogfood-fire.sh exactly.
 * The orchestrator picks up the ORCHESTRATE/SYNTHESIZE task on the `default`
 * queue and drives the pipeline end-to-end. We return the build id.
 */
async function fireBuild(job: CreatedJob): Promise<CreatedBuild> {
  const client = pgClient()
  await client.connect()
  try {
    const res = await client.query<{ id: string }>(
      `WITH ins_build AS (
         INSERT INTO titan.builds
           (job_id, build_number, status, triggered_by, trigger_type, queued_at)
         VALUES ($1, 1, 'QUEUED', 'e2e-approval', 'manual', NOW())
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
      [job.jobId],
    )
    const buildId = Number(res.rows[0]!.id)
    expect(buildId).toBeGreaterThan(0)
    return { buildId, jobId: job.jobId, fullName: job.fullName }
  } finally {
    await client.end()
  }
}

/** Delete the job + cascade rows. Idempotent. */
async function cleanupJob(jobId: number): Promise<void> {
  const client = pgClient()
  await client.connect()
  try {
    // Order: dependents first — Postgres cascades cover most of it but be
    // explicit on the wide tables in case any FK isn't ON DELETE CASCADE.
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

/** Poll the build status with a hard deadline. Returns final status. */
async function pollBuildStatus(
  api: APIRequestContext,
  bearer: string,
  buildId: number,
  predicate: (status: string) => boolean,
  budgetMs: number,
): Promise<string> {
  const deadline = Date.now() + budgetMs
  let status = 'QUEUED'
  let lastBody = ''
  while (Date.now() < deadline) {
    const r = await api.get(`${API_BASE}/api/v1/builds/${buildId}`, {
      headers: { Authorization: `Bearer ${bearer}` },
    })
    if (r.ok()) {
      const body = (await r.json()) as { status?: string }
      lastBody = JSON.stringify(body)
      status = body.status ?? status
      if (predicate(status)) return status
    } else {
      lastBody = `HTTP ${r.status()} ${await r.text().catch(() => '')}`
    }
    await new Promise((res) => setTimeout(res, 500))
  }
  throw new Error(
    `build ${buildId} did not reach predicate within ${budgetMs}ms — last status=${status}, body=${lastBody}`,
  )
}

/** Find the (latest) PENDING approval row for a build. Returns null if none. */
async function fetchPendingApproval(
  api: APIRequestContext,
  bearer: string,
  buildId: number,
): Promise<{ id: number; prompt: string } | null> {
  const r = await api.get(`${API_BASE}/api/v1/approvals?status=PENDING&limit=200`, {
    headers: { Authorization: `Bearer ${bearer}` },
  })
  expect(r.ok(), `GET /api/v1/approvals: ${r.status()}`).toBe(true)
  const body = (await r.json()) as {
    items: Array<{ id: number; buildId: number; prompt: string }>
  }
  const row = body.items.find((a) => a.buildId === buildId)
  return row ? { id: row.id, prompt: row.prompt } : null
}

/** Poll until a PENDING approval exists for the build. Hard deadline. */
async function waitForPendingApproval(
  api: APIRequestContext,
  bearer: string,
  buildId: number,
  budgetMs: number,
): Promise<{ id: number; prompt: string }> {
  const deadline = Date.now() + budgetMs
  while (Date.now() < deadline) {
    const row = await fetchPendingApproval(api, bearer, buildId)
    if (row) return row
    await new Promise((res) => setTimeout(res, 500))
  }
  throw new Error(`no PENDING approval surfaced for build ${buildId} within ${budgetMs}ms`)
}

// ─── Tests ──────────────────────────────────────────────────────────────────

test.describe('v3 approval-flow @golden', () => {
  // Each test holds its own build so cleanup is straightforward + reruns don't
  // collide on full_name (we mint a fresh name each time).
  let bearer: string

  test.beforeAll(async () => {
    bearer = await fetchBearerToken(ENV)
  })

  test('approve path: build reaches PENDING_APPROVAL, banner renders, approve resumes SUCCESS', async ({
    page,
    request,
  }) => {
    test.setTimeout(120_000)

    const fullName = `e2e-approval-approve-${Date.now()}`
    const job = await createJob(request, bearer, fullName, PIPELINE_LONG)
    let buildId = 0
    try {
      buildId = (await fireBuild(job)).buildId

      // 1. Wait for the orchestrator to park the approval step.
      const approval = await waitForPendingApproval(request, bearer, buildId, 30_000)
      expect(approval.prompt).toBe(APPROVAL_PROMPT)

      // 2. Login + navigate to the build detail.
      await loginViaKeycloak(page, ENV)
      await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)

      // 3. Inline banner renders with the prompt.
      const banner = page.getByTestId(`approval-banner-${approval.id}`)
      await expect(banner).toBeVisible({ timeout: 15_000 })
      await expect(banner).toContainText(APPROVAL_PROMPT)

      // 4. Click Approve — wait for the POST /approve response in parallel
      //    with the click so we don't race the React state.
      const approveBtn = page.getByTestId(`approval-approve-${approval.id}`)
      await expect(approveBtn).toBeEnabled({ timeout: 5_000 })
      const [resp] = await Promise.all([
        page.waitForResponse(
          (r) =>
            r.url().includes(`/api/v1/approvals/${approval.id}/approve`) &&
            r.request().method() === 'POST',
          { timeout: 10_000 },
        ),
        approveBtn.click(),
      ])
      expect(resp.status(), 'POST /approve must be 200').toBe(200)

      // 5. Build must resume + finish SUCCESS. The deploy stage has one more
      //    `sh: echo deployed` step after the approval — the worker runs it.
      const final = await pollBuildStatus(
        request,
        bearer,
        buildId,
        (s) => s === 'SUCCESS' || s === 'FAILED' || s === 'ABORTED',
        60_000,
      )
      expect(final, 'build must finish SUCCESS after approve').toBe('SUCCESS')

      // 6. Banner disappears once the row is no longer PENDING.
      await expect(banner).toBeHidden({ timeout: 10_000 })
    } finally {
      if (job.jobId) await cleanupJob(job.jobId)
    }
  })

  test('reject path: clicking Reject auto-fails the build', async ({ page, request }) => {
    test.setTimeout(120_000)

    const fullName = `e2e-approval-reject-${Date.now()}`
    const job = await createJob(request, bearer, fullName, PIPELINE_LONG)
    try {
      const { buildId } = await fireBuild(job)
      const approval = await waitForPendingApproval(request, bearer, buildId, 30_000)

      await loginViaKeycloak(page, ENV)
      await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)

      const banner = page.getByTestId(`approval-banner-${approval.id}`)
      await expect(banner).toBeVisible({ timeout: 15_000 })

      const rejectBtn = page.getByTestId(`approval-reject-${approval.id}`)
      await expect(rejectBtn).toBeEnabled({ timeout: 5_000 })
      const [resp] = await Promise.all([
        page.waitForResponse(
          (r) =>
            r.url().includes(`/api/v1/approvals/${approval.id}/reject`) &&
            r.request().method() === 'POST',
          { timeout: 10_000 },
        ),
        rejectBtn.click(),
      ])
      expect(resp.status()).toBe(200)

      const final = await pollBuildStatus(
        request,
        bearer,
        buildId,
        (s) => s === 'SUCCESS' || s === 'FAILED' || s === 'ABORTED',
        30_000,
      )
      expect(final, 'reject must drive build to FAILED').toBe('FAILED')

      // The approval row must now be terminal REJECTED (no longer PENDING).
      const stillPending = await fetchPendingApproval(request, bearer, buildId)
      expect(stillPending, 'no PENDING approval after reject').toBeNull()
    } finally {
      if (job.jobId) await cleanupJob(job.jobId)
    }
  })

  test('timeout path: no human action — TimerSweepWorker flips TIMED_OUT and build FAILS', async ({
    request,
  }) => {
    test.setTimeout(60_000)

    const fullName = `e2e-approval-timeout-${Date.now()}`
    const job = await createJob(request, bearer, fullName, PIPELINE_SHORT)
    try {
      const { buildId } = await fireBuild(job)
      const approval = await waitForPendingApproval(request, bearer, buildId, 30_000)
      expect(approval.prompt).toBe(APPROVAL_PROMPT)

      // Wait ≤ 30s for: 5s timeout + ≤5s sweep cadence + orchestrator
      // ADVANCE pass to FAIL the build.
      const final = await pollBuildStatus(
        request,
        bearer,
        buildId,
        (s) => s === 'SUCCESS' || s === 'FAILED' || s === 'ABORTED',
        30_000,
      )
      expect(final, 'timeout sweep must drive the build to FAILED').toBe('FAILED')

      // The approval row must now be TIMED_OUT (we check via the dedicated
      // status filter — the PENDING list will not contain it).
      const stillPending = await fetchPendingApproval(request, bearer, buildId)
      expect(stillPending, 'no PENDING approval after timeout').toBeNull()

      const timedOut = await request.get(
        `${API_BASE}/api/v1/approvals?status=TIMED_OUT&limit=200`,
        { headers: { Authorization: `Bearer ${bearer}` } },
      )
      expect(timedOut.ok()).toBe(true)
      const timedOutBody = (await timedOut.json()) as {
        items: Array<{ id: number; status: string }>
      }
      const found = timedOutBody.items.find((a) => a.id === approval.id)
      expect(found, 'approval row must surface in the TIMED_OUT list').toBeTruthy()
      expect(found!.status).toBe('TIMED_OUT')
    } finally {
      if (job.jobId) await cleanupJob(job.jobId)
    }
  })

  // ─── Adversarial cases ───────────────────────────────────────────────────

  test('unauthenticated decide returns 401 — the @RolesAllowed gate is wired', async ({
    request,
  }) => {
    test.setTimeout(60_000)

    const fullName = `e2e-approval-401-${Date.now()}`
    const job = await createJob(request, bearer, fullName, PIPELINE_LONG)
    try {
      const { buildId } = await fireBuild(job)
      const approval = await waitForPendingApproval(request, bearer, buildId, 30_000)

      // No Authorization header → Quarkus OIDC rejects with 401 before the
      // @RolesAllowed check fires. This is the floor — without it, the
      // decide endpoints would be wide open.
      const noAuth = await request.post(
        `${API_BASE}/api/v1/approvals/${approval.id}/approve`,
        {
          headers: { 'Content-Type': 'application/json' },
          data: {},
        },
      )
      expect(
        noAuth.status(),
        `unauthenticated POST /approve must be 401, got ${noAuth.status()}: ${await noAuth.text()}`,
      ).toBe(401)

      // Sanity: the build is still PENDING_APPROVAL — the unauth call was a
      // no-op on the row.
      const stillPending = await fetchPendingApproval(request, bearer, buildId)
      expect(stillPending?.id, 'unauth call must not flip the row').toBe(approval.id)
    } finally {
      if (job.jobId) await cleanupJob(job.jobId)
    }
  })

  test('double-approve returns 409 — terminal rows are immutable', async ({ request }) => {
    test.setTimeout(120_000)

    const fullName = `e2e-approval-409-${Date.now()}`
    const job = await createJob(request, bearer, fullName, PIPELINE_LONG)
    try {
      const { buildId } = await fireBuild(job)
      const approval = await waitForPendingApproval(request, bearer, buildId, 30_000)

      // First decide — winner.
      const first = await request.post(
        `${API_BASE}/api/v1/approvals/${approval.id}/approve`,
        {
          headers: {
            Authorization: `Bearer ${bearer}`,
            'Content-Type': 'application/json',
          },
          data: {},
        },
      )
      expect(first.status(), 'first approve must be 200').toBe(200)
      const firstBody = (await first.json()) as { applied: boolean; status: string }
      expect(firstBody.applied).toBe(true)
      expect(firstBody.status).toBe('APPROVED')

      // Second decide on the same row — loser. Backend contract: 409 +
      // applied=false. See ApprovalsApi.decide.
      const second = await request.post(
        `${API_BASE}/api/v1/approvals/${approval.id}/approve`,
        {
          headers: {
            Authorization: `Bearer ${bearer}`,
            'Content-Type': 'application/json',
          },
          data: {},
        },
      )
      expect(
        second.status(),
        `second approve on a terminal row must be 409, got ${second.status()}: ${await second.text()}`,
      ).toBe(409)
      const secondBody = (await second.json()) as { applied: boolean; status: string }
      expect(secondBody.applied).toBe(false)
      expect(secondBody.status).toBe('APPROVED')

      // Flipping an APPROVED row to REJECTED is also a loser — 409.
      const flip = await request.post(
        `${API_BASE}/api/v1/approvals/${approval.id}/reject`,
        {
          headers: {
            Authorization: `Bearer ${bearer}`,
            'Content-Type': 'application/json',
          },
          data: {},
        },
      )
      expect(flip.status(), 'cannot flip APPROVED → REJECTED').toBe(409)
    } finally {
      if (job.jobId) await cleanupJob(job.jobId)
    }
  })
})

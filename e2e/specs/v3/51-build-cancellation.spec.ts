/**
 * 51-build-cancellation — Layer-2 spec for build cancellation (closes #960).
 *
 * Why this exists:
 *   Cancel is a daily-driver path. Customers WILL cancel mid-flight (oops-
 *   commit, mis-fire, long-runner). If the cancel path leaks worker
 *   resources, leaves status RUNNING forever, or silently no-ops, the rig
 *   wedges and we look broken.
 *
 *   Spec #11 already covers the *UI button* against a seeded RUNNING build.
 *   This spec is the full system contract: a freshly-triggered build, a
 *   real worker pickup, an API-level cancel, with explicit assertions on:
 *     - terminal status name (ABORTED — discovered from server source,
 *       NOT CANCELED/STOPPED — see BuildAbortService.BUILD_TERMINAL),
 *     - task_queue rows clearing,
 *     - SSE log stream emitting `event:done ABORTED` and closing,
 *     - QUEUED → ABORTED without ever passing through RUNNING (startedAt=null),
 *     - cancel-on-terminal-build error envelope.
 *
 *   Marked @golden because cancel is a top-level user journey and a
 *   regression here is a P1.
 *
 * Live-rig discoveries baked into the assertions:
 *   - Endpoint: POST /api/v1/builds/{id}/cancel (BuildDetailApi.cancelBuild).
 *   - Terminal name: ABORTED (BuildAbortService.BUILD_TERMINAL = SUCCESS /
 *     FAILED / ABORTED / UNSTABLE).
 *   - Success response: HTTP 202 + {"aborted":true,"message":"..."}.
 *   - task_queue rows are removed (moved to task_archive) on cancel — verified
 *     by probing PG directly: 0 rows in task_queue for the build post-cancel.
 *   - SSE: BuildLogsSse closes the stream with `event:done\ndata:ABORTED`
 *     once the build hits terminal status. The SSE polling loop is 500 ms,
 *     so 30s is a generous budget.
 *   - Cancel on terminal build returns HTTP 409 + application/problem+json
 *     {type, title:"Conflict", status:409, detail:"Build is already terminal:
 *     STATUS", build_id, current_status} — see #962 (fixed).
 *
 * Follow-up issues filed alongside this spec:
 *   - #962 — cancel-on-terminal returns 409 problem+json (was 202 silent no-op).
 *   - #964 — no DELETE /api/v1/jobs/{id}. The finally{} cleanup is best-
 *     effort because the rig returns 405; spec tolerates that.
 *
 * Fixture strategy:
 *   Each test creates a fresh job whose pipeline has a single `sh: sleep 60`
 *   step. titan-demo (id=4) seeds a `sleep 2` step which is too short to
 *   reliably win the cancel race. Per-test RUN_TAG keeps job names unique
 *   so a leaked job from a prior run doesn't collide on full_name (409).
 *
 *   Cleanup: DELETE /api/v1/jobs/{id} is wired-not-yet (issue #964); we
 *   call it best-effort and tolerate 405. Leaked jobs accumulate in the
 *   rig — that's the cost-of-doing-business until #964 lands.
 */
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'
import { pgClient } from '../../fixtures/seed-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const RUN_TAG = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`

const SLEEP_PIPELINE_YAML = `stages:
  - stage: slow
    steps:
      - sh: |
          echo starting
          sleep 60
          echo done
`

interface JobCreateResp {
  id: number
  fullName: string
}
interface TriggerBuildResp {
  buildId: number
  buildNumber: number
  status: string
}
interface BuildDetail {
  id: number
  status: string
  startedAt: string | null
  finishedAt: string | null
}
interface FlowNode {
  nodeId: string
  nodeType: string
  status: string
}
interface AbortOutcome {
  aborted: boolean
  message: string
}

async function bearer(): Promise<string> {
  return fetchBearerToken(ENV)
}

async function createSleepJob(
  request: APIRequestContext,
  token: string,
  label: string,
): Promise<number> {
  const fullName = `e2e-cancel-${label}-${RUN_TAG}`
  const resp = await request.post(`${API_BASE}/api/v1/jobs`, {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    data: {
      fullName,
      displayName: `E2E cancel ${label}`,
      pipelineScript: SLEEP_PIPELINE_YAML,
      enabled: true,
    },
  })
  expect(
    resp.status(),
    `POST /api/v1/jobs (${fullName}) HTTP ${resp.status()}: ${await resp.text()}`,
  ).toBe(201)
  const body = (await resp.json()) as JobCreateResp
  expect(body.id).toBeGreaterThan(0)
  return body.id
}

async function triggerBuild(
  request: APIRequestContext,
  token: string,
  jobId: number,
): Promise<number> {
  const resp = await request.post(`${API_BASE}/api/v1/jobs/${jobId}/builds`, {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    data: { triggeredBy: 'e2e-spec-51' },
  })
  expect(
    resp.status(),
    `POST /api/v1/jobs/${jobId}/builds HTTP ${resp.status()}: ${await resp.text()}`,
  ).toBe(201)
  const body = (await resp.json()) as TriggerBuildResp
  expect(body.buildId).toBeGreaterThan(0)
  return body.buildId
}

async function getBuild(
  request: APIRequestContext,
  token: string,
  buildId: number,
): Promise<BuildDetail> {
  const resp = await request.get(`${API_BASE}/api/v1/builds/${buildId}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(resp.ok(), `GET /builds/${buildId} HTTP ${resp.status()}`).toBe(true)
  return (await resp.json()) as BuildDetail
}

async function getNodes(
  request: APIRequestContext,
  token: string,
  buildId: number,
): Promise<FlowNode[]> {
  const resp = await request.get(`${API_BASE}/api/v1/builds/${buildId}/nodes`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(resp.ok(), `GET /builds/${buildId}/nodes HTTP ${resp.status()}`).toBe(true)
  return (await resp.json()) as FlowNode[]
}

async function countTaskQueueRows(buildId: number): Promise<number> {
  const client = pgClient()
  await client.connect()
  try {
    const r = await client.query<{ n: string }>(
      `SELECT count(*)::text AS n FROM titan.task_queue WHERE build_id = $1`,
      [buildId],
    )
    return Number(r.rows[0]?.n ?? '0')
  } finally {
    await client.end()
  }
}

async function tryDeleteJob(
  request: APIRequestContext,
  token: string,
  jobId: number | undefined,
): Promise<void> {
  if (!jobId) return
  // DELETE not wired yet — issue #964. Tolerate 405 / any failure.
  await request
    .delete(`${API_BASE}/api/v1/jobs/${jobId}`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    .catch(() => null)
}

test.describe('v3 build-cancellation @golden', () => {
  // ── Test 1 ──────────────────────────────────────────────────────────────
  test('cancel a RUNNING build: status → ABORTED, task_queue clears, SSE emits done', async ({
    request,
  }) => {
    test.setTimeout(120_000)
    const token = await bearer()
    let jobId: number | undefined
    let buildId: number | undefined
    try {
      jobId = await createSleepJob(request, token, 'running')
      buildId = await triggerBuild(request, token, jobId)

      // Poll /nodes until at least one node is RUNNING. The orchestrator
      // synthesises + bakes the DAG within a few hundred ms; the worker
      // claims and runs the step a moment later. 30s is generous.
      await expect
        .poll(
          async () => {
            const nodes = await getNodes(request, token, buildId!)
            return nodes.some((n) => n.status === 'RUNNING')
          },
          {
            message: `no flow_node reached RUNNING for build ${buildId} within 30s`,
            timeout: 30_000,
            intervals: [500, 1_000, 2_000],
          },
        )
        .toBe(true)

      // Subscribe to SSE BEFORE the cancel so we capture the terminal event.
      // request.get with no timeout would hang; explicit 45s budget — the
      // poll loop is 500 ms, so the done event should arrive well inside
      // that. Read the body synchronously after; the server closes the
      // connection once it emits `event:done`.
      const sseBody = (async () => {
        const r = await request.get(`${API_BASE}/api/v1/builds/${buildId!}/logs`, {
          headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
          timeout: 45_000,
        })
        return r.text()
      })()

      // Brief breath so the SSE GET has actually opened before we cancel —
      // not load-bearing for correctness (the server replays buffered logs
      // either way) but avoids tightly racing the abort against subscribe.
      await expect.poll(async () => true, { timeout: 500 }).toBe(true)

      const cancelResp = await request.post(
        `${API_BASE}/api/v1/builds/${buildId}/cancel`,
        { headers: { Authorization: `Bearer ${token}` } },
      )
      expect(
        cancelResp.status(),
        `cancel HTTP ${cancelResp.status()}: ${await cancelResp.text()}`,
      ).toBe(202)
      const outcome = (await cancelResp.json()) as AbortOutcome
      expect(
        outcome.aborted,
        `expected aborted=true, got ${JSON.stringify(outcome)}`,
      ).toBe(true)

      // Within 15s: build.status === ABORTED.
      await expect
        .poll(
          async () => (await getBuild(request, token, buildId!)).status,
          {
            message: `build ${buildId} never reached ABORTED within 15s`,
            timeout: 15_000,
            intervals: [500, 1_000],
          },
        )
        .toBe('ABORTED')

      // Within 30s: titan.task_queue rows for the build are gone (moved to
      // task_archive on completion + cancel path drives any remaining
      // live tasks to CANCELLED + archives them).
      await expect
        .poll(() => countTaskQueueRows(buildId!), {
          message: `task_queue still has live rows for build ${buildId} 30s after cancel`,
          timeout: 30_000,
          intervals: [1_000, 2_000],
        })
        .toBe(0)

      // The SSE stream must emit a terminal `event:done` carrying ABORTED.
      // We deliberately do NOT assert "stream closed" (the playwright
      // APIRequestContext request hides that signal) — we just assert the
      // done frame appears in the buffered body.
      const sseText = await sseBody
      expect(
        sseText,
        `SSE stream did not emit event:done — got:\n${sseText.slice(0, 800)}`,
      ).toContain('event:done')
      expect(
        sseText,
        `SSE stream emitted done but not with ABORTED — got:\n${sseText.slice(-400)}`,
      ).toMatch(/event:done\s*\r?\n\s*data:\s*ABORTED/)
    } finally {
      await tryDeleteJob(request, token, jobId)
    }
  })

  // ── Test 2 ──────────────────────────────────────────────────────────────
  test('cancel a QUEUED build: goes directly QUEUED → ABORTED, never RUNNING', async ({
    request,
  }) => {
    test.setTimeout(120_000)
    const token = await bearer()
    // The orchestrator + worker pickup is fast on the local rig (~hundreds of
    // ms). To deterministically catch a build while still QUEUED we retry up
    // to 5 times — each attempt creates a fresh job, triggers, and races the
    // cancel against the worker. The vast majority of single attempts win.
    // If all 5 lose the race the test reports loud — that means the local
    // rig is unusually fast and the spec needs a more aggressive isolation
    // (e.g. unbind the worker from the queue first).
    const MAX_ATTEMPTS = 5
    let succeeded = false
    let lastStartedAt: string | null | undefined
    let lastBuildId: number | undefined
    let lastJobId: number | undefined

    for (let attempt = 1; attempt <= MAX_ATTEMPTS && !succeeded; attempt++) {
      let jobId: number | undefined
      let buildId: number | undefined
      try {
        jobId = await createSleepJob(request, token, `queued-a${attempt}`)
        // Trigger and IMMEDIATELY cancel — no breathing room for the worker
        // to claim.
        buildId = await triggerBuild(request, token, jobId)
        lastJobId = jobId
        lastBuildId = buildId

        const cancelResp = await request.post(
          `${API_BASE}/api/v1/builds/${buildId}/cancel`,
          { headers: { Authorization: `Bearer ${token}` } },
        )
        expect(
          cancelResp.status(),
          `cancel HTTP ${cancelResp.status()}: ${await cancelResp.text()}`,
        ).toBe(202)
        const outcome = (await cancelResp.json()) as AbortOutcome
        expect(outcome.aborted).toBe(true)

        // Build reaches ABORTED.
        await expect
          .poll(
            async () => (await getBuild(request, token, buildId!)).status,
            {
              message: `build ${buildId} never reached ABORTED within 15s`,
              timeout: 15_000,
              intervals: [500, 1_000],
            },
          )
          .toBe('ABORTED')

        const final = await getBuild(request, token, buildId)
        lastStartedAt = final.startedAt
        // Live discovery: BuildDto serialises null `startedAt` as field-absent
        // (Jackson default omits nulls). So a build that never started has
        // `startedAt === undefined` in the parsed JSON, NOT === null. Both
        // mean "never transitioned RUNNING" for the purpose of this assertion.
        const neverStarted = final.startedAt === null || final.startedAt === undefined
        if (neverStarted) {
          // Won the race — assert what the test exists to assert.
          succeeded = true
          expect(
            neverStarted,
            `build ${buildId} startedAt=${JSON.stringify(final.startedAt)} — expected null|undefined (QUEUED → ABORTED, no RUNNING transition)`,
          ).toBe(true)
          // task_queue must also clear for the queued-cancel path.
          await expect
            .poll(() => countTaskQueueRows(buildId!), {
              message: `task_queue still has live rows for build ${buildId} after queued-cancel`,
              timeout: 30_000,
              intervals: [1_000, 2_000],
            })
            .toBe(0)
        }
        // else: lost the race against the worker — retry with a fresh job.
      } finally {
        await tryDeleteJob(request, token, jobId)
      }
    }

    expect(
      succeeded,
      `lost the cancel/worker race ${MAX_ATTEMPTS} times in a row ` +
        `(last build ${lastBuildId}, job ${lastJobId}, startedAt=${lastStartedAt}). ` +
        `The local rig's orchestrator+worker pickup is faster than the API call to ` +
        `/cancel — to make this deterministic, the rig would need a way to enqueue ` +
        `against a queue the worker isn't subscribed to (or a knob to delay synthesis).`,
    ).toBe(true)
  })

  // ── Test 3 ──────────────────────────────────────────────────────────────
  test('adversarial: cancel a TERMINAL build returns 409 problem+json (#962)', async ({
    request,
  }) => {
    test.setTimeout(60_000)
    const token = await bearer()
    // Find ANY stable terminal build from the rig. Whether seed-data.sh
    // landed titan-server fixture rows or only real dogfood-fire / e2e
    // builds, there's always at least one SUCCESS or FAILED build to
    // anchor on (the rig is post-bootstrap by this point — tasks 11, 21,
    // 26-31 all leave terminal builds behind).
    let terminalBuildId: number | undefined
    const client = pgClient()
    await client.connect()
    try {
      const r = await client.query<{ id: string; status: string }>(
        `SELECT b.id::text AS id, b.status
           FROM titan.builds b
          WHERE b.status IN ('SUCCESS','FAILED','UNSTABLE')
          ORDER BY b.id ASC
          LIMIT 1`,
      )
      const row = r.rows[0]
      expect(row, 'no SUCCESS/FAILED/UNSTABLE build present in the rig — run task dogfood:fire to land one').toBeTruthy()
      terminalBuildId = Number(row!.id)
    } finally {
      await client.end()
    }

    const before = await getBuild(request, token, terminalBuildId!)
    expect(['SUCCESS', 'FAILED', 'ABORTED', 'UNSTABLE']).toContain(before.status)

    const resp = await request.post(
      `${API_BASE}/api/v1/builds/${terminalBuildId}/cancel`,
      { headers: { Authorization: `Bearer ${token}` } },
    )
    const text = await resp.text()

    // ── #962 (fixed): cancel on terminal returns 409 + problem+json ───────
    // The envelope must name the current status in `detail` AND echo it
    // in the structured `current_status` field so clients can branch on
    // value, not regex the human string.
    expect(
      resp.status(),
      `cancel-on-terminal returned ${resp.status()}: ${text} — expected 409`,
    ).toBe(409)
    expect(
      resp.headers()['content-type'],
      `expected application/problem+json, got: ${resp.headers()['content-type']}`,
    ).toContain('application/problem+json')

    const problem = JSON.parse(text) as {
      type: string
      title: string
      status: number
      detail: string
      build_id: number
      current_status: string
    }
    expect(problem.status, `problem.status=${problem.status}`).toBe(409)
    expect(problem.title, `problem.title=${problem.title}`).toBe('Conflict')
    expect(
      problem.detail,
      `problem.detail must name current status (${before.status}): ${problem.detail}`,
    ).toContain(before.status)
    expect(problem.current_status, `problem.current_status=${problem.current_status}`).toBe(
      before.status,
    )
    expect(problem.build_id, `problem.build_id=${problem.build_id}`).toBe(terminalBuildId)

    // The build's status MUST be unchanged — a cancel of a terminal build
    // is a no-op, not a status rewrite (no SUCCESS → ABORTED retconning).
    const after = await getBuild(request, token, terminalBuildId!)
    expect(after.status, `terminal build status mutated by cancel: ${before.status} → ${after.status}`).toBe(
      before.status,
    )

    // Adversarial: cancel a build that does NOT exist — must be a clean
    // 404, not 500 / not silent 202. BuildDetailApi already enforces this.
    const ghostResp = await request.post(
      `${API_BASE}/api/v1/builds/99999999/cancel`,
      { headers: { Authorization: `Bearer ${token}` } },
    )
    expect(
      ghostResp.status(),
      `cancel of nonexistent build returned ${ghostResp.status()}: ${await ghostResp.text()}`,
    ).toBe(404)
  })
})

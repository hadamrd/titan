/**
 * 61-stage-retry-replay-from-failed — golden coverage for the two failed-stage
 * restart affordances (closes #122).
 *
 * Endpoints under test (previously vitest-only — nothing proved the semantics
 * on the rig):
 *   - POST /api/v1/builds/{id}/stages/{stageId}/retry   (StageRetryApi, #744)
 *   - POST /api/v1/builds/{id}/replay-from-failed       (BuildReplayApi, #664)
 *
 * Both tests run a purpose-built job that fails at a known stage
 * (node-app-with-failing-test pattern, distilled: `unit` succeeds, `test`
 * exits 1 deterministically), then drive the restart affordance and assert
 * the engine's DOCUMENTED contract — the javadocs on StageRetryApi /
 * BuildReplayApi + the service ITs (StageRetryApiIT,
 * BuildServiceReplayFromFailedIT) are the source of truth, not assumptions.
 *
 * ── What the engine promises ────────────────────────────────────────────────
 * stage-retry (in-place, same build row):
 *   200 { type:"applied", buildId, stageId, resetNodeIds:[stage first, then
 *   descendants], taskId } — stage nodes reset to QUEUED, step nodes to
 *   PENDING with a bumped `attempt` (the #125 dispatch-generation supersede),
 *   build flips FAILED→RUNNING (finishedAt cleared), ORCHESTRATE/ADVANCE
 *   enqueued, prior successful stages untouched. Retrying a non-FAILED stage
 *   → 409. We assert re-execution via fresh timestamps + verdict
 *   recomputation — the observable contract — rather than internal attempt
 *   bookkeeping.
 *
 * replay-from-failed (fork, NEW build):
 *   201 full BuildDto of a fresh build of the same job, triggerType "replay",
 *   anchored on the FIRST stage that ended FAILED in declared YAML order;
 *   in the new build every stage upstream of the anchor materialises SKIPPED
 *   and the anchor onward re-executes from the parent's baked pipeline model.
 *   The parent build is never mutated.
 *
 * ── Engine bugs this spec caught (both FIXED — the tests below are their
 *    standing acceptance) ─────────────────────────────────────────────────
 * #125 (fixed) — stage-retry used to never re-execute the step: the
 *   reconciler folded the previous attempt's archived FAILED task back onto
 *   the freshly-reset node. Fixed by dispatch-generation supersede keyed on
 *   flow_nodes.attempt (retryStage now resets step nodes to PENDING and
 *   bumps attempt). The third test asserts honest re-execution via fresh
 *   startedAt timestamps.
 * #126 (fixed) — BUILD_RERUN used to read only the legacy flat
 *   `titan.user_roles` table, 403ing everyone on a fresh rig. It now
 *   resolves through the canonical ScopedAuthz chain (rbac_user_role →
 *   legacy fallback → realm floor from the JWT), so the rig dev user's
 *   ADMIN group clears the gate with ZERO DB seeds — this spec's old
 *   pg-direct user_roles seed/revoke workaround has been deleted, and its
 *   absence is itself the #126 regression oracle (a 403 on replay means
 *   the canonical chain broke again).
 *
 * Ownership + teardown: per-run unique job names; safeDeleteJobCascade (#59)
 * in finally. Deterministic waits only (bounded poll loops — house style of
 * specs 25/44).
 */
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'
import { safeDeleteJobCascade } from '../../fixtures/teardown-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const TERMINAL = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'CANCELLED', 'UNSTABLE', 'ERROR'])

// unit succeeds, test deterministically fails. dependsOn is load-bearing:
// replay-from-failed's SKIPPED-upstream leg follows the DAG's dependsOn
// edges (see 25-replay-from-node) — without the edge the upstream set is
// empty and the SKIPPED assertion would be vacuous.
const FAILING_PIPELINE = `agent: linux
stages:
  - stage: unit
    steps:
      - sh: echo unit ok
  - stage: test
    dependsOn: [unit]
    steps:
      - sh: echo "deterministic failure for spec 61" && exit 1
`

interface BuildDto {
  id: number
  jobId: number
  buildNumber: number
  status: string
  triggerType?: string
  startedAt?: string | null
  finishedAt?: string | null
}

interface FlowNodeDto {
  buildId: number
  nodeId: string
  nodeType: string
  status: string
  attempt?: number | null
  startedAt?: string | null
  completedAt?: string | null
}

interface RetryApplied {
  type: string
  buildId: number
  stageId: string
  resetNodeIds: string[]
  taskId: number
}

async function apiJson<T>(
  request: APIRequestContext,
  bearer: string,
  method: 'GET' | 'POST',
  path: string,
  body?: unknown,
): Promise<{ ok: boolean; status: number; body: T | null; raw: string }> {
  const r = await request.fetch(`${API_BASE}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${bearer}`,
      Accept: 'application/json',
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    },
    data: body !== undefined ? JSON.stringify(body) : undefined,
  })
  const raw = await r.text()
  let parsed: T | null = null
  try {
    parsed = raw ? (JSON.parse(raw) as T) : null
  } catch {
    // leave null
  }
  return { ok: r.ok(), status: r.status(), body: parsed, raw }
}

async function createFailingJob(
  request: APIRequestContext,
  bearer: string,
  fullName: string,
): Promise<number> {
  const created = await apiJson<{ id: number }>(request, bearer, 'POST', '/api/v1/jobs', {
    fullName,
    displayName: 'E2E stage-retry / replay-from-failed (#122)',
    pipelineScript: FAILING_PIPELINE,
    enabled: true,
  })
  expect(
    created.status,
    `POST /api/v1/jobs '${fullName}' failed: HTTP ${created.status} ${created.raw.slice(0, 400)}`,
  ).toBe(201)
  return created.body!.id
}

async function triggerBuild(
  request: APIRequestContext,
  bearer: string,
  jobId: number,
): Promise<number> {
  const r = await apiJson<{ buildId: number }>(
    request,
    bearer,
    'POST',
    `/api/v1/jobs/${jobId}/builds`,
    {},
  )
  expect(
    r.ok && (r.body?.buildId ?? 0) > 0,
    `POST /api/v1/jobs/${jobId}/builds failed: HTTP ${r.status} ${r.raw.slice(0, 400)}`,
  ).toBe(true)
  return r.body!.buildId
}

async function pollBuildUntilTerminal(
  request: APIRequestContext,
  bearer: string,
  buildId: number,
  budgetMs: number,
  accept: (b: BuildDto) => boolean = (b) => TERMINAL.has(b.status),
): Promise<BuildDto> {
  const deadline = Date.now() + budgetMs
  let last: BuildDto | null = null
  while (Date.now() < deadline) {
    const r = await apiJson<BuildDto>(request, bearer, 'GET', `/api/v1/builds/${buildId}`)
    if (r.ok && r.body) {
      last = r.body
      if (accept(last)) return last
    }
    await new Promise((res) => setTimeout(res, 1_000))
  }
  throw new Error(
    `build ${buildId} did not reach the awaited state in ${budgetMs}ms; ` +
      `last=${JSON.stringify(last)}`,
  )
}

async function fetchNodes(
  request: APIRequestContext,
  bearer: string,
  buildId: number,
): Promise<FlowNodeDto[]> {
  const r = await apiJson<FlowNodeDto[]>(request, bearer, 'GET', `/api/v1/builds/${buildId}/nodes`)
  expect(
    r.ok && Array.isArray(r.body),
    `GET /api/v1/builds/${buildId}/nodes failed: HTTP ${r.status} ${r.raw.slice(0, 400)}`,
  ).toBe(true)
  return r.body!
}

function nodeById(nodes: FlowNodeDto[], nodeId: string): FlowNodeDto {
  const n = nodes.find((x) => x.nodeId === nodeId)
  expect(
    n,
    `node '${nodeId}' missing — nodes were: ` +
      JSON.stringify(nodes.map((x) => ({ id: x.nodeId, type: x.nodeType, st: x.status }))),
  ).toBeTruthy()
  return n!
}

/** Run one failing build to terminal FAILED and return its pre-restart state. */
async function failedParentBuild(
  request: APIRequestContext,
  bearer: string,
  jobId: number,
): Promise<{ build: BuildDto; nodes: FlowNodeDto[] }> {
  const buildId = await triggerBuild(request, bearer, jobId)
  const final = await pollBuildUntilTerminal(request, bearer, buildId, 120_000)
  expect(
    final.status,
    `parent build ${buildId} must end FAILED (the 'test' stage exits 1 by design); got ` +
      `${final.status}. If this stops being deterministic the whole spec's precondition is gone.`,
  ).toBe('FAILED')
  const nodes = await fetchNodes(request, bearer, buildId)
  expect(nodeById(nodes, 'unit').status, `stage 'unit' must SUCCEED pre-restart`).toBe('SUCCESS')
  expect(nodeById(nodes, 'test').status, `stage 'test' must FAIL pre-restart`).toBe('FAILED')
  return { build: final, nodes }
}

test.describe('v3 stage-retry + replay-from-failed @golden (closes #122)', () => {
  test('stage-retry: 409 on non-FAILED stage; 200 applied resets only the failed stage; sibling untouched; verdict recomputed', async ({
    request,
  }) => {
    test.setTimeout(4 * 60_000)
    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
    const bearer = await fetchBearerToken(ENV)
    let jobId: number | undefined
    try {
      jobId = await createFailingJob(request, bearer, `e2e-stage-retry-${runTag}`)
      const parent = await failedParentBuild(request, bearer, jobId)
      const buildId = parent.build.id
      const unitBefore = nodeById(parent.nodes, 'unit')
      const unitStepBefore = nodeById(parent.nodes, 'unit-s0')
      const finishedBefore = parent.build.finishedAt
      expect(finishedBefore, 'terminal parent must carry finishedAt').toBeTruthy()

      // ── 409 precondition guard (documented: "409 — stage is not in FAILED
      // state") — probed BEFORE the real retry so ordering is deterministic.
      const conflict = await apiJson<{ error: string; message: string }>(
        request,
        bearer,
        'POST',
        `/api/v1/builds/${buildId}/stages/unit/retry`,
      )
      expect(
        conflict.status,
        `retrying the SUCCESS stage 'unit' must 409, got HTTP ${conflict.status} ` +
          `${conflict.raw.slice(0, 300)} — an inadvertent retry of a green stage is the ` +
          `adversarial case the precondition exists for`,
      ).toBe(409)
      expect(conflict.body?.error).toBe('conflict')
      expect(conflict.body?.message ?? '').toContain('not in FAILED state')

      // ── the real retry: 200 { type:"applied", … } with the failed stage
      // first in resetNodeIds and ONLY that stage's subtree reset.
      const retry = await apiJson<RetryApplied>(
        request,
        bearer,
        'POST',
        `/api/v1/builds/${buildId}/stages/test/retry`,
      )
      expect(
        retry.status,
        `POST /stages/test/retry must 200: HTTP ${retry.status} ${retry.raw.slice(0, 300)}`,
      ).toBe(200)
      const applied = retry.body!
      expect(applied.type).toBe('applied')
      expect(applied.buildId).toBe(buildId)
      expect(applied.stageId).toBe('test')
      expect(
        applied.resetNodeIds[0],
        'documented: the retried stage is always FIRST in resetNodeIds',
      ).toBe('test')
      expect(
        applied.resetNodeIds,
        `the failed stage's step must be reset with it`,
      ).toContain('test-s0')
      expect(
        applied.resetNodeIds,
        `'unit' SUCCEEDED and is upstream — it must NEVER be reset by a retry of 'test'`,
      ).not.toContain('unit')
      expect(
        applied.resetNodeIds,
        `'unit-s0' belongs to the successful sibling — must not be reset`,
      ).not.toContain('unit-s0')
      expect(applied.taskId, 'an ORCHESTRATE/ADVANCE task id must be returned').toBeGreaterThan(0)

      // ── verdict recomputed: the build left its terminal state (finishedAt
      // was cleared by the retry) and re-completed with a FRESH finishedAt.
      // Poll for terminal-with-new-finishedAt: catching the transient RUNNING
      // window is a race we don't bet on; the fresh timestamp proves the
      // FAILED→RUNNING→terminal cycle happened.
      const after = await pollBuildUntilTerminal(
        request,
        bearer,
        buildId,
        120_000,
        (b) => TERMINAL.has(b.status) && !!b.finishedAt && b.finishedAt !== finishedBefore,
      )
      expect(
        Date.parse(after.finishedAt!),
        `post-retry finishedAt (${after.finishedAt}) must be strictly later than the ` +
          `pre-retry one (${finishedBefore}) — the verdict was recomputed, not carried over`,
      ).toBeGreaterThan(Date.parse(finishedBefore!))
      // Today the stage deterministically fails again, so the recomputed
      // verdict is FAILED. (Kept status-agnostic beyond "terminal" on purpose:
      // when #125 lands the step actually re-runs and STILL exits 1, so this
      // stays FAILED — but the load-bearing assertion is the fresh verdict.)
      expect(TERMINAL.has(after.status)).toBe(true)

      // ── successful sibling untouched: identical status + timing, and the
      // retried stage's subtree is the only thing that moved.
      const nodesAfter = await fetchNodes(request, bearer, buildId)
      const unitAfter = nodeById(nodesAfter, 'unit')
      const unitStepAfter = nodeById(nodesAfter, 'unit-s0')
      for (const [before, now, label] of [
        [unitBefore, unitAfter, 'unit'],
        [unitStepBefore, unitStepAfter, 'unit-s0'],
      ] as const) {
        expect(now.status, `${label} status must stay SUCCESS across the retry`).toBe('SUCCESS')
        expect(
          now.startedAt,
          `${label}.startedAt changed across the retry — the engine touched a successful sibling`,
        ).toBe(before.startedAt)
        expect(
          now.completedAt,
          `${label}.completedAt changed across the retry — the engine touched a successful sibling`,
        ).toBe(before.completedAt)
      }
      const testAfter = nodeById(nodesAfter, 'test')
      expect(
        testAfter.completedAt,
        `the retried stage must carry a fresh completion stamp`,
      ).not.toBe(nodeById(parent.nodes, 'test').completedAt)
    } finally {
      if (jobId !== undefined) await safeDeleteJobCascade(request, jobId)
    }
  })

  // Acceptance for the #125 fix: pre-fix, the orchestrator's reconciler
  // re-folded the PREVIOUS attempt's archived FAILED task onto the reset node
  // (no new EXECUTE_COMMAND, startedAt stayed NULL). Now the retry bumps the
  // node's dispatch generation and the stale task is superseded — the step
  // genuinely re-executes, proven by fresh startedAt timestamps below.
  test(
    'stage-retry actually RE-EXECUTES the failed stage — fresh dispatch, stale archived task superseded, not re-folded',
    async ({ request }) => {
      test.setTimeout(4 * 60_000)
      const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
      const bearer = await fetchBearerToken(ENV)
      let jobId: number | undefined
      try {
        jobId = await createFailingJob(request, bearer, `e2e-stage-reexec-${runTag}`)
        const parent = await failedParentBuild(request, bearer, jobId)
        const buildId = parent.build.id
        const finishedBefore = parent.build.finishedAt

        const retry = await apiJson<RetryApplied>(
          request,
          bearer,
          'POST',
          `/api/v1/builds/${buildId}/stages/test/retry`,
        )
        expect(retry.status).toBe(200)

        await pollBuildUntilTerminal(
          request,
          bearer,
          buildId,
          120_000,
          (b) => TERMINAL.has(b.status) && !!b.finishedAt && b.finishedAt !== finishedBefore,
        )

        // Re-execution proof: an executed step ALWAYS carries startedAt.
        // Under #125 the reconciler folds the stale task onto the reset node
        // without running anything, leaving startedAt NULL — that is the bug.
        const nodesAfter = await fetchNodes(request, bearer, buildId)
        const step = nodeById(nodesAfter, 'test-s0')
        expect(
          step.startedAt,
          `step 'test-s0' has no startedAt after the retry re-completed — the engine folded ` +
            `the previous attempt's result back instead of re-dispatching the work (bug #125). ` +
            `Full node: ${JSON.stringify(step)}`,
        ).toBeTruthy()
        const stage = nodeById(nodesAfter, 'test')
        expect(
          Date.parse(stage.startedAt ?? '') >
            Date.parse(nodeById(parent.nodes, 'test').completedAt ?? ''),
          `retried stage 'test' must have started AFTER the first attempt completed`,
        ).toBe(true)
      } finally {
        if (jobId !== undefined) await safeDeleteJobCascade(request, jobId)
      }
    },
  )

  test('replay-from-failed: 201 forks a NEW replay build anchored on the failed stage — upstream SKIPPED, anchor re-executed, parent untouched', async ({
    request,
  }) => {
    test.setTimeout(4 * 60_000)
    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
    const bearer = await fetchBearerToken(ENV)
    let jobId: number | undefined
    try {
      jobId = await createFailingJob(request, bearer, `e2e-replay-failed-${runTag}`)
      const parent = await failedParentBuild(request, bearer, jobId)
      const parentId = parent.build.id

      // ── 201 + full BuildDto of a genuinely NEW build (documented contract:
      // BuildServiceReplayFromFailedIT — QUEUED, triggerType "replay", same
      // job, anchored on the FIRST failed stage in declared order).
      const replayResp = await apiJson<BuildDto>(
        request,
        bearer,
        'POST',
        `/api/v1/builds/${parentId}/replay-from-failed`,
        {},
      )
      expect(
        replayResp.status,
        `POST /replay-from-failed must 201: HTTP ${replayResp.status} ` +
          `${replayResp.raw.slice(0, 300)}. A 403 here means BUILD_RERUN's canonical ` +
          `rbac_user_role/realm-floor resolution regressed (#126) — this test runs with ` +
          `ZERO DB role seeds on purpose.`,
      ).toBe(201)
      const replay = replayResp.body!
      expect(replay.id, 'replay must be a NEW build, never the parent').not.toBe(parentId)
      expect(replay.jobId, 'replay must belong to the SAME job').toBe(parent.build.jobId)
      expect(
        replay.buildNumber,
        'replay gets the next build number of the job',
      ).toBeGreaterThan(parent.build.buildNumber)
      expect(replay.status, 'replay starts life QUEUED').toBe('QUEUED')
      expect(replay.triggerType, 'replay builds carry triggerType "replay"').toBe('replay')

      // ── the replay runs: upstream-of-anchor SKIPPED, anchor re-executed.
      const replayFinal = await pollBuildUntilTerminal(request, bearer, replay.id, 120_000)
      const replayNodes = await fetchNodes(request, bearer, replay.id)
      for (const upstreamId of ['unit', 'unit-s0']) {
        expect(
          nodeById(replayNodes, upstreamId).status,
          `'${upstreamId}' succeeded on the parent and is upstream of the failed anchor — the ` +
            `replay must materialise it SKIPPED, not re-run it`,
        ).toBe('SKIPPED')
      }
      const anchor = nodeById(replayNodes, 'test')
      expect(anchor.status, `the failed anchor stage must RE-RUN in the replay`).not.toBe('SKIPPED')
      expect(
        anchor.startedAt,
        `anchor 'test' has no startedAt in the replay — it never actually executed. ` +
          `Full node: ${JSON.stringify(anchor)}`,
      ).toBeTruthy()
      // The step still deterministically exits 1, so the replay's verdict is
      // its OWN fresh FAILED — proving the anchor genuinely executed rather
      // than having the parent's result copied over.
      expect(replayFinal.status).toBe('FAILED')
      expect(
        nodeById(replayNodes, 'test-s0').startedAt,
        `anchor step 'test-s0' must have executed in the replay`,
      ).toBeTruthy()

      // ── parent untouched: replay is a fork, never an in-place mutation.
      const parentAfter = await apiJson<BuildDto>(request, bearer, 'GET', `/api/v1/builds/${parentId}`)
      expect(parentAfter.body?.status).toBe('FAILED')
      expect(
        parentAfter.body?.finishedAt,
        `parent.finishedAt changed — replay-from-failed must never mutate the parent build`,
      ).toBe(parent.build.finishedAt)
    } finally {
      if (jobId !== undefined) await safeDeleteJobCascade(request, jobId)
    }
  })
})

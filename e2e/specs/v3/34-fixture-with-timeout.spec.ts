/**
 * 34-fixture-with-timeout — a hung step under `timeout:` is KILLED, not left
 * RUNNING forever (closes #105).
 *
 * `timeout:` is a shipped CI-core PDL primitive (TimeoutScope parses
 * `30s/5m/2h/1d` at step + stage level) but until this spec NOTHING drove the
 * enforcement path on the rig. The failure mode it guards is the worst kind
 * for a CI product: a hung `sh` step that is never killed leaves a build
 * RUNNING forever (the rot class specs 18-no-stuck-running and the SRE-3am
 * journey exist to catch — but nothing exercised the timeout path
 * deliberately).
 *
 * The production chain under test (all four hops must work):
 *   1. `TimeoutEnforcer.armTimeout` — orchestrator arms a TIMEOUT timer when
 *      the step is dispatched (TitanOrchestrator L647).
 *   2. `TimerSweepWorker` (5s cadence) — claims the due timer, enqueues an
 *      ADVANCE, marks it FIRED.
 *   3. `TimeoutEnforcer.enforceTimeouts` — on the next tick, CASes the node
 *      QUEUED → FAILED with failureCategory=TIMEOUT and
 *      failureReason="step exceeded its timeout", and cancels the worker's
 *      task_queue row (status → CANCELLED).
 *   4. The worker's 2s cancel poll (LocalProcessExecutor) sees CANCELLED and
 *      escalates SIGTERM → SIGKILL on the process tree, logging
 *      "titan: step cancelled — sending SIGTERM".
 *
 * Oracles (strongest observables, per issue #105 — no weakened smoke-checks):
 *   - Build reaches terminal FAILED within a 90s poll budget. The step sleeps
 *     300s, so REACHING TERMINAL FAST **IS** THE ASSERTION — a build that
 *     only fails after ~300s means the timeout never fired and the `sh` ran
 *     to completion. We additionally record + assert the observed
 *     kill-latency explicitly.
 *   - The timed-out flow node is FAILED with failureCategory === 'TIMEOUT'
 *     and failureReason containing the exact enforcer message
 *     "step exceeded its timeout" (TimeoutEnforcer L64).
 *   - The step's log stream carries the worker's kill line
 *     "titan: step cancelled — sending SIGTERM". This is the ONLY observable
 *     that proves the sleep-300 process was actually killed on the worker:
 *     `TaskQueueDao.cancel` flips the row to CANCELLED server-side
 *     immediately, so task_queue state alone would pass even if the worker
 *     leaked the process (the #58 lesson). The log line is written by
 *     LocalProcessExecutor.terminateGracefully at the moment of the kill.
 *
 * Adversarial second test (issue #105): a sibling pipeline whose step carries
 * `timeout: 5m` and `sh: exit 0` still SUCCEEDS — the timeout must not fire
 * early. Because this file runs sequentially after the kill test (workers=1,
 * fullyParallel=false), the second build completing ALSO proves the worker
 * executor slot was freed by the kill — no leaked slot (#58).
 *
 * Fixture shape: purpose-built inline pipelineScript via POST /api/v1/jobs
 * (issue #105 calls for exactly this — NOT the shared fixture repo; the
 * vendored e2e/fixtures/titan-e2e-fixture/ mirror is byte-identical to
 * upstream and must not grow repo-local files).
 *
 * Trigger path: test 1 synthesizes + HMAC-signs a real `push` webhook (the
 * 26/27/32 family pattern); test 2 uses the manual POST /jobs/{id}/builds
 * trigger (the webhook path is already exercised by test 1 — spec 29
 * precedent).
 *
 * Teardown: safeDeleteJobCascade per #65 — cancel-then-drain-then-delete,
 * never yanking a leased task_queue row from under the worker. Per-run
 * RUN_TAG job names mean nothing collides across reruns.
 */
import * as crypto from 'node:crypto'
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'
import { safeDeleteJobCascade } from '../../fixtures/teardown-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const RUN_TAG = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
const HUNG_JOB_FULL_NAME = `e2e-with-timeout-hung-${RUN_TAG}`
const HAPPY_JOB_FULL_NAME = `e2e-with-timeout-happy-${RUN_TAG}`
const CREDENTIAL_KEY = `e2e-with-timeout-${RUN_TAG}`
const WEBHOOK_SECRET = `s3cr3t-${RUN_TAG}`

// The webhook payload's repo/branch — same synthetic identity the 26–32
// family uses; the job matches on its trigger config + HMAC credential, and
// the inline pipelineScript means nothing is ever cloned from it.
const FIXTURE_REPO = 'hadamrd/titan-e2e-fixture'
const FIXTURE_BRANCH = 'main'

/**
 * The hung pipeline: `sleep 300` under a 10s deadline. 300s dwarfs every
 * budget in this spec — if the build only terminates once the sleep exits,
 * the 90s terminal poll fails and the kill-latency assertion fails, loudly.
 */
const HUNG_PIPELINE_YAML = `# Purpose-built for e2e spec 34 (issue #105): hung step under a short timeout.
agent: linux

stages:
  - stage: Hang
    steps:
      - sh: "sleep 300"
        timeout: 10s
`

/**
 * The adversarial sibling: a trivially-succeeding step under a GENEROUS 5m
 * deadline. If the enforcer ever fires a timer early (or fires timers for the
 * wrong node), this build goes FAILED and the test catches it.
 */
const HAPPY_PIPELINE_YAML = `# Purpose-built for e2e spec 34 (issue #105): timeout must NOT fire early.
agent: linux

stages:
  - stage: Quick
    steps:
      - sh: "exit 0"
        timeout: 5m
`

// The exact strings the production code writes — assert content, not vibes.
const ENFORCER_REASON = 'step exceeded its timeout' // TimeoutEnforcer L64
const WORKER_KILL_LINE = 'titan: step cancelled — sending SIGTERM' // LocalProcessExecutor

const TERMINAL_STATUSES = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'UNSTABLE', 'ERROR'])

interface CredentialCreateResp {
  id: number
}
interface JobCreateResp {
  id: number
}
interface TriggerBuildResp {
  buildId: number
}
interface BuildListItem {
  id: number
  status: string
}
interface BuildsPage {
  items: BuildListItem[]
}
interface BuildDetail {
  id: number
  status: string
}
// Field names mirror FlowNodeDto (titan-server api/dto/FlowNodeDto.java).
interface FlowNode {
  nodeId: string
  nodeType?: string | null
  displayName?: string | null
  stepDescriptor?: string | null
  status?: string | null
  attempt?: number
  failureCategory?: string | null
  failureReason?: string | null
  logTaskId?: string | null
}

async function apiGet<T>(
  request: APIRequestContext,
  bearer: string,
  path: string,
): Promise<{ ok: boolean; status: number; body: T | null; raw: string }> {
  const r = await request.get(`${API_BASE}${path}`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  const raw = await r.text()
  let body: T | null = null
  try {
    body = JSON.parse(raw) as T
  } catch {
    // leave null
  }
  return { ok: r.ok(), status: r.status(), body, raw }
}

async function createJob(
  request: APIRequestContext,
  bearer: string,
  payload: Record<string, unknown>,
): Promise<number> {
  const resp = await request.post(`${API_BASE}/api/v1/jobs`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: payload,
  })
  const raw = await resp.text()
  expect(
    resp.status(),
    `POST /api/v1/jobs HTTP ${resp.status()} body=${raw.slice(0, 600)}`,
  ).toBe(201)
  const id = (JSON.parse(raw) as JobCreateResp).id
  expect(id).toBeGreaterThan(0)
  return id
}

/** Poll GET /builds/{id} until terminal; returns the terminal status. */
async function pollTerminal(
  request: APIRequestContext,
  bearer: string,
  buildId: number,
  budgetMs: number,
  why: string,
): Promise<string> {
  let observed = ''
  await expect
    .poll(
      async () => {
        const r = await apiGet<BuildDetail>(request, bearer, `/api/v1/builds/${buildId}`)
        if (!r.ok || !r.body) return ''
        observed = r.body.status
        return TERMINAL_STATUSES.has(observed) ? observed : ''
      },
      {
        message:
          `build ${buildId} did not reach a terminal status within ${budgetMs}ms ` +
          `(last observed "${observed}") — ${why}`,
        timeout: budgetMs,
        intervals: [500, 1_000, 2_000, 3_000],
      },
    )
    .not.toBe('')
  return observed
}

test.describe('v3 fixture-with-timeout @golden', () => {
  test('hung step (sleep 300, timeout 10s) is killed: FAILED fast, TIMEOUT-marked, worker SIGTERMs the process', async ({
    request,
  }) => {
    test.setTimeout(240_000)

    let bearer: string | undefined
    let credentialId: number | undefined
    let jobId: number | undefined
    let buildId: number | undefined
    let attached = false

    const dump = async (label: string) => {
      if (attached) return
      attached = true
      try {
        if (bearer && buildId) {
          const build = await apiGet(request, bearer, `/api/v1/builds/${buildId}`)
          await test.info().attach(`build-${label}.json`, {
            body: build.raw,
            contentType: 'application/json',
          })
          const nodes = await apiGet(request, bearer, `/api/v1/builds/${buildId}/nodes`)
          await test.info().attach(`flow-nodes-${label}.json`, {
            body: nodes.raw,
            contentType: 'application/json',
          })
        }
        if (bearer && jobId) {
          const job = await apiGet(request, bearer, `/api/v1/jobs/${jobId}`)
          await test.info().attach(`job-${label}.json`, {
            body: job.raw,
            contentType: 'application/json',
          })
        }
      } catch (e) {
        await test.info().attach('diagnostics-error.txt', {
          body: String(e),
          contentType: 'text/plain',
        })
      }
    }

    try {
      // ── 1. Bearer via ROPC. ────────────────────────────────────────────
      bearer = await fetchBearerToken(ENV)

      // ── 2. HMAC credential for the webhook (STRING per #793). ─────────
      const credCreate = await request.post(`${API_BASE}/api/v1/credentials`, {
        headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
        data: {
          kind: 'STRING',
          scope: 'github-webhook',
          key: CREDENTIAL_KEY,
          plaintext: WEBHOOK_SECRET,
        },
      })
      const credRaw = await credCreate.text()
      expect(
        credCreate.status(),
        `POST /api/v1/credentials HTTP ${credCreate.status()} body=${credRaw.slice(0, 400)}`,
      ).toBe(201)
      credentialId = (JSON.parse(credRaw) as CredentialCreateResp).id

      // ── 3. Purpose-built job: inline hung pipeline + github trigger. ──
      jobId = await createJob(request, bearer, {
        fullName: HUNG_JOB_FULL_NAME,
        displayName: 'E2E with-timeout: hung step',
        pipelineScript: HUNG_PIPELINE_YAML,
        configJson: JSON.stringify({
          triggers: [
            {
              type: 'github',
              id: 'github-1',
              branches: [FIXTURE_BRANCH],
              events: ['push'],
              credentialsId: CREDENTIAL_KEY,
            },
          ],
        }),
        enabled: true,
      })

      // ── 4. Synthesize + HMAC-sign a real `push` webhook. ──────────────
      const pushPayload = {
        ref: `refs/heads/${FIXTURE_BRANCH}`,
        before: '0'.repeat(40),
        after: 'e'.repeat(40),
        repository: { full_name: FIXTURE_REPO, default_branch: FIXTURE_BRANCH },
        pusher: { name: 'e2e-bot' },
        head_commit: { id: 'e'.repeat(40), message: 'e2e with-timeout push' },
      }
      const bodyBytes = Buffer.from(JSON.stringify(pushPayload), 'utf8')
      const sig =
        'sha256=' + crypto.createHmac('sha256', WEBHOOK_SECRET).update(bodyBytes).digest('hex')
      const webhookSentAt = Date.now()
      const webhookResp = await request.post(`${API_BASE}/api/v1/triggers/github`, {
        headers: {
          'Content-Type': 'application/json',
          'X-GitHub-Event': 'push',
          'X-Hub-Signature-256': sig,
          'X-GitHub-Delivery': `e2e-timeout-${RUN_TAG}`,
        },
        data: bodyBytes,
      })
      const webhookRaw = await webhookResp.text()
      expect(
        webhookResp.status(),
        `POST /api/v1/triggers/github HTTP ${webhookResp.status()} body=${webhookRaw}`,
      ).toBe(200)
      const webhookBody = JSON.parse(webhookRaw) as { dispatched: boolean; detail: string }
      expect(
        webhookBody.dispatched,
        `webhook accepted but dispatched=false (detail="${webhookBody.detail}")`,
      ).toBe(true)

      // ── 5. Build appears. ──────────────────────────────────────────────
      await expect
        .poll(
          async () => {
            const r = await apiGet<BuildsPage>(
              request,
              bearer!,
              `/api/v1/jobs/${jobId}/builds?offset=0&limit=20`,
            )
            const first = r.body?.items?.[0]
            if (first) {
              buildId = first.id
              return true
            }
            return false
          },
          {
            message: `no build appeared on job ${jobId} within 30s of webhook POST`,
            timeout: 30_000,
            intervals: [500, 1_000, 2_000],
          },
        )
        .toBe(true)

      // ── 6. Terminal within 90s — reaching terminal FAST is the assertion.
      // The step sleeps 300s. If the timeout chain (arm → sweep → enforce →
      // cancel) is broken, the build stays RUNNING until the sleep exits at
      // ~300s and this poll fails at 90s — exactly the regression signal.
      const finalStatus = await pollTerminal(
        request,
        bearer,
        buildId!,
        90_000,
        `the step sleeps 300s under timeout: 10s — a >90s terminal latency means ` +
          `the TIMEOUT timer never fired (TimeoutEnforcer/TimerSweepWorker chain broken)`,
      )
      const killLatencyMs = Date.now() - webhookSentAt
      await test.info().attach('kill-latency.txt', {
        body:
          `kill latency (webhook POST → terminal build status): ${killLatencyMs}ms\n` +
          `declared timeout: 10s; step sleep: 300s`,
        contentType: 'text/plain',
      })
      console.log(`[spec-34] observed kill latency: ${killLatencyMs}ms (timeout 10s, sleep 300s)`)

      // ── 7. HARD assert FAILED — a timed-out step fails the build. ──────
      expect(
        finalStatus,
        `build ${buildId} terminated ${finalStatus}, expected FAILED. SUCCESS would mean ` +
          `the sleep ran to completion or was skipped; ABORTED/ERROR means something other ` +
          `than the timeout path terminated it. Diagnostics attached.`,
      ).toBe('FAILED')

      // Belt-and-braces on the latency: terminal in << 300s (the sleep). The
      // 90s poll already enforces this; the explicit assert keeps the
      // intent survivable if someone ever loosens the poll budget.
      expect(
        killLatencyMs,
        `build went terminal after ${killLatencyMs}ms — that is NOT a timeout kill, ` +
          `that is the sleep(300s) running to completion.`,
      ).toBeLessThan(120_000)

      // ── 8. HARD assert the node is TIMEOUT-failed with the exact reason.
      const nodesResp = await apiGet<FlowNode[]>(
        request,
        bearer,
        `/api/v1/builds/${buildId}/nodes`,
      )
      expect(nodesResp.ok, `GET /builds/${buildId}/nodes HTTP ${nodesResp.status}`).toBe(true)
      const nodes = nodesResp.body ?? []
      expect(nodes.length, 'flow_nodes empty — orchestrator never baked the DAG').toBeGreaterThan(
        0,
      )

      const timedOut = nodes.filter((n) => n.failureCategory === 'TIMEOUT')
      expect(
        timedOut.length,
        `no flow_node carries failureCategory=TIMEOUT — the enforcer either never fired or ` +
          `failed the node under a different category. Nodes seen: ` +
          JSON.stringify(
            nodes.map((n) => ({
              nodeId: n.nodeId,
              type: n.nodeType,
              status: n.status,
              failureCategory: n.failureCategory,
              failureReason: n.failureReason,
            })),
          ),
      ).toBe(1)

      const hungNode = timedOut[0]!
      expect(
        hungNode.status,
        `TIMEOUT-categorised node ${hungNode.nodeId} has status=${hungNode.status}, expected FAILED`,
      ).toBe('FAILED')
      expect(
        hungNode.failureReason ?? '',
        `node ${hungNode.nodeId} failureReason="${hungNode.failureReason}" — expected the ` +
          `exact TimeoutEnforcer message. If the enforcer's wording changed, update BOTH ` +
          `(it is user-facing SRE triage copy, not an incidental string).`,
      ).toContain(ENFORCER_REASON)
      // A timed-out step must NOT be retried (TimeoutEnforcer javadoc): a hung
      // step that burned its full deadline must not silently burn another.
      expect(
        hungNode.attempt ?? 1,
        `timed-out node was re-attempted (attempt=${hungNode.attempt}) — timeout failures ` +
          `must be terminal, never retried`,
      ).toBe(1)

      // ── 9. HARD assert the worker ACTUALLY KILLED the process. ─────────
      // task_queue CANCELLED alone is written server-side by the enforcer and
      // proves nothing about the worker (the #58 leaked-slot lesson). The
      // worker's LocalProcessExecutor writes the SIGTERM line into the step
      // log at the moment of the kill — poll the real per-step log endpoint
      // (SSE; drains + closes once the build is terminal) until it appears.
      expect(
        hungNode.logTaskId,
        `timed-out node ${hungNode.nodeId} has no logTaskId — cannot verify the worker-side kill`,
      ).toBeTruthy()
      let lastLog = ''
      await expect
        .poll(
          async () => {
            const logResp = await request.get(
              `${API_BASE}/api/v1/builds/${buildId}/logs?taskId=${encodeURIComponent(
                hungNode.logTaskId!,
              )}`,
              {
                headers: { Authorization: `Bearer ${bearer}`, Accept: 'text/event-stream' },
              },
            )
            lastLog = await logResp.text()
            return logResp.ok() && lastLog.includes(WORKER_KILL_LINE)
          },
          {
            message:
              `step log for node ${hungNode.nodeId} (taskId=${hungNode.logTaskId}) never showed ` +
              `"${WORKER_KILL_LINE}" within 60s of the build going terminal. The build is FAILED ` +
              `but the worker never killed the process — the sleep(300) is LEAKED on the worker ` +
              `and its executor slot is burned (#58 class). Last log frame:\n` +
              lastLog.slice(-2_000),
            timeout: 60_000,
            intervals: [1_000, 2_000, 3_000],
          },
        )
        .toBe(true)
    } catch (err) {
      await dump('assertion-failure')
      throw err
    } finally {
      // Teardown per #65: cancel-then-drain-then-delete, scoped to OUR job.
      if (jobId !== undefined) {
        const res = await safeDeleteJobCascade(request, jobId).catch((e) => {
          console.warn(`[spec-34] safeDeleteJobCascade(${jobId}) threw: ${String(e)}`)
          return null
        })
        if (res && !res.deleted) {
          console.warn(
            `[spec-34] teardown left rows for job ${jobId} (builds ${res.leftoverBuildIds.join(
              ', ',
            )}) — leases never drained`,
          )
        }
      }
      if (bearer && credentialId !== undefined) {
        await request
          .delete(`${API_BASE}/api/v1/credentials/${credentialId}`, {
            headers: { Authorization: `Bearer ${bearer}` },
          })
          .catch(() => undefined)
      }
    }
  })

  test('adversarial sibling: timeout 5m + exit 0 SUCCEEDS — no early fire, executor slot freed after the kill', async ({
    request,
  }) => {
    test.setTimeout(180_000)

    let bearer: string | undefined
    let jobId: number | undefined
    let buildId: number | undefined

    try {
      bearer = await fetchBearerToken(ENV)

      // Manual trigger path (spec 29 precedent) — the webhook path is already
      // exercised by test 1; this test isolates timeout-arming semantics.
      jobId = await createJob(request, bearer, {
        fullName: HAPPY_JOB_FULL_NAME,
        displayName: 'E2E with-timeout: happy sibling',
        pipelineScript: HAPPY_PIPELINE_YAML,
        enabled: true,
      })

      const triggerResp = await request.post(`${API_BASE}/api/v1/jobs/${jobId}/builds`, {
        headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
        data: { triggeredBy: 'e2e-spec-34' },
      })
      const triggerRaw = await triggerResp.text()
      expect(
        triggerResp.status(),
        `POST /api/v1/jobs/${jobId}/builds HTTP ${triggerResp.status()} body=${triggerRaw.slice(0, 400)}`,
      ).toBe(201)
      buildId = (JSON.parse(triggerRaw) as TriggerBuildResp).buildId
      expect(buildId).toBeGreaterThan(0)

      // 90s budget. Two distinct regressions land here:
      //  (a) timeout fires early / for the wrong node → terminal FAILED below;
      //  (b) test 1's kill leaked the worker slot (#58) → this build never
      //      gets picked up and the poll times out. This file runs
      //      sequentially after the kill test (workers=1, fullyParallel=false),
      //      so a completing build here IS the freed-slot proof.
      const finalStatus = await pollTerminal(
        request,
        bearer,
        buildId,
        90_000,
        `either the worker slot was leaked by the previous timeout-kill (#58 class) ` +
          `or the engine is stuck`,
      )
      expect(
        finalStatus,
        `build ${buildId} terminated ${finalStatus}, expected SUCCESS. FAILED here means the ` +
          `5m timeout fired early (or fired for the wrong node) on a step that exits ` +
          `immediately — enforcer arming/matching bug.`,
      ).toBe('SUCCESS')

      // No node may carry a TIMEOUT failure on a successful build.
      const nodesResp = await apiGet<FlowNode[]>(
        request,
        bearer,
        `/api/v1/builds/${buildId}/nodes`,
      )
      expect(nodesResp.ok, `GET /builds/${buildId}/nodes HTTP ${nodesResp.status}`).toBe(true)
      const stray = (nodesResp.body ?? []).filter((n) => n.failureCategory === 'TIMEOUT')
      expect(
        stray.length,
        `SUCCESS build carries TIMEOUT-categorised node(s): ${JSON.stringify(stray)}`,
      ).toBe(0)
    } finally {
      if (jobId !== undefined) {
        const res = await safeDeleteJobCascade(request, jobId).catch((e) => {
          console.warn(`[spec-34] safeDeleteJobCascade(${jobId}) threw: ${String(e)}`)
          return null
        })
        if (res && !res.deleted) {
          console.warn(
            `[spec-34] teardown left rows for job ${jobId} (builds ${res.leftoverBuildIds.join(
              ', ',
            )}) — leases never drained`,
          )
        }
      }
    }
  })
})

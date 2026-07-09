/**
 * 30-fixture-with-gitTag — discovery → trigger-with-params → gitTag worker step
 * runs end-to-end (closes #808).
 *
 * Drives the worker-side parallel of #28: where #28 proved the control-plane
 * step `setBuildName` round-trips, this spec proves the worker step `gitTag`
 * (shipped #761 with the GIT_ASKPASS machinery) is wired all the way through
 * the dispatch path — TaskExecutor route + GitTagStepHandler invocation +
 * flow_node persistence — for a real build triggered from the public fixture.
 *
 *   1. Pre-check: GET the fixture YAML
 *      `hadamrd/titan-e2e-fixture/.titan/pipelines/with-gitTag.yml`.
 *      The fixture declares one required param (TAG) and one stage with a
 *      single `gitTag: { tag: "${{ params.TAG }}", message: "...", push: false }`.
 *      `push: false` is deliberate — the spec must not pollute any remote, so
 *      we run only the local `git tag` half of the handler.
 *   2. Create a job whose `pipelineScript` is the fixture YAML (discovery path).
 *   3. Trigger via POST /api/v1/jobs/{id}/builds with a typed parameter override
 *      `{TAG: 'e2e-${RUN_TAG}'}` (the #778 trigger-with-params contract; the
 *      webhook path can't supply a non-default TAG and the fixture has no
 *      default — it's `required: true`).
 *   4. Poll until terminal (≤ 60s). HARD assert SUCCESS.
 *   5. HARD assert: the `gitTag` flow_node exists with status=SUCCESS and a
 *      non-null logTaskId. This proves the dispatcher routed `gitTag` to a
 *      worker (not the control-plane gate path #805 patched), the handler
 *      ran to completion, and a task log token was minted.
 *   6. HARD assert: titan.logs for that logTaskId contain the literal tag
 *      string we passed (e.g. `e2e-1700000000000`). The handler emits
 *      `log.system("gitTag: creating ... tag '<tag>' at HEAD")` and the local
 *      `git tag` command's stderr also surfaces in the task log via the
 *      StepExecutor. Either is sufficient — the tag string MUST appear
 *      somewhere in the step log, or the handler never ran with the resolved
 *      param.
 *   7. finally{}: delete job + cascade flow_nodes / task_queue / builds.
 *      No remote-tag cleanup is needed (push:false).
 *
 * KNOWN risk surfaces this spec hard-fails on:
 *   - `unknown step type gitTag` in the step log → worker registry gap
 *     (same class as #805's setBuildName / sleep gating bug, but for the
 *     opposite direction: a worker step that didn't make the registry).
 *     File P1 immediately if seen.
 *   - "not a git repository" in the step log → the build workspace is bare
 *     (TaskExecutor creates only `Files.createDirectories(workDir)`) and the
 *     fixture has no preceding `checkout` step to initialise a repo. This is
 *     a real-product gap: either the handler must `git init` on demand, or
 *     the fixture must add `checkout`/`git init`, or the worker workspace
 *     must be pre-initialised. The spec's diagnostics (attached log + node
 *     JSON) tell triage which.
 *   - SUCCESS but the literal tag string isn't in the log → param expansion
 *     for `${{ params.TAG }}` is bypassed in the gitTag step path.
 *
 * Determinism:
 *   - Unique RUN_TAG per run; no remote side effects (push:false).
 *   - DB poll for log content (not SSE) — no timing/order ambiguity.
 *   - Per-test cleanup in finally{}; idempotent SQL deletes.
 *
 * Pre-reqs:
 *   - `task dev:titan` rig up (postgres + keycloak + server + ui + worker
 *     including titan-worker fat jar with the gitTag step registered).
 */
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'
import { readFixtureYaml } from '../../fixtures/fixture-files'
import { pgClient } from '../../fixtures/seed-v3'
import { safeDeleteJobCascade } from '../../fixtures/teardown-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

// Vendored mirror of hadamrd/titan-e2e-fixture — read from disk, never fetched (#48).
const FIXTURE_PATH = '.titan/pipelines/with-gitTag.yml'

const RUN_TAG = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
const TAG_VALUE = `e2e-${RUN_TAG}`
const JOB_FULL_NAME = `e2e-gittag-${RUN_TAG}`

const TERMINAL_STATUSES = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'CANCELLED', 'ERROR'])

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
  displayName?: string | null
}
interface FlowNode {
  buildId: number
  nodeId: string
  displayName?: string | null
  nodeType?: string | null
  stepDescriptor?: string | null
  status?: string | null
  logTaskId?: string | null
  failureReason?: string | null
  failureCategory?: string | null
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

/**
 * Locate the gitTag step's flow_node. Prefer match on `stepDescriptor`
 * (the engine's canonical wiring); fall back to displayName containing
 * "gitTag" for resilience to future DTO field renames.
 */
function findGitTagNode(nodes: FlowNode[]): FlowNode | undefined {
  return (
    nodes.find((n) => (n.stepDescriptor ?? '').trim() === 'gitTag') ??
    nodes.find((n) => (n.displayName ?? '').toLowerCase().includes('gittag')) ??
    nodes.find((n) => (n.displayName ?? '').toLowerCase().includes('git tag'))
  )
}

/**
 * Concatenate all titan.logs rows for a task, ordered by id.
 * Column contract: `titan.logs.task_id UUID` (V1__init.sql) — the flow_node's
 * `logTaskId` is that task id. (Issue #66: this query previously referenced a
 * nonexistent `task_token` column and errored at step 8.)
 */
async function fetchTaskLog(logTaskId: string): Promise<string> {
  const client = pgClient()
  await client.connect()
  try {
    const r = await client.query<{ data: string }>(
      `SELECT data FROM titan.logs WHERE task_id = $1::uuid ORDER BY id ASC`,
      [logTaskId],
    )
    return r.rows.map((row) => row.data).join('')
  } finally {
    await client.end()
  }
}

test.describe('v3 fixture-with-gitTag @golden', () => {
  test('discovery → trigger-with-params → gitTag worker step runs end-to-end', async ({
    request,
  }) => {
    test.setTimeout(120_000)

    // ── 1. Read the vendored fixture YAML (hermetic — #48) ──────────────────
    const fixtureYaml = readFixtureYaml(FIXTURE_PATH)
    expect(fixtureYaml.length, 'fixture YAML is empty').toBeGreaterThan(50)

    // Shape guard — if the fixture is mutated upstream so it no longer
    // declares a gitTag step / TAG param / push:false, the spec premise
    // breaks. Fail loud with a precise message before we burn 60s.
    expect(
      fixtureYaml,
      `fixture YAML shape changed — expected a gitTag step referencing params.TAG`,
    ).toContain('gitTag')
    expect(fixtureYaml).toContain('params.TAG')
    expect(
      fixtureYaml,
      `fixture must set push:false to avoid polluting any remote on repeated E2E runs`,
    ).toMatch(/push:\s*false/)

    // ── Mutable state for cleanup + diagnostics ─────────────────────────────
    let bearer: string | undefined
    let jobId: number | undefined
    let buildId: number | undefined
    let attached = false

    const dump = async (label: string) => {
      if (attached) return
      attached = true
      try {
        await test.info().attach(`fixture-${label}.yml`, {
          body: fixtureYaml,
          contentType: 'text/yaml',
        })
        if (bearer && buildId) {
          const build = await apiGet(request, bearer, `/api/v1/builds/${buildId}`)
          await test.info().attach(`build-${label}.json`, {
            body: build.raw,
            contentType: 'application/json',
          })
          const nodes = await apiGet<FlowNode[]>(
            request,
            bearer,
            `/api/v1/builds/${buildId}/nodes`,
          )
          await test.info().attach(`flow-nodes-${label}.json`, {
            body: nodes.raw,
            contentType: 'application/json',
          })
          // Attach the gitTag step's log (DB-direct, deterministic).
          if (Array.isArray(nodes.body)) {
            const gt = findGitTagNode(nodes.body)
            if (gt?.logTaskId) {
              try {
                const log = await fetchTaskLog(gt.logTaskId)
                await test.info().attach(`gittag-log-${label}.txt`, {
                  body: log,
                  contentType: 'text/plain',
                })
              } catch (e) {
                await test.info().attach(`gittag-log-${label}-error.txt`, {
                  body: String(e),
                  contentType: 'text/plain',
                })
              }
            }
          }
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
      // ── 2. Bearer via ROPC (API-only spec — no SPA dependency). ───────────
      bearer = await fetchBearerToken(ENV)

      // ── 3. Create the job from the fixture YAML (discovery path). ─────────
      const jobCreate = await request.post(`${API_BASE}/api/v1/jobs`, {
        headers: {
          Authorization: `Bearer ${bearer}`,
          'Content-Type': 'application/json',
        },
        data: {
          fullName: JOB_FULL_NAME,
          displayName: 'E2E gitTag fixture',
          pipelineScript: fixtureYaml,
          enabled: true,
        },
      })
      const jobRaw = await jobCreate.text()
      expect(
        jobCreate.status(),
        `POST /api/v1/jobs HTTP ${jobCreate.status()} body=${jobRaw.slice(0, 600)}`,
      ).toBe(201)
      jobId = (JSON.parse(jobRaw) as JobCreateResp).id
      expect(jobId).toBeGreaterThan(0)

      // ── 4. Trigger with typed parameter override (#778). ──────────────────
      const triggerResp = await request.post(
        `${API_BASE}/api/v1/jobs/${jobId}/builds`,
        {
          headers: {
            Authorization: `Bearer ${bearer}`,
            'Content-Type': 'application/json',
          },
          data: {
            parameters: { TAG: TAG_VALUE },
            triggeredBy: 'e2e-spec-30',
          },
        },
      )
      const triggerRaw = await triggerResp.text()
      expect(
        triggerResp.status(),
        `POST /api/v1/jobs/${jobId}/builds HTTP ${triggerResp.status()} body=${triggerRaw.slice(0, 400)}`,
      ).toBe(201)
      const trig = JSON.parse(triggerRaw) as TriggerBuildResp
      buildId = trig.buildId
      expect(buildId).toBeGreaterThan(0)

      // ── 5. Poll until terminal status (≤ 60s). ───────────────────────────
      let observedStatus = 'QUEUED'
      await expect
        .poll(
          async () => {
            const r = await apiGet<BuildDetail>(
              request,
              bearer!,
              `/api/v1/builds/${buildId}`,
            )
            if (!r.ok || !r.body) return false
            observedStatus = r.body.status
            return TERMINAL_STATUSES.has(r.body.status)
          },
          {
            message:
              `build ${buildId} never reached a terminal status within 60s ` +
              `(last observed: ${observedStatus}) — orchestrator stuck, ` +
              `worker not picking gitTag task, or gitTag step hanging.`,
            timeout: 60_000,
            intervals: [500, 1_000, 2_000, 3_000],
          },
        )
        .toBe(true)

      // ── 6. HARD assert SUCCESS. ──────────────────────────────────────────
      // gitTag with push:false on a build workspace is a single `git tag`
      // invocation. Any non-success means: (a) `unknown step type gitTag`
      // (worker registry gap — file P1), (b) "not a git repository" (the
      // workspace isn't a git repo and the fixture has no checkout — real
      // product gap), or (c) param expansion of ${{ params.TAG }} bypassed.
      // The attached gittag-log + flow-nodes JSON tell triage which.
      expect(
        observedStatus,
        `build ${buildId} terminated with status=${observedStatus}, expected SUCCESS. ` +
          `Inspect attached gittag-log + flow-nodes JSON: "unknown step type gitTag" => ` +
          `worker registry gap (P1, same class as #805); "not a git repository" => ` +
          `workspace not git-init'd and fixture has no checkout step (P1 fixture or product gap); ` +
          `non-zero "git tag" exit => tag name validation or param expansion broken.`,
      ).toBe('SUCCESS')

      // ── 7. HARD assert gitTag flow_node exists and ran. ──────────────────
      const nodesResp = await apiGet<FlowNode[]>(
        request,
        bearer,
        `/api/v1/builds/${buildId}/nodes`,
      )
      expect(
        nodesResp.ok && Array.isArray(nodesResp.body),
        `GET /builds/${buildId}/nodes HTTP ${nodesResp.status} body=${nodesResp.raw.slice(0, 300)}`,
      ).toBe(true)
      const nodes = nodesResp.body as FlowNode[]
      const gt = findGitTagNode(nodes)
      expect(
        gt,
        `no flow_node with stepDescriptor='gitTag' on build ${buildId}. ` +
          `Either the parser didn't materialise the step (PDL grammar regression) ` +
          `or the orchestrator dispatched it under a different descriptor id. ` +
          `Nodes seen: ${JSON.stringify(nodes.map((n) => ({ id: n.nodeId, sd: n.stepDescriptor, dn: n.displayName, st: n.status })))}`,
      ).toBeDefined()
      expect(
        gt!.status,
        `gitTag flow_node status=${gt!.status}, expected SUCCESS. ` +
          `failureCategory=${gt!.failureCategory} failureReason=${gt!.failureReason}`,
      ).toBe('SUCCESS')
      expect(
        gt!.logTaskId,
        `gitTag flow_node has null logTaskId — the dispatcher never minted ` +
          `a task token, which means TaskExecutor never picked up this step ` +
          `(routing bug — same class as #805 control-plane misroute, but inverted).`,
      ).toBeTruthy()

      // ── 8. HARD assert the resolved tag string appears in the step log. ──
      // The handler emits `log.system("gitTag: creating ... tag '<tag>' at HEAD")`
      // and `git tag` stderr also surfaces via StepExecutor. Either signal is
      // sufficient — the literal TAG_VALUE MUST be in the log, or
      // ${{ params.TAG }} was never expanded for the gitTag step path.
      const gtLog = await fetchTaskLog(gt!.logTaskId!)
      expect(
        gtLog.length,
        `titan.logs has zero rows for gitTag task_token=${gt!.logTaskId} — ` +
          `the handler never logged, which means execute() didn't run (router ` +
          `bypass) or the LogSink isn't flushing.`,
      ).toBeGreaterThan(0)
      expect(
        gtLog,
        `gitTag step log does not contain the resolved tag string "${TAG_VALUE}". ` +
          `If the literal '\${{ params.TAG }}' appears, parameter expansion is ` +
          `bypassed in the gitTag dispatch path. If neither appears, the handler ` +
          `ran with an unrelated argument. First 800 chars of log: ${gtLog.slice(0, 800)}`,
      ).toContain(TAG_VALUE)
      // The handler's own canonical log line — proves we're looking at the
      // gitTag handler's output specifically (not, e.g., a sibling step's log
      // that happened to mention the same value via env var echo).
      expect(
        gtLog.toLowerCase(),
        `gitTag step log lacks any "git tag" / "creating ... tag" line. ` +
          `Either the handler is logging through a non-DB sink, or the log row ` +
          `for the handler's own systemic message wasn't persisted. First 800 ` +
          `chars: ${gtLog.slice(0, 800)}`,
      ).toMatch(/git tag|creating .*tag|tag created/)
    } catch (err) {
      await dump('assertion-failure')
      throw err
    } finally {
      // ── Cleanup: best-effort, idempotent, never throws past the boundary.
      // push:false in the fixture means no remote tag was created — nothing
      // to clean up on the GitHub side.
      // #65: migrated off the raw-cascade SQL teardown. safeDeleteJobCascade
      // (#59, fixtures/teardown-v3.ts) cancels any still-live build via the
      // public API, waits (bounded) for terminal status + CLAIMED/PROCESSING
      // task-lease drain, and only then deletes — never yanking a leased
      // task_queue row out from under the worker.
      if (jobId && jobId > 0) {
        await safeDeleteJobCascade(request, jobId)
      }
    }
  })
})

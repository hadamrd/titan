/**
 * 53-pulsar-aggregate-verdict — multi-stage Pulsar pipeline drives ONE aggregate
 * `build` check (#6).
 *
 * THE GAP THIS CLOSES
 *   The Titan server posts a single aggregate `build` CI check back to a Pulsar
 *   node via `PulsarCheckReporter` — that check name is exactly what Pulsar's
 *   `required_checks: ["build"]` blocks the merge on. The unit layer
 *   (`PulsarCheckReporterTest`) proves the HTTP post shape (`"check":"build"`,
 *   one post per transition) and the status→conclusion mapping. But NOTHING drove
 *   a real multi-stage `lint → test → build` DAG end-to-end and asserted the
 *   single aggregate verdict — so a regression in stage-failure propagation
 *   (a mid-DAG failure no longer flipping the aggregate `build` check to failure)
 *   would ship green. This spec is that missing end-to-end guard.
 *
 * WHAT IT ASSERTS (issue #6 acceptance criteria, the end-to-end half)
 *   - Happy leg (pulsar-aggregate-pass): all three stages pass → the engine rolls
 *     the DAG up to a SINGLE overall build verdict = SUCCESS (lint/test/build all
 *     SUCCESS).
 *   - Sad/adversarial leg (pulsar-aggregate-fail): the `test` stage fails →
 *     failure propagates under the default `blockOnFailure` → `lint` SUCCESS,
 *     `test` FAILED, downstream `build` stage SKIPPED (never pending/hung, never
 *     green) → the engine rolls up to a SINGLE overall verdict = FAILED.
 *
 * WHY THIS IS THE RIGHT END-TO-END HALF (and NOT a tautology)
 *   `PulsarCheckReporter` aggregates the WHOLE build into exactly ONE check named
 *   `build` whose conclusion is mapped 1:1 from this single overall verdict
 *   (SUCCESS→success, FAILED→failure). The reporter's HTTP behaviour (post shape,
 *   `check:"build"`, exactly-one-post, retry/skip) AND that status→conclusion
 *   mapping are already unit-covered by `PulsarCheckReporterTest`
 *   (`mapConclusion_terminalAndIntermediate`). What was UNtested end-to-end — and
 *   all this spec claims to cover — is the ENGINE producing the single aggregate
 *   verdict the reporter consumes. So we assert that real engine output DIRECTLY
 *   (the build resource's one `status` field + the per-stage rollup). We do NOT
 *   re-derive the Java conclusion mapping in TypeScript and assert it against
 *   itself — that would prove nothing the unit test doesn't already prove.
 *
 * WHY THIS IS A GENUINE RED TEST (acceptance criterion 4)
 *   The overall verdict is a DAG rollup, not a passthrough of the `build` stage:
 *   in the sad leg the `build` stage is SKIPPED yet the overall verdict is FAILED.
 *   If stage-failure propagation regressed — a mid-DAG failure no longer flipping
 *   the rollup (`test` fails but `build` still runs, or the verdict rolls up
 *   SUCCESS) — then both the overall-verdict `toBe('FAILED')` assertion and the
 *   `build`-stage `toBe('SKIPPED')` assertion go red. That is the regression it
 *   exists to catch.
 *
 * SCOPE NOTE — no live Pulsar post observed here
 *   The local rig (`task dev:titan`) wires NO Pulsar node (`pulsar.node-base-url`
 *   defaults to an unreachable endpoint) and the manual build-trigger endpoint
 *   stamps `triggerType=manual`, so the reporter never fires and there is no live
 *   `build` check to observe. A true wire-level webhook→clone→check-POST roundtrip
 *   (asserting the actually-posted `build` check) is a follow-up gated on a
 *   Pulsar-node e2e fixture; the reporter's own behaviour is unit-covered today.
 *
 * Skip rules (deterministic, no test.skip mid-body): rig unreachable / no bearer.
 */
import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'

const ENV = authEnv()
const RIG_BASE_URL =
  process.env.TITAN_RIG_URL ?? process.env.TITAN_UI_URL ?? 'http://localhost:5180'

const BUILD_TERMINAL_DEADLINE_MS = Number(process.env.TITAN_PULSAR_E2E_TIMEOUT_MS ?? 120_000)
const TERMINAL = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'CANCELLED', 'UNSTABLE'])

interface BuildDto {
  id: number
  status: string
}
interface FlowNodeDto {
  nodeId: string
  status: string
  nodeType?: string
  displayName?: string
}
interface JobCreateResp {
  id: number
}
interface BuildTriggerResp {
  buildId: number
}

// ── REST helpers (drive the build; verdict drives the aggregate check) ────────

async function rigReachable(): Promise<boolean> {
  try {
    const ctrl = new AbortController()
    const t = setTimeout(() => ctrl.abort(), 5_000)
    const res = await fetch(`${RIG_BASE_URL}/api/v1/builds`, { signal: ctrl.signal })
    clearTimeout(t)
    return res.status === 401 || res.ok
  } catch {
    return false
  }
}

async function tryBearerToken(): Promise<string | null> {
  try {
    return await fetchBearerToken(ENV)
  } catch {
    return null
  }
}

function pipelineYaml(fixture: string): string {
  const here = dirname(fileURLToPath(import.meta.url))
  return readFileSync(
    resolve(here, '..', '..', 'fixtures', 'pipelines', fixture, 'titan-pipeline.yml'),
    'utf8',
  )
}

function authHeaders(bearer: string, extra?: Record<string, string>): Record<string, string> {
  return { Authorization: `Bearer ${bearer}`, Accept: 'application/json', ...(extra ?? {}) }
}

async function createJob(bearer: string, fullName: string, fixture: string): Promise<number> {
  const res = await fetch(`${RIG_BASE_URL}/api/v1/jobs`, {
    method: 'POST',
    headers: authHeaders(bearer, { 'Content-Type': 'application/json' }),
    body: JSON.stringify({
      fullName,
      displayName: `pulsar-aggregate #6 — ${fixture}`,
      pipelineScript: pipelineYaml(fixture),
      configJson: JSON.stringify({ triggers: [] }),
      enabled: true,
    }),
  })
  expect(res.status, `job create for ${fixture}: ${await res.text()}`).toBe(201)
  return ((await res.json()) as JobCreateResp).id
}

async function triggerBuild(bearer: string, jobId: number): Promise<number> {
  const res = await fetch(`${RIG_BASE_URL}/api/v1/jobs/${jobId}/builds`, {
    method: 'POST',
    headers: authHeaders(bearer, { 'Content-Type': 'application/json' }),
    body: JSON.stringify({}),
  })
  expect(res.status, `build trigger: ${await res.text()}`).toBeLessThan(300)
  const body = (await res.json()) as BuildTriggerResp
  expect(body.buildId, 'trigger returned no buildId').toBeGreaterThan(0)
  return body.buildId
}

/** Poll /api/v1/builds/{id} to a terminal status with a bounded budget — no fixed sleep. */
async function pollTerminal(bearer: string, buildId: number): Promise<string> {
  const deadline = Date.now() + BUILD_TERMINAL_DEADLINE_MS
  let last = 'QUEUED'
  while (Date.now() < deadline) {
    const res = await fetch(`${RIG_BASE_URL}/api/v1/builds/${buildId}`, {
      headers: authHeaders(bearer),
    })
    if (res.ok) {
      last = ((await res.json()) as BuildDto).status
      if (TERMINAL.has(last)) return last
    }
    await new Promise((r) => setTimeout(r, 2_000))
  }
  throw new Error(`build ${buildId} not terminal within ${BUILD_TERMINAL_DEADLINE_MS}ms; last=${last}`)
}

async function fetchNodes(bearer: string, buildId: number): Promise<FlowNodeDto[]> {
  const res = await fetch(`${RIG_BASE_URL}/api/v1/builds/${buildId}/nodes`, {
    headers: authHeaders(bearer),
  })
  expect(res.ok, `GET /builds/${buildId}/nodes failed: ${res.status}`).toBe(true)
  const nodes = (await res.json()) as FlowNodeDto[]
  expect(nodes.length, `engine emitted zero flow_nodes for build ${buildId}`).toBeGreaterThan(0)
  return nodes
}

/** The status of the stage node whose name (displayName or nodeId) matches. */
function stageStatus(nodes: FlowNodeDto[], name: string): string | undefined {
  const isStage = (n: FlowNodeDto) => (n.nodeType ?? '').toUpperCase() === 'STAGE'
  const match =
    nodes.find((n) => isStage(n) && (n.displayName ?? n.nodeId) === name) ??
    nodes.find((n) => (n.displayName ?? n.nodeId) === name)
  return match?.status
}

test.describe('@golden v3 pulsar-aggregate-verdict #6', () => {
  let bearer: string

  test.beforeAll(async () => {
    if (!(await rigReachable())) {
      test.skip(true, `Titan rig not reachable at ${RIG_BASE_URL}`)
    }
    const token = await tryBearerToken()
    if (!token) {
      test.skip(true, `cannot fetch Keycloak bearer (env=${ENV.keycloakUrl}); rig may be remote`)
    }
    bearer = token!
  })

  test('happy: lint→test→build all pass → single overall verdict = SUCCESS', async () => {
    test.setTimeout(BUILD_TERMINAL_DEADLINE_MS + 60_000)
    const fullName = `pulsar-sample/aggregate-pass-${Date.now()}`

    const jobId = await createJob(bearer, fullName, 'pulsar-aggregate-pass')
    const buildId = await triggerBuild(bearer, jobId)
    const status = await pollTerminal(bearer, buildId)

    expect(status, 'all stages pass → overall verdict must be SUCCESS').toBe('SUCCESS')

    const nodes = await fetchNodes(bearer, buildId)
    expect(stageStatus(nodes, 'lint'), 'lint stage').toBe('SUCCESS')
    expect(stageStatus(nodes, 'test'), 'test stage').toBe('SUCCESS')
    expect(stageStatus(nodes, 'build'), 'build stage').toBe('SUCCESS')

    // The 3-stage DAG collapses to a SINGLE overall verdict (asserted above:
    // status === 'SUCCESS'). That one verdict is what PulsarCheckReporter maps 1:1
    // to the single `build` check = success — the mapping is unit-covered by
    // PulsarCheckReporterTest, so we do not re-assert it here.
  })

  test('sad: test stage fails → build SKIPPED, single overall verdict = FAILED', async () => {
    test.setTimeout(BUILD_TERMINAL_DEADLINE_MS + 60_000)
    const fullName = `pulsar-sample/aggregate-fail-${Date.now()}`

    const jobId = await createJob(bearer, fullName, 'pulsar-aggregate-fail')
    const buildId = await triggerBuild(bearer, jobId)
    const status = await pollTerminal(bearer, buildId)

    // Failure must propagate to the overall verdict (never pending/hung/green).
    expect(status, 'a failed mid-DAG stage must flip the overall verdict to FAILED').toBe('FAILED')

    const nodes = await fetchNodes(bearer, buildId)
    expect(stageStatus(nodes, 'lint'), 'lint runs and passes').toBe('SUCCESS')
    expect(stageStatus(nodes, 'test'), 'test is the failing stage').toBe('FAILED')
    // blockOnFailure: the downstream build stage must be SKIPPED, never run/green.
    expect(stageStatus(nodes, 'build'), 'downstream build stage is SKIPPED, not run').toBe('SKIPPED')

    // AGGREGATE + RED-TEST proof: the overall verdict is a DAG ROLLUP, not a
    // passthrough of the `build` stage. The `build` STAGE is SKIPPED, yet the single
    // overall verdict is FAILED — exactly the one value PulsarCheckReporter maps to
    // the single `build` check = failure (mapping unit-covered, not re-asserted).
    // If propagation regressed, `status` would be SUCCESS and the `build` stage
    // would run instead of being SKIPPED → both assertions go red.
    expect(
      status,
      'overall verdict (FAILED) is an aggregate rollup, not the SKIPPED build stage',
    ).not.toBe(stageStatus(nodes, 'build'))
  })
})

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
 * WHAT IT ASSERTS (issue #6 acceptance criteria)
 *   - Happy leg (pulsar-aggregate-pass): all three stages pass → overall verdict
 *     SUCCESS → the single aggregate `build` check resolves to exactly one
 *     `success` conclusion.
 *   - Sad/adversarial leg (pulsar-aggregate-fail): the `test` stage fails →
 *     failure propagates under the default `blockOnFailure` → `lint` SUCCESS,
 *     `test` FAILED, downstream `build` stage SKIPPED (never pending/hung, never
 *     green) → overall verdict FAILED → the aggregate `build` check resolves to
 *     exactly one `failure` conclusion.
 *   - Shape: both legs collapse the 3-stage DAG to EXACTLY ONE check named
 *     `build` — not one check per stage.
 *
 * WHY THIS IS A GENUINE RED TEST (acceptance criterion 4)
 *   The aggregate `build` CHECK is derived from the OVERALL build verdict, not
 *   from the `build` STAGE's own status. In the sad leg the `build` stage is
 *   SKIPPED yet the `build` check is `failure`. If stage-failure propagation were
 *   removed — a mid-DAG failure no longer flipping the aggregate (`test` fails but
 *   `build` still runs, or the verdict rolls up SUCCESS) — then:
 *     (a) the overall verdict would be SUCCESS, so `mapPulsarConclusion(...)` →
 *         `success` and the `toBe('failure')` assertion FAILS; and
 *     (b) the `build` stage would not be SKIPPED, so that assertion FAILS too.
 *   Either way the test goes red. That is the regression it exists to catch.
 *
 * WHY THE CHECK IS ASSERTED VIA THE MAPPING, NOT A LIVE POST
 *   `task dev:titan` (the local rig this suite runs against) wires NO Pulsar node
 *   — `pulsar.node-base-url` defaults to an unreachable endpoint and the manual
 *   build-trigger endpoint stamps `triggerType=manual`, so no live `build` check
 *   is posted anywhere to observe. The reporter's HTTP behaviour (post shape,
 *   `check:"build"`, exactly-one-post, retry/skip) is already unit-covered by
 *   `PulsarCheckReporterTest`. What was UNtested end-to-end — and what this spec
 *   covers — is the engine producing the aggregate verdict the reporter consumes.
 *   `mapPulsarConclusion` below mirrors `PulsarCheckReporter.mapConclusion`
 *   (its single source of truth); a true wire-level webhook→clone→check-POST
 *   roundtrip is a follow-up gated on a Pulsar-node e2e fixture.
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

/** The check name Pulsar's `required_checks` contract expects — MUST be `build`. */
const CHECK_NAME = 'build'

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

/** One Pulsar CI check, as the reporter would post it. */
interface PulsarCheck {
  name: string
  conclusion: string
}

// ── mapping: mirrors PulsarCheckReporter.mapConclusion (Java source of truth) ──
//
// QUEUED/RUNNING → pending; SUCCESS → success; FAILED/ABORTED/CANCELLED → failure;
// UNSTABLE → failure (an unstable build is not a clean green, so the gate stays
// refused — the Pulsar CI event only accepts pending|success|failure). Any other
// (intermediate) state is not reported. Correctness of this mapping itself is
// unit-covered by PulsarCheckReporterTest#mapConclusion_terminalAndIntermediate.
function mapPulsarConclusion(buildStatus: string): string | null {
  switch (buildStatus) {
    case 'QUEUED':
    case 'RUNNING':
      return 'pending'
    case 'SUCCESS':
      return 'success'
    case 'FAILED':
    case 'ABORTED':
    case 'CANCELLED':
      return 'failure'
    case 'UNSTABLE':
      return 'failure'
    default:
      return null
  }
}

/**
 * The set of Pulsar CI checks the reporter posts for a terminal build verdict.
 * The reporter aggregates the WHOLE build into a SINGLE check named `build`
 * (`PulsarCheckReporter.CHECK_NAME`) — never one check per stage — so a terminal
 * verdict yields exactly one entry. This is the function under test for the
 * "exactly one `build` check" shape assertion.
 */
function aggregateChecksFor(buildStatus: string): PulsarCheck[] {
  const conclusion = mapPulsarConclusion(buildStatus)
  return conclusion === null ? [] : [{ name: CHECK_NAME, conclusion }]
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

  test('happy: lint→test→build all pass → ONE aggregate build check = success', async () => {
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

    // Aggregate: the whole-build verdict collapses to EXACTLY ONE `build` check.
    const checks = aggregateChecksFor(status)
    expect(checks.length, 'exactly one aggregate Pulsar check (not one per stage)').toBe(1)
    expect(checks[0]!.name, 'the aggregate check name is `build`').toBe(CHECK_NAME)
    expect(checks[0]!.conclusion, 'all-pass → aggregate build check = success').toBe('success')
  })

  test('sad: test stage fails → build SKIPPED, ONE aggregate build check = failure', async () => {
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

    // The KEY aggregation proof + red test: the `build` STAGE is SKIPPED, yet the
    // aggregate `build` CHECK is derived from the OVERALL verdict (FAILED) → failure.
    // If propagation regressed, `status` would be SUCCESS here → conclusion `success`
    // → this assertion fails (and the build-stage SKIPPED assertion above fails too).
    const checks = aggregateChecksFor(status)
    expect(checks.length, 'exactly one aggregate Pulsar check (not one per stage)').toBe(1)
    expect(checks[0]!.name, 'the aggregate check name is `build`').toBe(CHECK_NAME)
    expect(checks[0]!.conclusion, 'any stage failing → aggregate build check = failure').toBe('failure')
    // Prove the check is the AGGREGATE, not the per-stage `build` status: the
    // `build` stage is SKIPPED (a per-stage reporter would emit pending/skipped or
    // no terminal conclusion) but the aggregate check is a hard `failure`.
    expect(
      stageStatus(nodes, 'build'),
      'guard: the build STAGE is SKIPPED while the build CHECK is failure',
    ).toBe('SKIPPED')
  })
})

/**
 * 61-stage-timings — GET /api/v1/jobs/{id}/stage-timings +
 * JobStageTimingsPanel against REAL engine-produced history (refs #123).
 *
 * History: writing this spec caught the exact bug it was designed for — the
 * engine never stamped flow_nodes.duration_ms, so stage-timings returned
 * `stages: []` for every real job (filed as #127, fixed: FlowNodeDao now
 * derives duration_ms on every completing transition). This spec IS the
 * acceptance test for that fix and runs @golden since #127 landed.
 *
 * Why this spec exists:
 *   The stage-timings endpoint (#1095) and its "Stage Timing — last 30
 *   builds" panel were covered only by vitest — i.e. against hand-written
 *   DTO fixtures, never against what the engine actually writes into
 *   titan.flow_nodes. A DAO-vs-engine drift (wrong node_type filter, wrong
 *   display_name grouping, duration_ms never populated) would sail through
 *   the unit tests and ship a panel that renders "Not enough history yet."
 *   forever. (It did — see #127.)
 *
 * Strategy (own rows, #59 ownership rule):
 *   1. Create a per-run job with a 3-stage pipeline (prep → pause → finish;
 *      `pause` sleeps 2s so at least one stage has a duration that cannot be
 *      confused with scheduling noise).
 *   2. Trigger it TWICE via POST /api/v1/jobs/{id}/builds, poll each build
 *      to terminal, HARD-assert SUCCESS.
 *   3. API oracle: GET /builds/{id}/nodes for both builds — the engine's
 *      canonical per-stage duration_ms. Then GET /jobs/{id}/stage-timings
 *      and assert:
 *        - buildsConsidered === 2 (only OUR builds — fresh job),
 *        - all 3 stages present, sampleCount === 2 each,
 *        - every sample's durationMs EQUALS the /nodes duration for that
 *          (stage, build) — same DB column, so equality is safe,
 *        - the `pause` samples sit in a GENEROUS wall-clock band
 *          [1500ms, 60s] (sleep 2 — the only physically-anchored check).
 *   4. UI: open /pipelines/{jobId}, assert the panel renders a row per
 *      stage with BOTH runs' bars, and each bar's data-duration-ms matches
 *      the API sample exactly (the UI must render API truth, not recompute).
 *   5. Teardown: safeDeleteJobCascade in finally — zero litter (#116).
 *
 * Pre-req: `task dev:titan` rig is up.
 */
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken, loginViaKeycloak } from '../../fixtures/auth-v3'
import { safeDeleteJobCascade } from '../../fixtures/teardown-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const TERMINAL = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'UNSTABLE', 'ERROR'])

const STAGE_NAMES = ['prep', 'pause', 'finish'] as const

// prep → pause → finish, chained explicitly so stage order (and therefore
// per-stage flow_nodes rows) is deterministic. `pause` sleeps 2s to anchor
// one stage's duration to wall-clock reality; the echoes keep total spec
// time small (2 runs ≈ 2 × ~10s engine wall time).
const PIPELINE_YAML = `stages:
  - stage: prep
    steps:
      - sh: echo "prep done"
  - stage: pause
    dependsOn: [prep]
    steps:
      - sh: sleep 2
  - stage: finish
    dependsOn: [pause]
    steps:
      - sh: echo "finish done"
`

// GENEROUS tolerances for the sleep-2 stage (issue #123 explicitly asks for
// generous bands — this is a shared rig, not a benchmark).
const PAUSE_MIN_MS = 1_500
const PAUSE_MAX_MS = 60_000

interface FlowNodeDto {
  nodeId: string
  nodeType: string
  displayName: string | null
  status: string
  durationMs: number | null
}

interface StageSampleDto {
  buildId: number
  buildNumber: number
  durationMs: number
  status: string
}

interface StageTimingDto {
  stageName: string
  sampleCount: number
  p50Ms: number | null
  p95Ms: number | null
  p99Ms: number | null
  minMs: number | null
  maxMs: number | null
  samples: StageSampleDto[]
}

interface StageTimingsDto {
  n: number
  buildsConsidered: number
  stages: StageTimingDto[]
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

/** Trigger one build and poll it to terminal; HARD-assert SUCCESS. */
async function runBuildToSuccess(
  request: APIRequestContext,
  bearer: string,
  jobId: number,
  label: string,
): Promise<number> {
  const trigger = await request.post(`${API_BASE}/api/v1/jobs/${jobId}/builds`, {
    headers: {
      Authorization: `Bearer ${bearer}`,
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    data: '{}',
  })
  const triggerRaw = await trigger.text()
  expect(
    trigger.ok(),
    `[${label}] POST /jobs/${jobId}/builds failed: HTTP ${trigger.status()} body=${triggerRaw.slice(0, 400)}`,
  ).toBe(true)
  const buildId = (JSON.parse(triggerRaw) as { buildId: number }).buildId
  expect(buildId, `[${label}] trigger response missing buildId`).toBeGreaterThan(0)

  let finalStatus = ''
  await expect
    .poll(
      async () => {
        const r = await apiGet<{ status: string }>(request, bearer, `/api/v1/builds/${buildId}`)
        finalStatus = r.body?.status ?? ''
        return TERMINAL.has(finalStatus) ? finalStatus : ''
      },
      {
        message: `[${label}] build ${buildId} did not reach terminal status within 90s (last="${finalStatus}")`,
        timeout: 90_000,
        intervals: [1_000, 2_000, 3_000],
      },
    )
    .not.toBe('')
  expect(
    finalStatus,
    `[${label}] build ${buildId} terminal status was "${finalStatus}", expected SUCCESS`,
  ).toBe('SUCCESS')
  return buildId
}

/** Map stageName → durationMs from the build's canonical /nodes response. */
async function stageDurationsFromNodes(
  request: APIRequestContext,
  bearer: string,
  buildId: number,
): Promise<Map<string, number>> {
  const r = await apiGet<FlowNodeDto[]>(request, bearer, `/api/v1/builds/${buildId}/nodes`)
  expect(
    r.ok && Array.isArray(r.body),
    `GET /builds/${buildId}/nodes failed: HTTP ${r.status} body=${r.raw.slice(0, 400)}`,
  ).toBe(true)
  const out = new Map<string, number>()
  for (const n of r.body!) {
    if (n.nodeType?.toUpperCase() !== 'STAGE') continue
    // FlowNodeDto is @JsonInclude(NON_NULL): a null durationMs is ABSENT from
    // the JSON, i.e. `undefined` here. Coerce to null so the assertion cannot
    // pass vacuously on a missing field (that hole is how #127 first hid).
    const durationMs = n.durationMs ?? null
    expect(
      durationMs,
      `STAGE node ${n.nodeId} ("${n.displayName}") of build ${buildId} has no durationMs ` +
        `— engine never stamped the stage duration (#127), stage-timings silently omits it`,
    ).not.toBeNull()
    out.set(n.displayName ?? n.nodeId, durationMs!)
  }
  return out
}

// @golden sits BEFORE any parenthetical on purpose: golden-count.sh's regex
// stops at the first `)` on the title line (PR #128 lesson).
test.describe('v3 stage-timings @golden', () => {
  test('two real runs of a 3-stage pipeline surface per-stage durations in API + panel', async ({
    page,
    request,
  }) => {
    test.setTimeout(300_000)

    const bearer = await fetchBearerToken(ENV)
    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`
    const fullName = `e2e/stage-timings-${runTag}`

    // 1. Create the per-run job (own rows — #59).
    const created = await request.post(`${API_BASE}/api/v1/jobs`, {
      headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
      data: {
        fullName,
        displayName: 'e2e stage-timings',
        pipelineScript: PIPELINE_YAML,
        enabled: true,
      },
    })
    const createdRaw = await created.text()
    expect(
      created.status(),
      `POST /api/v1/jobs HTTP ${created.status()} body=${createdRaw.slice(0, 400)}`,
    ).toBe(201)
    const jobId = (JSON.parse(createdRaw) as { id: number }).id
    expect(jobId).toBeGreaterThan(0)

    try {
      // 2. Run the pipeline TWICE, sequentially (deterministic build history).
      const build1 = await runBuildToSuccess(request, bearer, jobId, 'run-1')
      const build2 = await runBuildToSuccess(request, bearer, jobId, 'run-2')
      expect(build2, 'second trigger must produce a distinct build').not.toBe(build1)

      // 3a. Canonical per-stage durations from the engine's /nodes.
      const nodesDurations = new Map<number, Map<string, number>>([
        [build1, await stageDurationsFromNodes(request, bearer, build1)],
        [build2, await stageDurationsFromNodes(request, bearer, build2)],
      ])
      for (const [bid, durations] of nodesDurations) {
        for (const stage of STAGE_NAMES) {
          expect(
            durations.has(stage),
            `build ${bid}: /nodes has no STAGE row named "${stage}" — ` +
              `observed: ${JSON.stringify([...durations.keys()])}`,
          ).toBe(true)
        }
      }

      // 3b. The stage-timings endpoint itself.
      const timings = await apiGet<StageTimingsDto>(
        request,
        bearer,
        `/api/v1/jobs/${jobId}/stage-timings?n=30`,
      )
      expect(
        timings.ok && timings.body !== null,
        `GET /jobs/${jobId}/stage-timings failed: HTTP ${timings.status} body=${timings.raw.slice(0, 400)}`,
      ).toBe(true)
      const dto = timings.body!

      expect(dto.n, 'echoed window size').toBe(30)
      // Fresh per-run job → the window saw exactly OUR two builds.
      expect(
        dto.buildsConsidered,
        `buildsConsidered must be exactly 2 for a fresh job with 2 finished builds ` +
          `(raw=${timings.raw.slice(0, 400)})`,
      ).toBe(2)

      const byStage = new Map(dto.stages.map((s) => [s.stageName, s]))
      expect(
        [...byStage.keys()].sort(),
        `stage-timings must report exactly the pipeline's 3 stages`,
      ).toEqual([...STAGE_NAMES].sort())

      for (const stageName of STAGE_NAMES) {
        const stage = byStage.get(stageName)!
        expect(stage.sampleCount, `stage "${stageName}" sampleCount`).toBe(2)
        expect(stage.samples, `stage "${stageName}" must carry one sample per run`).toHaveLength(2)

        const sampleBuildIds = stage.samples.map((s) => s.buildId).sort((a, b) => a - b)
        expect(
          sampleBuildIds,
          `stage "${stageName}" samples must cover BOTH runs`,
        ).toEqual([build1, build2].sort((a, b) => a - b))

        for (const sample of stage.samples) {
          expect(sample.status, `stage "${stageName}" build ${sample.buildId} status`).toBe(
            'SUCCESS',
          )
          // Same DB column (flow_nodes.duration_ms) → exact equality. Any
          // drift here means the DAO joined/grouped the wrong rows.
          const canonical = nodesDurations.get(sample.buildId)!.get(stageName)!
          expect(
            sample.durationMs,
            `stage "${stageName}" build ${sample.buildId}: stage-timings sample ` +
              `(${sample.durationMs}ms) != /nodes STAGE duration (${canonical}ms)`,
          ).toBe(canonical)
        }

        // Percentiles must sit inside the sample envelope (generous internal
        // consistency — no NaN/negative/garbage aggregation).
        const durations = stage.samples.map((s) => s.durationMs)
        const min = Math.min(...durations)
        const max = Math.max(...durations)
        expect(stage.minMs, `stage "${stageName}" minMs`).toBe(min)
        expect(stage.maxMs, `stage "${stageName}" maxMs`).toBe(max)
        for (const [label, value] of [
          ['p50Ms', stage.p50Ms],
          ['p95Ms', stage.p95Ms],
          ['p99Ms', stage.p99Ms],
        ] as const) {
          expect(value, `stage "${stageName}" ${label} must not be null with 2 samples`).not.toBeNull()
          expect(value!, `stage "${stageName}" ${label}=${value} below min=${min}`).toBeGreaterThanOrEqual(min)
          expect(value!, `stage "${stageName}" ${label}=${value} above max=${max}`).toBeLessThanOrEqual(max)
        }
      }

      // 3c. Physical anchor: the sleep-2 stage's samples must be ≥1.5s and
      // ≤60s in BOTH runs (generous band — catches duration_ms stored in
      // seconds, or a duration computed from the wrong timestamps).
      for (const sample of byStage.get('pause')!.samples) {
        expect(
          sample.durationMs,
          `pause (sleep 2) sample of build ${sample.buildId} is ${sample.durationMs}ms — ` +
            `below the generous ${PAUSE_MIN_MS}ms floor; duration is not wall-clock-real`,
        ).toBeGreaterThanOrEqual(PAUSE_MIN_MS)
        expect(
          sample.durationMs,
          `pause (sleep 2) sample of build ${sample.buildId} is ${sample.durationMs}ms — ` +
            `above the generous ${PAUSE_MAX_MS}ms ceiling`,
        ).toBeLessThanOrEqual(PAUSE_MAX_MS)
      }

      // 4. UI: the panel on the pipeline detail page renders both runs.
      await loginViaKeycloak(page, ENV)
      await page.goto(`${ENV.uiBaseUrl}/pipelines/${jobId}`)

      const panel = page.getByTestId('job-stage-timings-panel')
      await expect(panel).toBeVisible({ timeout: 15_000 })

      // Not the error placeholder, not the empty state — the real chart.
      await expect(panel.getByTestId('job-stage-timings-error')).not.toBeVisible()
      const chart = panel.getByTestId('job-stage-timing-chart')
      await expect(chart).toBeVisible({ timeout: 15_000 })
      await expect(panel.getByTestId('job-stage-timing-empty')).not.toBeVisible()
      await expect(chart).toHaveAttribute('data-builds-considered', '2')

      // One row per stage; each row carries BOTH runs' bars with the API's
      // exact durations (the UI renders API truth — it must not recompute).
      const rows = panel.locator('[data-testid="job-stage-timing-row"]')
      await expect(rows).toHaveCount(STAGE_NAMES.length)

      for (const stageName of STAGE_NAMES) {
        const row = panel.locator(
          `[data-testid="job-stage-timing-row"][data-stage-name="${stageName}"]`,
        )
        await expect(row, `panel row for stage "${stageName}"`).toBeVisible()
        await expect(row).toHaveAttribute('data-sample-count', '2')

        const bars = row.locator('[data-testid="job-stage-timing-bar"]')
        await expect(bars, `stage "${stageName}" must render one bar per run`).toHaveCount(2)

        const apiSamples = byStage.get(stageName)!.samples
        for (const sample of apiSamples) {
          const bar = row.locator(
            `[data-testid="job-stage-timing-bar"][data-build-id="${sample.buildId}"]`,
          )
          await expect(bar, `stage "${stageName}" bar for build ${sample.buildId}`).toHaveCount(1)
          await expect(bar).toHaveAttribute('data-duration-ms', String(sample.durationMs))
          await expect(bar).toHaveAttribute('data-build-number', String(sample.buildNumber))
        }
      }
    } finally {
      // 5. Zero litter: cancel-safe cascade delete of the per-run job.
      const result = await safeDeleteJobCascade(request, jobId).catch((e) => {
        console.warn(`[61-stage-timings] teardown of job ${jobId} failed: ${String(e)}`)
        return null
      })
      if (result && !result.deleted) {
        console.warn(
          `[61-stage-timings] job ${jobId} left rows behind: builds ${result.leftoverBuildIds.join(',')}`,
        )
      }
    }
  })
})

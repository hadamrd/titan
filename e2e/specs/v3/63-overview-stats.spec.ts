/**
 * 63-overview-stats — golden coverage for the Overview dashboard's stats
 * surfaces against REAL builds (closes #138).
 *
 * Why this spec exists
 * ────────────────────
 * The coverage audit found /api/v1/stats (StatsApi), /api/v1/activity
 * (ActivityApi), /api/v1/jobs/top-failing (TopFailingJobsApi) and the
 * Overview route covered only by pre-standards specs that never assert the
 * numbers reflect real builds. The no-lies bar for dashboards: a KPI tile
 * must not render synthesized-looking data from a wrong query — the only way
 * to prove that at rig level is to run a KNOWN workload through the real
 * engine and assert the stats move by exactly that delta.
 *
 * Strategy — own jobs, real builds, race-free oracles (#140)
 * ──────────────────────────────────────────────────────────
 *   1. Create two purpose-built jobs with unique-per-run names:
 *        - e2e-ov-pass-<runTag>: `sh: echo …` → SUCCESS, run N=2 builds.
 *        - e2e-ov-fail-<runTag>: `sh: … exit 1` → FAILED, run M=3 builds.
 *      M=3 is load-bearing: TopFailingJobDao's HAVING clause only surfaces
 *      jobs with ≥3 terminal builds in the window + ≥1 FAILED, so the fail
 *      job clears the threshold while the pass job (0 failures) must NOT
 *      appear. The failure is deterministic (`exit 1` — the pattern spec 25
 *      documents as the fallback when a "missing binary" failure stops being
 *      deterministic).
 *   2. Drive all 5 builds through the REAL worker to terminal, hard-assert
 *      the expected statuses (2× SUCCESS, 3× FAILED).
 *   3. API oracles — every assertion is a monotone lower bound, a same-beat
 *      consistency check, or ownership-scoped (exact `===` only on rows
 *      keyed by our unique job names). A before/after DIFF on a global
 *      counter is racy by construction on the self-cleaning suite (#140):
 *      every neighbor spec DELETEs its builds in teardown, so the
 *      cluster-wide count legally SHRINKS between the two reads — `>=`
 *      tolerance covers concurrent additions only, not deletions.
 *        - stats.buildsToday ≥ N+M — a lower bound backed purely by rows WE
 *          own: our builds were queued after UTC midnight and still exist
 *          (teardown runs later), so no foreign insert OR delete can push
 *          the global counter below it. (buildsToday counts queued-since-
 *          UTC-midnight, so this is skipped — annotated, not silently — in
 *          the ~1/720 run that straddles a UTC midnight rollover.)
 *        - stats.successRate ∈ [0, 1) — strict <1 is sound: our M FAILED
 *          builds finished inside the 24h success-rate window, so a 1.0
 *          success rate would be a lie.
 *        - /activity contains EXACTLY our N+M builds under our job names,
 *          with the right per-build terminal status — the ownership-scoped
 *          "our builds really are counted" oracle.
 *        - /jobs/top-failing?since=24h lists the fail job with
 *          totalBuilds=3, failedBuilds=3, failureRate=1.0 and a
 *          lastFailedBuildId that is one of OUR build ids — and does NOT
 *          list the pass job.
 *   4. UI oracles — the Overview route renders the same truths:
 *        - kpi-builds tile == /api/v1/stats buildsToday read in the SAME
 *          poll beat (no-lies consistency: the tile mirrors the API, never
 *          a baseline captured minutes earlier), and the matched value
 *          honors the ≥ N+M ownership lower bound (same midnight guard),
 *        - kpi-fail-rate tile shows a real percentage (never "—"/"no data"
 *          once terminal builds exist),
 *        - the Top-failing card renders the fail job's row with the exact
 *          "3/3 failed (100%)" numerator/denominator,
 *        - Recent activity shows at least one of our builds (the card
 *          slices the 10 most recent terminal builds cluster-wide, so
 *          "contains ours" is the strongest claim that can't be flaked by a
 *          concurrent spec finishing its own builds).
 *
 * Ownership + teardown (e2e/README "Spec-ownership rule")
 * ───────────────────────────────────────────────────────
 * Both jobs + all builds are created by THIS spec under unique-per-run
 * names; `finally{}` tears down via `safeDeleteJobCascade` (cancel →
 * terminal wait → lease drain → scoped delete). Zero litter. Reads of
 * foreign rows (the cluster-wide stats) are bounds/consistency checks and
 * never mutated.
 */
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken, loginViaKeycloak } from '../../fixtures/auth-v3'
import { safeDeleteJobCascade } from '../../fixtures/teardown-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

/** N successful + M failed builds — the known workload. M ≥ 3 is load-bearing (see header). */
const N_SUCCESS = 2
const M_FAILED = 3

const TERMINAL = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'UNSTABLE', 'ERROR'])

const PASS_PIPELINE = `stages:
  - stage: run
    steps:
      - sh: echo overview-stats-pass-ok
`

// Deterministic failure: `exit 1` in a sh step — no dependency on a missing
// binary or the worker image contents.
const FAIL_PIPELINE = `stages:
  - stage: run
    steps:
      - sh: echo overview-stats-failing-on-purpose && exit 1
`

// ─── Wire shapes (mirroring titan-server DTO records) ───────────────────────

interface StatsDto {
  buildsToday: number
  successRate: number
  medianDurationMs: number
}

interface ActivityItemDto {
  id: string
  type: string
  ts: string
  jobName: string
  buildId: number
  status: string
  durationMs: number
}

interface ActivityPage {
  items: ActivityItemDto[]
  nextCursor: string | null
}

interface TopFailingJobDto {
  jobId: number
  jobName: string
  totalBuilds: number
  failedBuilds: number
  failureRate: number
  lastFailedBuildId: number | null
}

// ─── Small API helpers ───────────────────────────────────────────────────────

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
    // leave null — callers assert on ok/status with the raw body in the message
  }
  return { ok: r.ok(), status: r.status(), body: parsed, raw }
}

async function getStats(request: APIRequestContext, bearer: string): Promise<StatsDto> {
  const r = await apiJson<StatsDto>(request, bearer, 'GET', '/api/v1/stats')
  expect(r.ok && r.body != null, `GET /api/v1/stats HTTP ${r.status} body=${r.raw.slice(0, 400)}`).toBe(true)
  return r.body!
}

async function createJob(
  request: APIRequestContext,
  bearer: string,
  fullName: string,
  pipelineScript: string,
): Promise<number> {
  const r = await apiJson<{ id: number }>(request, bearer, 'POST', '/api/v1/jobs', {
    fullName,
    displayName: fullName,
    pipelineScript,
    enabled: true,
  })
  expect(r.status, `POST /api/v1/jobs (${fullName}) HTTP ${r.status} body=${r.raw.slice(0, 500)}`).toBe(201)
  expect(r.body!.id).toBeGreaterThan(0)
  return r.body!.id
}

async function triggerBuild(
  request: APIRequestContext,
  bearer: string,
  jobId: number,
): Promise<number> {
  const r = await apiJson<{ buildId: number }>(request, bearer, 'POST', `/api/v1/jobs/${jobId}/builds`, {})
  expect(r.ok, `POST /api/v1/jobs/${jobId}/builds HTTP ${r.status} body=${r.raw.slice(0, 400)}`).toBe(true)
  expect(r.body!.buildId).toBeGreaterThan(0)
  return r.body!.buildId
}

async function pollBuildTerminal(
  request: APIRequestContext,
  bearer: string,
  buildId: number,
  timeoutMs: number,
): Promise<string> {
  const start = Date.now()
  let last = ''
  while (Date.now() - start < timeoutMs) {
    const r = await apiJson<{ status: string }>(request, bearer, 'GET', `/api/v1/builds/${buildId}`)
    if (r.ok && r.body) {
      last = r.body.status
      if (TERMINAL.has(last)) return last
    }
    await new Promise((res) => setTimeout(res, 1_000))
  }
  throw new Error(`build ${buildId} not terminal after ${timeoutMs}ms (last status="${last}")`)
}

/**
 * Walk /api/v1/activity newest-first and collect every item belonging to one
 * of `jobNames`. Follows the opaque cursor for up to `maxPages` pages — our
 * builds finished seconds ago, so page 1 all but always carries them; the
 * extra pages make the oracle robust to a burst of concurrent terminal rows.
 */
async function collectOwnedActivity(
  request: APIRequestContext,
  bearer: string,
  jobNames: Set<string>,
  maxPages = 5,
): Promise<ActivityItemDto[]> {
  const found: ActivityItemDto[] = []
  let cursor: string | null = null
  for (let pageNo = 0; pageNo < maxPages; pageNo++) {
    const qs: string = cursor
      ? `?limit=100&before=${encodeURIComponent(cursor)}`
      : '?limit=100'
    const r = await apiJson<ActivityPage>(request, bearer, 'GET', `/api/v1/activity${qs}`)
    expect(r.ok && r.body != null, `GET /api/v1/activity HTTP ${r.status} body=${r.raw.slice(0, 400)}`).toBe(true)
    for (const item of r.body!.items) {
      if (jobNames.has(item.jobName)) found.push(item)
    }
    cursor = r.body!.nextCursor
    if (!cursor) break
  }
  return found
}

/** UTC calendar day marker — guards the buildsToday lower bound across a midnight rollover. */
function utcDay(): string {
  return new Date().toISOString().slice(0, 10)
}

// @golden sits BEFORE the parenthetical: dev/rig-smoke/golden-count.sh greps
// `describe\([^)]*@golden`, so a `)` ahead of the tag would drop this spec
// from the golden floor.
test.describe('v3 overview dashboard stats @golden (closes #138)', () => {
  test('KPI tiles, activity feed and top-failing reflect a seeded real workload exactly', async ({
    page,
    request,
  }) => {
    // 5 real worker builds + UI assertions — same budget class as spec 25.
    test.setTimeout(300_000)

    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
    const passJobName = `e2e-ov-pass-${runTag}`
    const failJobName = `e2e-ov-fail-${runTag}`
    let passJobId: number | undefined
    let failJobId: number | undefined

    try {
      // ── 0. Login (UI session for the dashboard) + API bearer. ──────────────
      await loginViaKeycloak(page, ENV)
      const bearer = await fetchBearerToken(ENV)

      // ── 1. Create the two owned jobs. ───────────────────────────────────────
      passJobId = await createJob(request, bearer, passJobName, PASS_PIPELINE)
      failJobId = await createJob(request, bearer, failJobName, FAIL_PIPELINE)

      // ── 2. Trigger N success + M failed builds; drive to terminal. ─────────
      // UTC day at queue time — if it still matches after the builds finish,
      // all N+M owned builds count toward buildsToday (queued-since-UTC-
      // midnight semantics) and the lower-bound oracles below are exact.
      const dayQueued = utcDay()
      const passBuildIds: number[] = []
      for (let i = 0; i < N_SUCCESS; i++) {
        passBuildIds.push(await triggerBuild(request, bearer, passJobId))
      }
      const failBuildIds: number[] = []
      for (let i = 0; i < M_FAILED; i++) {
        failBuildIds.push(await triggerBuild(request, bearer, failJobId))
      }

      const passStatuses = await Promise.all(
        passBuildIds.map((id) => pollBuildTerminal(request, bearer, id, 180_000)),
      )
      const failStatuses = await Promise.all(
        failBuildIds.map((id) => pollBuildTerminal(request, bearer, id, 180_000)),
      )
      expect(
        passStatuses,
        `pass builds ${JSON.stringify(passBuildIds)} must all be SUCCESS, got ${JSON.stringify(passStatuses)}`,
      ).toEqual(Array(N_SUCCESS).fill('SUCCESS'))
      expect(
        failStatuses,
        `fail builds ${JSON.stringify(failBuildIds)} must all be FAILED (the pipeline is 'exit 1'), ` +
          `got ${JSON.stringify(failStatuses)}`,
      ).toEqual(Array(M_FAILED).fill('FAILED'))

      // ── 3a. /api/v1/stats — race-free oracles (#140). ───────────────────────
      // NOT a before/after diff: on the self-cleaning suite every neighbor
      // spec DELETEs its builds in teardown, so a global counter legally
      // shrinks between two reads — `after >= before + N+M` flaked on exactly
      // that (2026-07-09 smoke, before=20 after=24). The lower bound below is
      // backed purely by rows WE own — queued today, torn down only in
      // finally{} — so no foreign insert or delete can falsify it.
      const statsAfter = await getStats(request, bearer)
      const dayAfter = utcDay()
      if (dayQueued === dayAfter) {
        expect(
          statsAfter.buildsToday,
          `stats.buildsToday=${statsAfter.buildsToday} must be ≥ ${N_SUCCESS + M_FAILED} — ` +
            `our ${N_SUCCESS + M_FAILED} builds were queued after UTC midnight and still ` +
            `exist, so the KPI is not counting real builds`,
        ).toBeGreaterThanOrEqual(N_SUCCESS + M_FAILED)
      } else {
        test.info().annotations.push({
          type: 'note',
          description:
            `UTC day rolled over mid-test (${dayQueued} → ${dayAfter}); ` +
            `buildsToday lower-bound assertion skipped — window semantics reset at midnight.`,
        })
      }
      // Our M FAILED builds finished inside the 24h success-rate window, so a
      // success rate of 1.0 would mean the query is not seeing real failures.
      expect(
        statsAfter.successRate,
        `stats.successRate=${statsAfter.successRate} must be < 1.0 — ${M_FAILED} FAILED ` +
          `builds just finished inside the 24h window`,
      ).toBeLessThan(1.0)
      expect(statsAfter.successRate).toBeGreaterThanOrEqual(0)
      expect(statsAfter.medianDurationMs).toBeGreaterThanOrEqual(0)

      // ── 3b. /api/v1/activity — ownership-scoped, exact. ────────────────────
      const owned = await collectOwnedActivity(request, bearer, new Set([passJobName, failJobName]))
      const ownedSummary = JSON.stringify(
        owned.map((i) => ({ job: i.jobName, buildId: i.buildId, status: i.status })),
      )
      expect(
        owned.length,
        `activity feed must carry EXACTLY our ${N_SUCCESS + M_FAILED} terminal builds ` +
          `(got ${owned.length}: ${ownedSummary})`,
      ).toBe(N_SUCCESS + M_FAILED)
      const ownedSuccess = owned.filter((i) => i.jobName === passJobName && i.status === 'SUCCESS')
      const ownedFailed = owned.filter((i) => i.jobName === failJobName && i.status === 'FAILED')
      expect(ownedSuccess.length, `expected ${N_SUCCESS} SUCCESS items for ${passJobName}: ${ownedSummary}`).toBe(N_SUCCESS)
      expect(ownedFailed.length, `expected ${M_FAILED} FAILED items for ${failJobName}: ${ownedSummary}`).toBe(M_FAILED)
      expect(new Set(ownedSuccess.map((i) => i.buildId))).toEqual(new Set(passBuildIds))
      expect(new Set(ownedFailed.map((i) => i.buildId))).toEqual(new Set(failBuildIds))
      for (const item of owned) {
        expect(item.type, `activity item ${item.id} type`).toBe('build.terminal')
        expect(Number.isFinite(Date.parse(item.ts)), `activity item ${item.id} ts="${item.ts}" not ISO`).toBe(true)
        expect(item.durationMs, `activity item ${item.id} durationMs`).toBeGreaterThanOrEqual(0)
      }

      // ── 3c. /api/v1/jobs/top-failing — ownership-scoped, exact. ────────────
      const topResp = await apiJson<TopFailingJobDto[]>(
        request,
        bearer,
        'GET',
        '/api/v1/jobs/top-failing?since=24h&limit=20',
      )
      expect(
        topResp.ok && Array.isArray(topResp.body),
        `GET /api/v1/jobs/top-failing HTTP ${topResp.status} body=${topResp.raw.slice(0, 400)}`,
      ).toBe(true)
      const topRows = topResp.body!
      const failRows = topRows.filter((r) => r.jobName === failJobName)
      expect(
        failRows.length,
        `top-failing must list ${failJobName} exactly once (M=${M_FAILED} ≥ 3 clears the HAVING ` +
          `threshold). Rows: ${JSON.stringify(topRows.map((r) => ({ name: r.jobName, f: r.failedBuilds, t: r.totalBuilds })))}`,
      ).toBe(1)
      const failRow = failRows[0]!
      expect(failRow.jobId, 'top-failing row jobId').toBe(failJobId)
      expect(Number(failRow.totalBuilds), 'top-failing totalBuilds').toBe(M_FAILED)
      expect(Number(failRow.failedBuilds), 'top-failing failedBuilds').toBe(M_FAILED)
      expect(failRow.failureRate, 'top-failing failureRate — 3/3 must be exactly 1.0').toBe(1.0)
      expect(
        failBuildIds,
        `lastFailedBuildId=${failRow.lastFailedBuildId} must be one of our fail builds ${JSON.stringify(failBuildIds)}`,
      ).toContain(failRow.lastFailedBuildId)
      // The all-green job must NOT appear (0 FAILED — filtered by HAVING).
      expect(
        topRows.find((r) => r.jobName === passJobName),
        `${passJobName} has zero failures and must not appear in top-failing`,
      ).toBeUndefined()

      // ── 4. The Overview dashboard renders the same truths. ─────────────────
      await page.goto(`${ENV.uiBaseUrl}/`)
      await expect(page.getByTestId('overview-page')).toBeVisible({ timeout: 15_000 })

      // kpi-builds: no-lies consistency — the tile must equal the API's
      // buildsToday when both are read in the SAME poll beat (#140). Comparing
      // the tile against a baseline captured minutes earlier is racy: neighbor
      // teardowns shrink the global count in between. The tile fetches on page
      // load and refetches every 30s (useStats refetchInterval), so the first
      // beats usually match; the 90s budget rides out ≥2 refetch cycles when a
      // neighbor mutates the count between the tile's fetch and ours.
      let sameBeatBuilds = -1
      await expect
        .poll(
          async () => {
            const txt = (
              await page.locator('[data-testid="kpi-builds"] .metric-value').textContent()
            )?.trim()
            const ui = Number(txt)
            const api = (await getStats(request, bearer)).buildsToday
            if (Number.isFinite(ui) && ui === api) {
              sameBeatBuilds = api
              return 'UI == API'
            }
            return `UI="${txt ?? ''}" API=${api}`
          },
          {
            timeout: 90_000,
            message:
              'kpi-builds tile never equaled /api/v1/stats buildsToday in the same beat — ' +
              'the KPI is rendering something other than the stats endpoint',
          },
        )
        .toBe('UI == API')
      // The same-beat value honors the ownership lower bound (same midnight
      // guard as the API oracle): the tile can never show fewer builds than
      // the N+M we own — queued today, still present until finally{}.
      if (dayQueued === utcDay()) {
        expect(
          sameBeatBuilds,
          `kpi-builds shows ${sameBeatBuilds} — must be ≥ our ${N_SUCCESS + M_FAILED} owned ` +
            `builds queued today`,
        ).toBeGreaterThanOrEqual(N_SUCCESS + M_FAILED)
      }

      // kpi-fail-rate must be a real percentage — never "—"/"no data yet" once
      // terminal builds exist in the window (the no-lies rendering contract).
      await expect
        .poll(
          async () =>
            (await page.locator('[data-testid="kpi-fail-rate"] .metric-value').textContent())?.trim() ?? '',
          { timeout: 15_000, message: 'kpi-fail-rate tile never rendered a percentage' },
        )
        .toMatch(/^\d+%$/)

      // Top-failing card: our fail job's row with the exact numerator/denominator.
      await expect(page.getByTestId('top-failing-jobs')).toBeVisible()
      const failRowUi = page.getByTestId(`top-failing-row-${failJobId}`)
      await expect(
        failRowUi,
        `Top-failing card must list ${failJobName} (jobId=${failJobId}) — it is a 100%-failure ` +
          `job with ${M_FAILED} builds in the last 24h. If this fails while the API assertion ` +
          `passed, the widget's limit=5 slice was crowded out by ≥5 other 100%-fail jobs — ` +
          `which on a zero-litter rig is itself a bug worth investigating.`,
      ).toBeVisible({ timeout: 15_000 })
      await expect(failRowUi).toContainText(failJobName)
      await expect(page.getByTestId(`top-failing-stats-${failJobId}`)).toHaveText(
        `${M_FAILED}/${M_FAILED} failed (100%)`,
      )

      // Recent activity: at least one of our builds is in the 10-row slice.
      await expect(page.getByTestId('overview-recent-activity')).toBeVisible()
      const ourActivitySelector = [...passBuildIds, ...failBuildIds]
        .map((id) => `[data-testid="activity-row-build-${id}"]`)
        .join(', ')
      await expect
        .poll(async () => page.locator(ourActivitySelector).count(), {
          timeout: 15_000,
          message:
            `Recent activity card shows none of our builds ` +
            `${JSON.stringify([...passBuildIds, ...failBuildIds])} — the feed is not ` +
            `reflecting real terminal builds (it slices the 10 most recent).`,
        })
        .toBeGreaterThan(0)
    } finally {
      // Ownership teardown: cancel-wait-delete BOTH jobs + all their builds.
      // safeDeleteJobCascade never yanks a leased task_queue row; on the happy
      // path everything is already terminal.
      if (failJobId !== undefined) {
        await safeDeleteJobCascade(request, failJobId)
      }
      if (passJobId !== undefined) {
        await safeDeleteJobCascade(request, passJobId)
      }
    }
  })
})

/**
 * 59-cron-trigger-fires — Layer-1 golden proof that cron triggers fire REAL
 * builds through the whole driver chain (closes #103).
 *
 * Driver chain under test (all production, zero seams):
 *   Quarkus @Scheduled 5s tick (TriggerEngine, quarkus.scheduler.titan.trigger)
 *     → DbTriggerSubsystem.owners() (re-parses titan.jobs.config_json per tick)
 *     → TriggerDispatch.evaluateOne (baseline-on-first-sight, never retroactive)
 *     → CronSchedule.isDue (minute-boundary walk, one-fire-per-boundary clamp)
 *     → DbTriggerScope.fire() (creates build + enqueues ORCHESTRATE SYNTHESIZE)
 *     → QueueProcessor → worker → terminal build status.
 *
 * Bug-class precedent this guards against:
 *   - #81: a production scheduler (TimerSweepWorker) simply never ran — nothing
 *     observed it. Same silent-death mode exists for TriggerEngine.
 *   - #106: cron fires DID happen but every fired build instantly FAILED with
 *     "unknown orchestration action 'null'" because DbTriggerScope enqueued a
 *     payload without an `action` key. Found while building THIS spec; fixed in
 *     PR #110. The SUCCESS assertion below is the standing regression net.
 *
 * Empirically-validated timing model (live-rig probe, 2026-07-09):
 *   - The trigger id in configJson is LOAD-BEARING: DbTriggerSubsystem re-parses
 *     config_json every tick, and the per-trigger baseline in titan.job_triggers
 *     is keyed (job_id, trigger_id). A missing/unstable id yields a fresh
 *     trigger identity each tick → baseline re-stamped forever → NEVER fires.
 *   - Baseline is stamped at first sight (no retroactive fire), so the first
 *     fire lands on the next minute boundary after the first tick that sees the
 *     job: observed create→first-fire latency ~25-70s. Budget 150s (two minute
 *     boundaries + tick slack, per the issue).
 *   - Once a fire happened, the next one is deterministic on the following
 *     minute boundary (~60s + ≤5s tick + fire latency). Budget 100s.
 *
 * Oracles:
 *   1. A build with triggerType === 'TitanTimerCause' appears without ANY
 *      manual POST /builds — the engine fired it.
 *   2. That build reaches terminal SUCCESS through the worker (post-#106; the
 *      one-step `sh: echo` pipeline keeps it fast + deterministic).
 *   3. A SECOND distinct cron build appears — recurring schedule, not a
 *      one-shot.
 *   4. Exactly-once: all cron builds' queuedAt floored to the minute are
 *      pairwise distinct (the CronSchedule last-fired mid-minute clamp is the
 *      contract — a double-fire on one boundary is the regression).
 *   5. Sad path: there is no disable API, and DbTriggerOwner.schedulingEnabled()
 *      reads job.enabled per tick — so `UPDATE titan.jobs SET enabled=false`
 *      (direct SQL, same pg fixture the teardown uses) must stop the firehose:
 *      after a 10s grace (2 ticks) we hold 75s (≥1 full minute boundary) and
 *      assert ZERO new cron builds.
 *
 * On fixed waits: CONSTITUTION §7 bans sleeps as *synchronisation*; the 10s
 * grace + 75s hold here are not synchronisation but the bounded OBSERVATION
 * WINDOW of an absence oracle (issue #103 prescribes exactly this "hold and
 * assert" shape). During the hold we still poll every 5s so a violation fails
 * fast with the offending build in the message.
 *
 * TEARDOWN IS SAFETY-CRITICAL for this spec: a leaked enabled `* * * * *` job
 * fires a build EVERY MINUTE FOREVER and poisons every future smoke run.
 * Defence in depth:
 *   a. The test-body finally{} disables the job via SQL immediately, whatever
 *      happened — this stops the firehose even if deletion later fails.
 *   b. afterAll (own timeout, own APIRequestContext — survives a body timeout
 *      aborting the finally) runs safeDeleteJobCascade (#59/#65: cancel
 *      non-terminal builds via the API, wait for lease drain, then delete).
 *   c. afterAll's LAST assertion re-checks via SQL that the job row and its
 *      titan.job_triggers rows are GONE, and throws if not — a leak fails the
 *      suite loudly instead of rotting silently.
 */
import { test, expect, request as pwRequest, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'
import { pgClient } from '../../fixtures/seed-v3'
import { safeDeleteJobCascade } from '../../fixtures/teardown-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const RUN_TAG = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
const JOB_FULL_NAME = `cron-fire-e2e-${RUN_TAG}`
// STABLE id — load-bearing, see header. Never generate this per-request.
const TRIGGER_ID = 'cron-e2e-1'
const CRON_CAUSE = 'TitanTimerCause'

const TERMINAL_STATUSES = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'UNSTABLE', 'ERROR'])

// Trivial one-step pipeline: keeps fired builds fast (seconds) so oracle 2
// (terminal SUCCESS) costs almost nothing on top of the fire waits.
const JOB_PIPELINE = `stages:
  - stage: fire
    steps:
      - sh: echo cron fire ok
`

// ─── DTOs ───────────────────────────────────────────────────────────────────

interface JobCreateResp {
  id: number
}
interface BuildListItem {
  id: number
  buildNumber: number
  status: string
  triggerType?: string
  queuedAt?: string
}
interface BuildsPage {
  items: BuildListItem[]
  total: number
}
interface JobTriggerDto {
  id: string
  type: string
  expression?: string
  lastFiredAt?: string
  lastError?: string
  nextFireAt?: string
}

// ─── Helpers ────────────────────────────────────────────────────────────────

const sleep = (ms: number) => new Promise((res) => setTimeout(res, ms))

async function apiGet<T>(
  api: APIRequestContext,
  bearer: string,
  path: string,
): Promise<{ ok: boolean; status: number; body: T | null; raw: string }> {
  const r = await api.get(`${API_BASE}${path}`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  const raw = await r.text()
  let body: T | null = null
  try {
    body = JSON.parse(raw) as T
  } catch {
    /* leave null */
  }
  return { ok: r.ok(), status: r.status(), body, raw }
}

/** All builds on the job whose cause is the cron/timer cause, oldest-first. */
async function listCronBuilds(
  api: APIRequestContext,
  bearer: string,
  jobId: number,
): Promise<BuildListItem[]> {
  const r = await apiGet<BuildsPage>(api, bearer, `/api/v1/jobs/${jobId}/builds?offset=0&limit=200`)
  if (!r.ok || !r.body) return []
  return r.body.items
    .filter((b) => b.triggerType === CRON_CAUSE)
    .sort((a, b) => a.buildNumber - b.buildNumber)
}

/** Trigger runtime state (lastFiredAt / lastError / nextFireAt) — diagnostics. */
async function triggerState(
  api: APIRequestContext,
  bearer: string,
  jobId: number,
): Promise<string> {
  const r = await apiGet<JobTriggerDto[]>(api, bearer, `/api/v1/jobs/${jobId}/triggers`)
  return r.raw.slice(0, 800)
}

async function setJobEnabled(jobId: number, enabled: boolean): Promise<void> {
  const client = pgClient()
  await client.connect()
  try {
    await client.query(`UPDATE titan.jobs SET enabled = $2 WHERE id = $1`, [jobId, enabled])
  } finally {
    await client.end()
  }
}

/** Rows left behind for this spec's job — MUST be all-zero after teardown. */
async function leakAudit(jobId: number): Promise<{ jobs: number; triggers: number; builds: number }> {
  const client = pgClient()
  await client.connect()
  try {
    const q = async (sql: string): Promise<number> => {
      const res = await client.query<{ n: string }>(sql, [jobId])
      return Number(res.rows[0]!.n)
    }
    return {
      jobs: await q(`SELECT count(*)::text AS n FROM titan.jobs WHERE id = $1`),
      triggers: await q(`SELECT count(*)::text AS n FROM titan.job_triggers WHERE job_id = $1`),
      builds: await q(`SELECT count(*)::text AS n FROM titan.builds WHERE job_id = $1`),
    }
  } finally {
    await client.end()
  }
}

// Shared across test ↔ afterAll so the safety-net teardown can find the job
// even when the body aborted before its finally{} completed.
let createdJobId: number | undefined

test.describe('v3 cron-trigger-fires @golden', () => {
  // Safety net (defence layer b+c in the header): runs even if the test body
  // timed out mid-flight. Own request context — the test-scoped `request`
  // fixture is not available in afterAll.
  test.afterAll(async () => {
    test.setTimeout(240_000)
    if (createdJobId === undefined) return
    const jobId = createdJobId

    // Stop the firehose first, delete second. Idempotent with the body's
    // finally{} (which already disabled on the happy path).
    await setJobEnabled(jobId, false).catch((e) =>
      console.error(`[59-cron] afterAll: disable of job ${jobId} failed: ${String(e)}`),
    )

    const ctx = await pwRequest.newContext()
    try {
      const result = await safeDeleteJobCascade(ctx, jobId)
      const leaks = await leakAudit(jobId)
      if (!result.deleted || leaks.jobs + leaks.triggers + leaks.builds > 0) {
        // A leaked * * * * * cron job would fire forever — fail the suite
        // LOUDLY. (Job is at least disabled by the UPDATE above, but rows
        // must not survive either.)
        throw new Error(
          `[59-cron] TEARDOWN LEAK for job ${jobId} ('${JOB_FULL_NAME}'): ` +
            `deleted=${result.deleted} leftoverBuildIds=${JSON.stringify(result.leftoverBuildIds)} ` +
            `residual rows=${JSON.stringify(leaks)}. Manual cleanup required — see teardown-v3.ts.`,
        )
      }
    } finally {
      await ctx.dispose()
    }
  })

  test('a * * * * * cron trigger fires real builds, exactly once per minute, until disabled', async ({
    request,
  }) => {
    // Worst-case wall clock: 150s (first fire) + 90s (terminal) + 100s
    // (second fire) + 10s grace + 75s hold + slack ≈ 455s. Cap at 480s.
    test.setTimeout(480_000)
    const bearer = await fetchBearerToken(ENV)

    try {
      // ── 1. Create the purpose-built cron job ──────────────────────────────
      const tCreate = Date.now()
      const jobCreate = await request.post(`${API_BASE}/api/v1/jobs`, {
        headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
        data: {
          fullName: JOB_FULL_NAME,
          displayName: 'Cron-fire E2E (issue #103)',
          pipelineScript: JOB_PIPELINE,
          configJson: JSON.stringify({
            triggers: [{ type: 'cron', id: TRIGGER_ID, spec: '* * * * *' }],
          }),
          enabled: true,
        },
      })
      const jobRaw = await jobCreate.text()
      expect(
        jobCreate.status(),
        `POST /api/v1/jobs failed: HTTP ${jobCreate.status()} body=${jobRaw.slice(0, 500)}`,
      ).toBe(201)
      const jobId = (JSON.parse(jobRaw) as JobCreateResp).id
      expect(jobId).toBeGreaterThan(0)
      createdJobId = jobId

      // ── 2. Oracle 1: the engine fires a build — no manual POST anywhere ──
      // Budget 150s = two minute boundaries + 5s tick slack (issue #103).
      let firstFire: BuildListItem | undefined
      {
        const deadline = tCreate + 150_000
        while (Date.now() < deadline && !firstFire) {
          const cronBuilds = await listCronBuilds(request, bearer, jobId)
          firstFire = cronBuilds[0]
          if (!firstFire) await sleep(2_000)
        }
        if (!firstFire) {
          const state = await triggerState(request, bearer, jobId)
          throw new Error(
            `#81-class regression: no ${CRON_CAUSE} build appeared on job ${jobId} within 150s ` +
              `of creation — TriggerEngine never fired. Trigger runtime state ` +
              `(lastFiredAt/lastError/nextFireAt): ${state}`,
          )
        }
      }
      const firstFireLatencyMs = Date.now() - tCreate
      console.log(
        `[59-cron] first fire: build ${firstFire.id} (#${firstFire.buildNumber}) ` +
          `queuedAt=${firstFire.queuedAt} — ${Math.round(firstFireLatencyMs / 1000)}s after create`,
      )

      // ── 3. Oracle 2: the fired build reaches terminal SUCCESS ────────────
      // Pre-#110 this terminal-FAILED inside the controller in <1s
      // ("unknown orchestration action 'null'", issue #106) — this assertion
      // is the standing net for that whole bug class.
      let firstFinal = firstFire.status
      {
        const deadline = Date.now() + 90_000
        while (Date.now() < deadline && !TERMINAL_STATUSES.has(firstFinal)) {
          await sleep(1_500)
          const r = await apiGet<BuildListItem>(request, bearer, `/api/v1/builds/${firstFire.id}`)
          if (r.ok && r.body?.status) firstFinal = r.body.status
        }
      }
      expect(
        firstFinal,
        `cron-fired build ${firstFire.id} must reach SUCCESS through the worker (one-step ` +
          `'sh: echo'). Got '${firstFinal}'. FAILED with "unknown orchestration action" means ` +
          `the #106 payload-shape fix regressed in DbTriggerScope.fire().`,
      ).toBe('SUCCESS')

      // ── 4. Oracle 3: a SECOND fire — the schedule recurs ─────────────────
      // Deterministic now: next minute boundary ≤60s away + 5s tick + fire
      // latency. Budget 100s.
      let secondFire: BuildListItem | undefined
      {
        const deadline = Date.now() + 100_000
        while (Date.now() < deadline && !secondFire) {
          const cronBuilds = await listCronBuilds(request, bearer, jobId)
          secondFire = cronBuilds.find((b) => b.id !== firstFire!.id)
          if (!secondFire) await sleep(2_000)
        }
        if (!secondFire) {
          const state = await triggerState(request, bearer, jobId)
          throw new Error(
            `cron fired once but not twice: no second ${CRON_CAUSE} build on job ${jobId} ` +
              `within 100s of build ${firstFire.id} going terminal — one-shot instead of ` +
              `recurring. Trigger runtime state: ${state}`,
          )
        }
      }
      console.log(
        `[59-cron] second fire: build ${secondFire.id} (#${secondFire.buildNumber}) ` +
          `queuedAt=${secondFire.queuedAt}`,
      )

      // ── 5. Oracle 4: exactly-once per minute boundary ────────────────────
      // CronSchedule's last-fired mid-minute clamp (CronSchedule.java L79-80)
      // is the contract: flooring every cron build's queuedAt to the minute
      // must yield pairwise-distinct values. A duplicate = double-fire.
      const allCron = await listCronBuilds(request, bearer, jobId)
      expect(allCron.length).toBeGreaterThanOrEqual(2)
      const minutes = allCron.map((b) => {
        expect(
          b.queuedAt,
          `cron build ${b.id} has no queuedAt — cannot verify the exactly-once oracle`,
        ).toBeTruthy()
        return b.queuedAt!.slice(0, 16) // ISO-8601 'YYYY-MM-DDTHH:MM'
      })
      expect(
        new Set(minutes).size,
        `double-fire detected: ${allCron.length} cron builds map to only ` +
          `${new Set(minutes).size} distinct minute boundaries. ` +
          `builds=${JSON.stringify(allCron.map((b) => ({ id: b.id, queuedAt: b.queuedAt })))}`,
      ).toBe(minutes.length)

      // ── 6. Oracle 5 (sad path): enabled=false stops the firehose ─────────
      // No disable API exists; DbTriggerOwner.schedulingEnabled() reads
      // job.enabled on every 5s tick, so a direct SQL flip is the product's
      // only off-switch. 10s grace = 2 ticks; then observe ≥1 full minute
      // boundary (75s) and require zero new cron builds.
      await setJobEnabled(jobId, false)
      await sleep(10_000) // grace: let in-flight tick/fire settle (2 ticks)

      const baseline = new Set((await listCronBuilds(request, bearer, jobId)).map((b) => b.id))
      const holdDeadline = Date.now() + 75_000
      while (Date.now() < holdDeadline) {
        const now = await listCronBuilds(request, bearer, jobId)
        const rogue = now.filter((b) => !baseline.has(b.id))
        expect(
          rogue,
          `job ${jobId} was disabled (enabled=false) but the engine STILL fired: ` +
            `${JSON.stringify(rogue.map((b) => ({ id: b.id, queuedAt: b.queuedAt })))} — ` +
            `DbTriggerOwner.schedulingEnabled() is not being honoured per tick.`,
        ).toEqual([])
        await sleep(5_000)
      }
    } finally {
      // Defence layer a: stop the firehose IMMEDIATELY, whatever happened
      // above. Deletion + leak verification live in afterAll (layers b+c).
      if (createdJobId !== undefined) {
        await setJobEnabled(createdJobId, false).catch((e) =>
          console.error(`[59-cron] finally: disable of job ${createdJobId} failed: ${String(e)}`),
        )
      }
    }
  })
})

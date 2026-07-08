/**
 * teardown-v3 — spec-ownership-safe cleanup for jobs whose builds ran
 * through the REAL engine (issue #59).
 *
 * Why this exists: pre-#59, spec teardowns cascade-DELETEd titan.task_queue
 * rows (directly, or transitively via `DELETE FROM titan.builds` — the FK is
 * ON DELETE CASCADE) while the worker still held a CLAIMED/PROCESSING lease
 * on them. Yanking a leased row out from under an executing worker wedged an
 * executor slot pre-#58 and still poisons the run's state post-#58.
 *
 * The rule (see "Spec-ownership rule" in e2e/README.md):
 *   1. Only delete rows your spec created (scope by the job id you created).
 *   2. Never delete a task_queue row in CLAIMED/PROCESSING. Drive the build
 *      to a terminal state via the public API first (POST /builds/{id}/cancel
 *      archives its task rows), then wait for the lease to drain.
 *
 * `safeDeleteJobCascade` implements both: cancel every non-terminal build via
 * the API, wait (bounded) for terminal status + zero leased task rows, then
 * delete the child rows + builds + job. If leased rows survive the bounded
 * wait, it deletes NOTHING for that job and returns the leftovers — per-run
 * unique job names mean orphaned rows cannot collide with the next run.
 */
import type { APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken } from './auth-v3'
import { pgClient } from './seed-v3'

const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const TERMINAL_STATUSES = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'UNSTABLE', 'ERROR'])

const sleep = (ms: number) => new Promise((res) => setTimeout(res, ms))

export interface SafeTeardownResult {
  /** Build ids that still were non-terminal / leased when the budget ran out. */
  leftoverBuildIds: number[]
  /** True when every row was deleted. */
  deleted: boolean
}

async function readBuilds(jobId: number): Promise<Array<{ id: number; status: string }>> {
  const client = pgClient()
  await client.connect()
  try {
    const res = await client.query<{ id: string; status: string }>(
      `SELECT id::text AS id, status FROM titan.builds WHERE job_id = $1`,
      [jobId],
    )
    return res.rows.map((r) => ({ id: Number(r.id), status: r.status }))
  } finally {
    await client.end()
  }
}

async function countLeasedTasks(buildIds: number[]): Promise<number> {
  if (buildIds.length === 0) return 0
  const client = pgClient()
  await client.connect()
  try {
    const res = await client.query<{ n: string }>(
      `SELECT count(*)::text AS n FROM titan.task_queue
        WHERE build_id = ANY($1::bigint[]) AND status IN ('CLAIMED','PROCESSING')`,
      [buildIds],
    )
    return Number(res.rows[0]!.n)
  } finally {
    await client.end()
  }
}

/**
 * Cancel-then-delete a job the calling spec created, without ever pulling a
 * leased task_queue row out from under the worker.
 *
 * @param api      Playwright request context (used for the cancel API calls).
 * @param jobId    The job the SPEC created — never someone else's.
 * @param opts.terminalBudgetMs  Max wait for cancelled builds to go terminal.
 * @param opts.drainBudgetMs     Max wait for CLAIMED/PROCESSING rows to drain.
 */
export async function safeDeleteJobCascade(
  api: APIRequestContext,
  jobId: number,
  opts: { terminalBudgetMs?: number; drainBudgetMs?: number } = {},
): Promise<SafeTeardownResult> {
  const terminalBudgetMs = opts.terminalBudgetMs ?? 90_000
  const drainBudgetMs = opts.drainBudgetMs ?? 60_000

  const builds = await readBuilds(jobId)
  const buildIds = builds.map((b) => b.id)
  const nonTerminal = builds.filter((b) => !TERMINAL_STATUSES.has(b.status))

  // 1. Abort still-live builds via the public API. The bearer from the test
  // body may have expired on long specs, so fetch a fresh one; cancel
  // failures (401/404/409-on-terminal) are tolerated — the terminal wait
  // below is the actual gate.
  if (nonTerminal.length > 0) {
    let bearer: string | null = null
    try {
      bearer = await fetchBearerToken(authEnv())
    } catch {
      bearer = null
    }
    if (bearer) {
      for (const b of nonTerminal) {
        await api
          .post(`${API_BASE}/api/v1/builds/${b.id}/cancel`, {
            headers: { Authorization: `Bearer ${bearer}` },
          })
          .catch(() => undefined)
      }
    }

    // 2. Bounded wait for every build to reach a terminal status.
    const deadline = Date.now() + terminalBudgetMs
    for (;;) {
      const current = await readBuilds(jobId)
      const live = current.filter((b) => !TERMINAL_STATUSES.has(b.status))
      if (live.length === 0) break
      if (Date.now() > deadline) {
        console.warn(
          `[teardown-v3] job ${jobId}: builds ${live
            .map((b) => `${b.id}=${b.status}`)
            .join(', ')} never went terminal within ${terminalBudgetMs}ms — ` +
            `leaving all rows in place (never delete under a live build).`,
        )
        return { leftoverBuildIds: live.map((b) => b.id), deleted: false }
      }
      await sleep(1_500)
    }
  }

  // 3. Bounded wait for leased task rows to drain (cancel archives them; a
  // PROCESSING leaf finishes or is reaped). We never delete a leased row.
  {
    const deadline = Date.now() + drainBudgetMs
    for (;;) {
      const leased = await countLeasedTasks(buildIds)
      if (leased === 0) break
      if (Date.now() > deadline) {
        console.warn(
          `[teardown-v3] job ${jobId}: ${leased} CLAIMED/PROCESSING task_queue ` +
            `row(s) still leased after ${drainBudgetMs}ms — leaving all rows in ` +
            `place rather than deleting under the worker.`,
        )
        return { leftoverBuildIds: buildIds, deleted: false }
      }
      await sleep(1_500)
    }
  }

  // 4. Now every build is terminal and no lease exists: delete the child
  // rows, the builds, and the job — all scoped to the job THIS spec created.
  // (FK cascades cover most children; explicit deletes keep this idempotent
  // on schema drift, mirroring the pre-#59 teardowns.)
  const client = pgClient()
  await client.connect()
  try {
    if (buildIds.length > 0) {
      for (const table of ['approvals', 'test_result', 'artifact', 'flow_nodes']) {
        await client
          .query(`DELETE FROM titan.${table} WHERE build_id = ANY($1::bigint[])`, [buildIds])
          .catch(() => undefined)
      }
      // Belt-and-braces even after the drain wait: never touch leased rows.
      await client
        .query(
          `DELETE FROM titan.task_queue
            WHERE build_id = ANY($1::bigint[])
              AND status NOT IN ('CLAIMED','PROCESSING')`,
          [buildIds],
        )
        .catch(() => undefined)
      await client.query(`DELETE FROM titan.builds WHERE id = ANY($1::bigint[])`, [buildIds])
    }
    await client.query(`DELETE FROM titan.jobs WHERE id = $1`, [jobId])
  } finally {
    await client.end()
  }
  return { leftoverBuildIds: [], deleted: true }
}

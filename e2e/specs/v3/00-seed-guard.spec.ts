/**
 * 00-seed-guard — the seed-data contract, asserted LOUD before anything else (#116).
 *
 * `rig/local/seed-data.sh` seeds a small fleet of demo jobs that several specs
 * treat as a CONTRACT:
 *
 *   - `titan-hello`  — the smallest real runnable pipeline. Anchors
 *     21-golden-path-real-build, 08-configure-trigger, 09-run-and-watch-build,
 *     log-search (build #1's 9 seeded log chunks), 06-sre-3am-journey.
 *   - `titan-ui`     — carries the seeded RUNNING build the gate/cancel specs
 *     (01, 02, 11) pause/approve/restore (the ONE sanctioned shared-mutable
 *     fixture, see e2e/README.md "Spec-ownership rule" §5).
 *   - `integration-tests` + the rest of the seed fleet — dashboard surfaces
 *     (overview, sparklines) assume a non-empty, plausible jobs table.
 *
 * When a seed job is missing, the downstream failures are misleading ("no
 * build appeared", "titan-hello job not found (200 jobs)") and burn hours of
 * triage — the 2026-07-09 incident behind #116. This guard turns that into a
 * red test with the fix in the message, in <1s, at the very front of the run.
 *
 * Forensic note (#116): titan-hello was never actually deleted in that
 * incident — ~200 accumulated "e2e-…"/"gp-…" litter jobs pushed it past the
 * first `?limit=200` page of the full_name-ordered jobs list. This guard asserts on
 * the DB row directly, so it is immune to pagination and fires only when the
 * seed row is REALLY gone (deleted or renamed).
 *
 * Ownership: READS only — this spec never mutates the seed rows (#59 rule).
 */
import { test, expect } from '@playwright/test'
import { pgClient } from '../../fixtures/seed-v3'

/**
 * The seed-contract job names. Extend when a spec starts anchoring on another
 * seeded row — and seed it in rig/local/seed-data.sh in the same PR.
 */
const SEED_CONTRACT_JOBS = ['titan-hello', 'titan-ui', 'integration-tests'] as const

test.describe('seed-data guard @golden', () => {
  test('seed-contract jobs exist on the rig (titan-hello, titan-ui, integration-tests)', async () => {
    const client = pgClient()
    await client.connect()
    let present: string[]
    try {
      const res = await client.query<{ full_name: string }>(
        `SELECT full_name FROM titan.jobs WHERE full_name = ANY($1::text[])`,
        [[...SEED_CONTRACT_JOBS]],
      )
      present = res.rows.map((r) => r.full_name)
    } finally {
      await client.end()
    }

    const missing = SEED_CONTRACT_JOBS.filter((name) => !present.includes(name))
    expect(
      missing,
      `\n─────────────────────────────────────────────────────────────────────\n` +
        `SEED CONTRACT BROKEN: seed job(s) missing from titan.jobs: ${missing.join(', ')}\n` +
        `\n` +
        `These rows are seeded by rig/local/seed-data.sh and are load-bearing\n` +
        `for the gate/cancel/golden-path/log specs. Something deleted or\n` +
        `renamed them — specs may ONLY delete jobs they created (#59/#116).\n` +
        `\n` +
        `Fix:  re-run  rig/local/seed-data.sh  against the rig, then find and\n` +
        `stop whatever removed the row (check titan.audit_log for JOB_DELETE,\n` +
        `and recent spec teardowns for non-own-row DELETEs).\n` +
        `─────────────────────────────────────────────────────────────────────\n`,
    ).toEqual([])
  })

  test('titan-hello build #1 (the seeded log-bearing SUCCESS build) exists', async () => {
    // log-search + 10-log-stream anchor on titan-hello#1's seeded log chunks;
    // a jobs row without its seeded build is still a broken contract.
    const client = pgClient()
    await client.connect()
    let count: number
    try {
      const res = await client.query<{ n: string }>(
        `SELECT count(*)::text AS n
           FROM titan.builds b
           JOIN titan.jobs j ON j.id = b.job_id
          WHERE j.full_name = 'titan-hello' AND b.build_number = 1`,
      )
      count = Number(res.rows[0]!.n)
    } finally {
      await client.end()
    }
    expect(
      count,
      `titan-hello build #1 is missing (jobs row may exist but the seeded ` +
        `SUCCESS build is gone) — re-run rig/local/seed-data.sh.`,
    ).toBeGreaterThan(0)
  })
})

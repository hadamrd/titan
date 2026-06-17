/**
 * parkour — adversarial seed helpers for the v3 parkour suite (issue #1061).
 *
 * The parkour suite needs three classes of "weird" data on every list page:
 *   (a) long-string rows  — 200-char title, unicode, special chars, emoji.
 *       Catches truncation, XSS escaping, and "I forgot title was nullable"
 *       crashes when the UI does `title.slice(0, N).toUpperCase()`.
 *   (b) null/empty-field rows — optional columns the backend serialises with
 *       `@JsonInclude(NON_NULL)` and thus OMITS from the response. The
 *       #1036 `scopes`-missing crash was this class. The UI must `??` these.
 *   (c) rapid-mutation rows — a row whose status changes 5× in 200ms. Surfaces
 *       stale-state bugs in react-query where the row is rendered from a
 *       cached snapshot newer than what the response just delivered.
 *
 * The helpers below seed those rows into the running rig's Postgres via the
 * same connection string `seed-v3.ts` uses, plus a cleanup helper that
 * removes anything created by a given test run (keyed on a unique prefix).
 *
 * Schema reference: titan-db-core/src/main/resources/io/adaptiq/titan/db/
 * migration/V1__init.sql.
 *
 * Why direct-to-Postgres instead of going through the HTTP API: the HTTP API
 * rejects most of the adversarial shapes (length validators, charset checks).
 * That is correct behaviour at the API boundary — but the UI has to survive
 * a database that already contains pre-existing rows from a less-strict era
 * (a real concern: every Titan deployment shipped before the length check
 * was added has rows the API would now reject). Seeding direct-to-DB models
 * exactly that production hazard.
 */
import pg from 'pg'

const DEFAULT_CONN = {
  host: process.env.TITAN_PG_HOST ?? 'localhost',
  port: Number(process.env.TITAN_PG_PORT ?? 5432),
  user: process.env.TITAN_PG_USER ?? 'titan',
  password: process.env.TITAN_PG_PASSWORD ?? 'titan-dev-only',
  database: process.env.TITAN_PG_DB ?? 'titan',
}

function client(): pg.Client {
  return new pg.Client(DEFAULT_CONN)
}

/** Long string composed of unicode + emoji + reserved HTML chars. 200 chars. */
export const ADVERSARIAL_LONG_TITLE =
  // 50 ASCII + 50 Cyrillic + 50 CJK + ~10 emoji + special chars, total ≥200.
  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' +
  'Тестовая ветка релиз-кандидата для платформы Титан ' +
  '测试分支用于发布候选版本的平台泰坦真的非常长真的真的真的长' +
  '🚀🔥💥🎉🛠️🐛📦🧪🧨🚨' +
  '<script>&"\'</'

export const ADVERSARIAL_SPECIAL_CHARS = `<>&"'/\\\n\t`

/** Unique-per-run prefix so cleanup can scope its DELETE safely. */
export function parkourPrefix(): string {
  return `parkour-${process.env.TITAN_PARKOUR_RUN_ID ?? `local-${Date.now()}`}`
}

export interface ParkourSeed {
  /** Prefix used in display names so cleanup can find seeded rows. */
  prefix: string
  /** Job IDs created by this seed. */
  jobIds: number[]
  /** Build IDs created by this seed. */
  buildIds: number[]
}

/**
 * Seed three adversarial jobs + a long-title build under each.
 *
 *   1. long-title job — title is ADVERSARIAL_LONG_TITLE; default_branch is
 *      a 100-char emoji-laden string. Builds rendered on /builds and /jobs.
 *   2. null-field job — display_name is NULL, default_branch is NULL,
 *      description is empty. Mimics `@JsonInclude(NON_NULL)` serialisation
 *      omitting these fields on the wire.
 *   3. churn job — has 5 builds inserted within a 200ms window so the row
 *      ordering "by createdAt DESC" must stay stable under polling.
 *
 * Returns a ParkourSeed the caller passes to `cleanParkourSeed` in afterAll.
 * Cleanup is idempotent so a hanging seed from a previous run doesn't poison
 * the next one — call `cleanParkourSeed(seedFor(prefix))` to wipe.
 */
export async function seedAdversarialRows(): Promise<ParkourSeed> {
  const prefix = parkourPrefix()
  const c = client()
  await c.connect()
  const jobIds: number[] = []
  const buildIds: number[] = []
  try {
    // 1. long-title job
    {
      const fullName = `${prefix}-long`
      const res = await c.query<{ id: string }>(
        `INSERT INTO titan.jobs (full_name, display_name, default_branch, repo_url, source_type, kind)
         VALUES ($1, $2, $3, 'https://example.invalid/repo', 'TITAN', 'PIPELINE')
         ON CONFLICT (full_name) DO UPDATE SET display_name = EXCLUDED.display_name
         RETURNING id::text AS id`,
        [fullName, ADVERSARIAL_LONG_TITLE, '🌿' + 'x'.repeat(99)],
      )
      const jobId = Number(res.rows[0]!.id)
      jobIds.push(jobId)
      const b = await c.query<{ id: string }>(
        `INSERT INTO titan.builds
           (job_id, build_number, status, started_at, finished_at,
            display_name, branch, commit_sha)
         VALUES ($1, 1, 'SUCCESS', NOW() - INTERVAL '5 min', NOW(),
                 $2, $3, $4)
         RETURNING id::text AS id`,
        [jobId, ADVERSARIAL_LONG_TITLE, '🚀' + 'b'.repeat(99), 'a'.repeat(40)],
      )
      buildIds.push(Number(b.rows[0]!.id))
    }

    // 2. null-field job
    {
      const fullName = `${prefix}-nulls`
      const res = await c.query<{ id: string }>(
        `INSERT INTO titan.jobs (full_name, display_name, default_branch, repo_url, source_type, kind)
         VALUES ($1, NULL, NULL, 'https://example.invalid/repo', 'TITAN', 'PIPELINE')
         ON CONFLICT (full_name) DO UPDATE SET display_name = NULL
         RETURNING id::text AS id`,
        [fullName],
      )
      const jobId = Number(res.rows[0]!.id)
      jobIds.push(jobId)
      const b = await c.query<{ id: string }>(
        `INSERT INTO titan.builds
           (job_id, build_number, status, started_at, finished_at,
            display_name, branch, commit_sha)
         VALUES ($1, 1, 'SUCCESS', NOW() - INTERVAL '1 min', NOW(),
                 NULL, NULL, NULL)
         RETURNING id::text AS id`,
        [jobId],
      )
      buildIds.push(Number(b.rows[0]!.id))
    }

    // 3. churn job — 5 builds inserted in rapid succession with monotonically
    //    increasing build_number; the UI's polling should never render them
    //    out of order or duplicate a row.
    {
      const fullName = `${prefix}-churn`
      const res = await c.query<{ id: string }>(
        `INSERT INTO titan.jobs (full_name, display_name, default_branch, repo_url, source_type, kind)
         VALUES ($1, $1, 'main', 'https://example.invalid/repo', 'TITAN', 'PIPELINE')
         ON CONFLICT (full_name) DO UPDATE SET display_name = EXCLUDED.display_name
         RETURNING id::text AS id`,
        [fullName],
      )
      const jobId = Number(res.rows[0]!.id)
      jobIds.push(jobId)
      for (let n = 1; n <= 5; n++) {
        const b = await c.query<{ id: string }>(
          `INSERT INTO titan.builds
             (job_id, build_number, status, started_at,
              display_name, branch, commit_sha)
           VALUES ($1, $2, 'RUNNING', NOW(),
                   $3, 'main', $4)
           RETURNING id::text AS id`,
          [jobId, n, `churn-${n}`, n.toString().padStart(40, '0')],
        )
        buildIds.push(Number(b.rows[0]!.id))
      }
    }
    return { prefix, jobIds, buildIds }
  } finally {
    await c.end()
  }
}

/**
 * Idempotently remove all rows created by `seedAdversarialRows()` for the
 * given seed. Safe to call multiple times. Safe to call with a stale seed
 * (matches by prefix, not by id, so a re-run with the same prefix cleans up
 * the previous run's leftovers too).
 */
export async function cleanParkourSeed(seed: ParkourSeed): Promise<void> {
  const c = client()
  await c.connect()
  try {
    // ORDER MATTERS — flow_nodes → task_queue → builds → jobs (FK direction).
    await c.query(
      `DELETE FROM titan.flow_nodes WHERE build_id IN (
         SELECT id FROM titan.builds WHERE job_id IN (
           SELECT id FROM titan.jobs WHERE full_name LIKE $1
         )
       )`,
      [`${seed.prefix}-%`],
    )
    await c.query(
      `DELETE FROM titan.task_queue WHERE queue_name LIKE $1`,
      [`${seed.prefix}-%`],
    )
    await c.query(
      `DELETE FROM titan.builds WHERE job_id IN (
         SELECT id FROM titan.jobs WHERE full_name LIKE $1
       )`,
      [`${seed.prefix}-%`],
    )
    await c.query(`DELETE FROM titan.jobs WHERE full_name LIKE $1`, [`${seed.prefix}-%`])
  } finally {
    await c.end()
  }
}

/**
 * Count how many builds currently exist in the rig. Used by the empty-state
 * spec to skip when the rig isn't actually empty (the spec is a no-op on
 * the normal seeded rig — operator-runnable only against a fresh DB).
 */
export async function countBuilds(): Promise<number> {
  const c = client()
  await c.connect()
  try {
    const res = await c.query<{ n: string }>(`SELECT count(*)::text AS n FROM titan.builds`)
    return Number(res.rows[0]!.n)
  } finally {
    await c.end()
  }
}

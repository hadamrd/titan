/**
 * seed-v3 — direct-to-Postgres test data helpers for the v3 rig.
 *
 * The local rig's `rig/local/seed-data.sh` seeds 2 jobs + 6 builds but no
 * paused-gate build, no test_result rows, no artifact rows. Tests that need
 * those shapes insert them here in `beforeAll` and clean up in `afterEach`.
 *
 * Schema reference: titan-db-core/src/main/resources/io/adaptiq/titan/db/
 * migration/V1__init.sql — flow_nodes (build_id, node_id PK; status RUNNING
 * is what GatesApi.listPending matches on).
 *
 * Connection defaults to localhost:5432 / titan / titan-dev-only / titan —
 * the rig/local docker-compose exposes 5432 on the host.
 */
import pg from 'pg'

const DEFAULT_CONN = {
  host: process.env.TITAN_PG_HOST ?? 'localhost',
  port: Number(process.env.TITAN_PG_PORT ?? 5432),
  user: process.env.TITAN_PG_USER ?? 'titan',
  password: process.env.TITAN_PG_PASSWORD ?? 'titan-dev-only',
  database: process.env.TITAN_PG_DB ?? 'titan',
}

export function pgClient(): pg.Client {
  return new pg.Client(DEFAULT_CONN)
}

export interface PausedGateSeed {
  buildId: number
  nodeId: string
}

/**
 * Find the first running titan-ui build (the seed script leaves build #1 of
 * the titan-ui job in RUNNING). Returns its `builds.id`.
 */
export async function findRunningUiBuildId(): Promise<number> {
  const client = pgClient()
  await client.connect()
  try {
    const res = await client.query<{ id: string }>(
      `SELECT b.id::text AS id
         FROM titan.builds b
         JOIN titan.jobs j ON j.id = b.job_id
        WHERE j.full_name = 'titan-ui' AND b.status = 'RUNNING'
        ORDER BY b.build_number DESC
        LIMIT 1`,
    )
    if (res.rows.length === 0) {
      throw new Error('No RUNNING titan-ui build found — seed-data.sh may not have run')
    }
    return Number(res.rows[0]!.id)
  } finally {
    await client.end()
  }
}

/**
 * Insert a STAGE flow_node + a paused gate STEP flow_node onto an existing
 * build, so GatesApi.listPending(buildId) returns it.
 *
 * The gate node has node_type='STEP', status='RUNNING', and a stable node_id
 * the tests can refer to (`gate.deploy-prod`). approvers/audit JSON lives
 * inside step_args_json — but GatesApi reads the approver list from the
 * baked PipelineModel, NOT from this row. For tests that just need a
 * pending-gate to render in the UI, the row alone is sufficient because the
 * UI calls /gates which inspects the model + this row's status.
 *
 * NOTE: A fully-baked PipelineModel is required for GatesApi.listPending to
 * actually return the gate via the API path. For now we ALSO bake a minimal
 * pipeline_model_json onto the build so model.getGates() finds the gate.
 */
export async function seedPendingGate(
  buildId: number,
  nodeId = 'gate.deploy-prod',
  gateName = 'Deploy to production',
  approvers: string[] = ['dev'],
): Promise<PausedGateSeed> {
  const client = pgClient()
  await client.connect()
  try {
    // Bake a minimal pipeline_model_json onto the build so TitanFlowExecution
    // .loadModel() finds the gate. The shape mirrors what the orchestrator
    // would write; only the gate-shaped slice is required for listPending.
    const model = {
      stages: [
        {
          id: 'stage.deploy-prod',
          name: 'Deploy to prod',
          gate: { id: nodeId, name: gateName, approvers, kind: 'manual' },
        },
      ],
      gates: [{ id: nodeId, name: gateName, approvers, kind: 'manual' }],
    }
    await client.query(
      `UPDATE titan.builds SET pipeline_model_json = $1 WHERE id = $2`,
      [JSON.stringify(model), buildId],
    )

    // Upsert the paused gate flow_node. status='RUNNING' is what GatesApi
    // matches; started_at is what the UI reads as awaitingSince.
    await client.query(
      `INSERT INTO titan.flow_nodes
         (build_id, node_id, node_type, display_name, status, started_at)
       VALUES ($1, $2, 'STEP', $3, 'RUNNING', NOW())
       ON CONFLICT (build_id, node_id)
         DO UPDATE SET status = 'RUNNING',
                       started_at = NOW(),
                       completed_at = NULL,
                       result_json = NULL`,
      [buildId, nodeId, gateName],
    )
    return { buildId, nodeId }
  } finally {
    await client.end()
  }
}

/** Remove a seeded gate flow_node. Idempotent. */
export async function cleanFlowNode(buildId: number, nodeId: string): Promise<void> {
  const client = pgClient()
  await client.connect()
  try {
    await client.query(`DELETE FROM titan.flow_nodes WHERE build_id = $1 AND node_id = $2`, [
      buildId,
      nodeId,
    ])
  } finally {
    await client.end()
  }
}

/** Read the current status of a flow_node. Returns null if not present. */
export async function readFlowNodeStatus(
  buildId: number,
  nodeId: string,
): Promise<string | null> {
  const client = pgClient()
  await client.connect()
  try {
    const res = await client.query<{ status: string }>(
      `SELECT status FROM titan.flow_nodes WHERE build_id = $1 AND node_id = $2`,
      [buildId, nodeId],
    )
    return res.rows.length === 0 ? null : (res.rows[0]!.status ?? null)
  } finally {
    await client.end()
  }
}

/** Read the current status of a build. Returns null if not present. */
export async function readBuildStatus(buildId: number): Promise<string | null> {
  const client = pgClient()
  await client.connect()
  try {
    const res = await client.query<{ status: string }>(
      `SELECT status FROM titan.builds WHERE id = $1`,
      [buildId],
    )
    return res.rows.length === 0 ? null : (res.rows[0]!.status ?? null)
  } finally {
    await client.end()
  }
}

/**
 * Set a build's status. Used by the cancel spec to reset the seeded
 * RUNNING build back to RUNNING after the test mutates it to ABORTED, so
 * downstream specs that depend on a running build keep working.
 *
 * ONLY use this on the designated seeded fixture build (see
 * findRunningUiBuildId) or on rows the calling spec inserted itself —
 * never on builds another spec (or the engine) is driving. See the
 * "Spec-ownership rule" section in e2e/README.md (#59).
 *
 * NOTE the non-terminal check is computed in JS and passed as a separate
 * boolean parameter: reusing `$2` in both `SET status = $2` and a
 * `$2 IN ('RUNNING','QUEUED')` comparison makes Postgres deduce two
 * different types for the same untyped parameter (varchar vs text) and
 * fail with "inconsistent types deduced for parameter $2" — which is how
 * the pre-#59 restore paths silently never restored anything.
 */
export async function setBuildStatus(buildId: number, status: string): Promise<void> {
  const client = pgClient()
  await client.connect()
  const clearFinishedAt = status === 'RUNNING' || status === 'QUEUED'
  try {
    await client.query(
      `UPDATE titan.builds
          SET status = $2,
              finished_at = CASE WHEN $3 THEN NULL ELSE finished_at END
        WHERE id = $1`,
      [buildId, status, clearFinishedAt],
    )
  } finally {
    await client.end()
  }
}

// ── Owned-job seeding (#59) ─────────────────────────────────────────────────
//
// Specs that need builds in a particular status mix MUST create their own
// job + build rows instead of flipping rows that belong to another spec or
// to the engine. These helpers insert engine-inert rows (no task_queue rows
// are ever created for them, so the worker never touches them) under a
// caller-supplied unique full_name, and delete exactly those rows afterwards.

export interface OwnedBuildSpec {
  /** One of QUEUED | RUNNING | SUCCESS | FAILED | ABORTED | UNSTABLE. */
  status: string
  /** Optional builds.display_name — useful as a search marker on /builds. */
  displayName?: string
  /** Optional builds.triggered_by — the /builds server-side search ILIKEs this. */
  triggeredBy?: string
}

export interface OwnedJobSeed {
  jobId: number
  fullName: string
  buildIds: number[]
}

/**
 * Insert a job (unique full_name — embed a per-run tag) plus one build row
 * per entry in `builds`, newest last. RUNNING builds get started_at=NOW()
 * and finished_at=NULL; QUEUED builds have neither; terminal builds get a
 * plausible started_at/finished_at/duration.
 *
 * The rows are engine-inert: nothing is enqueued on titan.task_queue, so no
 * worker will ever claim or mutate them, and `deleteOwnedJob` can remove
 * them without racing the engine.
 */
export async function seedOwnedJobWithBuilds(
  fullName: string,
  builds: OwnedBuildSpec[],
): Promise<OwnedJobSeed> {
  const client = pgClient()
  await client.connect()
  try {
    const jobRes = await client.query<{ id: string }>(
      `INSERT INTO titan.jobs (full_name, display_name, pipeline_script, config_json, enabled)
       VALUES ($1, $1, 'stages: []', '{}', TRUE)
       RETURNING id::text AS id`,
      [fullName],
    )
    const jobId = Number(jobRes.rows[0]!.id)
    const buildIds: number[] = []
    for (let i = 0; i < builds.length; i++) {
      const b = builds[i]!
      // Status-derived flags computed in JS and passed as dedicated boolean
      // params — reusing the status param in a text comparison trips the
      // "inconsistent types deduced" failure documented on setBuildStatus.
      const isQueued = b.status === 'QUEUED'
      const nonTerminal = b.status === 'RUNNING' || isQueued
      const res = await client.query<{ id: string }>(
        `INSERT INTO titan.builds
           (job_id, build_number, status, queued_at, started_at, finished_at,
            duration_ms, triggered_by, display_name)
         VALUES
           ($1, $2, $3,
            NOW() - INTERVAL '10 minutes' + ($8 * INTERVAL '1 second'),
            CASE WHEN $5 THEN NULL
                 WHEN $4 THEN NOW()
                 ELSE NOW() - INTERVAL '5 minutes' END,
            CASE WHEN $4 THEN NULL ELSE NOW() - INTERVAL '4 minutes' END,
            CASE WHEN $4 THEN NULL ELSE 60000 END,
            $6, $7)
         RETURNING id::text AS id`,
        [
          jobId,
          i + 1,
          b.status,
          nonTerminal,
          isQueued,
          b.triggeredBy ?? 'e2e-seed',
          b.displayName ?? null,
          // $8 — queued_at offset in seconds; a dedicated param (not a reuse
          // of $2) because `$2 * INTERVAL` would deduce $2 as float8 while
          // build_number needs integer (verified against live PG).
          i,
        ],
      )
      buildIds.push(Number(res.rows[0]!.id))
    }
    return { jobId, fullName, buildIds }
  } finally {
    await client.end()
  }
}

/**
 * Delete a job created via `seedOwnedJobWithBuilds` plus its builds.
 * ONLY safe for engine-inert seeded jobs (no task_queue rows can exist for
 * them). Jobs whose builds actually ran through the engine must go through
 * `safeDeleteJobCascade` (fixtures/teardown-v3.ts) instead, which cancels
 * via the API and waits for CLAIMED task rows to drain first.
 */
export async function deleteOwnedJob(jobId: number): Promise<void> {
  const client = pgClient()
  await client.connect()
  try {
    await client.query(`DELETE FROM titan.builds WHERE job_id = $1`, [jobId])
    await client.query(`DELETE FROM titan.jobs WHERE id = $1`, [jobId])
  } finally {
    await client.end()
  }
}

export interface QueuedTaskSeed {
  taskId: number
  queueName: string
}

/**
 * Insert a QUEUED task into titan.task_queue with a stable queue_name prefix
 * so the cleanup helper can find + remove the rows after the test. The
 * payload is minimal — the admin queue page reads taskId/queueName/priority
 * out of the queue endpoint, none of which need a real build.
 */
export async function seedQueuedTask(
  queueName: string,
  priority: number,
): Promise<QueuedTaskSeed> {
  const client = pgClient()
  await client.connect()
  try {
    const res = await client.query<{ id: string }>(
      `INSERT INTO titan.task_queue
         (type, queue_name, status, priority, payload_json,
          attempts, max_attempts, visibility_timeout_seconds, available_at)
       VALUES
         ('ORCHESTRATE', $1, 'QUEUED', $2, '{}'::jsonb,
          0, 3, 3600, NOW())
       RETURNING id::text AS id`,
      [queueName, priority],
    )
    return { taskId: Number(res.rows[0]!.id), queueName }
  } finally {
    await client.end()
  }
}

/**
 * Delete every row in titan.task_queue whose queue_name matches the prefix —
 * except rows the worker currently holds a lease on (#59: never delete a
 * CLAIMED/PROCESSING task out from under the worker; a leased synthetic task
 * fails and archives on its own, and the per-run prefix keeps any leftover
 * from colliding with the next run).
 */
export async function clearQueuedTasksByPrefix(prefix: string): Promise<void> {
  const client = pgClient()
  await client.connect()
  try {
    await client.query(
      `DELETE FROM titan.task_queue
        WHERE queue_name LIKE $1
          AND status NOT IN ('CLAIMED','PROCESSING')`,
      [`${prefix}%`],
    )
  } finally {
    await client.end()
  }
}

/** Count rows in titan.task_queue with status='QUEUED'. */
export async function countQueuedTasks(): Promise<number> {
  const client = pgClient()
  await client.connect()
  try {
    const res = await client.query<{ n: string }>(
      `SELECT count(*)::text AS n FROM titan.task_queue WHERE status = 'QUEUED'`,
    )
    return Number(res.rows[0]!.n)
  } finally {
    await client.end()
  }
}

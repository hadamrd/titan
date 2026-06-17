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
 */
export async function setBuildStatus(buildId: number, status: string): Promise<void> {
  const client = pgClient()
  await client.connect()
  try {
    await client.query(
      `UPDATE titan.builds
          SET status = $2,
              finished_at = CASE WHEN $2 IN ('RUNNING','QUEUED') THEN NULL ELSE finished_at END
        WHERE id = $1`,
      [buildId, status],
    )
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

/** Delete every row in titan.task_queue whose queue_name matches the prefix. */
export async function clearQueuedTasksByPrefix(prefix: string): Promise<void> {
  const client = pgClient()
  await client.connect()
  try {
    await client.query(
      `DELETE FROM titan.task_queue WHERE queue_name LIKE $1`,
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

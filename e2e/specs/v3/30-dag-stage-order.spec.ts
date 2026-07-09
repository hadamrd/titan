/**
 * 30-dag-stage-order @golden — DAG columns must render in pipeline-declared
 * (topological) order, not lexical-by-node_id order.
 *
 * Closes #922. Before the fix, the server's `GET /api/v1/builds/{id}/nodes`
 * endpoint orders by `node_id`, which on a 3-stage with-approval pipeline
 * returned `Approval, Deploy, Prepare` despite the YAML declaring
 * `Prepare -> Approval -> Deploy`. The UI then bucketed stages in first-seen
 * order, producing a wrong-direction graph.
 *
 * Strategy:
 *   1. API-side: hit `/api/v1/builds` and find a build whose `/nodes` response
 *      has at least one STAGE with a parent (i.e. a real chain). Compute the
 *      expected topological order from the parentIds graph.
 *   2. UI-side: navigate to `/builds/{id}`, read the DOM positions of the
 *      stage cards, and assert they're laid out left-to-right in the same
 *      topological order.
 *
 * Skips cleanly when no chain-containing build exists — the rig may have
 * been freshly seeded with only single-stage hellos.
 *
 * Pre-req: `task dev:titan` is up.
 */
import { test, expect } from '@playwright/test'
import { authEnv, fetchBearerToken, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()
// 18080 is the rig's server port (rig/local) — every other v3 spec defaults to
// it. 8080 was drift and killed this spec with ECONNREFUSED on smoke runs (#76).
const API = process.env.TITAN_API_URL ?? 'http://localhost:18080'

interface FlowNode {
  nodeId: string
  parentIds: string | null
  nodeType: string
  displayName: string | null
}

interface BuildSummary {
  id: number
}

/**
 * Topological order of STAGE nodes — same algorithm as the UI's
 * `DagView.topoOrder`, kept inline so the test stays self-contained.
 */
function expectedStageOrder(nodes: FlowNode[]): string[] {
  const stages = nodes.filter((n) => n.nodeType?.toUpperCase() === 'STAGE')
  const ids = new Set(stages.map((n) => n.nodeId))
  const remaining = new Map<string, number>()
  const children = new Map<string, string[]>()

  for (const n of stages) {
    const parents = (n.parentIds ?? '')
      .split(',')
      .map((p) => p.trim())
      .filter((p) => p.length > 0 && ids.has(p))
    remaining.set(n.nodeId, parents.length)
    for (const p of parents) {
      if (!children.has(p)) children.set(p, [])
      children.get(p)!.push(n.nodeId)
    }
  }

  const queue: string[] = []
  for (const n of stages) if (remaining.get(n.nodeId)! === 0) queue.push(n.nodeId)
  const out: string[] = []
  while (queue.length > 0) {
    const id = queue.shift()!
    out.push(id)
    for (const childId of children.get(id) ?? []) {
      const rem = remaining.get(childId)! - 1
      remaining.set(childId, rem)
      if (rem === 0) queue.push(childId)
    }
  }
  return out
}

test('@golden DAG columns render in pipeline-declared (topological) order', async ({ page }) => {
  test.setTimeout(60_000)
  const token = await fetchBearerToken(ENV)
  const authHeader = { Authorization: `Bearer ${token}` }

  // Find a build whose node graph has at least one parent edge between stages.
  const buildsRes = await page.request.get(`${API}/api/v1/builds?limit=50`, { headers: authHeader })
  expect(buildsRes.ok(), 'GET /api/v1/builds').toBeTruthy()
  const buildsPayload = (await buildsRes.json()) as { items?: BuildSummary[] } | BuildSummary[]
  const builds: BuildSummary[] = Array.isArray(buildsPayload)
    ? buildsPayload
    : (buildsPayload.items ?? [])

  let chosenBuild: number | null = null
  let chosenNodes: FlowNode[] = []
  for (const b of builds) {
    const nodesRes = await page.request.get(`${API}/api/v1/builds/${b.id}/nodes`, {
      headers: authHeader,
    })
    if (!nodesRes.ok()) continue
    const nodes = (await nodesRes.json()) as FlowNode[]
    const stages = nodes.filter((n) => n.nodeType?.toUpperCase() === 'STAGE')
    if (stages.length < 2) continue
    // Need at least one parent edge among stages — single-stage or all-root
    // builds can't exhibit the bug.
    const stageIds = new Set(stages.map((s) => s.nodeId))
    const hasEdge = stages.some((s) =>
      (s.parentIds ?? '')
        .split(',')
        .map((p) => p.trim())
        .some((p) => p.length > 0 && stageIds.has(p)),
    )
    if (hasEdge) {
      chosenBuild = b.id
      chosenNodes = nodes
      break
    }
  }

  test.skip(
    chosenBuild === null,
    'no multi-stage build with parent edges in the rig — re-run after seeding a with-approval pipeline',
  )

  const expected = expectedStageOrder(chosenNodes)
  expect(expected.length).toBeGreaterThanOrEqual(2)

  await loginViaKeycloak(page, ENV)
  await page.goto(`${ENV.uiBaseUrl}/builds/${chosenBuild}`)

  // Wait for the DAG canvas to mount.
  await expect(page.getByTestId('dag-canvas')).toBeVisible({ timeout: 15_000 })

  // Read the rendered x-coordinate (transform: translate(x,y)) of each stage
  // card by its data-id (xyflow stamps `data-id=<nodeId>` on its node DOM).
  // Falling back to bounding-box left if the transform isn't readable keeps
  // the assertion meaningful across xyflow internal refactors.
  const xs = await page.evaluate(async (ids: string[]) => {
    const out: Array<{ id: string; x: number }> = []
    for (const id of ids) {
      const el = document.querySelector<HTMLElement>(
        `.react-flow__node[data-id="${CSS.escape(id)}"]`,
      )
      if (!el) {
        out.push({ id, x: Number.NaN })
        continue
      }
      const m = /translate\(\s*(-?\d+(?:\.\d+)?)/.exec(el.style.transform ?? '')
      const x = m ? Number(m[1]) : el.getBoundingClientRect().left
      out.push({ id, x })
    }
    return out
  }, expected)

  // Every expected stage must have rendered AND its x must be strictly
  // greater than its predecessor's. Strict-ascending ensures we don't have
  // the bug case where two stages share / invert columns.
  const missing = xs.filter((row) => Number.isNaN(row.x)).map((row) => row.id)
  expect(missing, `stages missing from DOM: ${missing.join(',')}`).toEqual([])

  for (let i = 1; i < xs.length; i += 1) {
    expect(
      xs[i]!.x,
      `stage ${xs[i]!.id} must render to the RIGHT of ${xs[i - 1]!.id} ` +
        `(expected x[${i}]=${xs[i]!.x} > x[${i - 1}]=${xs[i - 1]!.x})`,
    ).toBeGreaterThan(xs[i - 1]!.x)
  }
})

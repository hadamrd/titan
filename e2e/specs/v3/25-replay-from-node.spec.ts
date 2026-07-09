/**
 * 25-replay-from-node — hard-asserts the "Replay from here" affordance against a
 * REAL, FAILED build (closes #321).
 *
 * Why this spec exists:
 *   #321 / spec 03 left replay-from-node `test.skip` with a TODO to seed a
 *   FAILED build whose flow_nodes are populated so the per-node "Replay from
 *   here" button has something to anchor on. PR #517-era shipped the button
 *   on every OK/FAIL node card (see titan-ui/src/routes/builds/$buildId.tsx
 *   L555-564); BuildService.replay (titan-server) materialises every
 *   upstream node as terminal SKIPPED with `reason: REPLAY` and re-runs from
 *   the anchor onward.
 *
 *   Asserting that path needs a real build with multiple terminal nodes (one
 *   FAILED, ≥1 predecessor SUCCESS) — seed-fixture builds carry the right
 *   shape for the UI but their pipeline_model_json is hand-rolled, so a
 *   replay against them blows up in BuildService.replay's "no synthesised
 *   model" guard. The only reliable way to get a parent build whose model +
 *   nodes are mutually consistent is to fire one through the engine.
 *
 * Strategy:
 *   1. PKCE login + extract bearer.
 *   2. POST /api/v1/jobs to create a *purpose-built* job
 *      `e2e-replay-node-<runTag>` whose pipeline has 3 sequential stages:
 *        - `prep`   — `sh: echo prep ok`        (will SUCCEED)
 *        - `build`  — `sh: echo build ok`       (will SUCCEED)
 *        - `it`     — `sh: ./gradlew integrationTest`  (will FAIL —
 *          the worker container ships no gradlew, the step is a sh-exec, so
 *          the process exits non-zero and the FAILED status is deterministic).
 *      Multi-stage layout is load-bearing: replay-from-node only proves the
 *      SKIPPED-upstream + RAN-downstream pattern when there are upstream
 *      nodes to skip.
 *   3. POST /api/v1/jobs/{id}/builds → parent buildId.
 *   4. Poll until terminal FAILED (with diagnostic dump on timeout).
 *   5. Fetch parent's /nodes; identify the FAILED step node (status==FAILED
 *      && nodeType==STEP) — this is our replay anchor.
 *   6. Navigate to /builds/{parent}, click the tree-row for the anchor node
 *      to select it (the per-node "Replay from here" button only appears in
 *      the bd-node-head once a node is selected — see $buildId.tsx L555).
 *   7. Click `Replay from here` and intercept the POST
 *      /api/v1/builds/{parent}/replay response to capture newBuildId.
 *   8. Poll replay build until terminal.
 *   9. Fetch replay's /nodes and hard-assert:
 *        - every parent-node whose nodeId !== anchor and is upstream-of-anchor
 *          appears as SKIPPED in the replay,
 *        - the anchor node ran fresh (status != SKIPPED + startedAt is set).
 *
 * Diagnostics:
 *   On any assertion failure, attach the parent's + replay's /nodes JSON
 *   so the side-by-side diff is one click away in the Playwright report.
 *
 * Hard rules from CONSTITUTION §6 / brief:
 *   - NO test.skip / test.fixme (the whole point of #321 is to remove that).
 *   - Deterministic waits only (poll loops + expect.poll, never sleep).
 *   - Ownership + teardown (#135 promotion audit): the job is created with a
 *     unique-per-run fullName (`e2e-replay-node-<runTag>`) so concurrent runs
 *     never collide, and `finally{}` tears it (+ parent AND replay builds)
 *     down via `safeDeleteJobCascade` — zero litter. The previous revision
 *     reused a fixed `replay-from-node-e2e-v2` job across runs and never
 *     deleted it, which violated the e2e/README spec-ownership rule and
 *     accumulated FAILED builds on the rig.
 */
import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { safeDeleteJobCascade } from '../../fixtures/teardown-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

interface BuildDto {
  id: number
  jobId: number
  buildNumber: number
  status: string
  startedAt: string | null
  finishedAt: string | null
}

interface FlowNodeDto {
  buildId: number
  nodeId: string
  parentIds: string | null
  nodeType: string
  displayName: string | null
  stepDescriptor: string | null
  status: string
  startedAt: string | null
  completedAt: string | null
  durationMs: number | null
}

const TERMINAL = new Set([
  'SUCCESS',
  'FAILED',
  'ABORTED',
  'CANCELLED',
  'UNSTABLE',
  'FAILURE',
])

// dependsOn is load-bearing: BuildService's REPLAY_FROM_NODE upstream-walk
// (QueueProcessor.computeUpstreamNodes) follows the DAG's dependsOn edges,
// NOT the YAML-file order. Sequential stages with no dependsOn yield zero
// upstream from any target — the SKIPPED-upstream assertion would be vacuous.
// We chain prep → build → it explicitly so replaying from `it` produces a
// SKIPPED `prep` + SKIPPED `build`.
const JOB_PIPELINE = `stages:
  - stage: prep
    steps:
      - sh: echo prep ok
  - stage: build
    dependsOn: [prep]
    steps:
      - sh: echo build ok
  - stage: it
    dependsOn: [build]
    steps:
      - sh: ./gradlew integrationTest
`

async function extractAccessToken(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    for (let i = 0; i < window.sessionStorage.length; i++) {
      const key = window.sessionStorage.key(i)
      if (!key || !key.startsWith('oidc.user:')) continue
      try {
        const raw = window.sessionStorage.getItem(key)
        if (!raw) continue
        const parsed = JSON.parse(raw) as { access_token?: string }
        if (parsed.access_token) return parsed.access_token
      } catch {
        // ignore
      }
    }
    return null
  })
  if (!token) throw new Error('no oidc.user access_token in sessionStorage post-login')
  return token
}

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
    // leave null
  }
  return { ok: r.ok(), status: r.status(), body: parsed, raw }
}

async function pollBuildUntilTerminal(
  request: APIRequestContext,
  bearer: string,
  buildId: number,
  timeoutMs: number,
): Promise<BuildDto> {
  const start = Date.now()
  let last: BuildDto | null = null
  let lastRaw = ''
  while (Date.now() - start < timeoutMs) {
    const r = await apiJson<BuildDto>(request, bearer, 'GET', `/api/v1/builds/${buildId}`)
    lastRaw = r.raw
    if (r.ok && r.body) {
      last = r.body
      if (TERMINAL.has(last.status)) return last
    }
    await new Promise((res) => setTimeout(res, 1_000))
  }
  throw new Error(
    `build ${buildId} did not reach terminal status in ${timeoutMs}ms. ` +
      `Last status=${last?.status ?? 'unknown'}. Last body=${lastRaw.slice(0, 500)}`,
  )
}

async function createJob(
  request: APIRequestContext,
  bearer: string,
  fullName: string,
): Promise<number> {
  // Plain create — the fullName carries a per-run unique runTag (#135
  // promotion audit), so there is nothing to reuse and a 409 is a real error.
  // The pre-promotion GET-or-create against a FIXED name violated the
  // spec-ownership rule (the job outlived every run and was never deleted).
  const created = await apiJson<{ id: number }>(request, bearer, 'POST', `/api/v1/jobs`, {
    fullName,
    displayName: 'Replay-from-node E2E',
    pipelineScript: JOB_PIPELINE,
    enabled: true,
  })
  if (!created.ok || !created.body) {
    throw new Error(
      `POST /api/v1/jobs failed: HTTP ${created.status} body=${created.raw.slice(0, 500)}`,
    )
  }
  return created.body.id
}

/**
 * Compute the transitive-upstream set of `anchorNodeId` from the parent's
 * flow_nodes. parentIds is the wire-encoded predecessor list (the API stores
 * it as a comma-separated string of nodeIds; empty/null means root).
 */
function upstreamOf(nodes: FlowNodeDto[], anchorNodeId: string): Set<string> {
  const byId = new Map(nodes.map((n) => [n.nodeId, n]))
  const upstream = new Set<string>()
  const visit = (id: string) => {
    const n = byId.get(id)
    if (!n) return
    const parents =
      n.parentIds && n.parentIds.length > 0
        ? n.parentIds.split(',').map((s) => s.trim()).filter(Boolean)
        : []
    for (const p of parents) {
      if (upstream.has(p)) continue
      upstream.add(p)
      visit(p)
    }
  }
  visit(anchorNodeId)
  return upstream
}

// @golden sits BEFORE the parenthetical: dev/rig-smoke/golden-count.sh greps
// `describe\([^)]*@golden`, so a `)` ahead of the tag would drop this spec
// from the golden floor.
test.describe('v3 replay-from-node @golden (closes #321)', () => {
  test('replays a FAILED build from the failing step — upstream SKIPPED, anchor RAN fresh', async ({
    page,
    request,
  }) => {
    test.setTimeout(240_000)
    let attachedDiagnostics = false
    let jobId: number | undefined
    let parentBuildId: number | undefined
    let replayBuildId: number | undefined
    let bearer: string | undefined

    const dumpDiagnostics = async (label: string) => {
      if (attachedDiagnostics) return
      attachedDiagnostics = true
      try {
        if (bearer && parentBuildId !== undefined) {
          const r = await apiJson<FlowNodeDto[]>(
            request,
            bearer,
            'GET',
            `/api/v1/builds/${parentBuildId}/nodes`,
          )
          await test.info().attach(`parent-nodes-${parentBuildId}.json`, {
            body: r.raw,
            contentType: 'application/json',
          })
        }
        if (bearer && replayBuildId !== undefined) {
          const r = await apiJson<FlowNodeDto[]>(
            request,
            bearer,
            'GET',
            `/api/v1/builds/${replayBuildId}/nodes`,
          )
          await test.info().attach(`replay-nodes-${replayBuildId}.json`, {
            body: r.raw,
            contentType: 'application/json',
          })
        }
        const png = await page.screenshot({ fullPage: true })
        await test.info().attach(`screenshot-${label}.png`, {
          body: png,
          contentType: 'image/png',
        })
      } catch (e) {
        await test.info().attach('diagnostics-error.txt', {
          body: String(e),
          contentType: 'text/plain',
        })
      }
    }

    try {
      // 1. PKCE login + bearer.
      await loginViaKeycloak(page, ENV)
      bearer = await extractAccessToken(page)

      // 2. Create the purpose-built job with a unique-per-run name (#135
      // promotion audit) — this spec OWNS every row it asserts on, and the
      // finally{} below deletes them all. No cross-run reuse.
      const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
      const jobName = `e2e-replay-node-${runTag}`
      jobId = await createJob(request, bearer, jobName)
      expect(jobId, `failed to resolve jobId for '${jobName}'`).toBeGreaterThan(0)

      // 3. Trigger a parent build via the public API.
      const triggered = await apiJson<{ buildId: number; buildNumber: number }>(
        request,
        bearer,
        'POST',
        `/api/v1/jobs/${jobId}/builds`,
        {},
      )
      expect(
        triggered.ok,
        `POST /api/v1/jobs/${jobId}/builds failed: HTTP ${triggered.status} ${triggered.raw.slice(0, 500)}`,
      ).toBe(true)
      parentBuildId = triggered.body!.buildId
      expect(parentBuildId).toBeGreaterThan(0)

      // 4. Wait for parent to fail.
      const parentFinal = await pollBuildUntilTerminal(request, bearer, parentBuildId, 90_000)
      expect(
        parentFinal.status,
        `parent build ${parentBuildId} expected to FAIL (./gradlew integrationTest has no ` +
          `gradlew in the worker), got ${parentFinal.status}. The pipeline shape is the load-bearing ` +
          `pre-condition for this spec — if FAILED stops being deterministic, switch the failing ` +
          `step to 'sh: exit 1'.`,
      ).toBe('FAILED')

      // 5. Fetch parent nodes; pick the failing STEP node as the replay anchor.
      const parentNodesResp = await apiJson<FlowNodeDto[]>(
        request,
        bearer,
        'GET',
        `/api/v1/builds/${parentBuildId}/nodes`,
      )
      expect(
        parentNodesResp.ok && Array.isArray(parentNodesResp.body),
        `GET /api/v1/builds/${parentBuildId}/nodes failed: HTTP ${parentNodesResp.status} ${parentNodesResp.raw.slice(0, 500)}`,
      ).toBe(true)
      const parentNodes = parentNodesResp.body!
      expect(
        parentNodes.length,
        `parent build ${parentBuildId} has no flow_nodes — engine did not emit a DAG`,
      ).toBeGreaterThan(2)

      // The replay endpoint validates nodeId against PipelineModel.getAllNodes(),
      // which today returns STAGES + GATES + PRECONDITIONS only — not STEPS
      // (see PipelineModel.java L227-233). The UI shows "Replay from here" on
      // step cards too, but the server rejects step nodeIds as
      // `node '<step-id>' does not exist in build N` — see Refs note in PR
      // body for the per-step UI/API mismatch follow-up. For this spec we
      // anchor on the failing STAGE node, which the server accepts and which
      // is the meaningful unit of replay anyway (a stage is a re-runnable
      // bucket; a step is the leaf of that bucket).
      const anchor = parentNodes.find((n) => n.status === 'FAILED' && n.nodeType === 'STAGE')
      expect(
        anchor,
        `no FAILED STAGE node in parent — cannot anchor replay. Nodes were: ` +
          JSON.stringify(parentNodes.map((n) => ({ id: n.nodeId, type: n.nodeType, st: n.status }))),
      ).toBeTruthy()
      const anchorNodeId = anchor!.nodeId

      // The anchor must have ≥1 transitive upstream — otherwise SKIPPED-upstream
      // is a vacuous assertion. The 3-stage pipeline guarantees this; assert it.
      const expectedUpstream = upstreamOf(parentNodes, anchorNodeId)
      expect(
        expectedUpstream.size,
        `anchor ${anchorNodeId} has 0 upstream nodes — pipeline shape regressed; the ` +
          `SKIPPED-upstream assertion would be vacuous`,
      ).toBeGreaterThan(0)

      // 6. Open the parent build page; select the anchor node in the tree rail.
      await page.goto(`${ENV.uiBaseUrl}/builds/${parentBuildId}`)
      const detailShell = page.locator('[data-testid="build-detail-v3"]')
      await expect(detailShell).toBeVisible({ timeout: 15_000 })

      const anchorRow = page.locator(`[data-testid="tree-row-${anchorNodeId}"]`)
      await expect(
        anchorRow,
        `tree-row-${anchorNodeId} did not render — TreeRail dropped the anchor`,
      ).toBeVisible({ timeout: 15_000 })
      await anchorRow.click()

      // 7. Click `Replay from here` (per-node, in the bd-node-head) and intercept
      //    the replay POST. The whole-build header button is labelled `Replay`
      //    (no "from here"), so the /from here/i filter pins us to the per-node
      //    affordance — guards against accidentally exercising the wrong path.
      const replayBtn = page.getByRole('button', { name: /replay from here/i })
      await expect(
        replayBtn,
        `'Replay from here' button not visible after selecting anchor — UI affordance regressed`,
      ).toBeVisible({ timeout: 10_000 })

      const replayPostPromise = page.waitForResponse(
        (r) =>
          r.url().includes(`/api/v1/builds/${parentBuildId}/replay`) &&
          r.request().method() === 'POST',
        { timeout: 15_000 },
      )
      await replayBtn.click()
      const replayResp = await replayPostPromise
      expect(
        replayResp.status(),
        `POST /api/v1/builds/${parentBuildId}/replay returned ${replayResp.status()} ` +
          `body=${await replayResp.text().catch(() => '')}`,
      ).toBe(201)
      const replayBody = (await replayResp.json()) as { newBuildId: number }
      replayBuildId = replayBody.newBuildId
      expect(replayBuildId, 'replay response missing newBuildId').toBeGreaterThan(0)

      // 8. Wait for replay to reach terminal status.
      const replayFinal = await pollBuildUntilTerminal(request, bearer, replayBuildId, 90_000)
      expect(
        TERMINAL.has(replayFinal.status),
        `replay build ${replayBuildId} did not reach terminal: ${replayFinal.status}`,
      ).toBe(true)

      // 9. Hard-assert the SKIPPED-upstream + RAN-anchor pattern on the replay.
      const replayNodesResp = await apiJson<FlowNodeDto[]>(
        request,
        bearer,
        'GET',
        `/api/v1/builds/${replayBuildId}/nodes`,
      )
      expect(
        replayNodesResp.ok && Array.isArray(replayNodesResp.body),
        `GET /nodes for replay ${replayBuildId} failed: HTTP ${replayNodesResp.status}`,
      ).toBe(true)
      const replayNodes = replayNodesResp.body!
      const replayById = new Map(replayNodes.map((n) => [n.nodeId, n]))

      // 9a. Every transitive-upstream node in the parent must appear in the
      //     replay with status SKIPPED.
      const notSkipped: Array<{ id: string; status: string }> = []
      for (const upId of expectedUpstream) {
        const rn = replayById.get(upId)
        if (!rn) {
          notSkipped.push({ id: upId, status: '<missing>' })
          continue
        }
        if (rn.status !== 'SKIPPED') {
          notSkipped.push({ id: upId, status: rn.status })
        }
      }
      expect(
        notSkipped,
        `replay ${replayBuildId} did not mark every upstream-of-anchor SKIPPED. ` +
          `Upstream set (parent): ${JSON.stringify([...expectedUpstream])}. ` +
          `Offenders: ${JSON.stringify(notSkipped)}. ` +
          `Replay nodes: ${JSON.stringify(replayNodes.map((n) => ({ id: n.nodeId, st: n.status })))}`,
      ).toEqual([])

      // 9b. The anchor node must have RAN FRESH in the replay — i.e. NOT
      //     SKIPPED, and startedAt is set. (Its terminal status will mirror
      //     the parent's FAILED because the same step still fails, but the
      //     load-bearing assertion is that the engine actually executed it.)
      const replayAnchor = replayById.get(anchorNodeId)
      expect(
        replayAnchor,
        `anchor ${anchorNodeId} missing in replay ${replayBuildId} — DAG materialisation broken`,
      ).toBeTruthy()
      expect(
        replayAnchor!.status,
        `anchor ${anchorNodeId} was SKIPPED in the replay — engine should have re-executed it`,
      ).not.toBe('SKIPPED')
      expect(
        replayAnchor!.startedAt,
        `anchor ${anchorNodeId} in replay has no startedAt — it never ran. Full node: ${JSON.stringify(replayAnchor)}`,
      ).not.toBeNull()
    } catch (err) {
      await dumpDiagnostics('failure')
      throw err
    } finally {
      // Ownership teardown (#135): cancel-wait-delete the job + parent AND
      // replay builds this run created. safeDeleteJobCascade never yanks a
      // leased task_queue row; both builds are terminal by the time the happy
      // path lands here, and on early failure it drives them terminal first.
      if (jobId !== undefined) {
        await safeDeleteJobCascade(request, jobId)
      }
    }
  })
})

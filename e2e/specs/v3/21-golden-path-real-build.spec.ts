/**
 * 21-golden-path-real-build — the customer's golden path against a REAL
 * worker-executed build.
 *
 * Why this spec exists (closes #514):
 *   Three production bugs slipped through inside three weeks (builds 21, 42,
 *   46): Pipeline tab empty, Logs tab empty, BAKE failure with no UI surface.
 *   In every case the API was correct and the UI silently rendered the empty
 *   state. The seeded specs (00..20) test the SEED SHAPE — they assert what
 *   `seed-data.sh` writes, not what the engine produces.
 *
 *   This spec drives the smallest possible real pipeline (`titan-hello`)
 *   end-to-end through the worker and asserts the UI matches the API for the
 *   three load-bearing surfaces a user actually opens after a build:
 *     - the Pipeline tab (stages + steps + status badge)
 *     - the Logs tab (at least one line, no "No log output yet")
 *     - the STATUS header pill
 *
 * Strategy:
 *   1. PKCE login via the SPA, pull bearer out of sessionStorage (matches
 *      spec 20 — single source of truth for "what bearer does the SPA have").
 *   2. Find `titan-hello`'s jobId via /api/v1/jobs (always seeded by
 *      rig/local/seed-data.sh — "titan-hello: a real, runnable, log-bearing
 *      SUCCESS build").
 *   3. Navigate to /jobs/$id, click "Run pipeline". Capture the trigger
 *      response (it carries buildId + buildNumber).
 *   4. Poll GET /api/v1/builds/$id until status ∈ {SUCCESS, FAILED, ABORTED}.
 *      Timeout 30s — titan-hello is the smallest possible real pipeline.
 *   5. Navigate to /builds/$buildId and assert UI matches API for the three
 *      surfaces.
 *
 * On failure, attach:
 *   - the API's /nodes JSON (so we can see what UI should have rendered)
 *   - the API's /logs first 500 chars (so we can see whether SSE was empty)
 *   - a screenshot of the failing UI state
 * The whole point: diagnosis is one-shot.
 */
import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

interface JobsPage {
  items: Array<{ id: number; fullName: string; displayName: string }>
  total: number
}

interface BuildDto {
  id: number
  jobId: number
  buildNumber: number
  status: string
  startedAt: string | null
  finishedAt: string | null
}

interface FlowNodeDto {
  nodeId: string
  status: string
  nodeType?: string
  displayName?: string
}

const TERMINAL = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'CANCELLED', 'UNSTABLE', 'FAILURE'])

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
    // leave as null
  }
  return { ok: r.ok(), status: r.status(), body, raw }
}

async function findTitanHelloJobId(
  request: APIRequestContext,
  bearer: string,
): Promise<number> {
  const r = await apiGet<JobsPage>(request, bearer, '/api/v1/jobs?offset=0&limit=200')
  if (!r.ok || !r.body) {
    throw new Error(`GET /api/v1/jobs failed: HTTP ${r.status} body=${r.raw.slice(0, 300)}`)
  }
  const hit = r.body.items.find((j) => j.fullName === 'titan-hello')
  if (!hit) {
    throw new Error(
      `titan-hello job not found in /api/v1/jobs response (${r.body.items.length} jobs). ` +
        `Is rig/local/seed-data.sh up to date? Available: ${r.body.items
          .map((j) => j.fullName)
          .join(', ')}`,
    )
  }
  return hit.id
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
    const r = await apiGet<BuildDto>(request, bearer, `/api/v1/builds/${buildId}`)
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

test.describe('v3 golden-path-real-build @golden', () => {
  test('titan-hello runs, Pipeline + Logs + STATUS all reflect engine truth', async ({
    page,
    request,
  }) => {
    test.setTimeout(120_000)
    let attachedDiagnostics = false
    let buildId: number | undefined
    let bearer: string | undefined

    // Diagnostics dumper — runs on any failure inside the test body. Calls
    // `test.info().attach()` so the failing run produces a single self-
    // describing artifact bundle (no need to ssh into CI).
    const dumpDiagnostics = async (label: string) => {
      if (attachedDiagnostics) return
      attachedDiagnostics = true
      try {
        if (buildId !== undefined && bearer !== undefined) {
          const nodes = await apiGet(request, bearer, `/api/v1/builds/${buildId}/nodes`)
          await test.info().attach(`nodes-${buildId}.json`, {
            body: nodes.raw,
            contentType: 'application/json',
          })
          const logs = await apiGet(request, bearer, `/api/v1/builds/${buildId}/logs`)
          await test.info().attach(`logs-${buildId}.txt`, {
            body: logs.raw.slice(0, 500),
            contentType: 'text/plain',
          })
        }
        const png = await page.screenshot({ fullPage: true })
        await test.info().attach(`screenshot-${label}-${buildId ?? 'pre'}.png`, {
          body: png,
          contentType: 'image/png',
        })
      } catch (e) {
        // Don't let diagnostics-collection itself fail the test in a confusing
        // way — surface the underlying assertion instead.
        await test.info().attach('diagnostics-error.txt', {
          body: String(e),
          contentType: 'text/plain',
        })
      }
    }

    try {
      // 1. PKCE login + extract bearer.
      await loginViaKeycloak(page, ENV)
      bearer = await extractAccessToken(page)

      // 2. Find titan-hello jobId.
      const jobId = await findTitanHelloJobId(request, bearer)

      // 3. Navigate to /jobs/$id (redirects to /pipelines/$id), click
      //    "Run pipeline". The UI now interposes a confirmation dialog
      //    ("About to run pipeline for …", TriggerPreviewModal — #641/#77):
      //    the POST fires only on Confirm.
      await page.goto(`${ENV.uiBaseUrl}/jobs/${jobId}`)
      const runBtn = page.getByRole('button', { name: /run pipeline/i })
      await expect(runBtn).toBeVisible({ timeout: 10_000 })

      // Record every trigger POST so the Cancel path below can prove NONE fired.
      let triggerPostCount = 0
      const isTriggerPost = (u: string, m: string) =>
        u.includes(`/api/v1/jobs/${jobId}/builds`) && m === 'POST'
      page.on('request', (r) => {
        if (isTriggerPost(r.url(), r.method())) triggerPostCount++
      })

      const confirmDialog = page.locator(
        '[data-testid="trigger-preview-backdrop"] [role="dialog"]',
      )

      // 3a. ADVERSARIAL: open the dialog, Cancel — the dialog closes and NO
      //     build is triggered. A Cancel that fires a POST is a sev1.
      await runBtn.click()
      await expect(confirmDialog).toBeVisible({ timeout: 10_000 })
      await expect(confirmDialog).toContainText(/about to run pipeline/i)
      await page.getByTestId('trigger-preview-cancel').click()
      await expect(confirmDialog).toBeHidden({ timeout: 5_000 })
      expect(
        triggerPostCount,
        'Cancel on the run-confirmation dialog fired a trigger POST — a build was queued without consent',
      ).toBe(0)

      // 3b. Re-open and Confirm — NOW the POST fires; capture its response.
      await runBtn.click()
      await expect(confirmDialog).toBeVisible({ timeout: 10_000 })
      const triggerRespPromise = page.waitForResponse(
        (r) =>
          r.url().includes(`/api/v1/jobs/${jobId}/builds`) &&
          r.request().method() === 'POST',
        { timeout: 10_000 },
      )
      await page.getByTestId('trigger-preview-confirm').click()
      const triggerResp = await triggerRespPromise
      expect(
        triggerResp.ok(),
        `trigger POST failed: HTTP ${triggerResp.status()} ${await triggerResp.text().catch(() => '')}`,
      ).toBe(true)
      const triggerBody = (await triggerResp.json()) as { buildId: number; buildNumber: number }
      buildId = triggerBody.buildId
      expect(buildId).toBeGreaterThan(0)

      // 4. Poll until terminal.
      const finalBuild = await pollBuildUntilTerminal(request, bearer, buildId, 30_000)
      expect(
        ['SUCCESS', 'FAILED', 'ABORTED'].includes(finalBuild.status),
        `build ${buildId} reached unexpected terminal status ${finalBuild.status}`,
      ).toBe(true)

      // 4b. Fetch the canonical node + log state from the API. These are the
      //     oracles the UI MUST agree with.
      const apiNodes = await apiGet<FlowNodeDto[]>(
        request,
        bearer,
        `/api/v1/builds/${buildId}/nodes`,
      )
      expect(
        apiNodes.ok && Array.isArray(apiNodes.body),
        `GET /nodes failed or returned non-array: HTTP ${apiNodes.status} body=${apiNodes.raw.slice(0, 300)}`,
      ).toBe(true)
      const nodes = apiNodes.body!
      expect(
        nodes.length,
        `API returned 0 flow_nodes for build ${buildId} — engine didn't emit nodes. ` +
          `Body: ${apiNodes.raw.slice(0, 300)}`,
      ).toBeGreaterThan(0)

      // 5. Open the build page.
      await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)

      // 6. DAG region (v3-stack, closes #539) — each assertion is its own
      //    expect() so failures pinpoint which specific surface broke.

      // 6a. Wait for the v3 build-detail shell.
      const detailShell = page.locator('[data-testid="build-detail-v3"]')
      await expect(detailShell).toBeVisible({ timeout: 10_000 })

      // 6b. The interactive xyflow canvas. xyflow injects `.react-flow`
      //     wrappers — our own data-testid lives on the canvas container.
      const dagCanvas = page.locator('[data-testid="dag-canvas"]')
      await expect(dagCanvas).toBeVisible({ timeout: 10_000 })

      // 6c. At least 1 xyflow node rendered. The chunky StackCard nodes
      //     are wrapped by xyflow's .react-flow__node container.
      const xyNodes = page.locator('.react-flow__node')
      await expect(xyNodes.first()).toBeVisible({ timeout: 15_000 })
      const uiNodeCount = await xyNodes.count()
      expect(
        uiNodeCount,
        `DAG rendered ${uiNodeCount} xyflow nodes but API has ${nodes.length} flow_nodes ` +
          `— UI dropped data on the floor (build-21-style regression).`,
      ).toBe(nodes.length)

      // 6d. The empty-state literal must NOT be present.
      await expect(page.getByText('No flow nodes recorded yet.')).not.toBeVisible()

      // 6e. The left tree rail renders one row per node. Use the testid
      //     prefix so a runtime layout shuffle doesn't break the count.
      const treeRows = page.locator('[data-testid^="tree-row-"]')
      await expect(treeRows.first()).toBeVisible({ timeout: 10_000 })
      expect(await treeRows.count()).toBe(nodes.length)

      // 6f. UI/API per-node STATUS agreement — build-24 regression. After
      //     the fix, NO node should display RUNNING once the API has
      //     declared every node terminal. The v3 StackCardNode tags each
      //     card with data-status=<wire status>, so we can assert on a
      //     stable attribute instead of label text.
      const apiAllTerminal = nodes.every((n) =>
        ['SUCCESS', 'FAILED', 'ABORTED', 'SKIPPED'].includes(n.status),
      )
      if (apiAllTerminal) {
        await expect
          .poll(
            async () => await page.locator('[data-status="RUNNING"]').count(),
            {
              message:
                `API reports every node terminal but DAG still shows RUNNING. ` +
                `Build-24 regression: useBuildNodes stale cache after terminal flip. ` +
                `API nodes: ${JSON.stringify(nodes.map((n) => ({ id: n.nodeId, s: n.status })))}`,
              timeout: 15_000,
              intervals: [500, 1_000, 2_000],
            },
          )
          .toBe(0)
      }

      // 7. Logs tab.
      await page.getByRole('tab', { name: /^logs$/i }).click()
      await expect(page.getByRole('tab', { name: /^logs$/i })).toHaveAttribute(
        'aria-selected',
        'true',
      )

      // 7a. "No log output yet." MUST NOT be visible after we give SSE a
      //     chance. The literal lives in LogScrubber.tsx.
      await expect(page.getByText('No log output yet.')).not.toBeVisible({ timeout: 15_000 })

      // 7b. At least one .log-line must render.
      const logLines = page.locator('.log-line')
      await expect(logLines.first()).toBeVisible({ timeout: 15_000 })
      const lineCount = await logLines.count()
      expect(
        lineCount,
        `Logs tab rendered 0 lines for a real build that ran — task_archive or SSE ` +
          `linkage broken (catches build-42 regression).`,
      ).toBeGreaterThan(0)

      // 8. STATUS header pill matches the API's terminal status. The v3
      //    topbar (.bd-topbar) renders the build-level StatusBadge.
      const headerStatus = page.locator('.bd-topbar').getByText(finalBuild.status, {
        exact: false,
      })
      await expect(headerStatus.first()).toBeVisible({ timeout: 5_000 })
    } catch (err) {
      await dumpDiagnostics('assertion-failure')
      throw err
    }
  })
})

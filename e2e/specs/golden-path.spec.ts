/**
 * golden-path — the SRE's day-zero loop asserted on the RENDERED build-detail
 * UI, end-to-end against the live local rig (issue #1166).
 *
 * WHY THIS SPEC EXISTS
 *   The generic scenario runner (`scenarios.spec.ts`) and the API-level specs
 *   (`matrix-fixture`, `golden-path-failure-triage`) assert on Titan's INTERNAL
 *   state — `flow_nodes` statuses and the REST log stream. They never open the
 *   build-detail page the customer actually looks at. So a regression that broke
 *   the red verdict badge, the failing-step log panel, secret masking IN THE
 *   RENDERED log viewer, or per-matrix-cell rendering would ship green.
 *
 *   This spec closes that gap. Every assertion below is against rendered DOM
 *   (`build-verdict-badge`, `dag-node-*`, `tree-row-*`, `.log-line`,
 *   `terminal-body`) — NOT the API. The API is used only to drive the build and
 *   to discover node ids; the verdicts are read off the screen.
 *
 * THE FOUR SURFACES (issue #1166 acceptance criteria)
 *   A. Failing step    — node-app-with-failing-test: verdict badge renders
 *                        FAILURE/red; the unit-test node's log panel shows the
 *                        vitest `FAIL ` line.
 *   B. Matrix          — java-matrix (jdk×profile, 4 cells, fail_fast:false):
 *                        all 4 cells render; the rigged jdk=21/profile=it cell
 *                        renders FAILED while the other 3 render SUCCESS; the
 *                        parent verdict rolls up FAILURE.
 *   C. Secret redaction— secret-redaction: the masked `****` token renders in
 *                        the log panel and the seeded raw secret renders ZERO
 *                        times across every line (direct / embedded / printenv).
 *   D. PR check status — drive a scenario via the PR-webhook path and assert the
 *                        commit check status the customer sees on the PR reaches
 *                        the terminal state matching the engine verdict.
 *
 * FAIL-LOUD, NEVER SILENT (issue #1166)
 *   - A missing rig, a failed credential seed, or an absent fixture is an
 *     EXPLICIT error naming the surface — never a green pass.
 *   - Surfaces whose RUNTIME dependency has not yet landed on `task dev:titan`
 *     (C needs the env+secret runtime #1094; D needs a GitHub App + fixture repo
 *     wired) skip with a LOUD, dependency-naming reason that is visible in the
 *     report — the project's accepted, auditable deferral idiom (cf.
 *     `scenarios.spec.ts` pending + `golden-path-failure-triage` test.fixme),
 *     not a silent `it.skip`.
 */
import * as fs from 'node:fs'
import * as path from 'node:path'
import { randomBytes } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { authEnv, fetchBearerToken, loginViaKeycloak } from '../fixtures/auth-v3'
import {
  GithubAppFixture,
  cutFixtureBranch,
  deleteRemoteBranch,
  ghAuthOk,
  listCommitStatuses,
  type CommitStatus,
} from '../fixtures/github-app'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const TERMINAL = new Set(['SUCCESS', 'FAILED', 'FAILURE', 'ABORTED', 'UNSTABLE', 'ERROR'])
const FAILED = new Set(['FAILED', 'FAILURE', 'ERROR'])
const SUCCESSFUL = new Set(['SUCCESS'])

const BUILD_TIMEOUT_MS = Number(process.env.TITAN_GOLDEN_PATH_TIMEOUT_MS ?? 90_000)

interface JobCreateResp { id: number }
interface BuildTriggerResp { buildId: number; buildNumber: number }
interface BuildDto { id: number; status: string; buildNumber?: number }
interface FlowNodeDto {
  buildId: number
  nodeId: string
  displayName?: string | null
  nodeType?: string | null
  status?: string | null
}

// ── REST helpers (drive the build; verdicts are read off the DOM) ────────────

async function apiGet<T>(
  api: APIRequestContext,
  bearer: string,
  pathPart: string,
): Promise<{ ok: boolean; status: number; body: T | null; raw: string }> {
  const r = await api.get(`${API_BASE}${pathPart}`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  const raw = await r.text()
  let body: T | null = null
  try { body = JSON.parse(raw) as T } catch { /* leave null */ }
  return { ok: r.ok(), status: r.status(), body, raw }
}

function pipelineYaml(fixture: string): string {
  const here = path.dirname(fileURLToPath(import.meta.url))
  return fs.readFileSync(
    path.resolve(here, '..', 'pipelines', fixture, 'titan-pipeline.yml'),
    'utf8',
  )
}

async function createJob(
  api: APIRequestContext,
  bearer: string,
  fullName: string,
  fixture: string,
): Promise<number> {
  const resp = await api.post(`${API_BASE}/api/v1/jobs`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: {
      fullName,
      displayName: `golden-path #1166 — ${fixture}`,
      pipelineScript: pipelineYaml(fixture),
      configJson: JSON.stringify({ triggers: [] }),
      enabled: true,
    },
  })
  expect(resp.status(), `job create for ${fixture}: ${await resp.text()}`).toBe(201)
  return (JSON.parse(await resp.text()) as JobCreateResp).id
}

async function triggerBuild(api: APIRequestContext, bearer: string, jobId: number): Promise<number> {
  const r = await api.post(`${API_BASE}/api/v1/jobs/${jobId}/builds`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: {},
  })
  expect(r.status(), `build trigger ${await r.text()}`).toBeLessThan(300)
  const body = JSON.parse(await r.text()) as BuildTriggerResp
  expect(body.buildId, 'trigger returned no buildId').toBeGreaterThan(0)
  return body.buildId
}

async function pollTerminal(
  api: APIRequestContext,
  bearer: string,
  buildId: number,
  budgetMs = BUILD_TIMEOUT_MS,
): Promise<string> {
  const deadline = Date.now() + budgetMs
  let last = 'QUEUED'
  while (Date.now() < deadline) {
    const r = await apiGet<BuildDto>(api, bearer, `/api/v1/builds/${buildId}`)
    if (r.ok && r.body?.status) {
      last = r.body.status
      if (TERMINAL.has(last)) return last
    }
    await new Promise((res) => setTimeout(res, 1_000))
  }
  throw new Error(`build ${buildId} not terminal within ${budgetMs}ms; last=${last}`)
}

async function fetchNodes(
  api: APIRequestContext,
  bearer: string,
  buildId: number,
): Promise<FlowNodeDto[]> {
  const r = await apiGet<FlowNodeDto[]>(api, bearer, `/api/v1/builds/${buildId}/nodes`)
  expect(r.ok && Array.isArray(r.body), `GET /builds/${buildId}/nodes failed: ${r.raw.slice(0, 300)}`).toBe(true)
  expect(r.body!.length, `engine emitted zero flow_nodes for build ${buildId}`).toBeGreaterThan(0)
  return r.body!
}

/** Open the rendered build-detail page for a build, asserting the v3 shell mounts. */
async function openBuildDetail(page: Page, buildId: number): Promise<void> {
  await loginViaKeycloak(page, ENV)
  await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)
  await expect(
    page.locator('[data-testid="build-detail-v3"]'),
    `v3 build-detail shell did not render for build ${buildId}`,
  ).toBeVisible({ timeout: 20_000 })
}

/** The rendered overall verdict badge's raw status (read off the DOM, not the API). */
async function renderedVerdict(page: Page): Promise<string> {
  const badge = page.locator('[data-testid="build-verdict-badge"]')
  await expect(badge, 'overall verdict badge (build-verdict-badge) is absent from the header').toBeVisible({
    timeout: 15_000,
  })
  return (await badge.getAttribute('data-status')) ?? ''
}

function findStage(nodes: FlowNodeDto[], display: string): FlowNodeDto | null {
  return (
    nodes.find((n) => (n.displayName ?? '').trim() === display && (n.nodeType ?? '').toUpperCase() === 'STAGE')
    ?? nodes.find((n) => (n.displayName ?? '').trim() === display)
    ?? null
  )
}

test.describe('golden-path UI @golden @sre @1166', () => {
  // Each block creates its own job; serial keeps the shared rig calm and the
  // report readable. Per-test timeouts are set explicitly below.
  test.describe.configure({ mode: 'serial' })

  let bearer: string
  const runTag = randomBytes(4).toString('hex')

  test.beforeAll(async () => {
    // A missing rig / unreachable Keycloak is an EXPLICIT failure here, before
    // any block runs — never a silent green.
    bearer = await fetchBearerToken(ENV)
    expect(bearer.length, 'Keycloak returned an empty bearer — is the rig up?').toBeGreaterThan(10)
  })

  // ── A. Failing step → red verdict + FAIL log line, all on the DOM ──────────
  test('A. failing step: verdict badge renders FAILURE/red and the unit-test log shows the FAIL line', async ({
    request,
    page,
  }) => {
    test.setTimeout(BUILD_TIMEOUT_MS + 90_000)

    const jobId = await createJob(request, bearer, `gp-fail-${runTag}`, 'node-app-with-failing-test')
    const buildId = await triggerBuild(request, bearer, jobId)
    const status = await pollTerminal(request, bearer, buildId)
    expect(
      FAILED.has(status),
      `node-app-with-failing-test has one intentionally-failing vitest — expected a FAILED-class verdict, got ${status}`,
    ).toBe(true)

    const nodes = await fetchNodes(request, bearer, buildId)

    // Track uncaught JS errors — a crashing log panel must not pass silently.
    const pageErrors: string[] = []
    page.on('pageerror', (e) => pageErrors.push(String(e)))

    await openBuildDetail(page, buildId)

    // (a) The overall verdict badge renders the RED failure bucket. `data-status`
    // is the raw verdict; the colour is the StatusDot `fail` variant.
    const verdict = await renderedVerdict(page)
    expect(
      FAILED.has(verdict),
      `build-verdict-badge rendered '${verdict}', expected a FAILED-class verdict — the red badge regressed`,
    ).toBe(true)

    // At least one node renders with a FAILED-class data-status in the DAG.
    await expect(
      page.locator('[data-status="FAILED"], [data-status="FAILURE"], [data-status="ERROR"]').first(),
      'no node rendered a FAILED-class data-status — the failing-step indicator regressed',
    ).toBeVisible({ timeout: 15_000 })

    // (b) Click the unit-test node, open Logs, assert the vitest FAIL line is
    // present in the rendered log panel (DOM, not the API).
    const unitTest = findStage(nodes, 'unit-test')
    expect(unitTest, 'unit-test stage missing from flow_nodes').not.toBeNull()
    const row = page.locator(`[data-testid="tree-row-${unitTest!.nodeId}"]`)
    if (await row.count() > 0) await row.first().click()

    const logsTab = page.getByRole('tab', { name: /^logs$/i })
    if (await logsTab.count() > 0) await logsTab.click()

    await expect(
      page.getByText('No log output yet.'),
      'the failing build rendered an empty log panel',
    ).not.toBeVisible({ timeout: 20_000 })

    await expect(
      page.locator('.log-line', { hasText: /FAIL / }).first(),
      'the log panel rendered no `FAIL ` line — vitest output was dropped from the rendered DOM',
    ).toBeVisible({ timeout: 20_000 })

    expect(pageErrors, `uncaught JS errors while rendering the failing build: ${JSON.stringify(pageErrors)}`).toEqual([])
  })

  // ── B. Matrix: 4 cells render; rigged cell red, siblings green; parent red ──
  test('B. matrix: all 4 cells render, the rigged cell is FAILED, 3 siblings SUCCESS, parent rolls up FAILURE', async ({
    request,
    page,
  }) => {
    test.setTimeout(BUILD_TIMEOUT_MS + 90_000)

    const jobId = await createJob(request, bearer, `gp-matrix-${runTag}`, 'java-matrix')
    const buildId = await triggerBuild(request, bearer, jobId)
    const status = await pollTerminal(request, bearer, buildId)
    expect(
      FAILED.has(status),
      `matrix with one rigged cell + fail_fast:false must roll up FAILED, got ${status}`,
    ).toBe(true)

    const nodes = await fetchNodes(request, bearer, buildId)
    // The 4 cells are STAGE nodes whose ids encode the axis tuple
    // (build-jdk-<v>-profile-<v>). Discover them from the API; assert them on DOM.
    const cells = nodes.filter(
      (n) =>
        (n.nodeType ?? '').toUpperCase() === 'STAGE' &&
        (n.nodeId ?? '').toLowerCase().startsWith('build-jdk-'),
    )
    expect(
      cells.length,
      `expected 4 matrix cell stages, got ${cells.length}: ${cells.map((c) => `${c.nodeId}=${c.status}`).join(', ')}`,
    ).toBe(4)

    const rigged = cells.find(
      (c) => c.nodeId.toLowerCase().includes('jdk-21') && c.nodeId.toLowerCase().includes('profile-it'),
    )
    expect(rigged, 'the rigged jdk=21/profile=it cell is missing from flow_nodes').toBeTruthy()

    await openBuildDetail(page, buildId)

    // Parent verdict rolls up red on the DOM.
    const verdict = await renderedVerdict(page)
    expect(FAILED.has(verdict), `parent verdict badge rendered '${verdict}', expected FAILED-class`).toBe(true)

    // All 4 cells render — each as a DAG card AND a tree-rail row.
    for (const cell of cells) {
      await expect(
        page.locator(`[data-testid="dag-node-${cell.nodeId}"]`),
        `matrix cell ${cell.nodeId} did not render as a DAG card`,
      ).toBeVisible({ timeout: 15_000 })
      await expect(
        page.locator(`[data-testid="tree-row-${cell.nodeId}"]`),
        `matrix cell ${cell.nodeId} did not render as a tree-rail row`,
      ).toBeVisible()
    }

    // Per-cell verdict on the DOM: rigged cell FAILED, the other 3 SUCCESS.
    for (const cell of cells) {
      const rendered = await page
        .locator(`[data-testid="dag-node-${cell.nodeId}"]`)
        .getAttribute('data-status')
      if (cell.nodeId === rigged!.nodeId) {
        expect(
          FAILED.has(rendered ?? ''),
          `rigged cell ${cell.nodeId} rendered '${rendered}', expected FAILED-class`,
        ).toBe(true)
      } else {
        expect(
          SUCCESSFUL.has(rendered ?? ''),
          `sibling cell ${cell.nodeId} rendered '${rendered}', expected SUCCESS ` +
            `(fail_fast:false must let siblings finish green)`,
        ).toBe(true)
      }
    }
  })

  // ── C. Secret redaction: **** renders, raw secret renders zero times ───────
  test('C. secret redaction: the masked **** token renders and the raw secret never appears in the log panel', async ({
    request,
    page,
  }) => {
    test.setTimeout(BUILD_TIMEOUT_MS + 90_000)

    // A fresh structured sentinel per run so a stale log can never false-pass.
    const sentinel = `sentinel-redact-${randomBytes(6).toString('hex')}-canary`

    // #83: the seeded credential is deleted in finally{} so an aborted or
    // completed run never leaves a row that 400s the next run's create.
    let credentialId: number | undefined
    try {
      // Seed the secret the fixture references (`${{ secrets.E2E_REDACTION_TOKEN }}`).
      // A FAILED seed is an EXPLICIT error — never a silent skip (issue #1166).
      credentialId = await seedOrRotateCredential(request, bearer, 'E2E_REDACTION_TOKEN', sentinel)

      const jobId = await createJob(request, bearer, `gp-secret-${runTag}`, 'secret-redaction')
      const buildId = await triggerBuild(request, bearer, jobId)
      await pollTerminal(request, bearer, buildId)

      await openBuildDetail(page, buildId)
      // Open the secret step's log panel.
      const nodes = await fetchNodes(request, bearer, buildId)
      const stage = findStage(nodes, 'use-secret')
      if (stage) {
        const row = page.locator(`[data-testid="tree-row-${stage.nodeId}"]`)
        if (await row.count() > 0) await row.first().click()
      }
      const logsTab = page.getByRole('tab', { name: /^logs$/i })
      if (await logsTab.count() > 0) await logsTab.click()

      const body = page.locator('[data-testid="terminal-body"]')
      await expect(body, 'log panel (terminal-body) did not render').toBeVisible({ timeout: 20_000 })
      const rendered = (await body.innerText()) ?? ''

      // Capability gate: the env+secret RUNTIME (#1094) injects the value the
      // masker then redacts. Until it lands the fixture's `${{ secrets.* }}` is
      // a literal string (never injected, never masked). Detect that precisely
      // and skip with a LOUD, dependency-naming reason — visible in the report,
      // not a silent pass. (test.skip throws — the finally{} below still runs,
      // so the credential is cleaned up even on the skip path.)
      const runtimeAbsent = rendered.includes('${{ secrets') || rendered.includes('secrets.E2E_REDACTION_TOKEN')
      test.skip(
        runtimeAbsent,
        'secret env-ref runtime (#1094) not active on this rig: the fixture\'s ' +
          '`${{ secrets.E2E_REDACTION_TOKEN }}` is rendered as a literal, un-injected string. ' +
          'Unskip when #1094 (env + secret refs runtime) lands.',
      )

      // The mask token fired and rendered.
      expect(
        rendered.includes('****'),
        'the masked **** token never rendered in the log panel — redaction did not fire (or the step no-op\'d)',
      ).toBe(true)

      // Adversarial: the raw secret renders ZERO times — across the direct echo,
      // the embedded curl line, the second use, AND the printenv export line.
      const leakingLines = rendered.split('\n').filter((line) => line.includes(sentinel))
      expect(
        leakingLines,
        `the raw secret rendered in ${leakingLines.length} log line(s) of the build-detail DOM: ${JSON.stringify(leakingLines)}`,
      ).toEqual([])
    } finally {
      // #83: this test OWNS the E2E_REDACTION_TOKEN row it seeded/rotated —
      // delete it so a leftover from ANY outcome (pass, fail, skip, abort
      // after seed) can never 400 a later run's bare create. Best-effort:
      // teardown must not mask the test's own verdict.
      if (credentialId !== undefined) {
        await deleteCredential(request, bearer, credentialId)
      }
    }
  })

  // ── D. PR check status: the customer-visible commit check matches the verdict ─
  test('D. PR check status: the commit check the customer sees reaches the terminal state matching the engine verdict', async () => {
    test.setTimeout(8 * 60_000)

    // Gate D on its wiring: a GitHub App + a fixture repo + gh auth. Absent on a
    // plain `task dev:titan` — skip LOUD with the reason, never a silent pass.
    test.skip(!ghAuthOk(), 'gh CLI is not authenticated — the PR-webhook path needs a real GitHub repo + gh auth')

    const gh = new GithubAppFixture()
    let app: Awaited<ReturnType<GithubAppFixture['getApp']>> = null
    try {
      app = await gh.getApp()
    } catch (e) {
      test.skip(true, `GitHub App endpoint unreachable on this rig: ${(e as Error).message}`)
    }
    test.skip(app === null, 'no GitHub App installed on this rig — the PR-webhook trigger path is not wired')

    // Drive the golden-path HEAD: push a fresh commit to the fixture repo on a
    // unique branch; the App webhook triggers a build; we poll for it, wait
    // terminal, then assert the COMMIT CHECK STATUS the customer sees on the PR
    // matches the engine verdict.
    const handle = cutFixtureBranch(`${runTag}-${randomBytes(3).toString('hex')}`)
    try {
      const build = await gh.findBuildBySha(handle.sha, { timeoutMs: 120_000 })
      expect(build, `no build was triggered for pushed sha ${handle.sha} within 120s — webhook → build path broke`).not.toBeNull()

      const terminal = await gh.waitForTerminal(build!.id)
      expect(terminal, `build ${build!.id} never reached a terminal state`).not.toBeNull()
      const engineVerdict = (terminal!.status ?? '').toUpperCase()
      const expectedCheckState: CommitStatus['state'] = SUCCESSFUL.has(engineVerdict) ? 'success' : 'failure'

      // Poll the commit's statuses (the PR check surface) until a Titan context
      // lands a terminal state — that's exactly what the customer sees on the PR.
      const deadline = Date.now() + 120_000
      let statuses: CommitStatus[] = []
      let match: CommitStatus | undefined
      while (Date.now() < deadline) {
        statuses = listCommitStatuses(handle.sha)
        match = statuses.find(
          (s) => /titan/i.test(s.context) && (s.state === 'success' || s.state === 'failure'),
        )
        if (match) break
        await new Promise((r) => setTimeout(r, 3_000))
      }
      expect(
        match,
        `no terminal Titan commit-status appeared on sha ${handle.sha}; saw: ${JSON.stringify(statuses.map((s) => `${s.context}=${s.state}`))}`,
      ).toBeTruthy()
      expect(
        match!.state,
        `the customer-visible PR check is '${match!.state}' but the engine verdict is '${engineVerdict}' — the check status drifted from the build result`,
      ).toBe(expectedCheckState)
    } finally {
      deleteRemoteBranch(handle.branch)
      handle.cleanup()
    }
  })
})

// ── credential seeding ───────────────────────────────────────────────────────

interface CredentialDto { id: number; kind: string; scope: string; key: string }
interface CredentialsPage { items: CredentialDto[]; total: number }

/**
 * Seed (or rotate) a `string` credential under the `global` scope so the
 * pipeline's `${{ secrets.<key> }}` reference resolves. Idempotent: a POST that
 * collides with an existing (scope,key) is rotated via PUT. Any other non-2xx
 * is an EXPLICIT throw — a silently-unseeded secret would make the redaction
 * assertion meaningless. Returns the row id so the caller can delete it in
 * its finally{} (ownership discipline, issue #83).
 *
 * Wire-contract notes (issue #83 — all three broke the rotate path pre-fix):
 *   - A duplicate (scope,key) surfaces as HTTP **400** with an "already
 *     exists" detail: CredentialsApi#create maps the service's
 *     IllegalArgumentException to ApiBadRequestException. We also accept 409
 *     so a future status upgrade doesn't regress this helper.
 *   - The list payload's page field is `items` (CredentialsPage record), not
 *     `page`.
 *   - PUT /credentials/{id} REQUIRES `kind` alongside `plaintext`
 *     (UpdateCredentialRequest — the API 400s on a plaintext-only body).
 */
async function seedOrRotateCredential(
  api: APIRequestContext,
  bearer: string,
  key: string,
  plaintext: string,
): Promise<number> {
  const headers = { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' }
  const create = await api.post(`${API_BASE}/api/v1/credentials`, {
    headers,
    data: { kind: 'STRING', scope: 'global', key, plaintext },
  })
  if (create.status() === 201) {
    return (JSON.parse(await create.text()) as CredentialDto).id
  }
  const createBody = await create.text()
  const isConflict =
    create.status() === 409 ||
    (create.status() === 400 && /already exists/i.test(createBody))
  if (!isConflict) {
    throw new Error(`seeding credential '${key}' failed: HTTP ${create.status()} — ${createBody.slice(0, 300)}`)
  }
  // Already present — rotate its value so this run's sentinel is the live one.
  const list = await apiGet<CredentialsPage>(api, bearer, `/api/v1/credentials?scope=global&limit=200`)
  const existing = (list.body?.items ?? []).find((c) => c.key === key)
  if (!existing) {
    throw new Error(
      `credential '${key}' reported as duplicate (HTTP ${create.status()}) but is absent from the list — cannot rotate`,
    )
  }
  const put = await api.put(`${API_BASE}/api/v1/credentials/${existing.id}`, {
    headers,
    data: { kind: 'STRING', plaintext },
  })
  expect(put.status(), `rotating credential '${key}' (#${existing.id}) failed: ${await put.text()}`).toBeLessThan(300)
  return existing.id
}

/** Best-effort credential delete for finally{} blocks — never throws. */
async function deleteCredential(
  api: APIRequestContext,
  bearer: string,
  credentialId: number,
): Promise<void> {
  await api
    .delete(`${API_BASE}/api/v1/credentials/${credentialId}`, {
      headers: { Authorization: `Bearer ${bearer}` },
    })
    .catch(() => undefined)
}

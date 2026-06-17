/**
 * 24-discovered-pipeline-from-fixture — webhook → enqueue → bake → render
 * (closes #590).
 *
 * Drives the FULL inbound-webhook path end-to-end against the public fixture
 * repo `hadamrd/titan-e2e-fixture` (a real two-stack monorepo with a
 * `titan-pipeline.yml` at root):
 *
 *   1. Pre-check: GET raw.githubusercontent.com/.../titan-pipeline.yml. If the
 *      fixture has gone away (404), `test.skip` with a precise reason — the
 *      spec hard-depends on that fixture being reachable.
 *   2. Create an HMAC credential for the webhook secret via POST /api/v1/credentials
 *      (scope=`github-webhook`).
 *   3. Create a job via POST /api/v1/jobs whose `pipelineScript` is the fixture
 *      YAML and whose `configJson.triggers[]` carries a `github` trigger
 *      referencing the credentialsId + the `main` branch.
 *   4. Synthesize a real GitHub `push` webhook payload (ref=refs/heads/main),
 *      sign it HMAC-SHA256 with the known secret, POST to
 *      `/api/v1/triggers/github` with the canonical headers. Assert
 *      200 + dispatched=true (catches bad signature OR no-branch-match — both
 *      are silent failures otherwise).
 *   5. Poll GET /api/v1/jobs/{jobId}/builds until a build appears (the webhook
 *      receiver enqueues it; orchestrator/BAKE picks it up).
 *   6. Poll GET /api/v1/builds/{id}/nodes until BAKE has populated flow_nodes
 *      (BAKE writes nodes BEFORE the engine starts executing stages, so this
 *      assertion is independent of whether containerised stages can actually
 *      pull `maven:3.9` / `node:20-alpine` on the dev rig).
 *   7. HARD assert every YAML stage name appears as a flow_node displayName.
 *   8. UI assert: navigate to /builds/{id}; assert each stage label is in DOM.
 *
 * What this spec deliberately does NOT assert:
 *   - Terminal SUCCESS — the fixture pipeline uses `image: maven:3.9-...`
 *     containerised stages; whether the dev rig can pull those is orthogonal
 *     to "webhook → discover → render". This spec proves wiring, not runtime.
 *
 * Diagnostics on fail: dumps fixture YAML, GET /builds/{id}/nodes JSON, and a
 * full-page screenshot.
 */
import * as crypto from 'node:crypto'
import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import yaml from 'js-yaml'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const FIXTURE_REPO = 'hadamrd/titan-e2e-fixture'
const FIXTURE_BRANCH = 'main'
const FIXTURE_RAW_URL = `https://raw.githubusercontent.com/${FIXTURE_REPO}/${FIXTURE_BRANCH}/titan-pipeline.yml`
const FIXTURE_API_URL = `https://api.github.com/repos/${FIXTURE_REPO}/contents/titan-pipeline.yml`

// Use a per-run suffix so re-runs don't collide on fullName / credential key.
const RUN_TAG = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
const JOB_FULL_NAME = `e2e-discovered-${RUN_TAG}`
const CREDENTIAL_KEY = `e2e-webhook-${RUN_TAG}`
// HMAC secret kept in-spec; the rig's CredentialsService seals it server-side.
const WEBHOOK_SECRET = `s3cr3t-${RUN_TAG}`

interface PipelineDoc {
  stages?: Array<{ stage?: string; gate?: string }>
}

interface CredentialCreateResp {
  id: number
  key: string
  scope: string
}

interface JobCreateResp {
  id: number
  fullName: string
}

interface FlowNode {
  buildId: number
  nodeId: string
  displayName?: string | null
  nodeType?: string | null
  status?: string | null
}

interface BuildListItem {
  id: number
  jobId: number
  buildNumber: number
  status: string
}

interface BuildsPage {
  items: BuildListItem[]
  total: number
}

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
    // leave null
  }
  return { ok: r.ok(), status: r.status(), body, raw }
}

/** Extract `stage:` and `gate:` names from a parsed Titan YAML doc, in order. */
function stageNamesFromYaml(yamlText: string): string[] {
  const doc = yaml.load(yamlText) as PipelineDoc | null
  const stages = doc?.stages ?? []
  const names: string[] = []
  for (const entry of stages) {
    const name = entry?.stage ?? entry?.gate
    if (typeof name === 'string' && name.length > 0) {
      names.push(name)
    }
  }
  return names
}

test.describe('v3 discovered-pipeline-from-fixture @golden', () => {
  test('webhook → enqueue → bake → flow_nodes match YAML → UI renders stages', async ({
    page,
    request,
  }) => {
    test.setTimeout(120_000)

    // ── 1. Pre-check fixture availability ───────────────────────────────────
    const fixtureMeta = await request.get(FIXTURE_API_URL, {
      headers: { Accept: 'application/vnd.github.v3+json' },
    })
    test.skip(
      fixtureMeta.status() === 404,
      `Fixture repo file gone — ${FIXTURE_API_URL} returned 404. ` +
        `The spec hard-depends on hadamrd/titan-e2e-fixture being public + carrying titan-pipeline.yml.`,
    )
    expect(
      fixtureMeta.ok(),
      `GitHub API returned HTTP ${fixtureMeta.status()} for ${FIXTURE_API_URL}; ` +
        `not 404 so we don't skip, but the fixture is unreachable.`,
    ).toBe(true)

    // Fetch the raw YAML (we'll persist this as the job's pipelineScript and
    // use it as the oracle for expected stage names).
    const rawResp = await request.get(FIXTURE_RAW_URL)
    expect(rawResp.ok(), `raw YAML fetch HTTP ${rawResp.status()}`).toBe(true)
    const fixtureYaml = await rawResp.text()
    expect(fixtureYaml.length, 'fixture YAML is empty').toBeGreaterThan(100)

    const expectedStages = stageNamesFromYaml(fixtureYaml)
    expect(
      expectedStages.length,
      `parsed 0 stage/gate names from fixture YAML — parser regressed or fixture shape changed`,
    ).toBeGreaterThan(0)

    // ── State that diagnostics may need ─────────────────────────────────────
    let attached = false
    let bearer: string | undefined
    let jobId: number | undefined
    let credentialId: number | undefined
    let buildId: number | undefined

    const dump = async (label: string) => {
      if (attached) return
      attached = true
      try {
        await test.info().attach(`fixture-${label}.yml`, {
          body: fixtureYaml,
          contentType: 'text/yaml',
        })
        if (bearer && buildId) {
          const nodes = await apiGet<FlowNode[]>(
            request,
            bearer,
            `/api/v1/builds/${buildId}/nodes`,
          )
          await test.info().attach(`nodes-${label}.json`, {
            body: nodes.raw,
            contentType: 'application/json',
          })
          const build = await apiGet(request, bearer, `/api/v1/builds/${buildId}`)
          await test.info().attach(`build-${label}.json`, {
            body: build.raw,
            contentType: 'application/json',
          })
        }
        if (bearer && jobId) {
          const job = await apiGet(request, bearer, `/api/v1/jobs/${jobId}`)
          await test.info().attach(`job-${label}.json`, {
            body: job.raw,
            contentType: 'application/json',
          })
        }
        const png = await page.screenshot({ fullPage: true }).catch(() => null)
        if (png) {
          await test.info().attach(`screenshot-${label}.png`, {
            body: png,
            contentType: 'image/png',
          })
        }
      } catch (e) {
        await test.info().attach('diagnostics-error.txt', {
          body: String(e),
          contentType: 'text/plain',
        })
      }
    }

    try {
      // ── 2. Login (SPA PKCE) + pull bearer ─────────────────────────────────
      await loginViaKeycloak(page, ENV)
      bearer = await extractAccessToken(page)

      // ── 3. Create the HMAC credential. The webhook receiver resolves it
      //      via CredentialsService.resolvePlaintext("github-webhook", key).
      const credCreate = await request.post(`${API_BASE}/api/v1/credentials`, {
        headers: {
          Authorization: `Bearer ${bearer}`,
          'Content-Type': 'application/json',
        },
        data: {
          kind: 'STRING',
          scope: 'github-webhook',
          key: CREDENTIAL_KEY,
          plaintext: WEBHOOK_SECRET,
        },
      })
      const credRaw = await credCreate.text()
      expect(
        credCreate.status(),
        `POST /api/v1/credentials failed: HTTP ${credCreate.status()} body=${credRaw.slice(0, 400)}`,
      ).toBe(201)
      credentialId = (JSON.parse(credRaw) as CredentialCreateResp).id
      expect(credentialId).toBeGreaterThan(0)

      // ── 4. Create the job with the fixture YAML as pipelineScript + a
      //      configJson carrying the github trigger. The webhook receiver
      //      scans titan.jobs.config_json for a `triggers` array of objects
      //      whose `type=='github'`, matches the branch glob, and recomputes
      //      HMAC with the credential's plaintext.
      const triggersConfig = {
        triggers: [
          {
            type: 'github',
            id: 'github-1',
            branches: [FIXTURE_BRANCH],
            events: ['push'],
            credentialsId: CREDENTIAL_KEY,
          },
        ],
      }
      const jobCreate = await request.post(`${API_BASE}/api/v1/jobs`, {
        headers: {
          Authorization: `Bearer ${bearer}`,
          'Content-Type': 'application/json',
        },
        data: {
          fullName: JOB_FULL_NAME,
          displayName: 'E2E discovered from fixture',
          pipelineScript: fixtureYaml,
          configJson: JSON.stringify(triggersConfig),
          enabled: true,
        },
      })
      const jobRaw = await jobCreate.text()
      expect(
        jobCreate.status(),
        `POST /api/v1/jobs failed: HTTP ${jobCreate.status()} body=${jobRaw.slice(0, 600)}`,
      ).toBe(201)
      jobId = (JSON.parse(jobRaw) as JobCreateResp).id
      expect(jobId).toBeGreaterThan(0)

      // ── 5. Synthesize + sign + POST a GitHub push payload.
      const pushPayload = {
        ref: `refs/heads/${FIXTURE_BRANCH}`,
        before: '0'.repeat(40),
        after: 'f'.repeat(40),
        repository: { full_name: FIXTURE_REPO, default_branch: FIXTURE_BRANCH },
        pusher: { name: 'e2e-bot' },
        head_commit: {
          id: 'f'.repeat(40),
          message: 'e2e synthetic push',
        },
      }
      const bodyBytes = Buffer.from(JSON.stringify(pushPayload), 'utf8')
      const sig =
        'sha256=' +
        crypto.createHmac('sha256', WEBHOOK_SECRET).update(bodyBytes).digest('hex')

      const webhookResp = await request.post(`${API_BASE}/api/v1/triggers/github`, {
        headers: {
          'Content-Type': 'application/json',
          'X-GitHub-Event': 'push',
          'X-Hub-Signature-256': sig,
          'X-GitHub-Delivery': `e2e-${RUN_TAG}`,
        },
        data: bodyBytes,
      })
      const webhookRaw = await webhookResp.text()
      expect(
        webhookResp.status(),
        `POST /api/v1/triggers/github HTTP ${webhookResp.status()} body=${webhookRaw}`,
      ).toBe(200)
      const webhookBody = JSON.parse(webhookRaw) as {
        accepted: boolean
        dispatched: boolean
        detail: string
      }
      expect(
        webhookBody.dispatched,
        `webhook accepted but dispatched=false (detail="${webhookBody.detail}") — ` +
          `most likely: branch glob mismatch, credential not resolved, or signature mismatch.`,
      ).toBe(true)

      // ── 6. Poll for the build to appear on the job. ─────────────────────
      await expect
        .poll(
          async () => {
            const r = await apiGet<BuildsPage>(
              request,
              bearer!,
              `/api/v1/jobs/${jobId}/builds?offset=0&limit=20`,
            )
            if (!r.ok || !r.body) return 0
            const first = r.body.items[0]
            if (first) {
              buildId = first.id
              return r.body.items.length
            }
            return 0
          },
          {
            message: `no build appeared on job ${jobId} within 30s of webhook POST`,
            timeout: 30_000,
            intervals: [500, 1_000, 2_000],
          },
        )
        .toBeGreaterThan(0)
      expect(buildId).toBeGreaterThan(0)

      // ── 7. Poll for BAKE to populate flow_nodes. ────────────────────────
      let observedNodes: FlowNode[] = []
      await expect
        .poll(
          async () => {
            const r = await apiGet<FlowNode[]>(
              request,
              bearer!,
              `/api/v1/builds/${buildId}/nodes`,
            )
            if (r.ok && Array.isArray(r.body)) {
              observedNodes = r.body
              return r.body.length
            }
            return 0
          },
          {
            message:
              `flow_nodes empty for build ${buildId} after 60s — BAKE never ran (orchestrator stuck?)`,
            timeout: 60_000,
            intervals: [1_000, 2_000, 3_000],
          },
        )
        .toBeGreaterThan(0)

      // ── 8. HARD assert every YAML stage name has a flow_node displayName
      //      match. We index nodes by displayName for clarity in failure msg.
      const nodeNames = new Set(
        observedNodes
          .map((n) => (n.displayName ?? '').trim())
          .filter((s) => s.length > 0),
      )
      const missing = expectedStages.filter((s) => !nodeNames.has(s))
      expect(
        missing,
        `YAML declared stages not represented as flow_nodes:\n  missing: ${JSON.stringify(missing)}\n  ` +
          `observed displayNames: ${JSON.stringify([...nodeNames])}\n  ` +
          `expected (from YAML): ${JSON.stringify(expectedStages)}`,
      ).toEqual([])

      // Also assert the persisted build's pipelineScript matches the fixture
      // YAML (canonical store of what was discovered/used for BAKE).
      const buildResp = await apiGet<{ pipelineScript?: string }>(
        request,
        bearer,
        `/api/v1/builds/${buildId}`,
      )
      expect(buildResp.ok, `GET /builds/${buildId} HTTP ${buildResp.status}`).toBe(true)
      expect(
        (buildResp.body?.pipelineScript ?? '').trim(),
        'build.pipelineScript does not match the fixture YAML — discovery/bake captured the wrong source',
      ).toBe(fixtureYaml.trim())

      // ── 9. UI assert: open /builds/{id}, assert each stage label is in DOM.
      await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)
      await expect(page.getByRole('heading', { name: /builds.*#/i })).toBeVisible({
        timeout: 15_000,
      })
      // Wait for the DAG region to render: pipeline node count text or DagView.
      const dagBar = page.getByTestId('dag-collapsed-bar')
      const dagWrap = page.locator('.bd-dag-wrap')
      await expect(dagBar.or(dagWrap).first()).toBeVisible({ timeout: 15_000 })

      // The TreeRail renders one row per node with the displayName visible.
      // For each expected stage, assert at least one DOM occurrence inside the
      // build-detail layout. We scope to the main content area to avoid false
      // hits on sidebar/breadcrumb text.
      const buildLayout = page.locator('.bd-split, main.bd-pane, .bd-dag-wrap, body').first()
      for (const stage of expectedStages) {
        await expect(
          buildLayout.getByText(stage, { exact: false }).first(),
          `Pipeline tab does not render YAML stage "${stage}" — flow_nodes exist (API confirmed) ` +
            `but the UI failed to surface it.`,
        ).toBeVisible({ timeout: 10_000 })
      }
    } catch (err) {
      await dump('assertion-failure')
      throw err
    }
  })
})

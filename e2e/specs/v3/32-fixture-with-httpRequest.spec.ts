/**
 * 32-fixture-with-httpRequest — discovery → webhook trigger → httpRequest step
 * round-trips end-to-end (closes #786).
 *
 * Drives the worker-side `httpRequest` step against the public fixture
 * `hadamrd/titan-e2e-fixture/.titan/pipelines/with-httpRequest.yml`:
 *
 *     parameters:
 *       - name: TARGET_URL
 *         type: string
 *         default: "https://httpbin.org/post"
 *     stages:
 *       - stage: Call
 *         steps:
 *           - httpRequest:
 *               url: "${{ params.TARGET_URL }}"
 *               method: POST
 *               body: '{"e2e":true}'
 *
 * The fixture's own comment says the spec contract is: build SUCCESS AND the
 * httpRequest flow-node result_json carries `status: 200`. We follow that.
 *
 * Workflow (matches spec #26's discipline):
 *   1. Pre-check the fixture YAML is reachable + shape sanity-check
 *      (httpRequest + TARGET_URL still present).
 *   2. Login (PKCE) + extract bearer.
 *   3. Create an HMAC credential (kind=STRING, scope=github-webhook).
 *   4. Create the job: pipelineScript=fixtureYaml, configJson carries the
 *      github trigger pointing at the credential.
 *   5. Synthesise + HMAC-sign a `push` payload; POST /api/v1/triggers/github.
 *      HARD assert dispatched=true (real webhook path, not inline trigger).
 *   6. Poll /jobs/{id}/builds until the build appears (≤ 30s).
 *   7. Poll /builds/{id} until terminal (≤ 120s — outbound HTTP to httpbin
 *      can be slow on cold worker).
 *   8. HARD assert build.status === SUCCESS.
 *   9. HARD assert that some flow_node in the build carries the http response
 *      status code 200 (the worker step persists `{status: 200, ...}` in
 *      result_json when httpbin responds; we accept either explicit
 *      result_json scrape OR a log oracle containing "200").
 *  10. finally{}: delete credential. Job rows aren't deletable today; the
 *      per-run RUN_TAG suffix avoids fullName collisions across reruns.
 *
 * Sad-path note: the matrix issue (#786) calls for a sad-path on one of the
 * specs. Specs 28-approval (REJECT path) and 29-retry (attempt counter) already
 * cover that; this spec stays focused on the happy webhook + httpRequest path.
 *
 * Out-of-rig dependency: this spec talks to https://httpbin.org through the
 * worker container. If the rig has no egress, the spec will hit a terminal
 * FAILED — that's a rig story, not a product regression — but the assertion
 * failure mode is clearly observable from the attached diagnostics (build
 * status + flow-node JSON).
 */
import * as crypto from 'node:crypto'
import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { readFixtureYaml } from '../../fixtures/fixture-files'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const FIXTURE_REPO = 'hadamrd/titan-e2e-fixture'
const FIXTURE_BRANCH = 'main'
const FIXTURE_PATH = '.titan/pipelines/with-httpRequest.yml'

const RUN_TAG = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
const JOB_FULL_NAME = `e2e-with-httpRequest-${RUN_TAG}`
const CREDENTIAL_KEY = `e2e-with-httpRequest-${RUN_TAG}`
const WEBHOOK_SECRET = `s3cr3t-${RUN_TAG}`

const TERMINAL_STATUSES = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'UNSTABLE', 'ERROR'])

interface CredentialCreateResp {
  id: number
  key: string
}

interface JobCreateResp {
  id: number
}

interface BuildListItem {
  id: number
  buildNumber: number
  status: string
}

interface BuildsPage {
  items: BuildListItem[]
  total: number
}

interface BuildDetail {
  id: number
  status: string
}

interface FlowNode {
  id: number
  name?: string
  type?: string
  status?: string
  resultJson?: string | null
  logTaskId?: string | null
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

test.describe('v3 fixture-with-httpRequest @golden', () => {
  test('webhook → SUCCESS → httpRequest flow-node carries 200', async ({ page, request }) => {
    test.setTimeout(240_000)

    // ── 1. Read the vendored fixture YAML (hermetic — #48) + shape sanity ──
    const fixtureYaml = readFixtureYaml(FIXTURE_PATH)
    expect(fixtureYaml.length, 'fixture YAML is empty').toBeGreaterThan(50)
    expect(
      fixtureYaml,
      'fixture YAML drift — expected httpRequest step',
    ).toMatch(/httpRequest/)
    expect(fixtureYaml, 'fixture YAML drift — expected TARGET_URL param').toMatch(/TARGET_URL/)

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
          const build = await apiGet(request, bearer, `/api/v1/builds/${buildId}`)
          await test.info().attach(`build-${label}.json`, {
            body: build.raw,
            contentType: 'application/json',
          })
          const nodes = await apiGet(request, bearer, `/api/v1/builds/${buildId}/nodes`)
          await test.info().attach(`nodes-${label}.json`, {
            body: nodes.raw,
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
      // ── 2. Login + bearer ────────────────────────────────────────────────
      await loginViaKeycloak(page, ENV)
      bearer = await extractAccessToken(page)

      // ── 3. Create HMAC credential (STRING per #793). ─────────────────────
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
        `POST /api/v1/credentials HTTP ${credCreate.status()} body=${credRaw.slice(0, 400)}`,
      ).toBe(201)
      credentialId = (JSON.parse(credRaw) as CredentialCreateResp).id

      // ── 4. Create the job (fixture YAML + github trigger). ───────────────
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
          displayName: 'E2E with-httpRequest from fixture',
          pipelineScript: fixtureYaml,
          configJson: JSON.stringify(triggersConfig),
          enabled: true,
        },
      })
      const jobRaw = await jobCreate.text()
      expect(
        jobCreate.status(),
        `POST /api/v1/jobs HTTP ${jobCreate.status()} body=${jobRaw.slice(0, 600)}`,
      ).toBe(201)
      jobId = (JSON.parse(jobRaw) as JobCreateResp).id

      // ── 5. Real GitHub webhook POST (signed). ────────────────────────────
      const pushPayload = {
        ref: `refs/heads/${FIXTURE_BRANCH}`,
        before: '0'.repeat(40),
        after: 'f'.repeat(40),
        repository: { full_name: FIXTURE_REPO, default_branch: FIXTURE_BRANCH },
        pusher: { name: 'e2e-bot' },
        head_commit: { id: 'f'.repeat(40), message: 'e2e with-httpRequest push' },
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
        `webhook accepted but dispatched=false (detail="${webhookBody.detail}")`,
      ).toBe(true)

      // ── 6. Poll for build to appear. ─────────────────────────────────────
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

      // ── 7. Poll terminal status. ─────────────────────────────────────────
      let finalStatus = ''
      await expect
        .poll(
          async () => {
            const r = await apiGet<BuildDetail>(request, bearer!, `/api/v1/builds/${buildId}`)
            if (!r.ok || !r.body) return ''
            finalStatus = r.body.status
            return TERMINAL_STATUSES.has(finalStatus) ? finalStatus : ''
          },
          {
            message:
              `build ${buildId} did not reach terminal status within 120s ` +
              `(last observed "${finalStatus}") — engine stuck or worker not dispatched`,
            timeout: 120_000,
            intervals: [1_000, 2_000, 3_000],
          },
        )
        .not.toBe('')

      // ── 8. HARD assert SUCCESS. ──────────────────────────────────────────
      expect(
        finalStatus,
        `build ${buildId} terminal status was "${finalStatus}", expected SUCCESS — ` +
          `httpRequest fixture is the worker-step golden path. Any non-SUCCESS is a regression. ` +
          `If diagnostics show 'no route to host' for httpbin.org, the rig has no egress (rig issue, not product).`,
      ).toBe('SUCCESS')

      // ── 9. HARD assert a flow_node carries the response status 200. ──────
      const nodesResp = await apiGet<FlowNode[] | { items: FlowNode[] }>(
        request,
        bearer,
        `/api/v1/builds/${buildId}/nodes`,
      )
      expect(
        nodesResp.ok,
        `GET /builds/${buildId}/nodes HTTP ${nodesResp.status}`,
      ).toBe(true)
      const nodeArray: FlowNode[] = Array.isArray(nodesResp.body)
        ? nodesResp.body
        : (nodesResp.body?.items ?? [])
      expect(nodeArray.length, 'no flow_nodes returned for build').toBeGreaterThan(0)

      // Oracle A: scrape any node's result_json for "status":200.
      // Oracle B: scrape any node's step log (via logTaskId) for "200".
      // Either is sufficient; we prefer A but fall back to B.
      let found200 = false
      for (const n of nodeArray) {
        if (n.resultJson && /"status"\s*:\s*200\b/.test(n.resultJson)) {
          found200 = true
          break
        }
      }
      if (!found200) {
        for (const n of nodeArray) {
          if (!n.logTaskId) continue
          const log = await apiGet<string>(
            request,
            bearer,
            `/api/v1/logs/${n.logTaskId}?offset=0&limit=200000`,
          )
          if (log.raw && /\b200\b/.test(log.raw)) {
            found200 = true
            break
          }
        }
      }
      expect(
        found200,
        `no flow_node carries http status 200 in result_json or step log on build ${buildId}. ` +
          `Either the httpRequest step did not run, the response status was not persisted, ` +
          `or the endpoint returned a non-200 (rig egress / httpbin down). ` +
          `Observed node types: ${JSON.stringify(nodeArray.map((n) => n.type ?? n.name))}`,
      ).toBe(true)
    } catch (err) {
      await dump('assertion-failure')
      throw err
    } finally {
      // ── 10. Cleanup credential (jobs not deletable today). ───────────────
      if (bearer && credentialId) {
        await request
          .delete(`${API_BASE}/api/v1/credentials/${credentialId}`, {
            headers: { Authorization: `Bearer ${bearer}` },
          })
          .catch(() => {
            /* best-effort */
          })
      }
    }
  })
})

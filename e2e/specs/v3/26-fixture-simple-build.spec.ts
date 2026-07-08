/**
 * 26-fixture-simple-build — minimal per-primitive E2E (closes #796).
 *
 * Drives the FULL inbound-webhook path end-to-end against the smallest
 * possible real fixture: `hadamrd/titan-e2e-fixture` ships
 * `.titan/pipelines/simple-build.yml`, a one-stage pipeline that writes
 * `out.txt` and archives it:
 *
 *   stages:
 *     - stage: Build
 *       steps:
 *         - sh: "echo hello > out.txt"
 *         - archiveArtifacts: { artifacts: "out.txt" }
 *
 * Unlike spec #24 (which only proves wiring up to BAKE + DOM render and
 * deliberately skips terminal-status assertions because the fixture there
 * pulls containerised stages), this spec hard-asserts the engine produced
 * a SUCCESS build AND that `out.txt` was archived AND that its bytes round-
 * trip through the download endpoint. This is the per-primitive golden path:
 *
 *   webhook --> dispatched --> build appears --> build runs to SUCCESS
 *           --> artifact 'out.txt' exists on the build
 *           --> GET /api/v1/artifacts/{id}/download body contains 'hello'
 *
 * Workflow:
 *   1. Read the fixture YAML from the vendored mirror
 *      `e2e/fixtures/titan-e2e-fixture/.titan/pipelines/simple-build.yml`
 *      (#48 — Layer-1 specs are hermetic; no runtime GitHub fetch).
 *   2. Create HMAC credential (kind='STRING' per #793 — `STRING` is the only
 *      kind that round-trips for webhook-secret use; `secret-text` was the
 *      legacy mistake).
 *   3. Create job with pipelineScript = the fixed YAML + configJson.triggers
 *      carrying a github trigger referencing the credential.
 *   4. POST a synthetic push webhook payload, HMAC-signed; assert dispatched.
 *   5. Poll /api/v1/jobs/{jobId}/builds until a build appears (<=30s).
 *   6. Poll /api/v1/builds/{buildId} until terminal status (<=90s).
 *   7. HARD assert status == SUCCESS (no soft toBeTruthy).
 *   8. GET /api/v1/builds/{buildId}/artifacts; HARD assert at least one row
 *      with name matching /out\.txt/.
 *   9. GET /api/v1/artifacts/{artifactId}/download; HARD assert body contains
 *      'hello'.
 *  10. finally{}: cleanup the credential (jobs intentionally not deleted —
 *      JobsApi exposes no DELETE today, full-name suffix per-run ensures no
 *      collision across re-runs).
 *
 * Diagnostics on failure: dumps fixture YAML, build JSON, nodes JSON, artifact
 * list JSON, and a full-page screenshot.
 */
import * as crypto from 'node:crypto'
import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { readFixtureYaml } from '../../fixtures/fixture-files'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const FIXTURE_REPO = 'hadamrd/titan-e2e-fixture'
const FIXTURE_BRANCH = 'main'
const FIXTURE_PATH = '.titan/pipelines/simple-build.yml'

// Per-run suffix so re-runs don't collide on fullName / credential key.
const RUN_TAG = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
const JOB_FULL_NAME = `e2e-simple-build-${RUN_TAG}`
const CREDENTIAL_KEY = `e2e-simple-build-${RUN_TAG}`
const WEBHOOK_SECRET = `s3cr3t-${RUN_TAG}`

// Terminal build statuses we treat as "engine has finished, stop polling".
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
  pipelineScript?: string
}

interface ArtifactDto {
  id: number
  name: string
  sizeBytes: number
  downloadUrl: string
}

interface ArtifactsPage {
  items: ArtifactDto[]
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

test.describe('v3 fixture-simple-build @golden', () => {
  test('webhook → build SUCCESS → out.txt artifact archived with "hello" content', async ({
    page,
    request,
  }) => {
    test.setTimeout(180_000)

    // ── 1. Read the vendored fixture YAML (hermetic — #48) ──────────────────
    const fixtureYaml = readFixtureYaml(FIXTURE_PATH)
    expect(fixtureYaml.length, 'fixture YAML is empty').toBeGreaterThan(50)
    // Sanity-check the fixture really is the simple-build oracle we expect.
    expect(
      fixtureYaml,
      'fixture YAML shape changed — expected single-stage sh + archiveArtifacts',
    ).toMatch(/archiveArtifacts/)
    expect(fixtureYaml).toMatch(/out\.txt/)

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
          const arts = await apiGet(request, bearer, `/api/v1/builds/${buildId}/artifacts`)
          await test.info().attach(`artifacts-${label}.json`, {
            body: arts.raw,
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
      // ── 2. Login (SPA PKCE) + pull bearer ────────────────────────────────
      await loginViaKeycloak(page, ENV)
      bearer = await extractAccessToken(page)

      // ── 3. Create the HMAC credential. kind='STRING' per #793. ───────────
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

      // ── 4. Create the job with the fixture YAML + github trigger. ────────
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
          displayName: 'E2E simple-build from fixture',
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

      // ── 5. Synthesize + sign + POST a GitHub push payload. ───────────────
      const pushPayload = {
        ref: `refs/heads/${FIXTURE_BRANCH}`,
        before: '0'.repeat(40),
        after: 'f'.repeat(40),
        repository: { full_name: FIXTURE_REPO, default_branch: FIXTURE_BRANCH },
        pusher: { name: 'e2e-bot' },
        head_commit: { id: 'f'.repeat(40), message: 'e2e simple-build push' },
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
          `branch glob mismatch, credential not resolved, or signature mismatch.`,
      ).toBe(true)

      // ── 6. Poll for the build to appear on the job. ──────────────────────
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

      // ── 7. Poll the build until it reaches a terminal status. ────────────
      let finalStatus = ''
      await expect
        .poll(
          async () => {
            const r = await apiGet<BuildDetail>(
              request,
              bearer!,
              `/api/v1/builds/${buildId}`,
            )
            if (!r.ok || !r.body) return ''
            finalStatus = r.body.status
            return TERMINAL_STATUSES.has(finalStatus) ? finalStatus : ''
          },
          {
            message:
              `build ${buildId} did not reach a terminal status within 90s ` +
              `(last observed: "${finalStatus}") — engine stuck or worker not dispatched`,
            timeout: 90_000,
            intervals: [1_000, 2_000, 3_000],
          },
        )
        .not.toBe('')

      // ── 8. HARD assert SUCCESS. ──────────────────────────────────────────
      expect(
        finalStatus,
        `build ${buildId} terminal status was "${finalStatus}", expected SUCCESS — ` +
          `the simple-build fixture is the golden path; any non-SUCCESS is a regression`,
      ).toBe('SUCCESS')

      // ── 9. List artifacts; assert out.txt is present. ────────────────────
      const artsResp = await apiGet<ArtifactsPage>(
        request,
        bearer,
        `/api/v1/builds/${buildId}/artifacts`,
      )
      expect(
        artsResp.ok,
        `GET /builds/${buildId}/artifacts HTTP ${artsResp.status} body=${artsResp.raw.slice(0, 400)}`,
      ).toBe(true)
      const items = artsResp.body?.items ?? []
      const outTxt = items.find((a) => /out\.txt/.test(a.name))
      expect(
        outTxt,
        `no artifact matching /out\\.txt/ on build ${buildId}; ` +
          `archiveArtifacts step did not register one. ` +
          `Observed artifacts: ${JSON.stringify(items.map((a) => a.name))}`,
      ).toBeDefined()
      expect(
        outTxt!.sizeBytes,
        `out.txt artifact has zero bytes — sh step ran but stdout redirect missed?`,
      ).toBeGreaterThan(0)

      // ── 10. Download the artifact + assert content. ──────────────────────
      const dlResp = await request.get(`${API_BASE}/api/v1/artifacts/${outTxt!.id}/download`, {
        headers: { Authorization: `Bearer ${bearer}` },
      })
      expect(
        dlResp.status(),
        `GET /api/v1/artifacts/${outTxt!.id}/download HTTP ${dlResp.status()}`,
      ).toBe(200)
      const dlBody = await dlResp.text()
      expect(
        dlBody,
        `downloaded out.txt body did not contain 'hello' (got: ${JSON.stringify(dlBody.slice(0, 200))}) — ` +
          `sh step output was not captured into the archived file`,
      ).toContain('hello')
    } catch (err) {
      await dump('assertion-failure')
      throw err
    } finally {
      // ── Cleanup. JobsApi has no DELETE today; the per-run RUN_TAG suffix
      // ensures the next run does not collide on fullName / credential key.
      // Credentials API does expose DELETE, so we clean that up to keep the
      // credentials table from growing on repeated runs.
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

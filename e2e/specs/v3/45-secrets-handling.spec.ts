/**
 * 45-secrets-handling — secrets must never leak plaintext (CONSTITUTION §6).
 *
 * Drives Titan's declarative `credentials:` binding (design/39) end-to-end
 * against an adversarial fixture: `hadamrd/titan-e2e-fixture` ships
 * `.titan/pipelines/secrets-handling.yml`. The YAML has two stages:
 *
 *   1. Consume — binds a STRING credential into env $SECRET_TOKEN and
 *      proves consumption by printing the LENGTH (not the value).
 *   2. LeakAttempt — deliberately writes $SECRET_TOKEN to leaked.txt and
 *      archives it. This is the adversarial probe; the engine SHOULD
 *      either mask the value in stdout AND/OR sanitise/refuse the file.
 *
 * Hard assertions (plaintext = `super-secret-<RUN_TAG>`):
 *   A. Build terminal status SUCCESS — proves the binding worked.
 *   B. JobDto.pipelineScript does NOT contain plaintext — proves the
 *      persisted job script is id-only (model carries id, never secret).
 *   C. SSE log stream (collected as text) does NOT contain plaintext —
 *      the worker's log-masker must redact the value.
 *   D. The archived leaked.txt body does NOT contain plaintext.
 *      If it does → HARD FAIL: "secret leaked into archived artifact —
 *      engine does not sanitise artifact contents". This is the data we
 *      need; the spec ships even if the engine fails this assertion.
 *
 * If the rig's fixture is missing this YAML the spec test.skip()s — exactly
 * like spec #26, because the fixture is the contract.
 *
 * Cleanup: deletes the credential. The job is left (no DELETE on JobsApi);
 * per-run RUN_TAG ensures no cross-run collision on fullName / scope-key.
 */
import * as crypto from 'node:crypto'
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'
import { readFixtureYaml } from '../../fixtures/fixture-files'
import { safeDeleteJobCascade } from '../../fixtures/teardown-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const FIXTURE_REPO = 'hadamrd/titan-e2e-fixture'
const FIXTURE_BRANCH = 'main'
const FIXTURE_PATH = '.titan/pipelines/secrets-handling.yml'

// Per-run suffix: unique job fullName, unique credential scope/key, unique
// plaintext. Plaintext is created at runtime — never committed to git.
const RUN_TAG = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
const JOB_FULL_NAME = `e2e-secrets-handling-${RUN_TAG}`
const CRED_SCOPE = 'e2e-secrets'
const CRED_KEY = `token-${RUN_TAG}`
const PLAINTEXT = `super-secret-${RUN_TAG}`
const HMAC_KEY = `e2e-secrets-hmac-${RUN_TAG}`
const WEBHOOK_SECRET = `whk-${RUN_TAG}`

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
interface JobDetail {
  id: number
  pipelineScript: string
}
interface ArtifactDto {
  id: number
  name: string
  sizeBytes: number
}
interface ArtifactsPage {
  items: ArtifactDto[]
  total: number
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

/**
 * Drain the SSE logs endpoint as plain text. The server closes the stream
 * once the build is terminal (see BuildLogsSse) so this read returns and
 * we can grep it for plaintext. The endpoint emits `event: log\ndata: ...`
 * frames — we don't bother parsing, we just substring-match.
 */
async function readLogsText(
  request: APIRequestContext,
  bearer: string,
  buildId: number,
): Promise<string> {
  const r = await request.get(`${API_BASE}/api/v1/builds/${buildId}/logs`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'text/event-stream' },
    timeout: 30_000,
  })
  if (!r.ok()) {
    return `<<logs HTTP ${r.status()}>>`
  }
  return await r.text()
}

test.describe('v3 secrets-handling @golden', () => {
  test('credential consumed; plaintext never leaks to pipelineScript / logs / artifact', async ({
    request,
  }) => {
    test.setTimeout(180_000)

    // ── 1. Read the vendored fixture YAML (hermetic — #48) ──────────────────
    let fixtureYaml = readFixtureYaml(FIXTURE_PATH)
    expect(fixtureYaml.length, 'fixture YAML is empty').toBeGreaterThan(50)
    // Sanity-check the fixture shape is what the spec was written against.
    expect(fixtureYaml, 'fixture YAML missing credentials: scope').toMatch(/credentials:/)
    expect(fixtureYaml, 'fixture YAML missing __KEY__ placeholder').toMatch(/__KEY__/)
    expect(fixtureYaml, 'fixture YAML missing leak archive step').toMatch(/leaked\.txt/)

    // Inline-rewrite the placeholder credential id so concurrent runs do not
    // collide on the credentials-store (scope, key) uniqueness constraint.
    fixtureYaml = fixtureYaml.replace(/__KEY__/g, CRED_KEY)
    expect(fixtureYaml, 'placeholder substitution failed').not.toMatch(/__KEY__/)
    expect(fixtureYaml).toContain(`${CRED_SCOPE}/${CRED_KEY}`)

    // ── Diagnostics state ──────────────────────────────────────────────────
    let attached = false
    let bearer: string | undefined
    let jobId: number | undefined
    let credentialId: number | undefined
    let hmacCredentialId: number | undefined
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
      } catch (e) {
        await test.info().attach('diagnostics-error.txt', {
          body: String(e),
          contentType: 'text/plain',
        })
      }
    }

    try {
      // ── 2. Bearer via direct-access-grants (titan-e2e client) ────────────
      // Pure API surface — same pattern as sibling fixture specs #27 / #40.
      // No browser PKCE flow: faster, and immune to SPA-landing-page drift
      // that can leave the Keycloak form un-rendered (the old browser path
      // stalled on #kc-form-login). The assertions below are all API/SSE.
      bearer = await fetchBearerToken(ENV)

      // ── 3. Create the STRING credential the pipeline binds. ──────────────
      //   scope=e2e-secrets, key=token-<RUN_TAG>, plaintext=super-secret-<RUN_TAG>.
      //   The credential is the unit under test — the pipeline references it
      //   by `id: e2e-secrets/token-<RUN_TAG>` after placeholder substitution.
      const credCreate = await request.post(`${API_BASE}/api/v1/credentials`, {
        headers: {
          Authorization: `Bearer ${bearer}`,
          'Content-Type': 'application/json',
        },
        data: {
          kind: 'STRING',
          scope: CRED_SCOPE,
          key: CRED_KEY,
          plaintext: PLAINTEXT,
        },
      })
      const credRaw = await credCreate.text()
      expect(
        credCreate.status(),
        `POST /api/v1/credentials (target) HTTP ${credCreate.status()} body=${credRaw.slice(0, 400)}`,
      ).toBe(201)
      credentialId = (JSON.parse(credRaw) as CredentialCreateResp).id
      expect(credentialId).toBeGreaterThan(0)

      // ── 4. Create the HMAC credential the github trigger validates. ──────
      const hmacCreate = await request.post(`${API_BASE}/api/v1/credentials`, {
        headers: {
          Authorization: `Bearer ${bearer}`,
          'Content-Type': 'application/json',
        },
        data: {
          kind: 'STRING',
          scope: 'github-webhook',
          key: HMAC_KEY,
          plaintext: WEBHOOK_SECRET,
        },
      })
      const hmacRaw = await hmacCreate.text()
      expect(
        hmacCreate.status(),
        `POST /api/v1/credentials (hmac) HTTP ${hmacCreate.status()} body=${hmacRaw.slice(0, 400)}`,
      ).toBe(201)
      hmacCredentialId = (JSON.parse(hmacRaw) as CredentialCreateResp).id

      // ── 5. Create the job. pipelineScript = our substituted fixture. ─────
      const triggersConfig = {
        triggers: [
          {
            type: 'github',
            id: 'github-1',
            branches: [FIXTURE_BRANCH],
            events: ['push'],
            credentialsId: HMAC_KEY,
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
          displayName: 'E2E secrets-handling adversarial probe',
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
      expect(jobId).toBeGreaterThan(0)

      // ── 6. Synthesise + sign + POST a github push payload. ───────────────
      const pushPayload = {
        ref: `refs/heads/${FIXTURE_BRANCH}`,
        before: '0'.repeat(40),
        after: 'f'.repeat(40),
        repository: { full_name: FIXTURE_REPO, default_branch: FIXTURE_BRANCH },
        pusher: { name: 'e2e-bot' },
        head_commit: { id: 'f'.repeat(40), message: 'e2e secrets-handling push' },
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

      // ── 7. Poll for the build. ───────────────────────────────────────────
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

      // ── 8. Poll the build to terminal status. ────────────────────────────
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
              `(last observed: "${finalStatus}")`,
            timeout: 120_000,
            intervals: [1_000, 2_000, 3_000],
          },
        )
        .not.toBe('')

      // ── ASSERTION A: SUCCESS — proves credential binding worked. ─────────
      expect(
        finalStatus,
        `build ${buildId} terminal status was "${finalStatus}", expected SUCCESS. ` +
          `The Consume stage prints len=$\{#SECRET_TOKEN\}; any non-SUCCESS means the ` +
          `engine failed to resolve / bind the credential into the worker's env.`,
      ).toBe('SUCCESS')

      // ── ASSERTION B: plaintext absent from persisted pipelineScript. ─────
      const jobResp = await apiGet<JobDetail>(request, bearer, `/api/v1/jobs/${jobId}`)
      expect(jobResp.ok, `GET /jobs/${jobId} HTTP ${jobResp.status}`).toBe(true)
      const persistedScript = jobResp.body?.pipelineScript ?? ''
      expect(persistedScript.length, 'pipelineScript empty').toBeGreaterThan(0)
      expect(
        persistedScript.includes(PLAINTEXT),
        `CONSTITUTION §6 violated: plaintext "${PLAINTEXT}" found in JobDto.pipelineScript ` +
          `(persisted pipeline_script column). The grammar only carries the credential id; ` +
          `the secret must never appear in the model.`,
      ).toBe(false)
      // Positive: the id reference MUST be present (sanity — proves we are looking at the right field).
      expect(
        persistedScript.includes(`${CRED_SCOPE}/${CRED_KEY}`),
        `expected credential id "${CRED_SCOPE}/${CRED_KEY}" reference in pipelineScript`,
      ).toBe(true)

      // ── ASSERTION C: plaintext absent from step log stream. ──────────────
      const logsText = await readLogsText(request, bearer, buildId!)
      // Positive sanity: the Consume stage's "len=" marker should be present —
      // proves we actually streamed logs (not an empty / errored read).
      expect(
        logsText.length,
        `logs stream returned empty / errored body: ${logsText.slice(0, 200)}`,
      ).toBeGreaterThan(0)
      expect(
        logsText,
        `expected "consume ok: len=" marker in logs — log stream might be filtered. ` +
          `First 400 chars: ${JSON.stringify(logsText.slice(0, 400))}`,
      ).toMatch(/consume ok: len=/)
      expect(
        logsText.includes(PLAINTEXT),
        `CONSTITUTION §6 violated: plaintext "${PLAINTEXT}" leaked into the SSE log stream ` +
          `for build ${buildId}. The worker's log-masker (CredentialResolver maskValues) ` +
          `did not redact the bound STRING credential value.`,
      ).toBe(false)

      // ── ASSERTION D: plaintext absent from the archived leak.txt. ────────
      const artsResp = await apiGet<ArtifactsPage>(
        request,
        bearer,
        `/api/v1/builds/${buildId}/artifacts`,
      )
      expect(
        artsResp.ok,
        `GET /builds/${buildId}/artifacts HTTP ${artsResp.status}`,
      ).toBe(true)
      const items = artsResp.body?.items ?? []
      const leaked = items.find((a) => /leaked\.txt/.test(a.name))

      // Two acceptable outcomes for the engine on the LeakAttempt stage:
      //   (a) leaked.txt was NOT archived at all (engine refused / sanitised).
      //   (b) leaked.txt exists but its body does NOT contain plaintext
      //       (engine masked the env var before redirecting to the file).
      // Any other outcome = plaintext on disk = hard fail with clear message.
      if (leaked) {
        const dlResp = await request.get(
          `${API_BASE}/api/v1/artifacts/${leaked.id}/download`,
          { headers: { Authorization: `Bearer ${bearer}` } },
        )
        expect(
          dlResp.status(),
          `GET /api/v1/artifacts/${leaked.id}/download HTTP ${dlResp.status()}`,
        ).toBe(200)
        const dlBody = await dlResp.text()
        expect(
          dlBody.includes(PLAINTEXT),
          `SECRET LEAKED INTO ARCHIVED ARTIFACT: build ${buildId} archived ` +
            `leaked.txt with plaintext "${PLAINTEXT}". The engine masks the log ` +
            `stream but does not sanitise files written by sh steps before ` +
            `archiveArtifacts captures them. File an engine bug: implement ` +
            `artifact-content sanitisation on the archiveArtifacts step or refuse ` +
            `to archive files containing known credential values. ` +
            `Body bytes (first 200): ${JSON.stringify(dlBody.slice(0, 200))}`,
        ).toBe(false)
      }
      // else: engine refused / didn't capture — also acceptable. Recorded
      //       in the build-success path as a soft observation, no assertion.
    } catch (err) {
      await dump('assertion-failure')
      throw err
    } finally {
      // Cleanup — credentials + the job this spec created (#116: used to leak
      // one e2e-secrets-handling-* job per run).
      if (bearer && credentialId) {
        await request
          .delete(`${API_BASE}/api/v1/credentials/${credentialId}`, {
            headers: { Authorization: `Bearer ${bearer}` },
          })
          .catch(() => {
            /* best-effort */
          })
      }
      if (bearer && hmacCredentialId) {
        await request
          .delete(`${API_BASE}/api/v1/credentials/${hmacCredentialId}`, {
            headers: { Authorization: `Bearer ${bearer}` },
          })
          .catch(() => {
            /* best-effort */
          })
      }
      if (jobId) {
        await safeDeleteJobCascade(request, jobId).catch(() => {
          /* best-effort — leftovers are logged by the helper */
        })
      }
    }
  })
})

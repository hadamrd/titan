/**
 * when-skipped.golden — structured `when:` step guard renders SKIPPED in the UI (GH #1093).
 *
 * This is the issue test-matrix's `e2e @golden: fixture pipeline shows SKIPPED in UI` bar —
 * the browser-level companion to the orchestrator-side `WhenBranchIT` (which proves the engine
 * emits SKIPPED) and the `variantOf` unit fix (which proves SKIPPED maps to the skip card).
 *
 * Strategy (no external fixture — the pipeline YAML is inline, so the spec is self-contained):
 *
 *   1. Create an HMAC webhook credential (kind='STRING' per #793).
 *   2. Create a job whose pipelineScript has TWO sequential sh steps; the second is guarded by
 *      a structured `when: { branch: "release/*" }`.
 *   3. POST a synthetic, HMAC-signed push webhook on refs/heads/main. The guard's glob
 *      (`release/*`) does NOT match `main` → the second step MUST skip.
 *   4. Poll the build to terminal; HARD assert SUCCESS (a skip is a clean no-op, never a failure).
 *   5. API contract: GET /builds/{id}/nodes — HARD assert exactly one node is SKIPPED and the
 *      run-step node is SUCCESS (the orchestrator produced the SKIPPED terminal state).
 *   6. UI golden: open /builds/{id}; the build-detail rail foot counts the SKIPPED node under
 *      `skip` (the `variantOf` fix — before it, a SKIPPED node fell through to `queued` and the
 *      skip total would read 0). This is the browser-level proof the SKIPPED card renders.
 *
 * Lightweight per the repo convention: asserts on the nodes API + a single DOM count, not a
 * full-page snapshot. Runs only against the live local rig (under `task e2e`).
 */
import * as crypto from 'node:crypto'
import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const TRIGGER_BRANCH = 'main'
// The guard glob deliberately does NOT match the trigger branch, so the guarded step skips.
const GUARD_GLOB = 'release/*'

// Per-run suffix so re-runs don't collide on fullName / credential key.
const RUN_TAG = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
const JOB_FULL_NAME = `e2e-when-skipped-${RUN_TAG}`
const CREDENTIAL_KEY = `e2e-when-skipped-${RUN_TAG}`
const WEBHOOK_SECRET = `s3cr3t-${RUN_TAG}`
const REPO_FULL_NAME = `hadamrd/titan-e2e-when-${RUN_TAG}`

// The inline pipeline: the first sh step always runs; the second is branch-guarded and must
// skip on `main` (the guard targets release/*).
//
// GH #51 spec fix: the PDL grammar has NO step-level `name:` key — a step is exactly ONE
// descriptor key (`sh:`, `script:`, …) plus registered scope keys (`when:`, `image:`, …); the
// server 400s on `name:` ("a step has exactly one descriptor key"), so the original YAML could
// never even create the job. This mirrors the engine's own canonical fixture
// (titan-server/src/integrationTest/resources/.../when-branch.yml + WhenBranchIT): node ids are
// deterministic `<stage-slug>-s<index>` — `build-s0` (unguarded) and `build-s1` (guarded).
const PIPELINE_YAML = `stages:
  - stage: build
    steps:
      - sh: "echo built"
      - sh: "echo deploy-release"
        when:
          branch: "${GUARD_GLOB}"
`

const TERMINAL_STATUSES = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'UNSTABLE', 'ERROR'])

interface CredentialCreateResp {
  id: number
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
}
interface BuildDetail {
  status: string
}
interface FlowNode {
  nodeId: string
  displayName?: string
  nodeType: string
  status: string
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

test.describe('v3 when-skipped @golden', () => {
  test('branch-guarded step skips on non-matching branch and renders SKIPPED in build-detail', async ({
    page,
    request,
  }) => {
    test.setTimeout(180_000)

    let bearer: string | undefined
    let jobId: number | undefined
    let credentialId: number | undefined
    let buildId: number | undefined

    try {
      // ── 1. Login (SPA PKCE) + pull bearer. ───────────────────────────────
      await loginViaKeycloak(page, ENV)
      bearer = await extractAccessToken(page)

      // ── 2. Create the HMAC credential. ───────────────────────────────────
      const credCreate = await request.post(`${API_BASE}/api/v1/credentials`, {
        headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
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

      // ── 3. Create the job with the inline guarded pipeline + github trigger. ─
      const triggersConfig = {
        triggers: [
          {
            type: 'github',
            id: 'github-1',
            branches: [TRIGGER_BRANCH],
            events: ['push'],
            credentialsId: CREDENTIAL_KEY,
          },
        ],
      }
      const jobCreate = await request.post(`${API_BASE}/api/v1/jobs`, {
        headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
        data: {
          fullName: JOB_FULL_NAME,
          displayName: 'E2E when-skipped branch guard',
          pipelineScript: PIPELINE_YAML,
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

      // ── 4. Synthesize + sign + POST a push on refs/heads/main. ───────────
      const pushPayload = {
        ref: `refs/heads/${TRIGGER_BRANCH}`,
        before: '0'.repeat(40),
        after: 'f'.repeat(40),
        repository: { full_name: REPO_FULL_NAME, default_branch: TRIGGER_BRANCH },
        pusher: { name: 'e2e-bot' },
        head_commit: { id: 'f'.repeat(40), message: 'e2e when-skipped push' },
      }
      const bodyBytes = Buffer.from(JSON.stringify(pushPayload), 'utf8')
      const sig =
        'sha256=' + crypto.createHmac('sha256', WEBHOOK_SECRET).update(bodyBytes).digest('hex')

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
      const webhookBody = JSON.parse(webhookRaw) as { dispatched: boolean; detail: string }
      expect(
        webhookBody.dispatched,
        `webhook accepted but dispatched=false (detail="${webhookBody.detail}")`,
      ).toBe(true)

      // ── 5. Poll for the build, then poll to terminal. ────────────────────
      await expect
        .poll(
          async () => {
            const r = await apiGet<BuildsPage>(
              request,
              bearer!,
              `/api/v1/jobs/${jobId}/builds?offset=0&limit=20`,
            )
            const first = r.body?.items?.[0]
            if (first) {
              buildId = first.id
              return 1
            }
            return 0
          },
          { message: `no build appeared on job ${jobId} within 30s`, timeout: 30_000 },
        )
        .toBeGreaterThan(0)

      let finalStatus = ''
      await expect
        .poll(
          async () => {
            const r = await apiGet<BuildDetail>(request, bearer!, `/api/v1/builds/${buildId}`)
            finalStatus = r.body?.status ?? ''
            return TERMINAL_STATUSES.has(finalStatus) ? finalStatus : ''
          },
          {
            message: `build ${buildId} not terminal within 90s (last: "${finalStatus}")`,
            timeout: 90_000,
          },
        )
        .not.toBe('')

      // A skipped step is a clean no-op — the build MUST still succeed.
      expect(
        finalStatus,
        `build ${buildId} terminal status "${finalStatus}", expected SUCCESS — ` +
          `a SKIPPED step must not fail the build (GH #1093)`,
      ).toBe('SUCCESS')

      // ── 6. API contract: the guarded step is SKIPPED, the plain step SUCCESS. ─
      const nodesResp = await apiGet<FlowNode[]>(request, bearer, `/api/v1/builds/${buildId}/nodes`)
      expect(nodesResp.ok, `GET /builds/${buildId}/nodes HTTP ${nodesResp.status}`).toBe(true)
      const nodes = nodesResp.body ?? []
      // GH #51: steps are not nameable in the PDL grammar; node ids are the engine's
      // deterministic `<stage-slug>-s<index>` (same ids WhenBranchIT asserts on).
      const byId = (id: string) => nodes.find((n) => n.nodeId === id)
      const deploy = byId('build-s1')
      const build = byId('build-s0')
      expect(
        deploy?.status,
        `branch-guarded step 'build-s1' status was "${deploy?.status}", expected SKIPPED — ` +
          `nodes: ${JSON.stringify(nodes.map((n) => [n.nodeId, n.status]))}`,
      ).toBe('SKIPPED')
      expect(build?.status, `plain step 'build-s0' should be SUCCESS`).toBe('SUCCESS')

      // ── 7. UI golden: build-detail rail counts the SKIPPED node as `skip`. ─
      // Before the variantOf fix a SKIPPED node fell through to `queued` and this total read 0.
      await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)
      const railFoot = page.locator('.bd-rail-foot')
      await expect(railFoot, 'build-detail rail foot did not render').toBeVisible({ timeout: 15_000 })
      const skipText = await railFoot.innerText()
      const skipMatch = skipText.match(/(\d+)\s*skip/i)
      expect(skipMatch, `rail foot had no 'N skip' total; got: ${JSON.stringify(skipText)}`).not.toBeNull()
      expect(
        Number(skipMatch![1]),
        `build-detail rail showed ${skipMatch![1]} skip, expected >=1 — ` +
          `the SKIPPED node must render under the skip variant (the GH #1093 variantOf fix)`,
      ).toBeGreaterThanOrEqual(1)
    } finally {
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

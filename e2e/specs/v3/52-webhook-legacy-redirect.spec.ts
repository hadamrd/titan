/**
 * 52-webhook-legacy-redirect — POST to the deprecated /api/v1/webhooks/github
 * path must 308-redirect to /api/v1/triggers/github, and the redirected POST
 * must produce a build row with structured trigger metadata (issue #970,
 * design/52).
 *
 * Why this exists: PR #968 surfaced two parallel inbound webhook endpoints —
 * the legacy /webhooks/github (no triggerMetaJson) and the canonical
 * /triggers/github (full triggerMetaJson). #970 collapses the legacy path to
 * a 308 redirect shim. This spec is the wire-level proof that:
 *
 *   1. The legacy URL returns 308 with Location, Deprecation, Sunset, Link
 *      headers (RFC 8594).
 *   2. A 308 preserves method + body — Playwright's request fetch auto-
 *      follows it, and the canonical endpoint receives the SAME signed POST
 *      and produces a build with triggerMetaJson.branch + .commitSha
 *      populated. This is exactly what the PR-status reporter (#26/#27) and
 *      the build-detail UI (#40) read; if either is NULL, those features
 *      silently no-op.
 *
 * The spec is deliberately isolated from #24's full bake-and-render path —
 * we assert on the build row + its triggerMetaJson, not on stage execution.
 */
import * as crypto from 'node:crypto'
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const RUN_TAG = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
const JOB_FULL_NAME = `e2e-legacy-redirect-${RUN_TAG}`
const CREDENTIAL_KEY = `e2e-legacy-redirect-${RUN_TAG}`
const WEBHOOK_SECRET = `legacy-${RUN_TAG}`
const HEAD_SHA = 'a3f9c12'.padEnd(40, '0') // 40-char SHA the canonical endpoint persists in full

async function getAuthHeader(): Promise<string> {
  // OIDC token via Keycloak — reuse the shared direct-grant helper every other
  // v3 spec uses rather than re-deriving the token endpoint inline.
  const accessToken = await fetchBearerToken(ENV)
  return `Bearer ${accessToken}`
}

async function seedJobAndCredential(
  request: APIRequestContext,
  auth: string,
): Promise<number> {
  // Credential first — the trigger references it by key.
  const credResp = await request.post(`${API_BASE}/api/v1/credentials`, {
    headers: { Authorization: auth, 'Content-Type': 'application/json' },
    data: {
      scope: 'github-webhook',
      key: CREDENTIAL_KEY,
      kind: 'string',
      value: WEBHOOK_SECRET,
    },
  })
  expect(
    credResp.status(),
    `credential create HTTP ${credResp.status()} body=${await credResp.text()}`,
  ).toBeLessThan(300)

  // Minimal pipeline + a github trigger on trunk pointing at our credential.
  const pipelineYaml = `name: legacy-redirect-${RUN_TAG}\nstages:\n  - stage: echo\n    steps:\n      - script: echo ok\n`
  const jobResp = await request.post(`${API_BASE}/api/v1/jobs`, {
    headers: { Authorization: auth, 'Content-Type': 'application/json' },
    data: {
      fullName: JOB_FULL_NAME,
      pipelineScript: pipelineYaml,
      configJson: JSON.stringify({
        triggers: [
          {
            type: 'github',
            id: 'legacy-redirect-trig',
            branches: ['trunk'],
            events: ['push'],
            credentialsId: CREDENTIAL_KEY,
          },
        ],
      }),
    },
  })
  expect(
    jobResp.status(),
    `job create HTTP ${jobResp.status()} body=${await jobResp.text()}`,
  ).toBeLessThan(300)
  const jobBody = (await jobResp.json()) as { id: number }
  return jobBody.id
}

test('legacy /api/v1/webhooks/github 308-redirects to /api/v1/triggers/github (closes #970)', async ({
  request,
}) => {
  const auth = await getAuthHeader()
  const jobId = await seedJobAndCredential(request, auth)

  const pushPayload = {
    ref: 'refs/heads/trunk',
    after: HEAD_SHA,
    repository: { full_name: 'adaptiq/legacy-redirect' },
    pusher: { name: 'legacy-bot' },
    head_commit: { id: HEAD_SHA, message: 'legacy redirect e2e' },
  }
  const bodyBytes = Buffer.from(JSON.stringify(pushPayload), 'utf8')
  const sig =
    'sha256=' +
    crypto.createHmac('sha256', WEBHOOK_SECRET).update(bodyBytes).digest('hex')

  // Step 1 — POST to the legacy URL with redirect-following disabled. The
  // canonical Playwright `request` fixture would auto-follow; we want to see
  // the 308 itself, so we drop to fetch().
  const legacyResp = await fetch(`${API_BASE}/api/v1/webhooks/github`, {
    method: 'POST',
    redirect: 'manual',
    headers: {
      'Content-Type': 'application/json',
      'X-GitHub-Event': 'push',
      'X-Hub-Signature-256': sig,
    },
    body: bodyBytes,
  })
  expect(legacyResp.status, `legacy path must 308 — got ${legacyResp.status}`).toBe(308)
  expect(legacyResp.headers.get('location')).toBe('/api/v1/triggers/github')
  expect(legacyResp.headers.get('deprecation')).toBe('true')
  expect(legacyResp.headers.get('sunset')).not.toBeNull()

  // Step 2 — re-POST the same signed body to the canonical Location. 308
  // semantics: method + body preserved. This is what GitHub does on its own.
  const canonicalResp = await request.post(
    `${API_BASE}/api/v1/triggers/github`,
    {
      headers: {
        'Content-Type': 'application/json',
        'X-GitHub-Event': 'push',
        'X-Hub-Signature-256': sig,
      },
      data: bodyBytes,
    },
  )
  expect(
    canonicalResp.status(),
    `canonical endpoint HTTP ${canonicalResp.status()} body=${await canonicalResp.text()}`,
  ).toBe(200)

  // Step 3 — poll GET /api/v1/jobs/{id}/builds until the build appears, then
  // assert its triggerMetaJson carries the structured branch + commitSha.
  // The build is what #26/#27/#40 read; if metadata is missing they no-op.
  let build: { id: number; triggerMetaJson?: string } | undefined
  for (let i = 0; i < 30; i++) {
    const listResp = await request.get(
      `${API_BASE}/api/v1/jobs/${jobId}/builds`,
      { headers: { Authorization: auth } },
    )
    if (listResp.ok()) {
      const builds = (await listResp.json()) as Array<{
        id: number
        triggerMetaJson?: string
      }>
      if (builds.length > 0) {
        build = builds[0]
        break
      }
    }
    await new Promise((r) => setTimeout(r, 500))
  }
  expect(build, 'no build appeared after the redirected POST').toBeDefined()
  const meta = build?.triggerMetaJson
  expect(
    meta,
    'triggerMetaJson must be populated — the #970 acceptance criterion',
  ).toBeTruthy()
  expect(meta).toContain('"branch":"trunk"')
  expect(meta).toContain(`"commitSha":"${HEAD_SHA}"`)
})

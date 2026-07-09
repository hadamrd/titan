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
 *   2. A 308 preserves method + body — the canonical endpoint receives the
 *      SAME signed POST and produces a build with triggerMeta.branch +
 *      .commitSha populated (the typed projection of trigger_meta_json,
 *      issue #589). This is exactly what the PR-status reporter (#26/#27)
 *      and the build-detail UI (#40) read; if either is NULL, those
 *      features silently no-op.
 *
 * The spec is deliberately isolated from #24's full bake-and-render path —
 * we assert on the build row + its triggerMetaJson, not on stage execution.
 *
 * Repaired for #87: the original never passed against the current API —
 * credential create sent `value:`/`kind:'string'` where CreateCredentialRequest
 * requires `plaintext` (+ kind 'STRING'), the builds poll read
 * GET /jobs/{id}/builds as a bare array where the endpoint returns
 * `{items,total}`, it asserted on a raw `triggerMetaJson` string where the
 * DTO ships the typed `triggerMeta` object, and nothing was ever cleaned
 * up. Now uses the real contracts and tears down its job
 * (safeDeleteJobCascade) + credential in a finally block, per the
 * spec-ownership rule in e2e/README.md.
 */
import * as crypto from 'node:crypto'
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'
import { safeDeleteJobCascade } from '../../fixtures/teardown-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const RUN_TAG = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
const JOB_FULL_NAME = `e2e-legacy-redirect-${RUN_TAG}`
const CREDENTIAL_KEY = `e2e-legacy-redirect-${RUN_TAG}`
const WEBHOOK_SECRET = `legacy-${RUN_TAG}`
const HEAD_SHA = 'a3f9c12'.padEnd(40, '0') // 40-char SHA the canonical endpoint persists in full

interface CredentialCreateResp {
  id: number
}
interface JobCreateResp {
  id: number
}
interface TriggerMetaDto {
  branch?: string
  commitSha?: string
  actor?: string
}
interface BuildListItem {
  id: number
  /**
   * Typed projection of titan.builds.trigger_meta_json (issue #589) — the
   * API never ships the raw JSON string; BuildDto.parseTriggerMeta maps it
   * to {branch, commitSha, actor} and NON_NULL-strips it on manual builds.
   */
  triggerMeta?: TriggerMetaDto
}
interface BuildsPage {
  items: BuildListItem[]
  total: number
}

async function getAuthHeader(): Promise<string> {
  // OIDC token via Keycloak — reuse the shared direct-grant helper every other
  // v3 spec uses rather than re-deriving the token endpoint inline.
  const accessToken = await fetchBearerToken(ENV)
  return `Bearer ${accessToken}`
}

async function seedJobAndCredential(
  request: APIRequestContext,
  auth: string,
): Promise<{ jobId: number; credentialId: number }> {
  // Credential first — the trigger references it by key. CreateCredentialRequest
  // requires `plaintext` (NOT `value`) and the kind enum is uppercase 'STRING'
  // (#87; same payload as when-skipped.golden / 26-fixture-simple-build).
  const credResp = await request.post(`${API_BASE}/api/v1/credentials`, {
    headers: { Authorization: auth, 'Content-Type': 'application/json' },
    data: {
      kind: 'STRING',
      scope: 'github-webhook',
      key: CREDENTIAL_KEY,
      plaintext: WEBHOOK_SECRET,
    },
  })
  const credRaw = await credResp.text()
  expect(
    credResp.status(),
    `credential create HTTP ${credResp.status()} body=${credRaw.slice(0, 400)}`,
  ).toBeLessThan(300)
  const credentialId = (JSON.parse(credRaw) as CredentialCreateResp).id

  // Minimal pipeline + a github trigger on trunk pointing at our credential.
  // (No root `name:` key — the PDL grammar rejects unknown root keys.)
  const pipelineYaml = `stages:\n  - stage: echo\n    steps:\n      - sh: "echo ok"\n`
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
  const jobRaw = await jobResp.text()
  expect(
    jobResp.status(),
    `job create HTTP ${jobResp.status()} body=${jobRaw.slice(0, 600)}`,
  ).toBeLessThan(300)
  const jobId = (JSON.parse(jobRaw) as JobCreateResp).id
  return { jobId, credentialId }
}

test('legacy /api/v1/webhooks/github 308-redirects to /api/v1/triggers/github (closes #970)', async ({
  request,
}) => {
  const auth = await getAuthHeader()
  let jobId: number | undefined
  let credentialId: number | undefined
  try {
    const seeded = await seedJobAndCredential(request, auth)
    jobId = seeded.jobId
    credentialId = seeded.credentialId

    const pushPayload = {
      ref: 'refs/heads/trunk',
      before: '0'.repeat(40),
      after: HEAD_SHA,
      repository: { full_name: 'adaptiq/legacy-redirect', default_branch: 'trunk' },
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
    // The endpoint returns a `{items,total}` page, not a bare array (#87).
    // The build is what #26/#27/#40 read; if metadata is missing they no-op.
    let build: BuildListItem | undefined
    await expect
      .poll(
        async () => {
          const listResp = await request.get(
            `${API_BASE}/api/v1/jobs/${jobId}/builds?offset=0&limit=20`,
            { headers: { Authorization: auth } },
          )
          if (!listResp.ok()) return 0
          const page = (await listResp.json()) as BuildsPage
          const first = page.items?.[0]
          if (first) {
            build = first
            return 1
          }
          return 0
        },
        {
          message: `no build appeared on job ${jobId} after the redirected POST`,
          timeout: 30_000,
        },
      )
      .toBeGreaterThan(0)

    const meta = build?.triggerMeta
    expect(
      meta,
      `triggerMeta must be populated — the #970 acceptance criterion ` +
        `(build=${JSON.stringify(build)})`,
    ).toBeTruthy()
    expect(meta?.branch, 'triggerMeta.branch — what #26/#27/#40 read').toBe('trunk')
    expect(meta?.commitSha, 'triggerMeta.commitSha — what #26/#27/#40 read').toBe(HEAD_SHA)
  } finally {
    // Ownership cleanup (#87, e2e/README spec-ownership rule): the webhook
    // build ran through the REAL engine, so tear the job down via
    // safeDeleteJobCascade (cancels live builds, waits for lease drain,
    // then deletes) and drop the per-run credential.
    if (jobId !== undefined) {
      await safeDeleteJobCascade(request, jobId).catch((e) =>
        console.warn(`[52-legacy-redirect] job teardown failed (job ${jobId}):`, e),
      )
    }
    if (credentialId !== undefined) {
      await request
        .delete(`${API_BASE}/api/v1/credentials/${credentialId}`, {
          headers: { Authorization: auth },
        })
        .catch(() => {
          /* best-effort */
        })
    }
  }
})

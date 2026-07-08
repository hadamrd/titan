/**
 * golden-build — shared helper for @golden fixture specs that drive the full inbound-webhook
 * path against a pipeline committed to `hadamrd/titan-e2e-fixture`, then assert on a build
 * artifact's bytes.
 *
 * Extracted from the spec-26 pattern (webhook → dispatched → build → SUCCESS → artifact bytes),
 * which several specs copy verbatim. Specs 47/48 (#1228) reuse it so the only thing each spec
 * owns is its unique fixture path + the marker it asserts. Each spec still declares its own
 * `FIXTURE_PATH` literal so the 00-fixture-guard collector sees it; the YAML itself is read
 * from the vendored mirror via fixture-files.ts (#48 — no network in Layer-1 specs).
 */
import * as crypto from 'node:crypto'
import { test, expect, type Page, type APIRequestContext } from '@playwright/test'

const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const TERMINAL_STATUSES = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'UNSTABLE', 'ERROR'])

interface BuildListItem {
  id: number
  status: string
}
interface BuildsPage {
  items: BuildListItem[]
}
interface BuildDetail {
  id: number
  status: string
}
interface ArtifactDto {
  id: number
  name: string
  sizeBytes: number
}
interface ArtifactsPage {
  items: ArtifactDto[]
}

/** Pull the OIDC access token the SPA stashed in sessionStorage post-login. */
export async function extractAccessToken(page: Page): Promise<string> {
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

export interface BuiltArtifact {
  buildId: number
  text: string
  sizeBytes: number
}

/**
 * Create a webhook-triggered job from `fixtureYaml`, fire a signed push, poll the build to a
 * terminal status, HARD-assert SUCCESS, then download the single artifact matching
 * `artifactNameRe` and return its bytes-as-text. Cleans up the credential in a finally.
 *
 * `bearer` must be a live access token (see {@link extractAccessToken}).
 */
export async function runFixtureBuildAndFetchArtifact(opts: {
  request: APIRequestContext
  page: Page
  bearer: string
  fixtureRepo: string
  fixtureBranch: string
  fixtureYaml: string
  runTag: string
  artifactNameRe: RegExp
}): Promise<BuiltArtifact> {
  const { request, page, bearer, fixtureRepo, fixtureBranch, fixtureYaml, runTag, artifactNameRe } =
    opts
  const jobFullName = `e2e-${runTag}`
  const credentialKey = `e2e-${runTag}`
  const webhookSecret = `s3cr3t-${runTag}`

  let credentialId: number | undefined
  let buildId: number | undefined

  try {
    // 1. HMAC credential (kind='STRING' per #793).
    const credCreate = await request.post(`${API_BASE}/api/v1/credentials`, {
      headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
      data: { kind: 'STRING', scope: 'github-webhook', key: credentialKey, plaintext: webhookSecret },
    })
    const credRaw = await credCreate.text()
    expect(credCreate.status(), `POST /credentials HTTP ${credCreate.status()} body=${credRaw.slice(0, 400)}`).toBe(
      201,
    )
    credentialId = (JSON.parse(credRaw) as { id: number }).id

    // 2. Job with the fixture YAML + a github push trigger.
    const triggersConfig = {
      triggers: [
        { type: 'github', id: 'github-1', branches: [fixtureBranch], events: ['push'], credentialsId: credentialKey },
      ],
    }
    const jobCreate = await request.post(`${API_BASE}/api/v1/jobs`, {
      headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
      data: {
        fullName: jobFullName,
        displayName: jobFullName,
        pipelineScript: fixtureYaml,
        configJson: JSON.stringify(triggersConfig),
        enabled: true,
      },
    })
    const jobRaw = await jobCreate.text()
    expect(jobCreate.status(), `POST /jobs HTTP ${jobCreate.status()} body=${jobRaw.slice(0, 600)}`).toBe(201)
    const jobId = (JSON.parse(jobRaw) as { id: number }).id
    expect(jobId).toBeGreaterThan(0)

    // 3. Signed push webhook.
    const pushPayload = {
      ref: `refs/heads/${fixtureBranch}`,
      before: '0'.repeat(40),
      after: 'f'.repeat(40),
      repository: { full_name: fixtureRepo, default_branch: fixtureBranch },
      pusher: { name: 'e2e-bot' },
      head_commit: { id: 'f'.repeat(40), message: `e2e ${runTag}` },
    }
    const bodyBytes = Buffer.from(JSON.stringify(pushPayload), 'utf8')
    const sig = 'sha256=' + crypto.createHmac('sha256', webhookSecret).update(bodyBytes).digest('hex')
    const webhookResp = await request.post(`${API_BASE}/api/v1/triggers/github`, {
      headers: {
        'Content-Type': 'application/json',
        'X-GitHub-Event': 'push',
        'X-Hub-Signature-256': sig,
        'X-GitHub-Delivery': `e2e-${runTag}`,
      },
      data: bodyBytes,
    })
    const webhookRaw = await webhookResp.text()
    expect(webhookResp.status(), `POST /triggers/github HTTP ${webhookResp.status()} body=${webhookRaw}`).toBe(200)
    const webhookBody = JSON.parse(webhookRaw) as { dispatched: boolean; detail: string }
    expect(webhookBody.dispatched, `webhook accepted but dispatched=false (detail="${webhookBody.detail}")`).toBe(true)

    // 4. Poll for the build to appear.
    await expect
      .poll(
        async () => {
          const r = await apiGet<BuildsPage>(request, bearer, `/api/v1/jobs/${jobId}/builds?offset=0&limit=20`)
          const first = r.body?.items?.[0]
          if (first) {
            buildId = first.id
            return 1
          }
          return 0
        },
        { message: `no build appeared on job ${jobId} within 30s`, timeout: 30_000, intervals: [500, 1_000, 2_000] },
      )
      .toBeGreaterThan(0)

    // 5. Poll to terminal, HARD-assert SUCCESS.
    let finalStatus = ''
    await expect
      .poll(
        async () => {
          const r = await apiGet<BuildDetail>(request, bearer, `/api/v1/builds/${buildId}`)
          finalStatus = r.body?.status ?? ''
          return TERMINAL_STATUSES.has(finalStatus) ? finalStatus : ''
        },
        {
          message: `build ${buildId} did not reach terminal status within 120s (last="${finalStatus}")`,
          timeout: 120_000,
          intervals: [1_000, 2_000, 3_000],
        },
      )
      .not.toBe('')
    if (finalStatus !== 'SUCCESS') {
      const nodes = await apiGet(request, bearer, `/api/v1/builds/${buildId}/nodes`)
      await test.info().attach(`nodes-${runTag}.json`, { body: nodes.raw, contentType: 'application/json' })
    }
    expect(finalStatus, `build ${buildId} terminal status was "${finalStatus}", expected SUCCESS`).toBe('SUCCESS')

    // 6. Find + download the artifact.
    const artsResp = await apiGet<ArtifactsPage>(request, bearer, `/api/v1/builds/${buildId}/artifacts`)
    expect(artsResp.ok, `GET /builds/${buildId}/artifacts HTTP ${artsResp.status} body=${artsResp.raw.slice(0, 400)}`).toBe(
      true,
    )
    const items = artsResp.body?.items ?? []
    const artifact = items.find((a) => artifactNameRe.test(a.name))
    expect(
      artifact,
      `no artifact matching ${artifactNameRe} on build ${buildId}. Observed: ${JSON.stringify(items.map((a) => a.name))}`,
    ).toBeDefined()
    expect(artifact!.sizeBytes, `artifact ${artifact!.name} has zero bytes`).toBeGreaterThan(0)

    const dlResp = await request.get(`${API_BASE}/api/v1/artifacts/${artifact!.id}/download`, {
      headers: { Authorization: `Bearer ${bearer}` },
    })
    expect(dlResp.status(), `GET /artifacts/${artifact!.id}/download HTTP ${dlResp.status()}`).toBe(200)
    const text = await dlResp.text()
    return { buildId: buildId!, text, sizeBytes: artifact!.sizeBytes }
  } finally {
    if (credentialId) {
      await request
        .delete(`${API_BASE}/api/v1/credentials/${credentialId}`, { headers: { Authorization: `Bearer ${bearer}` } })
        .catch(() => {
          /* best-effort */
        })
    }
    await page.screenshot({ fullPage: true }).then(
      (png) => test.info().attach(`screenshot-${runTag}.png`, { body: png, contentType: 'image/png' }),
      () => {
        /* best-effort */
      },
    )
  }
}

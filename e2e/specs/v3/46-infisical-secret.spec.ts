/**
 * 46-infisical-secret — a pipeline binds an **Infisical-sourced** secret and it
 * never leaks plaintext (CONSTITUTION §6, issue #1227).
 *
 * The sibling spec #45 proves leak-proof binding of a **db-envelope**-stored
 * secret (created via `POST /api/v1/credentials`). This spec proves the same bar
 * for a secret that lives in **Infisical**, resolved through the `infisical`
 * SecretsBackend (`InfisicalSecretsBackend`, #1227). Infisical owns the secret
 * lifecycle, so there is NO `POST /api/v1/credentials` here — the secret is
 * pre-seeded in Infisical and the controller runs with
 * `TITAN_SECRETS_BACKEND=infisical`.
 *
 * Because that is a rig-deployment posture (the default rig runs `db-envelope`),
 * the spec is **gated**: it `test.skip()`s unless the operator provides the two
 * coordinates that say "this rig is wired for the Infisical e2e":
 *
 *   - `E2E_INFISICAL_CRED`  — the `scope/key` the pipeline binds; the `key` part
 *                             MUST equal the Infisical secret name pre-seeded.
 *   - `E2E_INFISICAL_VALUE` — the known plaintext of that Infisical secret, so
 *                             the spec can assert length-not-value + non-leak.
 *
 * This mirrors #45's "fixture is the contract; skip if missing" discipline.
 *
 * Pipeline (inline, self-contained — no SCM checkout, manual trigger):
 *   1. Consume     — binds the Infisical secret into $SECRET_TOKEN and proves
 *                    consumption by printing its LENGTH (never its value).
 *   2. LeakAttempt — deliberately writes $SECRET_TOKEN to leaked.txt and
 *                    archives it (the adversarial probe, same as #45).
 *
 * Hard assertions (plaintext = the Infisical secret value):
 *   A. Build terminal status SUCCESS — proves the Infisical binding worked.
 *   B. JobDto.pipelineScript does NOT contain plaintext (model carries id only)
 *      and DOES contain the credential id reference.
 *   C. SSE log stream contains `consume ok: len=<N>` (N = the real value's
 *      length — proof the *correct* Infisical secret was bound) and does NOT
 *      contain plaintext.
 *   D. The archived leaked.txt body does NOT contain plaintext (same bar as #45).
 */
import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

// Rig-provided coordinates — absent on a default (db-envelope) rig → spec skips.
const CRED_ID = process.env.E2E_INFISICAL_CRED ?? ''
const SECRET_VALUE = process.env.E2E_INFISICAL_VALUE ?? ''

const RUN_TAG = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
const JOB_FULL_NAME = `e2e-infisical-secret-${RUN_TAG}`

const TERMINAL_STATUSES = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'UNSTABLE', 'ERROR'])

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

/** Self-contained pipeline that binds the Infisical credential by id, no SCM checkout needed. */
function buildPipelineYaml(credId: string): string {
  return [
    'stages:',
    '  - stage: Consume',
    '    steps:',
    '      - sh: \'echo "consume ok: len=${#SECRET_TOKEN}"\'',
    '        credentials:',
    `          - id: ${credId}`,
    '            type: string',
    '            variable: SECRET_TOKEN',
    '  - stage: LeakAttempt',
    '    dependsOn: [Consume]',
    '    steps:',
    "      - sh: 'printf \"%s\" \"$SECRET_TOKEN\" > leaked.txt'",
    '        credentials:',
    `          - id: ${credId}`,
    '            type: string',
    '            variable: SECRET_TOKEN',
    '      - archiveArtifacts:',
    '          artifacts: "leaked.txt"',
    '',
  ].join('\n')
}

test.describe('v3 infisical-secret @real-commit', () => {
  test('Infisical-sourced credential consumed; plaintext never leaks to script / logs / artifact', async ({
    page,
    request,
  }) => {
    test.setTimeout(180_000)

    // ── Gate: only run when the rig is wired for the Infisical e2e. ──────────
    test.skip(
      CRED_ID.length === 0 || SECRET_VALUE.length === 0,
      'Infisical e2e not configured — set E2E_INFISICAL_CRED (scope/key) + E2E_INFISICAL_VALUE ' +
        'and run the controller with TITAN_SECRETS_BACKEND=infisical + a pre-seeded secret.',
    )
    expect(CRED_ID, 'E2E_INFISICAL_CRED must be a scope/key id').toContain('/')

    const pipelineYaml = buildPipelineYaml(CRED_ID)
    const expectedLen = SECRET_VALUE.length

    let bearer: string | undefined
    let jobId: number | undefined
    let buildId: number | undefined

    // ── Login + bearer ──────────────────────────────────────────────────────
    await loginViaKeycloak(page, ENV)
    bearer = await extractAccessToken(page)

    // ── Create the job (pipelineScript binds the Infisical credential by id). ─
    const jobCreate = await request.post(`${API_BASE}/api/v1/jobs`, {
      headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
      data: {
        fullName: JOB_FULL_NAME,
        displayName: 'E2E infisical-secret adversarial probe',
        pipelineScript: pipelineYaml,
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

    // ── Trigger a manual build (no webhook / HMAC credential needed). ────────
    const trigger = await request.post(`${API_BASE}/api/v1/jobs/${jobId}/builds`, {
      headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
      data: { triggeredBy: 'e2e-infisical' },
    })
    const triggerRaw = await trigger.text()
    expect(
      trigger.ok(),
      `POST /api/v1/jobs/${jobId}/builds HTTP ${trigger.status()} body=${triggerRaw.slice(0, 400)}`,
    ).toBe(true)

    // ── Poll for the build to appear, then reach terminal status. ────────────
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
    expect(buildId).toBeGreaterThan(0)

    let finalStatus = ''
    await expect
      .poll(
        async () => {
          const r = await apiGet<BuildDetail>(request, bearer!, `/api/v1/builds/${buildId}`)
          finalStatus = r.body?.status ?? ''
          return TERMINAL_STATUSES.has(finalStatus) ? finalStatus : ''
        },
        {
          message: `build ${buildId} did not reach terminal status within 120s (last: "${finalStatus}")`,
          timeout: 120_000,
          intervals: [1_000, 2_000, 3_000],
        },
      )
      .not.toBe('')

    // ── ASSERTION A: SUCCESS — proves the Infisical binding resolved. ────────
    expect(
      finalStatus,
      `build ${buildId} terminal status was "${finalStatus}", expected SUCCESS. ` +
        `Any non-SUCCESS means the engine failed to resolve the Infisical-sourced credential ` +
        `"${CRED_ID}" into the worker's env (check the controller runs TITAN_SECRETS_BACKEND=infisical ` +
        `and the secret is pre-seeded in Infisical).`,
    ).toBe('SUCCESS')

    // ── ASSERTION B: plaintext absent from persisted pipelineScript. ─────────
    const jobResp = await apiGet<JobDetail>(request, bearer, `/api/v1/jobs/${jobId}`)
    expect(jobResp.ok, `GET /jobs/${jobId} HTTP ${jobResp.status}`).toBe(true)
    const persistedScript = jobResp.body?.pipelineScript ?? ''
    expect(persistedScript.length, 'pipelineScript empty').toBeGreaterThan(0)
    expect(
      persistedScript.includes(SECRET_VALUE),
      `CONSTITUTION §6 violated: plaintext leaked into JobDto.pipelineScript. The model carries ` +
        `the credential id only; the Infisical secret value must never appear in it.`,
    ).toBe(false)
    expect(
      persistedScript.includes(CRED_ID),
      `expected credential id "${CRED_ID}" reference in pipelineScript`,
    ).toBe(true)

    // ── ASSERTION C: length proven; plaintext absent from the log stream. ────
    const logsText = await readLogsText(request, bearer, buildId!)
    expect(
      logsText.length,
      `logs stream returned empty / errored body: ${logsText.slice(0, 200)}`,
    ).toBeGreaterThan(0)
    expect(
      logsText,
      `expected "consume ok: len=${expectedLen}" — proves the *correct* Infisical secret was ` +
        `bound (right length), not an empty/wrong value. First 400 chars: ` +
        `${JSON.stringify(logsText.slice(0, 400))}`,
    ).toContain(`consume ok: len=${expectedLen}`)
    expect(
      logsText.includes(SECRET_VALUE),
      `CONSTITUTION §6 violated: plaintext leaked into the SSE log stream for build ${buildId}. ` +
        `The worker's log-masker did not redact the bound Infisical secret value.`,
    ).toBe(false)

    // ── ASSERTION D: plaintext absent from the archived leaked.txt. ──────────
    const artsResp = await apiGet<ArtifactsPage>(
      request,
      bearer,
      `/api/v1/builds/${buildId}/artifacts`,
    )
    expect(artsResp.ok, `GET /builds/${buildId}/artifacts HTTP ${artsResp.status}`).toBe(true)
    const leaked = (artsResp.body?.items ?? []).find((a) => /leaked\.txt/.test(a.name))
    if (leaked) {
      const dl = await request.get(`${API_BASE}/api/v1/artifacts/${leaked.id}/download`, {
        headers: { Authorization: `Bearer ${bearer}` },
      })
      expect(dl.status(), `GET /artifacts/${leaked.id}/download HTTP ${dl.status()}`).toBe(200)
      const dlBody = await dl.text()
      expect(
        dlBody.includes(SECRET_VALUE),
        `SECRET LEAKED INTO ARCHIVED ARTIFACT: build ${buildId} archived leaked.txt with the ` +
          `Infisical plaintext. The engine masks the log stream but does not sanitise files ` +
          `written by sh steps before archiveArtifacts captures them — same engine bug as #45.`,
      ).toBe(false)
    }
    // else: engine refused / didn't capture — also acceptable (same as #45).
  })
})

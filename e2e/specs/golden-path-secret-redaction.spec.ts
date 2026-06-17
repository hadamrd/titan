/**
 * golden-path-secret-redaction — a step that needs a real secret runs GREEN and
 * the raw value never leaks into any log surface the build-detail UI exposes.
 *
 * Closes issue #1175 (secret half; extends the #1173 / #1130 golden-path suite).
 *
 * This is the v3 (standalone, Quarkus + Keycloak) port of the secret-redaction
 * coverage. The original e2e/tests/secret-redaction.spec.ts (#1135) was written
 * against the legacy plugin-host harness (fixtures/index.ts -> TitanApi, which POSTs
 * Groovy to /scriptText and polls /job/<name>/... URLs). Phase 3 deleted the legacy
 * plugin host (docs/design/58-phase3-deletion-completed.md), so that harness can no longer
 * reach the rig — the old spec is retired to a tombstone pointing here. This
 * spec REUSES the same fixture YAML (e2e/pipelines/secret-redaction/
 * titan-pipeline.yml) — it does NOT author a parallel fixture — and drives it
 * through the v3 REST + build-detail UI, mirroring golden-path-failure-triage.
 *
 * The secret-seed surface is REAL: POST /api/v1/credentials (CredentialsApi,
 * #274) seals the plaintext server-side; the fixture binds it via
 * `env: API_TOKEN: ${{ secrets.E2E_REDACTION_TOKEN }}`.
 *
 * The sentinel is STRUCTURED (`sentinel-redact-<rand>-canary`) so a partial /
 * trailing-suffix-after-newline leak still trips `String.includes` — defending
 * the "masks TOKEN but lets the suffix through" class.
 *
 * Assertions (per #1175 acceptance + test matrix):
 *   A. build runs GREEN (masking must not break the step),
 *   B. the mask `****` APPEARS (proves redaction fired, not that the value was
 *      merely never printed),
 *   C. the raw sentinel NEVER appears in:
 *        (a) the streamed SSE log,
 *        (b) the persisted console artifact,
 *        (c) the build-detail UI DOM,
 *   D. ADVERSARIAL env-export leak: the `printenv`/export-site `API_TOKEN=` line
 *      is itself redacted — not just the use-site echo.
 *
 * PENDING — hard dependency #1094 (env+secret runtime) is genuinely unshipped:
 *   The PDL `env: X: ${{ secrets.<id> }}` expression is preserved by the parser
 *   (titan-pipeline-model, see SecretRedactionFixtureTest) but is NOT yet
 *   resolved at step dispatch — the controller's CredentialResolver handles the
 *   step-local `credentials:` binding, not the `env:`-expression form. Without
 *   that resolution + mask registration there is no value to inject and nothing
 *   to redact. So this spec self-skips with the exact gap named. Flip it on with
 *   TITAN_SECRET_RUNTIME=1 (CI sets it once #1094 lands); it then runs LIVE.
 */
import * as fs from 'node:fs'
import * as path from 'node:path'
import { randomBytes } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken, loginViaKeycloak } from '../fixtures/auth-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const SUCCESS_TIMEOUT_MS = Number(process.env.TITAN_SECRET_TIMEOUT_MS ?? 60_000)

/**
 * Hard gate: env+secret runtime (#1094). Default OFF — registered as a visible,
 * skipped PENDING test naming the exact blocking gap, exactly as the sibling
 * scenario fixtures are `pending:`. CI flips this to "1" once `${{ secrets.X }}`
 * env resolution + mask registration ship, and the spec then runs LIVE.
 */
const SECRET_RUNTIME_SHIPPED = process.env.TITAN_SECRET_RUNTIME === '1'
const PENDING_REASON =
  'PENDING #1094 — env+secret runtime unshipped: the PDL ' +
  '`env: X: ${{ secrets.<id> }}` expression is parsed but NOT resolved at step ' +
  'dispatch (CredentialResolver handles the step-local `credentials:` binding, ' +
  'not the env-expression form) so there is no injected value to redact. ' +
  'Set TITAN_SECRET_RUNTIME=1 to run live once #1094 lands.'

const SECRET_ID = 'E2E_REDACTION_TOKEN'
const TERMINAL = new Set(['SUCCESS', 'FAILED', 'FAILURE', 'ABORTED', 'UNSTABLE', 'ERROR'])

interface JobCreateResp { id: number }
interface BuildTriggerResp { buildId: number; buildNumber: number }
interface BuildDto { id: number; status: string }

/** Build a fresh structured sentinel per-run so a stale log can't false-pass. */
function buildSentinel(): string {
  return `sentinel-redact-${randomBytes(6).toString('hex')}-canary`
}

function fixtureRoot(): string {
  const here = path.dirname(fileURLToPath(import.meta.url))
  return path.resolve(here, '..', 'pipelines', 'secret-redaction')
}

function readPipelineYaml(): string {
  return fs.readFileSync(path.join(fixtureRoot(), 'titan-pipeline.yml'), 'utf8')
}

/**
 * Seed (or rotate) a string credential via the REAL standalone secret-seed
 * surface: POST /api/v1/credentials. The server seals the plaintext under
 * SecretCipher#seal and persists ciphertext only; the engine unseals it at
 * step dispatch. Idempotent on (scope, key): a 409/400 "already exists" is
 * rotated via PUT so reruns don't accrete duplicate rows.
 */
async function seedSecret(
  api: APIRequestContext,
  bearer: string,
  key: string,
  plaintext: string,
): Promise<void> {
  const create = await api.post(`${API_BASE}/api/v1/credentials`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: { kind: 'string', scope: 'GLOBAL', key, plaintext },
  })
  if (create.status() === 201) return
  // Already present — rotate the value so this run's sentinel is the live one.
  if (create.status() === 400 || create.status() === 409) {
    const list = await api.get(`${API_BASE}/api/v1/credentials?scope=GLOBAL&limit=200`, {
      headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
    })
    const page = JSON.parse(await list.text()) as { items?: Array<{ id: number; key: string }> }
    const existing = (page.items ?? []).find(c => c.key === key)
    expect(existing, `credential '${key}' neither created nor found for rotation`).toBeTruthy()
    const put = await api.put(`${API_BASE}/api/v1/credentials/${existing!.id}`, {
      headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
      data: { kind: 'string', plaintext },
    })
    expect(put.ok(), `credential rotate failed: ${put.status()} ${await put.text()}`).toBe(true)
    return
  }
  expect(create.ok(), `seedSecret create failed: ${create.status()} ${await create.text()}`).toBe(true)
}

async function createJob(api: APIRequestContext, bearer: string, fullName: string): Promise<number> {
  const resp = await api.post(`${API_BASE}/api/v1/jobs`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: {
      fullName,
      displayName: 'E2E secret redaction (#1175)',
      pipelineScript: readPipelineYaml(),
      configJson: JSON.stringify({ triggers: [] }),
      enabled: true,
    },
  })
  expect(resp.status(), `job create ${await resp.text()}`).toBe(201)
  return (JSON.parse(await resp.text()) as JobCreateResp).id
}

async function triggerBuild(api: APIRequestContext, bearer: string, jobId: number): Promise<number> {
  const r = await api.post(`${API_BASE}/api/v1/jobs/${jobId}/builds`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: {},
  })
  expect(r.status(), `build trigger ${await r.text()}`).toBeLessThan(300)
  const body = JSON.parse(await r.text()) as BuildTriggerResp
  expect(body.buildId).toBeGreaterThan(0)
  return body.buildId
}

async function pollTerminal(
  api: APIRequestContext,
  bearer: string,
  buildId: number,
  budgetMs: number,
): Promise<string> {
  const deadline = Date.now() + budgetMs
  let last = 'QUEUED'
  while (Date.now() < deadline) {
    const r = await api.get(`${API_BASE}/api/v1/builds/${buildId}`, {
      headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
    })
    if (r.ok()) {
      const body = JSON.parse(await r.text()) as BuildDto
      last = body.status
      if (TERMINAL.has(last)) return last
    }
    await new Promise(res => setTimeout(res, 1_000))
  }
  throw new Error(`build ${buildId} not terminal within ${budgetMs}ms; last=${last}`)
}

/**
 * The build's console text from the SSE log endpoint
 * (GET /api/v1/builds/{id}/logs). On v3 this single endpoint feeds BOTH the
 * live stream the UI subscribes to AND the persisted-row replay a late
 * subscriber / operator pulls after the build is over — so a post-terminal read
 * exercises log surfaces (a) streamed and (b) persisted-console at once. We
 * parse the `data:` payloads out of the event-stream framing.
 */
async function consoleViaSse(api: APIRequestContext, bearer: string, buildId: number): Promise<string> {
  const r = await api.get(`${API_BASE}/api/v1/builds/${buildId}/logs`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'text/event-stream' },
    timeout: 30_000,
  })
  const raw = await r.text()
  // SSE framing: collect every `data:` line; ignore `event:`/`id:`/comments.
  return raw
    .split('\n')
    .filter(line => line.startsWith('data:'))
    .map(line => line.slice('data:'.length).replace(/^ /, ''))
    .join('\n')
}

test.describe('golden-path-secret-redaction @golden @secret', () => {
  test.describe.configure({ mode: 'serial' })

  let bearer: string
  let jobId: number
  let buildId: number
  let sentinel: string

  test.beforeAll(async ({ request }) => {
    // test.skip() is illegal in beforeAll — gate with an early return; each
    // test self-skips via test.skip(!SECRET_RUNTIME_SHIPPED, ...) below.
    if (!SECRET_RUNTIME_SHIPPED) return
    bearer = await fetchBearerToken(ENV)
    sentinel = buildSentinel()
    await seedSecret(request, bearer, SECRET_ID, sentinel)
    jobId = await createJob(request, bearer, `e2e-secret-redaction-${Date.now().toString(36)}`)
  })

  test('1. secret-bound step runs GREEN and the mask **** fired', async ({ request }) => {
    test.skip(!SECRET_RUNTIME_SHIPPED, PENDING_REASON)
    test.setTimeout(SUCCESS_TIMEOUT_MS + 60_000)

    buildId = await triggerBuild(request, bearer, jobId)
    const status = await pollTerminal(request, bearer, buildId, SUCCESS_TIMEOUT_MS)

    // A — green. A masked-but-failed build would hide a different bug (the
    // masker eating non-secret bytes and breaking the shell).
    expect(
      status,
      `pipeline must succeed while binding ${SECRET_ID} — masking must not break the step. Got ${status}.`,
    ).toBe('SUCCESS')

    const console = await consoleViaSse(request, bearer, buildId)

    // B — the mask actually fired. Without this, a passing test could prove
    // nothing (the step silently no-op'd / printed nothing).
    expect(
      console.includes('****'),
      'mask token **** must appear in the console — proves redaction fired, not that the value was never printed.',
    ).toBe(true)

    // C(a)+(b) — the raw sentinel must NOT appear anywhere in the SSE/persisted
    // console: not at the use-site echo, not in the printenv export line, not in
    // a stack/timing frame.
    const leaks = console.split('\n').filter(l => l.includes(sentinel))
    expect(
      leaks,
      `raw secret leaked into ${leaks.length} console line(s): ${JSON.stringify(leaks)}`,
    ).toEqual([])

    // D — ADVERSARIAL env-export leak: the fixture's `printenv | grep API_TOKEN`
    // dumps the export-site line. It MUST be present (proves the dump ran) AND
    // masked (the value replaced by ****) — guarding the class where the
    // redactor masks the use-site echo but lets the `API_TOKEN=<raw>` export
    // line through.
    const exportLines = console.split('\n').filter(l => /(^|\b)API_TOKEN=/.test(l))
    expect(
      exportLines.length,
      'printenv export-site line for API_TOKEN not found — did the fixture step run? ' +
        'Cannot prove the export-site is redacted if it never appeared.',
    ).toBeGreaterThan(0)
    for (const line of exportLines) {
      expect(
        line.includes(sentinel),
        `env-export leak: the API_TOKEN export-site line carries the raw secret: ${JSON.stringify(line)}`,
      ).toBe(false)
      expect(
        line.includes('****'),
        `env-export line "${line}" was not masked — expected the value replaced by ****.`,
      ).toBe(true)
    }
  })

  test('2. build-detail UI DOM never renders the raw secret', async ({ page }) => {
    test.skip(!SECRET_RUNTIME_SHIPPED, PENDING_REASON)
    test.skip(!buildId, 'prior secret-build test did not complete')
    test.setTimeout(120_000)

    await loginViaKeycloak(page, ENV)
    await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)

    const detailShell = page.locator('[data-testid="build-detail-v3"], .build-detail-v3')
    await expect(detailShell.first(), 'v3 build-detail shell did not render').toBeVisible({ timeout: 15_000 })

    // Open the Logs tab so the console panel is rendered into the DOM.
    const logsTab = page.getByRole('tab', { name: /^logs$/i })
    if (await logsTab.count() > 0) {
      await logsTab.click()
      await expect(logsTab).toHaveAttribute('aria-selected', 'true')
    }
    await expect(page.locator('.log-line').first(), 'log panel rendered zero lines').toBeVisible({ timeout: 20_000 })

    // C(c) — a server-side redaction is meaningless if the UI template re-fetches
    // the unredacted source. Assert against the whole rendered detail surface.
    const dom = await detailShell.first().innerText()
    expect(
      dom.includes(sentinel),
      'raw secret must NOT appear in the build-detail UI DOM.',
    ).toBe(false)
    expect(
      dom.includes('****'),
      'mask token must be visible in the build-detail UI DOM (proves the UI renders the redacted log).',
    ).toBe(true)
  })
})

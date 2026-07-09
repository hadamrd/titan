/**
 * 60-pat-authenticates-api — PAT auth exercised END-TO-END through the
 * deployed seam (closes #104).
 *
 * Before this spec, no e2e test ever sent `Authorization: Bearer titanpat_…`
 * to the server: spec 14 and profile-tokens-* only exercise the CRUD surface
 * with a Keycloak bearer. This spec proves the product promise on the rig:
 *
 *   1. A freshly minted PAT authenticates an API request through a FRESH
 *      request context (no cookies, no OIDC bearer) and attributes to the
 *      creating principal (identity oracle: GET /api/v1/me/tokens under PAT
 *      auth returns the creator's own tokens; audit oracle: PAT_SCOPE_DENIED
 *      rows carry actor = the creating user).
 *   2. Adversarial 401s: a tampered token (one flipped char — same stored
 *      prefix, so the BCrypt-mismatch path is exercised, not the shape check)
 *      and a wrong-prefix token. The WWW-Authenticate header is asserted
 *      differentially: PAT-shaped rejects carry the PAT mechanism's
 *      `realm="titan", error="invalid_token"` challenge, wrong-prefix rejects
 *      fall through to OIDC (no PAT challenge) — proving mechanism ownership
 *      (PatAuthenticationMechanism priority 1002 vs OIDC 1001, #527).
 *   3. Scope enforcement (#500): a READ_JOB-scoped PAT lists jobs (200) but is
 *      denied on /api/v1/audit (403 — READ_AUDIT not granted).
 *   4. Job-pattern enforcement (#1082, PatJobScopeFilter): an in-pattern PAT
 *      reads the job (200); an out-of-pattern PAT gets the structured 403
 *      application/problem+json with type .../pat-scope-denied AND the
 *      PAT_SCOPE_DENIED audit row (strongest oracle the filter exposes).
 *   5. Revocation: DELETE /api/v1/me/tokens/{id} mid-spec, then the previously
 *      working PAT gets 401 (expect.poll — no sleep).
 *
 * SECURITY: the plaintext tokens live only in runtime variables. They are
 * NEVER logged, attached to the report, or embedded in assertion messages.
 * The only response body ever echoed into a failure message is a non-secret
 * one (jobs page / problem+json / token METADATA list — the list endpoint
 * never returns plaintext).
 *
 * Cleanup: afterAll revokes every minted token (best-effort, allSettled) and
 * then HARD-ASSERTS via the list endpoint that none is still active — zero
 * litter even when an assertion mid-spec fails.
 *
 * Job ownership (#112): this spec creates its OWN tiny job in beforeAll for
 * the authorizes/attributes/pattern assertions and deletes it in afterAll
 * (safeDeleteJobCascade). It must NOT depend on the shared seeded
 * 'titan-hello' row — the ownership rule cuts both ways: specs may not
 * DEPEND on shared seed rows any more than they may MUTATE them, and an
 * earlier spec in full-suite order can legitimately have removed them.
 */
import {
  test,
  expect,
  request as playwrightRequest,
  type APIRequestContext,
} from '@playwright/test'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'
import { createToken, deleteToken, listTokens, type CreateResponse } from '../../fixtures/pat'
import { safeDeleteJobCascade } from '../../fixtures/teardown-v3'

const ENV = authEnv()
const BASE = ENV.uiBaseUrl.replace(/\/$/, '')

/** Stable problem-type URI emitted by PatJobScopeFilter on a pattern deny. */
const SCOPE_DENIED_PROBLEM = 'https://titan.adaptiq.io/problems/pat-scope-denied'
/** Glob that can never match any job on the rig — used for the out-of-pattern PAT. */
const NO_MATCH_PATTERN = 'e2e-104-no-such-job-*'
/** Glob matching ONLY the job this spec creates (never a seeded/shared one). */
const OWN_JOB_PATTERN = 'e2e-104-pat-*'
/** Smallest valid pipeline — the job is never built, it only needs to EXIST. */
const MIN_YAML = 'stages:\n  - stage: build\n    steps:\n      - shell: echo hi\n'

interface JobsPage {
  items: Array<{ id: number; fullName: string }>
}

interface AuditPage {
  items: Array<{ action: string; targetType: string; targetId: string | null; actor: string }>
}

interface ProblemJson {
  type?: string
  status?: number
  detail?: string
}

// ── module state (minted in beforeAll, revoked in afterAll) ────────────────

let patCtx: APIRequestContext | undefined
let creatorBearer: string
let ownJobId: number | undefined
let ownJobName: string

let fullPat: CreateResponse | undefined
let scopedPat: CreateResponse | undefined
let inPatternPat: CreateResponse | undefined
let outPatternPat: CreateResponse | undefined

// ── helpers ─────────────────────────────────────────────────────────────────

/**
 * GET through the FRESH, credential-free context. `token === null` sends no
 * Authorization header at all (the negative control proving the context
 * carries no ambient auth).
 */
async function patGet(
  path: string,
  token: string | null,
): Promise<{ status: number; raw: string; headers: Record<string, string> }> {
  if (!patCtx) throw new Error('patCtx not initialised')
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (token !== null) headers['Authorization'] = `Bearer ${token}`
  const res = await patCtx.get(`${BASE}${path}`, { headers })
  return { status: res.status(), raw: await res.text(), headers: res.headers() }
}

/** GET with the Keycloak bearer (setup / oracle reads only — never the seam under test). */
async function bearerGet<T>(
  path: string,
  bearer: string,
): Promise<{ status: number; body: T | null; raw: string }> {
  const res = await fetch(`${BASE}${path}`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  const raw = await res.text()
  let body: T | null = null
  try {
    body = JSON.parse(raw) as T
  } catch {
    // leave null — caller asserts
  }
  return { status: res.status, body, raw }
}

function parseJsonOrNull<T>(raw: string): T | null {
  try {
    return JSON.parse(raw) as T
  } catch {
    return null
  }
}

/** Flip the last character of the token — same stored prefix, wrong secret. */
function tamper(token: string): string {
  const last = token.slice(-1)
  return token.slice(0, -1) + (last === 'A' ? 'B' : 'A')
}

function mintedIds(): string[] {
  return [fullPat, scopedPat, inPatternPat, outPatternPat]
    .filter((t): t is CreateResponse => t !== undefined)
    .map((t) => String(t.id))
}

// ── spec ────────────────────────────────────────────────────────────────────

test.describe('v3 pat-authenticates-api @golden', () => {
  test.beforeAll(async () => {
    creatorBearer = await fetchBearerToken(ENV)
    // FRESH context: brand-new cookie jar, no storage state, no default
    // headers — the ONLY credential it will ever carry is the PAT we pass
    // per-request. This is the seam the issue demands.
    patCtx = await playwrightRequest.newContext()

    // Own job (#112): created HERE, torn down in afterAll — never the shared
    // seeded 'titan-hello' row, which some earlier spec in suite order may
    // legitimately have deleted.
    const stamp = Date.now()
    ownJobName = `e2e-104-pat-${stamp}-${Math.floor(Math.random() * 1e6)}`
    const createRes = await fetch(`${BASE}/api/v1/jobs`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${creatorBearer}`,
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify({
        fullName: ownJobName,
        displayName: 'e2e-104-pat',
        pipelineScript: MIN_YAML,
        enabled: true,
      }),
    })
    const createRaw = await createRes.text()
    expect(
      createRes.status,
      `POST /api/v1/jobs (setup) failed: HTTP ${createRes.status} body=${createRaw.slice(0, 300)}`,
    ).toBe(201)
    const createdJob = parseJsonOrNull<{ id: number }>(createRaw)
    expect(
      createdJob !== null && createdJob.id > 0,
      'created job must carry a numeric id',
    ).toBe(true)
    ownJobId = createdJob!.id

    fullPat = await createToken(`e2e-104-full-${stamp}`, undefined, creatorBearer, ENV)
    scopedPat = await createToken(`e2e-104-scoped-${stamp}`, ['READ_JOB'], creatorBearer, ENV)
    inPatternPat = await createToken(`e2e-104-inpattern-${stamp}`, undefined, creatorBearer, ENV, {
      jobPattern: OWN_JOB_PATTERN,
    })
    outPatternPat = await createToken(
      `e2e-104-outpattern-${stamp}`,
      undefined,
      creatorBearer,
      ENV,
      { jobPattern: NO_MATCH_PATTERN },
    )
  })

  test.afterAll(async () => {
    // Zero-litter cleanup — runs even when an assertion failed mid-spec. A
    // fresh bearer is fetched because Keycloak access tokens are short-lived.
    const ids = mintedIds()
    try {
      const cleanupBearer = await fetchBearerToken(ENV).catch(() => creatorBearer)
      await Promise.allSettled(ids.map((id) => deleteToken(id, cleanupBearer, ENV)))
      // HARD oracle: every minted token must now be revoked (revoke is a
      // soft-delete by design — the row stays for audit with revoked_at set).
      const remaining = await listTokens(cleanupBearer, ENV)
      for (const id of ids) {
        const row = remaining.find((r) => String(r.id) === id)
        if (row && !row.revokedAt) {
          throw new Error(`cleanup failed: token id=${id} is still active after teardown`)
        }
      }
    } finally {
      try {
        // Zero-litter for the OWN job too (#112). The job never runs a build
        // in this spec (GET-only), so the cascade must fully delete — hard
        // oracle, not best-effort.
        if (ownJobId !== undefined && patCtx) {
          const res = await safeDeleteJobCascade(patCtx, ownJobId)
          if (!res.deleted) {
            throw new Error(
              `cleanup failed: job id=${ownJobId} (${ownJobName}) not deleted ` +
                `(leftover builds: ${res.leftoverBuildIds.join(', ')})`,
            )
          }
        }
      } finally {
        await patCtx?.dispose()
      }
    }
  })

  test('minted PAT authenticates a fresh cookie-free request and attributes to the creating principal', async () => {
    expect(fullPat, 'setup did not mint the unscoped PAT').toBeTruthy()

    // Negative control: the fresh context on its own is unauthenticated —
    // whatever succeeds next is attributable to the PAT alone.
    const anon = await patGet('/api/v1/jobs', null)
    expect(anon.status, 'credential-free context must be rejected').toBe(401)

    // (1) Authorized call through the PAT: a real jobs page containing the
    // job THIS spec created (searched by its unique name — immune to both
    // pagination and any shared seed row coming or going, #112).
    const jobs = await patGet(
      `/api/v1/jobs?search=${encodeURIComponent(ownJobName)}&offset=0&limit=50`,
      fullPat!.token,
    )
    expect(jobs.status, `PAT GET /api/v1/jobs → HTTP ${jobs.status}`).toBe(200)
    const page = parseJsonOrNull<JobsPage>(jobs.raw)
    expect(page && Array.isArray(page.items), 'jobs response is not a page').toBe(true)
    expect(
      page!.items.some((j) => j.id === ownJobId && j.fullName === ownJobName),
      `jobs page under PAT auth must contain the spec-owned job ${ownJobName}`,
    ).toBe(true)

    // (2) Principal attribution: /api/v1/me/tokens is strictly per-subject
    // (no admin override path) — seeing ALL four tokens we just minted with
    // the dev user's bearer proves the PAT resolved to that same subject.
    const mine = await patGet('/api/v1/me/tokens', fullPat!.token)
    expect(mine.status, `PAT GET /api/v1/me/tokens → HTTP ${mine.status}`).toBe(200)
    const rows = parseJsonOrNull<Array<{ id: string; prefix: string; revokedAt?: string | null }>>(
      mine.raw,
    )
    expect(Array.isArray(rows), 'me/tokens response is not a list').toBe(true)
    for (const id of mintedIds()) {
      expect(
        rows!.some((r) => String(r.id) === id),
        `token id=${id} minted by the creating user is missing from the PAT-authenticated list`,
      ).toBe(true)
    }
    const self = rows!.find((r) => String(r.id) === String(fullPat!.id))
    expect(self!.prefix).toBe(fullPat!.prefix)
    expect(self!.revokedAt ?? null, 'token must not be revoked yet').toBeNull()
  })

  test('tampered and wrong-prefix tokens are rejected with 401', async () => {
    expect(fullPat, 'setup did not mint the unscoped PAT').toBeTruthy()

    // Tampered: last char flipped — stored prefix is untouched, so the DAO
    // lookup finds the candidate row and the BCrypt compare must be what
    // rejects it. The PAT mechanism OWNS this 401 (its challenge shape).
    const tampered = await patGet('/api/v1/jobs', tamper(fullPat!.token))
    expect(tampered.status, 'tampered PAT must be rejected').toBe(401)
    const tamperedChallenge = tampered.headers['www-authenticate'] ?? ''
    expect(tamperedChallenge).toContain('realm="titan"')
    expect(tamperedChallenge).toContain('error="invalid_token"')

    // Wrong prefix: not PAT-shaped → the PAT mechanism must NOT claim it
    // (falls through to OIDC, which rejects the opaque bearer). Differential
    // on the challenge proves the dispatch boundary, not just the status.
    const wrongPrefix = await patGet(
      '/api/v1/jobs',
      fullPat!.token.replace(/^titanpat_/, 'titanx__'),
    )
    expect(wrongPrefix.status, 'wrong-prefix token must be rejected').toBe(401)
    expect(wrongPrefix.headers['www-authenticate'] ?? '').not.toContain('error="invalid_token"')
  })

  test('role-scoped PAT (READ_JOB): in-scope 200, out-of-scope 403', async () => {
    expect(scopedPat, 'setup did not mint the READ_JOB-scoped PAT').toBeTruthy()

    const inScope = await patGet('/api/v1/jobs?offset=0&limit=5', scopedPat!.token)
    expect(inScope.status, 'READ_JOB-scoped PAT must list jobs').toBe(200)

    // /api/v1/audit requires READ_AUDIT|ADMIN — outside the token's scope
    // intersection, even though the CREATOR (dev, ADMIN) could read it.
    const outOfScope = await patGet('/api/v1/audit?limit=5', scopedPat!.token)
    expect(outOfScope.status, 'audit read outside the PAT scopes must be denied').toBe(403)
  })

  test('job-pattern PAT: in-pattern job readable, out-of-pattern denied with problem+json and audit row', async () => {
    expect(inPatternPat && outPatternPat, 'setup did not mint the pattern PATs').toBeTruthy()

    // In-pattern (e2e-104-pat-* matches the spec-owned job): full access.
    const allowed = await patGet(`/api/v1/jobs/${ownJobId}`, inPatternPat!.token)
    expect(allowed.status, 'in-pattern PAT must read the job').toBe(200)

    // Out-of-pattern: the structured deny PatJobScopeFilter promises — 403,
    // application/problem+json, stable type URI, pattern echoed in detail.
    const denied = await patGet(`/api/v1/jobs/${ownJobId}`, outPatternPat!.token)
    expect(denied.status, 'out-of-pattern PAT must be denied').toBe(403)
    expect(denied.headers['content-type'] ?? '').toContain('application/problem+json')
    const problem = parseJsonOrNull<ProblemJson>(denied.raw)
    expect(problem?.type, `deny body: ${denied.raw.slice(0, 300)}`).toBe(SCOPE_DENIED_PROBLEM)
    expect(problem?.detail ?? '').toContain(NO_MATCH_PATTERN)

    // Audit oracle: the deny wrote a PAT_SCOPE_DENIED row targeting THIS
    // token, attributed to the creating user (actor). Poll — audit emission
    // is best-effort-synchronous but must not be assumed instantaneous.
    const auditBearer = await fetchBearerToken(ENV)
    await expect
      .poll(
        async () => {
          const r = await bearerGet<AuditPage>('/api/v1/audit?limit=50', auditBearer)
          if (r.status !== 200 || !r.body) return false
          return r.body.items.some(
            (e) =>
              e.action === 'PAT_SCOPE_DENIED' &&
              e.targetType === 'PAT' &&
              e.targetId === String(outPatternPat!.id) &&
              e.actor === ENV.username,
          )
        },
        {
          message:
            `audit row { action: PAT_SCOPE_DENIED, target: PAT/${outPatternPat!.id}, ` +
            `actor: ${ENV.username} } not visible within 15s`,
          timeout: 15_000,
          intervals: [500, 1_000, 2_000],
        },
      )
      .toBe(true)
  })

  test('revoked PAT stops authenticating', async () => {
    expect(fullPat, 'setup did not mint the unscoped PAT').toBeTruthy()

    // Sanity: still valid right before the revoke.
    const before = await patGet('/api/v1/jobs?offset=0&limit=1', fullPat!.token)
    expect(before.status, 'PAT must still authenticate before revocation').toBe(200)

    // Revoke through the API (the same surface the UI uses).
    const revokeBearer = await fetchBearerToken(ENV)
    await deleteToken(String(fullPat!.id), revokeBearer, ENV)

    // The revocation must take effect — poll, never sleep. (The DAO filters
    // revoked_at IS NULL with no cache, so this converges immediately; the
    // poll guards against any future caching layer regressing silently.)
    await expect
      .poll(async () => (await patGet('/api/v1/jobs?offset=0&limit=1', fullPat!.token)).status, {
        message: 'revoked PAT still authenticates after DELETE /api/v1/me/tokens/{id}',
        timeout: 10_000,
        intervals: [250, 500, 1_000],
      })
      .toBe(401)

    // The list endpoint (via bearer) must show the row as revoked metadata.
    const rows = await listTokens(revokeBearer, ENV)
    const row = rows.find((r) => String(r.id) === String(fullPat!.id))
    expect(row, 'revoked token row must remain listed (soft delete)').toBeTruthy()
    expect(row!.revokedAt, 'revokedAt must be stamped').toBeTruthy()
  })
})

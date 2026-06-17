/**
 * 56-golden-path-auth — the AUTH golden path against a REAL rig.
 *
 * Why this spec exists (closes #1200):
 *   Auth on the rig is the single most outage-prone surface in this repo's
 *   history, and — until this spec — the one thing NO test in the loop covered.
 *   Unit/integration tests MOCK OIDC, so a "green" build can ship onto a rig
 *   that nobody can log into. It happened twice in one day: first a missing
 *   Keycloak realm (`titan-dev`), then an OIDC issuer set to a placeholder so
 *   every bearer token was rejected with 401. Both shipped green.
 *
 *   `rig/k3s/smoke-auth.sh` now gates *deploys* (real login → bearer → authed
 *   `/api/v1/builds` → 200), but it is a curl script that runs *after* merge.
 *   This spec is the PR-time equivalent: the browser-level canary that turns a
 *   "deployed" outage into a RED PR. It drives the real PKCE redirect through
 *   the SPA — the exact path a human SRE walks when they log in.
 *
 * Strategy (mirrors spec 21 + rig/k3s/smoke-auth.sh, deliberately):
 *   1. Real browser OIDC: SPA → Keycloak hosted login (`#kc-form-login`) →
 *      submit dev/dev → land back authenticated on the SPA. Uses
 *      `loginViaKeycloak()` — does NOT re-implement the redirect dance.
 *   2. Pull the SPA's OWN bearer out of sessionStorage (single source of truth
 *      for "what token does the SPA hold") and assert an authenticated
 *      GET /api/v1/builds → HTTP 200.
 *   3. Assert the build list actually RENDERS in the UI — a builds list/row,
 *      or the explicit zero-builds empty-state — never a blank page and never
 *      the route's error boundary.
 *
 * Sad paths (falsifiable & loud — no silent green):
 *   - An UNauthenticated GET /api/v1/builds (Authorization header stripped)
 *     MUST return 401/403. Proves the endpoint is genuinely auth-gated, not
 *     wide open. This is the always-runnable negative.
 *   - A bad-realm login negative is provided as a `test.skip`-guarded check
 *     (enable with TITAN_E2E_NEGATIVE_REALM=1): point the realm at a bogus
 *     value and assert login fails loudly with the realm-missing diagnosis.
 *
 * Failure-attachment convention matches spec 21: on any failure we attach a
 * screenshot of the failing state + the API response body/status, so triage is
 * one-shot. Assertion messages are kept aligned with smoke-auth.sh so an SRE
 * sees the SAME diagnosis at PR time and at deploy time.
 */
import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { authEnv, type AuthEnv } from '../../fixtures/auth-v3'
import { loginWithDiagnosis } from '../../fixtures/auth-diagnosis'

// `authEnv()` is resolved LAZILY (memoised), NOT at module top-level. Evaluating
// it during Playwright COLLECTION — before the `beforeEach` skip guard on
// TITAN_RIG_URL — means a throwing/malformed env turns a clean `test.skip` into a
// hard collection error that fails the whole file (and every other spec in the
// run). Calling `env()` only inside test bodies keeps collection green on a
// rigless agent; the guard then skips cleanly. The try/catch is belt-and-braces:
// `authEnv()` only reads `process.env` with defaults today, but a future change
// that makes it throw must still surface as a readable skip, not a collection
// crash.
let _env: AuthEnv | null = null
function env(): AuthEnv {
  if (_env === null) {
    try {
      _env = authEnv()
    } catch (e) {
      throw new Error(
        `authEnv() failed to resolve the rig auth env (TITAN_KEYCLOAK_* / TITAN_*_URL). ` +
          `Set them to run the auth golden path. Underlying: ${String(e)}`,
      )
    }
  }
  return _env
}
// The authed API. Default mirrors spec 21 (the v3 rig serves titan-server on
// :18080; the SPA also proxies /api via nginx on :5180). TITAN_API_URL wins.
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'
// The /builds page size the SPA itself requests. SOURCE OF TRUTH:
//   titan-ui/src/routes/builds/index.tsx → `const PAGE_SIZE = 100`
// (the main `useFilteredBuilds(filterQuery)` call paginates at PAGE_SIZE).
// We default to that exact value so the page/total we read here is what the SPA
// would have read, and expose TITAN_BUILDS_LIMIT as the single knob to re-pin it
// if that constant ever moves. e2e/ is a separate pnpm package from titan-ui/,
// so we can't `import` the constant directly — but a comment-as-contract would
// silently drift when PAGE_SIZE changes. So the parity is now ENFORCED, not
// hoped for: `task check:builds-limit-sync`
// (dev/sprint-loop/check-builds-limit-sync.sh, wired into `task verify` and
// `task ci:verify`) greps PAGE_SIZE out of builds/index.tsx and this default and
// FAILS the PR if they disagree. Change one, the gate makes you change the other.
//   NOTE: `total` (the field step 3e compares) is the FULL row count and is
//   independent of `limit`, so this value only affects how many items a page
//   carries — it can never mask a total disagreement. Pinning it to the SPA's
//   value keeps the canary a faithful mirror of the real SPA request.
const BUILDS_LIMIT = Number(process.env.TITAN_BUILDS_LIMIT ?? '100')
const BUILDS_PATH = `/api/v1/builds?offset=0&limit=${BUILDS_LIMIT}`

interface BuildsPage {
  items: Array<{ id: number; status: string }>
  total: number
}

/**
 * Pull the SPA's live access_token out of sessionStorage. oidc-client-ts stores
 * the authenticated user under `oidc.user:<authority>:<client>`. We read the
 * SPA's OWN token rather than minting a fresh one so the test exercises exactly
 * the bearer the app holds — the single source of truth (matches spec 20/21).
 */
async function extractAccessToken(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    const keys = Object.keys(window.sessionStorage).filter((k) => k.startsWith('oidc.user:'))
    for (const key of keys) {
      try {
        const raw = window.sessionStorage.getItem(key)
        if (!raw) continue
        const parsed = JSON.parse(raw) as { access_token?: string }
        if (parsed.access_token) return parsed.access_token
      } catch {
        // ignore malformed entry, keep scanning
      }
    }
    return null
  })
  if (!token) {
    throw new Error(
      'no oidc.user access_token in sessionStorage post-login — SPA never completed ' +
        'the PKCE token exchange (silent auth failure)',
    )
  }
  return token
}

// NOTE: the realm-missing DIAGNOSIS logic (`keycloakRealmReachable` +
// `loginWithDiagnosis`) lives in `../../fixtures/auth-diagnosis.ts` so it can be
// unit-tested with injected fakes (see `56b-auth-diagnosis.unit.spec.ts`) —
// without it, those branches could only ever run against a live rig and the
// "wrong diagnosis fires silently on a real outage" failure mode was untestable.

async function apiGetBuilds(
  request: APIRequestContext,
  bearer: string | null,
): Promise<{ status: number; raw: string; body: BuildsPage | null }> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (bearer !== null) headers.Authorization = `Bearer ${bearer}`
  const r = await request.get(`${API_BASE}${BUILDS_PATH}`, { headers })
  const raw = await r.text()
  let body: BuildsPage | null = null
  try {
    body = JSON.parse(raw) as BuildsPage
  } catch {
    // non-JSON (e.g. an HTML error page) — leave null, raw carries the truth
  }
  // Return `status` (not r.ok()) deliberately — every caller asserts on an exact
  // code (200 happy, 401/403 negative), so a boolean would only be discarded.
  return { status: r.status(), raw, body }
}

test.describe('v3 golden-path-auth @golden @auth', () => {
  // Graceful skip in rigless environments. `authEnv()` falls back to localhost
  // defaults, so without an explicit rig wired up these specs would attempt a
  // doomed connection (and on a CI agent that has no rig, flood the run with
  // connection failures) instead of skipping cleanly. Gating every test on
  // TITAN_RIG_URL — the same env the harness uses to resolve the rig
  // (playwright.config.ts) — keeps collection green and emits a clear reason.
  test.beforeEach(() => {
    test.skip(
      !process.env.TITAN_RIG_URL,
      'no TITAN_RIG_URL — rig not configured (set it to run the auth golden path)',
    )
  })

  test('browser OIDC login → authed /api/v1/builds → 200 → build list renders', async ({
    page,
    request,
  }) => {
    test.setTimeout(120_000)
    // Resolve the rig env HERE (in the test body, after the beforeEach skip
    // guard) — never at module top-level — so collection can't fail rigless.
    const ENV = env()
    let attached = false
    // `string | null` (not `| undefined`) to match apiGetBuilds' param type and
    // make "null = unauthenticated" the explicit, single null-sentinel.
    let bearer: string | null = null
    let lastApiStatus: number | undefined
    let lastApiRaw = ''

    // One-shot diagnostics bundle on ANY failure: the API status+body we last
    // saw + a full-page screenshot of where the SPA wedged. Triage from the
    // artifact alone — no need to attach to the rig.
    const dumpDiagnostics = async (label: string) => {
      if (attached) return
      attached = true
      try {
        await test.info().attach(`api-builds-${label}.txt`, {
          body: `status=${lastApiStatus ?? 'n/a'}\n\n${lastApiRaw.slice(0, 1000)}`,
          contentType: 'text/plain',
        })
        const png = await page.screenshot({ fullPage: true })
        await test.info().attach(`screenshot-${label}.png`, {
          body: png,
          contentType: 'image/png',
        })
      } catch (e) {
        await test.info().attach('diagnostics-error.txt', {
          body: String(e),
          contentType: 'text/plain',
        })
      }
    }

    try {
      // 1. REAL browser OIDC flow: SPA → Keycloak `#kc-form-login` → dev/dev →
      //    back on the authenticated SPA. If the realm is missing or Keycloak
      //    is unreachable, loginViaKeycloak() times out waiting for the form;
      //    we re-throw with the smoke-auth.sh diagnosis so the cause is named.
      await loginWithDiagnosis(page, ENV)

      // 2. The SPA's OWN bearer (sessionStorage) — single source of truth.
      bearer = await extractAccessToken(page)

      // 2a. Authenticated API call MUST be 200. A 401/403 here is the exact
      //     "OIDC issuer/audience mismatch" outage smoke-auth.sh guards at
      //     deploy time — caught now at PR time.
      const authed = await apiGetBuilds(request, bearer)
      lastApiStatus = authed.status
      lastApiRaw = authed.raw
      expect(
        authed.status,
        `authed /api/v1/builds returned ${authed.status} — OIDC issuer/audience mismatch? ` +
          `(server's expected issuer vs token iss=${ENV.keycloakUrl}/realms/${ENV.realm}). ` +
          `Body: ${authed.raw.slice(0, 300)}`,
      ).toBe(200)
      expect(
        authed.body !== null && Array.isArray(authed.body.items),
        `authed /api/v1/builds returned 200 but body is not a builds page: ${authed.raw.slice(0, 300)}`,
      ).toBe(true)

      // 3. The build list must actually RENDER. Navigate, let skeletons clear,
      //    then assert we reached a real data view — a populated list OR the
      //    explicit zero-builds empty-state — and crucially NOT the route's
      //    error paragraph (rendered as `<p style="color: var(--fail)">` when
      //    the jobs/builds queries reject). Blank page and error boundary are
      //    both failures.
      await page.goto(`${ENV.uiBaseUrl}/builds`, { waitUntil: 'domcontentloaded' })

      // 3a. The page shell rendered (proves we're not on a crashed/blank route).
      await expect(
        page.getByRole('heading', { name: /^builds$/i }),
        'the /builds heading never rendered — SPA crashed or auth guard bounced us',
      ).toBeVisible({ timeout: 15_000 })

      // 3b. Skeletons must clear so we read REAL state, not loading.
      await expect
        .poll(async () => await page.locator('[data-testid="builds-list-loading"]').count(), {
          message: '/builds stuck on loading skeletons — queries never resolved',
          timeout: 15_000,
          intervals: [250, 500, 1_000],
        })
        .toBe(0)

      // Locators for the three TERMINAL states the route can settle into. Rows
      // are selected by their STABLE per-id testid (`builds-row-<id>`, emitted by
      // BuildRow.tsx and asserted by the builds unit suite), NOT by the
      // `.build-row` CSS class: a styling pass that renames the class would make
      // a class selector silently match nothing → the empty-state branch is
      // taken → a real regression ships green. The regex pins the anchor only —
      // `builds-row-<n>` — and excludes the row's sub-element testids
      // (`builds-row-label-…`, `-pill-…`, `-duration-…`) and the rail
      // (`row-rail-…`), so the count is exactly the number of rows. The error
      // state is the framed `[data-builds-error="jobs|builds"] role="alert"`
      // panel (#1187), rendered when useJobs()/useFilteredBuilds() reject; we
      // target the stable `data-builds-error` marker (not the inline `--fail`
      // style) so a cosmetic CSS-class refactor can't silently bypass the guard.
      const buildRows = page.getByTestId(/^builds-row-\d+$/)
      const emptyState = page.locator('[data-testid="builds-empty"]')
      const errorPara = page.locator('[data-builds-error]')

      // 3c. Wait for the route to SETTLE into one terminal state — rows, the
      //     empty-state, OR the error paragraph — before asserting anything about
      //     which one. Polling the union (rather than a point-in-time error-
      //     absence check followed by a separate data poll) closes the race where
      //     an error boundary arrives AFTER the absence check but BEFORE the data
      //     poll resolves: a mid-render frame can't slip an error state past us,
      //     because we don't judge the page until it has reached a terminal state.
      await expect
        .poll(
          async () =>
            (await buildRows.count()) > 0 ||
            (await emptyState.count()) > 0 ||
            (await errorPara.count()) > 0,
          {
            message:
              'build list never resolved: neither a build row, the builds-empty ' +
              'zero-state, nor an error paragraph appeared (blank canvas — stuck mid-render).',
            timeout: 15_000,
            intervals: [250, 500, 1_000],
          },
        )
        .toBe(true)

      // 3d. The route has SETTLED, so the error state is now a reliable read (not
      //     a mid-render frame). It MUST be absent: if the authed query was
      //     rejected (e.g. a 401 the SPA surfaces on an issuer/audience or RBAC
      //     mismatch), the error paragraph is already present and this fails LOUD,
      //     naming exactly which query rejected via the distinct testid.
      const errorCount = await errorPara.count()
      const failingQuery =
        errorCount > 0 ? await errorPara.first().getAttribute('data-builds-error') : ''
      const errorText = errorCount > 0 ? await errorPara.first().innerText() : ''
      expect(
        errorCount,
        `/builds rendered an ERROR state (failing query: ${failingQuery}) instead of data: ` +
          `"${errorText}". The authed query was rejected — issuer/audience or RBAC mismatch.`,
      ).toBe(0)

      // 3e. With the error state ruled out, the settled view is a real DATA view:
      //     EITHER ≥1 build row OR the explicit zero-builds empty-state.
      //     Distinguishing these two from "blank" and "error" is the whole point —
      //     a data view is never a dead canvas.
      const rowCount = await buildRows.count()
      if (rowCount === 0) {
        // Empty rig: the empty-state copy must be the explicit zero-builds line,
        // proving the UI distinguishes "0 builds" from "request failed".
        await expect(emptyState).toBeVisible()
      } else {
        // Populated rig: rows render AND the API agreed there are builds.
        // Use `?? 0` rather than a non-null assertion: the step-2a guard already
        // proved body is a builds page, but `!` would throw a raw TypeError (not
        // a readable test failure) if a future refactor reordered the guards.
        await expect(buildRows.first()).toBeVisible()
        const apiTotal = authed.body?.total ?? 0
        expect(
          apiTotal,
          `UI shows ${rowCount} build row(s) but API total=${apiTotal} — ` +
            `UI/API disagreement (rendered stale or dropped data).`,
        ).toBeGreaterThan(0)
      }
    } catch (err) {
      await dumpDiagnostics('assertion-failure')
      throw err
    }
  })

  test('UNauthenticated GET /api/v1/builds is rejected (401/403) — endpoint is auth-gated', async ({
    request,
  }) => {
    // The always-runnable adversarial negative: strip the Authorization header
    // entirely. If the endpoint answers 200, the API is wide open and the whole
    // auth golden path above is meaningless theatre — fail LOUD. We accept
    // 401 (no creds) or 403 (creds present but insufficient) as "auth-gated".
    const r = await apiGetBuilds(request, null)
    try {
      expect(
        [401, 403].includes(r.status),
        `/api/v1/builds answered ${r.status} to an UNauthenticated request — the endpoint ` +
          `is NOT auth-gated (expected 401/403). A regression here means the API is open ` +
          `to the world. Body: ${r.raw.slice(0, 200)}`,
      ).toBe(true)
    } catch (err) {
      // Same one-shot-triage convention as the happy path (spec 21): attach the
      // actual status + body so the artifact shows WHY it failed (a 200 open
      // endpoint vs a 302 redirect vs a 500) without anyone re-running the test.
      await test.info().attach('api-builds-unauth.txt', {
        body: `status=${r.status}\n\n${r.raw.slice(0, 500)}`,
        contentType: 'text/plain',
      })
      throw err
    }
  })

  // Bad-realm negative — proves login fails LOUD when the realm is missing
  // (the exact outage #1200 cites). Skipped by default because it deliberately
  // breaks auth config; enable with TITAN_E2E_NEGATIVE_REALM=1 for a manual /
  // CI-isolated run. Documented as the (a) option from the ticket's test matrix.
  //
  // SCHEDULE (so the flag isn't de-facto permanently off): `task e2e:auth-negative`
  // sets TITAN_E2E_NEGATIVE_REALM=1 and runs ONLY this test. Wire that target into
  // a nightly / rig-health profile so this sad-path branch is actually exercised
  // on a cadence rather than perpetually skipped (Taskfile.yml → e2e:auth-negative).
  test('bad realm → login fails with realm-missing diagnosis (negative, opt-in)', async ({
    page,
  }) => {
    test.skip(
      process.env.TITAN_E2E_NEGATIVE_REALM !== '1',
      'opt-in negative — set TITAN_E2E_NEGATIVE_REALM=1 (or run `task e2e:auth-negative`) to run; it deliberately points at a missing realm',
    )
    // Match the happy-path budget: loginViaKeycloak's waitForSelector on
    // `#kc-form-login` (which never appears on a 404-ing realm) can eat most of
    // Playwright's 30s default on a slow rig, flaking the timeout instead of
    // surfacing the realm-missing diagnosis. 60s gives the form-wait room to
    // resolve to the real failure.
    test.setTimeout(60_000)
    // Resolve the rig env in the test body (post-skip-guard), never at module top.
    const ENV = env()
    const bogusRealm = `${ENV.realm}-does-not-exist-${'x'.repeat(4)}`
    // IMPORTANT: we drive Keycloak's authorize endpoint DIRECTLY with the bogus
    // realm — we do NOT route through loginViaKeycloak()/loginWithDiagnosis().
    // That helper navigates the SPA, which derives its realm from the server's
    // runtime ui-config (it never reads env.realm), so passing a bogus realm
    // through it would silently log in against the REAL realm and prove nothing.
    // Hitting `/realms/<bogus>/protocol/openid-connect/auth` puts the missing
    // realm genuinely on the wire: Keycloak returns its "Page not found" error
    // page, so `#kc-form-login` never appears — the exact realm-missing outage
    // #1200 cites. We then assert the loud, smoke-auth.sh-aligned diagnosis.
    const authorizeUrl =
      `${ENV.keycloakUrl}/realms/${bogusRealm}/protocol/openid-connect/auth` +
      `?client_id=${encodeURIComponent(ENV.clientId)}&response_type=code&scope=openid` +
      `&redirect_uri=${encodeURIComponent(`${ENV.uiBaseUrl}/login/callback`)}`

    // Assert on Keycloak's REAL response — never on a test-manufactured wrapper
    // string. Re-wrapping the caught exception and regex-matching our OWN message
    // would be tautological: any failure (a network blip, a DNS error, a
    // Playwright crash) would satisfy a pattern we ourselves wrote, so it proves
    // nothing about the realm. Instead: Keycloak IS reachable (the navigation
    // resolves) but the realm is missing, so it serves its 404 error page rather
    // than the login form. We assert on THAT — a 4xx status and/or an error-page
    // body that names the failure, plus the ABSENCE of #kc-form-login. A genuine
    // network outage would reject `goto` and fail the test DIFFERENTLY, which is
    // exactly the point: this distinguishes realm-missing from an unrelated
    // failure, the gap the bare `threw === true` / self-wrapped regex left open.
    const resp = await page.goto(authorizeUrl, { waitUntil: 'domcontentloaded' })
    expect(
      resp,
      `navigation to the authorize endpoint returned no response — Keycloak at ` +
        `${ENV.keycloakUrl} is unreachable (realm ${ENV.realm} missing or Keycloak unreachable).`,
    ).not.toBeNull()
    const status = resp?.status() ?? 0
    const bodyText = await page.evaluate(() => document.body?.innerText ?? '')

    // The login form must be ABSENT — a missing realm has no hosted login page.
    expect(
      await page.locator('#kc-form-login').count(),
      `bogus realm "${bogusRealm}" STILL served the #kc-form-login login form — Keycloak is ` +
        `NOT validating the realm, so a missing-realm outage would ship green.`,
    ).toBe(0)

    // Keycloak's genuine realm-missing signal, read off the REAL response. The
    // PRIMARY guard is `status === 404`: on Keycloak a missing realm at
    // `/realms/<bogus>/…/auth` is ALWAYS a 404 ("Realm does not exist"). We
    // pin 404 exactly (not the looser `status >= 400`) because a PKCE-strict
    // Keycloak answers the REAL realm with a 400 when code_challenge params are
    // absent — so `>= 400` could not distinguish "realm missing (404)" from
    // "realm exists but PKCE params malformed (400)". The realm-not-found body
    // copy is a belt-and-suspenders fallback for Keycloak builds that render the
    // error page on a non-404 status. No bare `|error` arm — almost every HTML
    // page contains the word "error" (ARIA labels, validation copy, meta), so it
    // would be vacuously true and mask a genuinely wrong (e.g. 200 + login form)
    // response.
    const realmMissingSignal =
      status === 404 || /realm.*not\s*found|page not found|we\W*are\W*sorry/i.test(bodyText)
    expect(
      realmMissingSignal,
      `bogus realm "${bogusRealm}": expected Keycloak's realm-missing error — a 404 status ` +
        `(realm not found) or a "realm not found"/"page not found" error page — so an SRE sees the ` +
        `same diagnosis at PR time and deploy time (realm ${ENV.realm} missing or Keycloak ` +
        `unreachable). Got status=${status}, body="${bodyText.slice(0, 200)}".`,
    ).toBe(true)
  })
})

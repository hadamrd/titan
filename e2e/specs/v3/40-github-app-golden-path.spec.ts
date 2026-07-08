/**
 * 40-github-app-golden-path — EPIC #831 Child G (#838).
 *
 * The end-to-end @golden spec for the V1 GitHub App: manifest exchange →
 * installation sync → repo + pipeline discovery → real-commit push →
 * triggered build → SUCCESS → GitHub commit status round-trips back.
 *
 * Honest reconnaissance, recorded here so the spec doesn't lie about scope:
 *
 *  - PRODUCTION POSTS COMMIT STATUSES, not check-runs. See
 *    `GithubStatusReporter.report(...)` with CONTEXT="ci/titan". This spec
 *    polls `/commits/{sha}/statuses` (the older API) and asserts
 *    `state=success` + `context=ci/titan`. The check-runs API is NOT
 *    asserted here (50-github-pr-roundtrip surfaces that gap as follow-up).
 *  - Builds-by-SHA: `BuildDao.findAll` ILIKE-searches
 *    `trigger_meta_json->>'commitSha'` when `search` is given —
 *    `/api/v1/builds?search=<sha>` is the contract.
 *  - The "via GitHub App" badge on the build detail page is asserted as a
 *    `[data-testid="build-trigger-github-app"]` element with an `href` to
 *    the installation page (Child E — #836). Absent? `test.fixme` referencing
 *    #836, never a hard fail (production-code changes are out of scope for
 *    this ticket).
 *
 * Pre-conditions (env vars) — this is a LAYER-2 (real-commit) spec (#50):
 *   - LAYER2_RIG_AVAILABLE=1 — REQUIRED. The GitHub App prerequisites (a
 *     registered App row, an installation on the fixture org, and a webhook
 *     tunnel delivering to the rig) exist only on the Layer-2 / public rig
 *     (`task rig:smoke:real`, docs/ops github-app-dev runbook). Unset →
 *     every test here skips — the local `task dev:titan` rig can never
 *     satisfy them.
 *   - TITAN_RIG_URL — the Layer-2 rig base URL (fixtures/github-app.ts
 *     defaults to the local rig otherwise).
 *   - gh CLI must be authenticated (`gh auth status` exit 0).
 *   - TITAN_E2E_GH_APP_MANIFEST_CODE — temp code from Child F's GitHub-App
 *     creation flow. Absent → the manifest step is `test.skip`'d (a freshly
 *     registered App is GET-able via /api/v1/github-app and we'll use that).
 *   - TITAN_E2E_GH_INSTALLATION_ID — known installation id on the fixture
 *     org. Absent → sync step uses the first installation listed.
 *   - TITAN_E2E_FIXTURE_REPO — overrideable, defaults `hadamrd/titan-e2e-fixture`.
 *
 * Tagging (#50): @real-commit ONLY — deliberately NOT @golden. The local
 * @golden set (dev/rig-smoke/run-golden.sh, --grep @golden) must be green-able
 * on `task dev:titan` by definition; this spec inherently needs the Layer-2
 * rig, so it runs via `task rig:smoke:real` (--grep @real-commit) instead.
 *
 * @tag @real-commit
 */
import { test, expect } from '@playwright/test'
import {
  GithubAppFixture,
  FIXTURE_REPO,
  RIG_BASE_URL,
  cutFixtureBranch,
  deleteRemoteBranch,
  remoteBranchExists,
  listCommitStatuses,
  ghAuthOk,
  rigReachable,
  type GithubInstallationDto,
} from '../../fixtures/github-app'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { randomUUID } from 'node:crypto'

const ENV = authEnv()
const MANIFEST_CODE = process.env.TITAN_E2E_GH_APP_MANIFEST_CODE ?? ''
const INSTALL_ID_ENV = process.env.TITAN_E2E_GH_INSTALLATION_ID ?? ''
// #824 — build status reaching SUCCESS on the rig from a real-commit trigger
// depends on the durable-timer + queue fix. If unset, we still assert the
// build *appears*, then `test.fixme` the SUCCESS leg.
const ASSERT_BUILD_SUCCESS = process.env.TITAN_E2E_ASSERT_BUILD_SUCCESS === '1'

// --- skip predicates (deterministic — never test.skip inside a step) -------

async function shouldSkipReason(): Promise<string | null> {
  // Same posture guard as 53-archive-artifacts-real-commit: the GitHub App
  // env (registered App + installation on the fixture org + webhook tunnel)
  // exists only on the Layer-2 rig. Local `task dev:titan` rigs must skip,
  // never fail (#50).
  if (!process.env.LAYER2_RIG_AVAILABLE)
    return (
      'LAYER2_RIG_AVAILABLE unset; local-dev posture — GitHub App env ' +
      '(registered App + installation + webhook tunnel) exists only on the ' +
      'Layer-2 rig (task rig:smoke:real)'
    )
  if (!ghAuthOk()) return 'gh CLI not authenticated; cannot drive GitHub side'
  if (!(await rigReachable())) return `Titan rig not reachable at ${RIG_BASE_URL}`
  return null
}

// --- the spec --------------------------------------------------------------

// @real-commit only — NOT @golden. Layer-2 spec; see the header tagging note.
test.describe('@real-commit v3 github-app-golden-path', () => {
  test.describe.configure({ mode: 'serial' })

  test('manifest -> sync -> push -> build -> GitHub status -> UI badge', async ({
    page,
  }) => {
    test.setTimeout(15 * 60_000)

    const reason = await shouldSkipReason()
    if (reason) test.skip(true, reason)

    const fx = new GithubAppFixture(ENV)

    // Sanity: bearer (skip if Keycloak unreachable from this env — same
    // discipline as 50-github-pr-roundtrip.spec.ts).
    let bearer: string | null = null
    try {
      bearer = await fx.getBearer()
    } catch (e) {
      test.skip(
        true,
        `cannot fetch Keycloak bearer (${(e as Error).message}); rig may be remote`,
      )
    }
    expect(bearer).toBeTruthy()

    // ── 1. Manifest exchange (Child A — #832) ───────────────────────────
    // If a code is supplied, exchange it. Otherwise verify the App row
    // already exists (idempotent — the App is a tenant singleton).
    if (MANIFEST_CODE) {
      const res = await fx.manifestCallback(MANIFEST_CODE)
      expect(
        res.ok,
        `manifest-callback failed: ${res.status} ${await res.text()}`,
      ).toBe(true)
    }
    const app = await fx.getApp()
    expect(
      app,
      'GET /api/v1/github-app returned null — no App registered. ' +
        'Set TITAN_E2E_GH_APP_MANIFEST_CODE or pre-register the App (#832/#837).',
    ).not.toBeNull()
    expect(app!.slug).toBeTruthy()
    expect(app!.htmlUrl).toMatch(/^https:\/\/github\.com\/apps\//)

    // ── 2. List + sync installation (Child C — #834) ────────────────────
    const installs = await fx.listInstallations()
    expect(
      installs.length,
      'no installations returned — install the Titan App on the fixture org first (#836).',
    ).toBeGreaterThan(0)

    const first = installs[0]!
    const inst: GithubInstallationDto = INSTALL_ID_ENV
      ? installs.find((i) => String(i.id) === INSTALL_ID_ENV) ?? first
      : first

    const syncRes = await fx.syncInstallation(inst.id)
    expect(
      syncRes.ok,
      `installation sync failed: ${syncRes.status} ${await syncRes.text()}`,
    ).toBe(true)

    // The sync response is the enriched installation; assert ≥1 repo + ≥1 pipeline.
    const enriched = (await syncRes.json()) as GithubInstallationDto
    expect(enriched.repos?.length ?? 0).toBeGreaterThan(0)
    const pipelineCount =
      enriched.repos?.reduce((acc, r) => acc + r.pipelines.length, 0) ?? 0
    expect(
      pipelineCount,
      `no pipelines discovered under installation ${inst.id}. ` +
        `Fixture repo must contain .titan/pipelines/*.yml (#834).`,
    ).toBeGreaterThan(0)

    // ── 3. Cut a unique branch + push a sentinel commit ─────────────────
    const uuid = randomUUID()
    const checkout = cutFixtureBranch(uuid)

    try {
      // ── 4. Poll for triggered build (≤120s, 2s interval per spec) ────
      const build = await fx.findBuildBySha(checkout.sha, {
        timeoutMs: 120_000,
        intervalMs: 2_000,
      })
      expect(
        build,
        `no build appeared for sha=${checkout.sha} within 120s. ` +
          `Likely GitHub App webhook not delivering, or trigger_meta_json.commitSha ` +
          `not populated by the webhook handler (BuildDao searches that key).`,
      ).not.toBeNull()

      // ── 5. Wait for terminal SUCCESS (gated on #824) ────────────────
      if (!ASSERT_BUILD_SUCCESS) {
        test.fixme(
          true,
          'build SUCCESS assertion gated on TITAN_E2E_ASSERT_BUILD_SUCCESS=1 ' +
            '(blocks on #824 — durable timer + queue fix).',
        )
        return
      }
      const terminal = await fx.waitForTerminal(build!.id, { timeoutMs: 8 * 60_000 })
      expect(terminal, `build ${build!.id} did not reach terminal state`).not.toBeNull()
      expect(
        terminal!.status,
        `build terminal status was ${terminal!.status}, expected SUCCESS`,
      ).toBe('SUCCESS')

      // ── 6. GitHub commit status round-trip ──────────────────────────
      // Poll up to 90s for the status reporter to fire post-build.
      const statusDeadline = Date.now() + 90_000
      let titan = listCommitStatuses(checkout.sha).find(
        (s) => s.context === 'ci/titan',
      )
      while (!titan || titan.state === 'pending') {
        if (Date.now() > statusDeadline) break
        await new Promise((r) => setTimeout(r, 3_000))
        titan = listCommitStatuses(checkout.sha).find((s) => s.context === 'ci/titan')
      }
      expect(
        titan,
        `no ci/titan commit status posted for sha=${checkout.sha} within 90s of ` +
          `terminal build state. GithubStatusReporter not firing?`,
      ).toBeTruthy()
      expect(titan!.state).toBe('success')
      expect(titan!.context).toBe('ci/titan')

      // ── 7. UI: "via GitHub App" badge on build detail ───────────────
      await loginViaKeycloak(page, ENV)
      await page.goto(`${ENV.uiBaseUrl}/builds/${build!.id}`)
      const badge = page.locator('[data-testid="build-trigger-github-app"]')
      if ((await badge.count()) === 0) {
        // Surfaces #836's UI debt without failing the test-only ticket.
        test.fixme(
          true,
          'build-trigger-github-app badge not present on /builds/:id — follow-up #836.',
        )
        return
      }
      await expect(badge).toBeVisible()
      const href = await badge.getAttribute('href')
      expect(
        href,
        'badge has no href to installation page',
      ).toMatch(/\/installations\//)
    } finally {
      // ── 8. Cleanup (always) — assert remote branch is gone. ─────────
      deleteRemoteBranch(checkout.branch)
      checkout.cleanup()
      // Verify the branch is actually gone — cleanup is itself a test
      // (acceptance criterion line: "Verify via a follow-up git ls-remote").
      const stillThere = remoteBranchExists(checkout.branch)
      expect(
        stillThere,
        `cleanup failed: branch ${checkout.branch} still exists on ${FIXTURE_REPO}`,
      ).toBe(false)
    }
  })

  // ── adversarial: invalid HMAC ────────────────────────────────────────
  test('@adversarial webhook with invalid HMAC → no build row appears', async () => {
    test.setTimeout(60_000)
    const reason = await shouldSkipReason()
    if (reason) test.skip(true, reason)

    const fx = new GithubAppFixture(ENV)
    try {
      await fx.getBearer()
    } catch {
      test.skip(true, 'Keycloak unreachable')
    }

    // Synthesize a push event with a unique never-seen SHA — so we can
    // assert no build appears even after waiting.
    const fakeSha = 'f'.repeat(40).slice(0, 40) // 40 hex chars, all 'f'
    const eventBody = {
      ref: 'refs/heads/spec-40-bad-hmac',
      after: fakeSha,
      repository: { full_name: FIXTURE_REPO },
      installation: { id: 1 },
    }

    const res = await fx.postWebhookWithBadHmac(eventBody)
    // Bad HMAC must be rejected — accept any 4xx (401 is canonical;
    // implementation may surface 400 if header is missing entirely).
    expect(
      res.status,
      `webhook with bad HMAC must be rejected, got ${res.status}`,
    ).toBeGreaterThanOrEqual(400)
    expect(res.status).toBeLessThan(500)

    // Wait 30s and confirm no build row exists for that SHA.
    await new Promise((r) => setTimeout(r, 30_000))
    const build = await fx.findBuildBySha(fakeSha, {
      timeoutMs: 1_000,
      intervalMs: 500,
    })
    expect(
      build,
      `bad-HMAC webhook produced a build row for sha=${fakeSha} — security regression!`,
    ).toBeNull()
  })

  // ── adversarial: expired/revoked installation token ───────────────────
  // Hard to simulate without revoking a real install, so we drive the
  // "installation does not exist" path which exercises the same error
  // mapping in the controller (must return a structured 4xx, no half-write).
  test('@adversarial sync on unknown installation → structured 4xx, no half-write', async () => {
    test.setTimeout(60_000)
    const reason = await shouldSkipReason()
    if (reason) test.skip(true, reason)

    const fx = new GithubAppFixture(ENV)
    try {
      await fx.getBearer()
    } catch {
      test.skip(true, 'Keycloak unreachable')
    }

    const unknownInstallId = 999_999_999
    const res = await fx.syncInstallation(unknownInstallId)
    expect(
      res.status,
      `sync on unknown install ${unknownInstallId} must be 4xx, got ${res.status}`,
    ).toBeGreaterThanOrEqual(400)
    expect(res.status).toBeLessThan(500)

    // Body should be structured (JSON), not an HTML stacktrace.
    const ct = res.headers.get('content-type') ?? ''
    expect(
      ct,
      `expected JSON error body, got content-type=${ct}`,
    ).toMatch(/application\/(problem\+)?json/)

    // Confirm no row was written: installation list count is unchanged-ish
    // (we accept ≥0; the strict assertion is the absence of an install with
    // our bogus id).
    const installs = await fx.listInstallations()
    expect(
      installs.find((i) => i.id === unknownInstallId),
      `half-write detected: bogus install id ${unknownInstallId} now appears in list`,
    ).toBeUndefined()
  })
})

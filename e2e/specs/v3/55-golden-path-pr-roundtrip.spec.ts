/**
 * 55-golden-path-pr-roundtrip — seam guard for the wedge sentence (#1133).
 *
 * The "wedge sentence" for Titan is:
 *
 *   PR webhook → build dispatched → log stream visible in UI → status check
 *   posted back to GitHub against the PR HEAD SHA (not the base SHA).
 *
 * Today that path is covered piecemeal by 40-github-app-golden-path,
 * 40-github-app-ui and 50-github-pr-roundtrip — each asserts its own slice.
 * A regression at the seam (the canonical example: a BuildDispatcher refactor
 * that drops `pull_request.head.sha` from the check payload) would slip
 * through every existing spec because they each only see their own slice.
 *
 * This spec is the single seam-level guard: it drives the entire round-trip
 * end-to-end in one wall-clock window and asserts the cross-cutting invariants
 * that no single layer test can.
 *
 * ───────────────────────────────────────────────────────────────────────────
 * What this spec does (happy + adversarial in one file):
 *
 *   HAPPY (`pr-roundtrip-pass`):
 *     1. POST a synthetic `pull_request` (action=opened) webhook to
 *        /api/v1/github-app/events, signed with the configured webhook
 *        secret (env: TITAN_E2E_GH_APP_WEBHOOK_SECRET).
 *     2. Poll /api/v1/builds?search=<head-sha> until a build is dispatched
 *        within 15s (per AC).
 *     3. Open the build-detail UI and observe an SSE log delta — the line
 *        count must GROW inside a 2s window (not just render once).
 *     4. Poll the live GitHub commit-statuses API (gh CLI) for a `success`
 *        check against the PR HEAD SHA (NOT the base SHA — the regression
 *        guard). target_url must resolve (HTTP 200) and point at the
 *        build-detail UI.
 *
 *   SAD (`pr-roundtrip-fail`):
 *     1. Same shape, but driving a pipeline whose middle step does `exit 1`.
 *     2. Assert the GitHub check goes `failure` against the PR head SHA.
 *     3. Assert the check's `description` (or, if check-runs are wired,
 *        `output.summary`) NAMES the failed stage ("test").
 *     4. Assert the build-detail UI surfaces the same failure reason in an
 *        operator-visible location.
 *
 * ───────────────────────────────────────────────────────────────────────────
 * Hard skip predicates (deterministic — never test.skip mid-step):
 *
 *   - `gh auth status` fails                 → skip
 *   - rig /api/v1 not reachable              → skip
 *   - Keycloak bearer unfetchable            → skip (remote rig w/o passthrough)
 *   - TITAN_E2E_GH_APP_WEBHOOK_SECRET unset  → skip (synthetic webhook needs
 *     the rig's webhook secret to sign with; we don't read it from the rig DB)
 *   - TITAN_E2E_GH_INSTALLATION_ID and       → skip (the synthetic payload
 *     TITAN_E2E_GH_REPO_ID unset                must reference a real linked
 *                                                installation + repo so the
 *                                                webhook handler finds a job)
 *
 * Total wall-clock budget: ≤ 90s per variant on the local rig (AC line).
 *
 * @tag @golden @seam
 */
import { test, expect, type Page } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { createHmac, randomUUID } from 'node:crypto'
import { authEnv, loginViaKeycloak, fetchBearerToken } from '../../fixtures/auth-v3'
import {
  RIG_BASE_URL,
  FIXTURE_REPO,
  GithubAppFixture,
  ghAuthOk,
  rigReachable,
} from '../../fixtures/github-app'

const ENV = authEnv()
const WEBHOOK_SECRET = process.env.TITAN_E2E_GH_APP_WEBHOOK_SECRET ?? ''
const INSTALL_ID_ENV = process.env.TITAN_E2E_GH_INSTALLATION_ID ?? ''
const REPO_ID_ENV = process.env.TITAN_E2E_GH_REPO_ID ?? ''
const FIXTURE_REPO_OVERRIDE =
  process.env.TITAN_E2E_FIXTURE_REPO ?? FIXTURE_REPO

const BUILD_DISPATCH_DEADLINE_MS = 15_000 // AC: build dispatched within 15s
const BUILD_TERMINAL_DEADLINE_MS = 60_000 // 90s total budget − dispatch − reporter
const CHECK_DEADLINE_MS = 20_000

interface CommitStatus {
  context: string
  state: 'pending' | 'success' | 'failure' | 'error'
  description?: string
  target_url?: string
}

interface SkipCtx {
  reason: string | null
}

// ── skip predicates ────────────────────────────────────────────────────────

async function preflight(): Promise<SkipCtx> {
  if (!ghAuthOk()) return { reason: 'gh CLI not authenticated' }
  if (!(await rigReachable())) {
    return { reason: `rig not reachable at ${RIG_BASE_URL}` }
  }
  if (!WEBHOOK_SECRET) {
    return {
      reason:
        'TITAN_E2E_GH_APP_WEBHOOK_SECRET unset — synthetic webhook signing ' +
        'requires the rig App webhook secret. Set it in the e2e env to enable ' +
        'this seam guard. See docs/ops/runbooks/ for how to retrieve it from ' +
        'the rig.',
    }
  }
  if (!INSTALL_ID_ENV || !REPO_ID_ENV) {
    return {
      reason:
        'TITAN_E2E_GH_INSTALLATION_ID / TITAN_E2E_GH_REPO_ID unset — ' +
        'synthetic webhook needs a real linked installation + repo so the ' +
        'rig finds a job. Run the 40-github-app-golden-path spec first to ' +
        'provision the linkage, then export the ids.',
    }
  }
  try {
    await fetchBearerToken(ENV)
  } catch (e) {
    return { reason: `Keycloak bearer unfetchable: ${(e as Error).message}` }
  }
  return { reason: null }
}

// ── synthetic PR webhook ───────────────────────────────────────────────────

interface PrWebhookSpec {
  installationId: number
  repoId: number
  repoFullName: string
  branch: string
  baseSha: string
  headSha: string
  prNumber: number
}

function signBody(secret: string, raw: string): string {
  return 'sha256=' + createHmac('sha256', secret).update(raw).digest('hex')
}

function buildPullRequestPayload(s: PrWebhookSpec): object {
  return {
    action: 'opened',
    number: s.prNumber,
    pull_request: {
      number: s.prNumber,
      state: 'open',
      title: `spec-55 synthetic PR ${s.headSha.slice(0, 7)}`,
      head: { ref: s.branch, sha: s.headSha },
      base: { ref: 'main', sha: s.baseSha },
    },
    repository: {
      id: s.repoId,
      full_name: s.repoFullName,
      default_branch: 'main',
    },
    installation: { id: s.installationId },
    sender: { login: 'spec-55-bot', type: 'Bot' },
  }
}

async function postSyntheticPrWebhook(s: PrWebhookSpec): Promise<Response> {
  const raw = JSON.stringify(buildPullRequestPayload(s))
  const sig = signBody(WEBHOOK_SECRET, raw)
  return fetch(`${RIG_BASE_URL}/api/v1/github-app/events`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-GitHub-Event': 'pull_request',
      'X-GitHub-Delivery': `spec-55-${randomUUID()}`,
      'X-Hub-Signature-256': sig,
    },
    body: raw,
  })
}

// ── GitHub status probe (real GitHub via gh CLI) ──────────────────────────

function listCommitStatuses(repo: string, sha: string): CommitStatus[] {
  try {
    const out = execFileSync(
      'gh',
      ['api', `/repos/${repo}/commits/${sha}/statuses`],
      { encoding: 'utf8' },
    )
    return JSON.parse(out) as CommitStatus[]
  } catch {
    return []
  }
}

/**
 * Wait for a Titan-context status to appear on the PR head SHA with the
 * requested terminal state. Returns the status row if seen, else null.
 */
async function waitForTitanStatus(
  repo: string,
  headSha: string,
  expectedState: 'success' | 'failure',
  timeoutMs: number,
): Promise<CommitStatus | null> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const all = listCommitStatuses(repo, headSha)
    const titan = all.find((s) => /titan/i.test(s.context))
    if (titan && titan.state === expectedState) return titan
    if (titan && titan.state !== 'pending' && titan.state !== expectedState) {
      // Surfaced wrong terminal state — let the caller assert + fail loudly.
      return titan
    }
    await new Promise((r) => setTimeout(r, 2_000))
  }
  return null
}

// ── SSE log delta observation ─────────────────────────────────────────────

/**
 * Open /builds/:id in the UI and observe the log panel for a DELTA — the line
 * count must strictly grow inside `windowMs`. Returns {before, after} counts.
 * Throws if the log panel can't be located within `initialMs`.
 */
async function observeLogDelta(
  page: Page,
  buildId: number,
  windowMs: number = 2_000,
  initialMs: number = 10_000,
): Promise<{ before: number; after: number }> {
  await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)
  // The build-detail page renders an SSE-backed log panel. Selectors below
  // accept either the data-testid form (preferred) or a role=log fallback so
  // we don't pin to a churn-y class name.
  const panel = page
    .locator(
      '[data-testid="build-log-panel"], [data-testid="build-log-stream"], [role="log"]',
    )
    .first()
  await panel.waitFor({ state: 'visible', timeout: initialMs })

  const countLines = async (): Promise<number> => {
    // The panel renders one DOM node per line; we accept several flavors.
    const lines = panel.locator(
      '[data-testid^="log-line"], .log-line, [role="listitem"], li',
    )
    return lines.count()
  }

  const before = await countLines()
  await page.waitForTimeout(windowMs)
  const after = await countLines()
  return { before, after }
}

// ── shared fixture state cleaner ──────────────────────────────────────────

function syntheticSha(seed: string): string {
  // Deterministic-per-run 40 hex chars; uses crypto so it's well-distributed
  // and unique across spec runs.
  return createHmac('sha256', 'spec-55').update(seed).digest('hex').slice(0, 40)
}

// ── the spec ───────────────────────────────────────────────────────────────

test.describe('@golden @seam v3 55-golden-path-pr-roundtrip', () => {
  // 90s per variant per AC. Playwright's default 30s would kill us mid-poll.
  test.describe.configure({ mode: 'serial' })

  test('happy: PR webhook → build → SSE log delta → green check on PR head SHA', async ({
    page,
  }) => {
    test.setTimeout(120_000)

    const pre = await preflight()
    if (pre.reason) test.skip(true, pre.reason)

    const fx = new GithubAppFixture(ENV)
    const bearer = await fx.getBearer()
    expect(bearer).toBeTruthy()

    const runId = randomUUID()
    const headSha = syntheticSha(`${runId}-head`)
    const baseSha = syntheticSha(`${runId}-base`)
    const prSpec: PrWebhookSpec = {
      installationId: Number(INSTALL_ID_ENV),
      repoId: Number(REPO_ID_ENV),
      repoFullName: FIXTURE_REPO_OVERRIDE,
      branch: `spec-55/pass-${runId.slice(0, 8)}`,
      baseSha,
      headSha,
      prNumber: 55_001,
    }

    // ── 1. Post the signed synthetic webhook ────────────────────────────
    const wh = await postSyntheticPrWebhook(prSpec)
    expect(
      wh.status,
      `webhook intake refused signed pull_request: ${wh.status} ${await wh.text()}`,
    ).toBe(202)

    // ── 2. Build dispatched within 15s for the PR HEAD SHA ──────────────
    const dispatchStart = Date.now()
    const build = await fx.findBuildBySha(headSha, {
      timeoutMs: BUILD_DISPATCH_DEADLINE_MS,
      intervalMs: 1_000,
    })
    expect(
      build,
      `no build dispatched within ${BUILD_DISPATCH_DEADLINE_MS}ms for ` +
        `PR head sha=${headSha}. Likely seam regression: webhook handler ` +
        `accepted the payload but the dispatcher didn't enqueue.`,
    ).not.toBeNull()
    // eslint-disable-next-line no-console
    console.log(
      `[spec-55] dispatched build id=${build!.id} in ${Date.now() - dispatchStart}ms`,
    )

    // ── 3. UI: SSE log delta (proves stream, not static render) ─────────
    await loginViaKeycloak(page, ENV)
    const delta = await observeLogDelta(page, build!.id, 2_000)
    expect(
      delta.after,
      `SSE log line count did not grow inside the 2s observation ` +
        `window (before=${delta.before} after=${delta.after}). Either the ` +
        `stream stalled or the panel is a static one-shot render.`,
    ).toBeGreaterThan(delta.before)

    // ── 4. Status check POSTed against PR HEAD SHA — not base SHA ───────
    // Adversarial guard against the BuildDispatcher refactor case in the
    // ticket body: the regression posts the check against base, leaving
    // head with NO check. We assert (a) head HAS a success status, and
    // (b) base has NO Titan status whatsoever.
    const headStatus = await waitForTitanStatus(
      FIXTURE_REPO_OVERRIDE,
      headSha,
      'success',
      BUILD_TERMINAL_DEADLINE_MS + CHECK_DEADLINE_MS,
    )
    expect(
      headStatus,
      `no Titan status reached terminal SUCCESS on PR head sha=${headSha} ` +
        `within ${BUILD_TERMINAL_DEADLINE_MS + CHECK_DEADLINE_MS}ms`,
    ).not.toBeNull()
    expect(headStatus!.state).toBe('success')

    const baseHasTitanStatus = listCommitStatuses(
      FIXTURE_REPO_OVERRIDE,
      baseSha,
    ).some((s) => /titan/i.test(s.context))
    expect(
      baseHasTitanStatus,
      `regression detected: Titan posted a status against the PR BASE sha ` +
        `(${baseSha}) — must only post against HEAD (${headSha}).`,
    ).toBe(false)

    // ── 5. target_url resolves + lands on build-detail ──────────────────
    expect(headStatus!.target_url, 'check missing target_url').toBeTruthy()
    const targetRes = await fetch(headStatus!.target_url!, {
      headers: { Authorization: `Bearer ${bearer}` },
      redirect: 'follow',
    })
    expect(
      targetRes.ok,
      `target_url did not resolve: ${targetRes.status} ${headStatus!.target_url}`,
    ).toBe(true)
    expect(
      headStatus!.target_url!,
      `target_url ${headStatus!.target_url} does not point at the build-detail ` +
        `route for build ${build!.id}`,
    ).toContain(`/builds/${build!.id}`)
  })

  test('adversarial: failing step → red check named on PR head SHA + UI surfaces reason', async ({
    page,
  }) => {
    test.setTimeout(120_000)

    const pre = await preflight()
    if (pre.reason) test.skip(true, pre.reason)

    const fx = new GithubAppFixture(ENV)
    await fx.getBearer()

    const runId = randomUUID()
    const headSha = syntheticSha(`${runId}-fail-head`)
    const baseSha = syntheticSha(`${runId}-fail-base`)
    const prSpec: PrWebhookSpec = {
      installationId: Number(INSTALL_ID_ENV),
      repoId: Number(REPO_ID_ENV),
      repoFullName: FIXTURE_REPO_OVERRIDE,
      branch: `spec-55/fail-${runId.slice(0, 8)}`,
      baseSha,
      headSha,
      prNumber: 55_002,
    }

    // ── 1. Synthetic webhook ────────────────────────────────────────────
    // The `spec-55/fail-*` head branch deterministically routes to the
    // `.titan/pipelines/pr-roundtrip-fail.yml` fixture, whose `github:`
    // trigger is branch-scoped to `spec-55/fail-*` (its middle `test` stage
    // `exit 1`s). That branch-scope is what lets this RED leg coexist with
    // the GREEN `happy` leg on the same repo without a check collision —
    // see e2e/fixtures/pipelines/pr-roundtrip-fail.yml + the #1240 runbook.
    const wh = await postSyntheticPrWebhook(prSpec)
    expect(wh.status).toBe(202)

    const build = await fx.findBuildBySha(headSha, {
      timeoutMs: BUILD_DISPATCH_DEADLINE_MS,
      intervalMs: 1_000,
    })
    expect(
      build,
      `no build dispatched for adversarial leg (head=${headSha}). The ` +
        `pr-roundtrip-fail.yml fixture must be discovered + enabled on ` +
        `${FIXTURE_REPO_OVERRIDE} with a github trigger scoped to spec-55/fail-*.`,
    ).not.toBeNull()

    // ── 2. Wait terminal — the fixture is engineered to FAIL ────────────
    const terminal = await fx.waitForTerminal(build!.id, {
      timeoutMs: BUILD_TERMINAL_DEADLINE_MS,
      intervalMs: 2_000,
    })
    expect(terminal, 'build did not reach a terminal state').not.toBeNull()

    // State-mapping ground truth (#1240 AC4): a rig terminal FAILED MUST map
    // to a GitHub `failure`. A green here is the worst CI failure mode — a
    // broken build showing a misleading green check — so we HARD-fail (no
    // fixme escape) rather than silently degrade.
    expect(
      terminal!.status,
      `adversarial leg expected a FAILED build but the rig returned ` +
        `${terminal!.status}. The pr-roundtrip-fail.yml fixture (branch-scoped ` +
        `to spec-55/fail-*) must be the pipeline that fires for this PR — see ` +
        `docs/ops/runbooks/layer2-e2e.md "adversarial red-check leg".`,
    ).toBe('FAILED')

    const status = await waitForTitanStatus(
      FIXTURE_REPO_OVERRIDE,
      headSha,
      'failure',
      CHECK_DEADLINE_MS,
    )
    expect(
      status,
      `no Titan status reached terminal FAILURE on head=${headSha} within ` +
        `${CHECK_DEADLINE_MS}ms — rig was FAILED but GitHub never saw a red ` +
        `check (FAILED→failure mapping regression?).`,
    ).not.toBeNull()

    // ── 3. RED check, NAMES the failed stage ────────────────────────────
    expect(status!.state).toBe('failure')
    const description = status!.description ?? ''
    expect(
      /test/i.test(description) || /failing[- ]?step/i.test(description),
      `status description "${description}" does not name the failed stage. ` +
        `Operators need the stage name in the PR-checks tab without clicking ` +
        `through to the build-detail UI.`,
    ).toBe(true)

    // ── 4. UI: failure reason visible without scrolling logs ────────────
    await loginViaKeycloak(page, ENV)
    await page.goto(`${ENV.uiBaseUrl}/builds/${build!.id}`)
    const reason = page
      .locator(
        '[data-testid="build-failure-reason"], [data-testid="build-status-badge"], ' +
          '[data-testid="stage-test"] [data-testid="stage-status"]',
      )
      .first()
    await expect(
      reason,
      'build-detail UI does not surface a failure reason in an operator-visible ' +
        'location (build-failure-reason / status badge / failed stage row)',
    ).toBeVisible({ timeout: 10_000 })
    const reasonText = (await reason.textContent())?.toLowerCase() ?? ''
    expect(
      /fail|error|test/.test(reasonText),
      `failure-reason element text "${reasonText}" does not mention the ` +
        `failed stage or a fail/error term`,
    ).toBe(true)
  })
})

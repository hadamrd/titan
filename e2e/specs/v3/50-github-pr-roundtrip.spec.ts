/**
 * 50-github-pr-roundtrip — GitHub PR roundtrip @golden (#957).
 *
 * The conversion moment: open a PR on a real GitHub repo wired to the Titan
 * GitHub App, watch the commit status / check-run flip RUNNING -> SUCCESS in
 * < 60s, assert the rig built the corresponding head SHA. This is the spec
 * the demo room runs to close the deal.
 *
 * ------------------------------------------------------------------------
 * Honest reconnaissance findings (DON'T fabricate; these are what the code
 * actually does + what this spec surfaces as follow-up bugs):
 *
 *  - PRODUCTION POSTS COMMIT STATUSES, NOT CHECK-RUNS.
 *    `GithubStatusReporter.report(...)` calls `repo.createCommitStatus(sha,
 *    state, targetUrl, description, CONTEXT)` with CONTEXT = "ci/titan".
 *    That is GitHub's older "statuses" API (`/repos/.../statuses/{sha}`),
 *    not the "check-runs" API (`/repos/.../check-runs`). The brief asks us
 *    to poll check-runs first — we DO, but we ALSO poll the commit-statuses
 *    endpoint and report which one actually surfaces the result. If only
 *    statuses fire, that's a follow-up: "Titan posts commit statuses, not
 *    check-runs — PR-checks tab shows the legacy badge instead of the
 *    richer check-runs panel".
 *
 *  - BUILDS-BY-SHA: `/api/v1/builds?search=<sha>` works.
 *    `BuildDao.findAll` ILIKE-searches `trigger_meta_json->>'commitSha'`
 *    when `search` is given. `/api/v1/builds?head=...` and `?branch=...`
 *    (filter by branch column) do not match the head SHA. Using `search`.
 *
 *  - NO PR COMMENT IS POSTED ON BUILD COMPLETION.
 *    `GithubStatusReporter` only creates a commit status. We probe PR
 *    comments at the end purely to surface the gap as a follow-up, never
 *    to fail the test.
 *
 * Required env — this is a LAYER-2 (real-commit) spec (#50):
 *  - LAYER2_RIG_AVAILABLE=1 — REQUIRED. Opening a real PR and watching the
 *    check flip needs a GitHub App installed on the fixture repo with a
 *    webhook tunnel delivering to the rig — that env exists only on the
 *    Layer-2 / public rig (`task rig:smoke:real`, docs/ops github-app-dev
 *    runbook). Unset => skip: the local `task dev:titan` rig can never
 *    satisfy it.
 *  - TITAN_RIG_URL (or TITAN_UI_URL) — the Layer-2 rig base URL; defaults
 *    to the local rig otherwise.
 *  - gh CLI authenticated (`gh auth status` exit 0) with push rights on
 *    hadamrd/titan-e2e-fixture.
 *  - Keycloak direct-grant env for the rig (TITAN_KEYCLOAK_URL /
 *    TITAN_DEV_USER / TITAN_DEV_PASSWORD — fixtures/auth-v3.ts defaults).
 *
 * Skip rules (deterministic, no test.skip-in-the-middle):
 *  - LAYER2_RIG_AVAILABLE unset          => skip (local-dev posture, #50)
 *  - `gh auth status` fails              => skip
 *  - rig /api/v1 not reachable (5s)      => skip
 *  - cannot fetch Keycloak bearer token  => skip (prod rig keycloak host
 *    may not resolve from CI / dev env)
 *
 * Tagging (#50): @real-commit ONLY — deliberately NOT @golden. The local
 * @golden set (dev/rig-smoke/run-golden.sh, --grep @golden) must be green-able
 * on `task dev:titan` by definition; this spec runs in the Layer-2 smoke
 * (`task rig:smoke:real`, --grep @real-commit) instead.
 *
 * @tag @real-commit
 */
import { test, expect } from '@playwright/test'
import { execFileSync, spawnSync } from 'node:child_process'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'

const ENV = authEnv()
const FIXTURE_REPO = 'hadamrd/titan-e2e-fixture'
const RIG_BASE_URL =
  process.env.TITAN_RIG_URL ??
  process.env.TITAN_UI_URL ??
  'http://localhost:5180'

const CHECK_APPEARED_DEADLINE_MS = 60_000
const BUILD_TERMINAL_DEADLINE_MS = 8 * 60_000
const TERMINAL = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'CANCELLED', 'UNSTABLE'])

interface PrInfo {
  number: number
  headSha: string
  branch: string
  htmlUrl: string
}

interface BuildDto {
  id: number
  status: string
  jobId?: number
  buildNumber?: number
}

interface BuildsPage {
  items: BuildDto[]
  total: number
}

interface CommitStatus {
  context: string
  state: string
  description?: string
  target_url?: string
}

interface CheckRun {
  name: string
  status: string
  conclusion: string | null
  app?: { slug?: string; name?: string }
}

interface PrComment {
  id: number
  user: { login: string; type: string }
  body: string
}

// ── shelling out to gh / git ────────────────────────────────────────────

function gh(args: string[]): string {
  return execFileSync('gh', args, { encoding: 'utf8' }).trim()
}

function ghJson<T>(args: string[]): T {
  return JSON.parse(gh(args)) as T
}

function ghAuthOk(): boolean {
  const r = spawnSync('gh', ['auth', 'status'], { encoding: 'utf8' })
  return r.status === 0
}

function runGit(cwd: string, args: string[]): void {
  const r = spawnSync('git', args, { cwd, encoding: 'utf8' })
  if (r.status !== 0) {
    throw new Error(
      `git ${args.join(' ')} failed in ${cwd}: status=${r.status} ` +
        `stderr=${r.stderr?.slice(0, 500) ?? ''}`,
    )
  }
}

// ── rig probes ──────────────────────────────────────────────────────────

async function rigReachable(): Promise<boolean> {
  try {
    const ctrl = new AbortController()
    const t = setTimeout(() => ctrl.abort(), 5_000)
    const res = await fetch(`${RIG_BASE_URL}/api/v1/builds`, { signal: ctrl.signal })
    clearTimeout(t)
    // 401 means rig is up + protected; that's reachable for our purposes.
    return res.status === 401 || res.ok
  } catch {
    return false
  }
}

async function tryBearerToken(): Promise<string | null> {
  try {
    return await fetchBearerToken(ENV)
  } catch {
    return null
  }
}

async function findBuildByHeadSha(
  bearer: string,
  sha: string,
): Promise<BuildDto | null> {
  // BuildDao.findAll ILIKE-searches trigger_meta_json->>'commitSha' when
  // `search` is given. The full 40-char sha is unique enough to land a
  // single row.
  const url = new URL(`${RIG_BASE_URL}/api/v1/builds`)
  url.searchParams.set('search', sha)
  url.searchParams.set('limit', '10')
  const res = await fetch(url.toString(), {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  if (!res.ok) return null
  const page = (await res.json()) as BuildsPage
  return page.items[0] ?? null
}

async function readBuild(bearer: string, id: number): Promise<BuildDto | null> {
  const res = await fetch(`${RIG_BASE_URL}/api/v1/builds/${id}`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  if (!res.ok) return null
  return (await res.json()) as BuildDto
}

// ── repo manipulation ──────────────────────────────────────────────────

interface CheckoutHandle {
  dir: string
  branch: string
  cleanup: () => void
}

function cloneAndCutBranch(timestamp: string): CheckoutHandle {
  const tmp = mkdtempSync(join(tmpdir(), 'titan-pr-roundtrip-'))
  const branch = `spec-50/pr-roundtrip-${timestamp}`
  // gh clone uses configured ssh creds (the env has ssh + protocol=ssh per
  // gh auth status).
  execFileSync('gh', ['repo', 'clone', FIXTURE_REPO, tmp], {
    encoding: 'utf8',
  })
  runGit(tmp, ['config', 'user.email', 'spec-50@titan.test'])
  runGit(tmp, ['config', 'user.name', 'spec 50 roundtrip'])
  runGit(tmp, ['checkout', '-b', branch])
  // Mutate the sentinel — bytes change per timestamp so the commit-sha is
  // unique across spec runs.
  writeFileSync(join(tmp, 'SENTINEL.txt'), `spec-50 roundtrip ${timestamp}\n`)
  runGit(tmp, ['add', 'SENTINEL.txt'])
  runGit(tmp, ['commit', '-m', `spec 50 walkthrough ${timestamp}`])
  runGit(tmp, ['push', '-u', 'origin', branch])
  return {
    dir: tmp,
    branch,
    cleanup: () => {
      try {
        rmSync(tmp, { recursive: true, force: true })
      } catch {
        // best-effort
      }
    },
  }
}

function headSha(dir: string): string {
  return execFileSync('git', ['rev-parse', 'HEAD'], { cwd: dir, encoding: 'utf8' }).trim()
}

function deleteRemoteBranch(branch: string): void {
  // gh pr close --delete-branch deletes the remote branch only on PR close;
  // belt-and-braces here covers the case where the PR was never opened.
  spawnSync('gh', ['api', '-X', 'DELETE', `/repos/${FIXTURE_REPO}/git/refs/heads/${branch}`], {
    encoding: 'utf8',
  })
}

// ── GitHub status / check-run probes ───────────────────────────────────

function listCommitStatuses(sha: string): CommitStatus[] {
  try {
    return ghJson<CommitStatus[]>([
      'api',
      `/repos/${FIXTURE_REPO}/commits/${sha}/statuses`,
    ])
  } catch {
    return []
  }
}

function listCheckRuns(sha: string): CheckRun[] {
  try {
    const out = ghJson<{ check_runs: CheckRun[] }>([
      'api',
      `/repos/${FIXTURE_REPO}/commits/${sha}/check-runs`,
    ])
    return out.check_runs ?? []
  } catch {
    return []
  }
}

function listPrComments(prNumber: number): PrComment[] {
  try {
    return ghJson<PrComment[]>([
      'api',
      `/repos/${FIXTURE_REPO}/issues/${prNumber}/comments`,
    ])
  } catch {
    return []
  }
}

// ── the spec ───────────────────────────────────────────────────────────

// @real-commit only — NOT @golden. Layer-2 spec; see the header tagging note.
test.describe('@real-commit v3 github-pr-roundtrip', () => {
  test('PR open -> Titan check appears -> goes terminal -> rig SHA matches', async () => {
    test.setTimeout(15 * 60_000)

    // Same posture guard as 53-archive-artifacts-real-commit (#50): the
    // GitHub App installation + webhook tunnel exist only on the Layer-2 rig.
    test.skip(
      !process.env.LAYER2_RIG_AVAILABLE,
      'LAYER2_RIG_AVAILABLE unset; local-dev posture — GitHub App installation ' +
        '+ webhook tunnel exist only on the Layer-2 rig (task rig:smoke:real)',
    )
    if (!ghAuthOk()) {
      test.skip(true, 'gh CLI not authenticated; cannot drive the GitHub side')
    }
    if (!(await rigReachable())) {
      test.skip(true, `Titan rig not reachable at ${RIG_BASE_URL}`)
    }

    const bearer = await tryBearerToken()
    if (!bearer) {
      test.skip(
        true,
        `cannot fetch Keycloak bearer (env=${ENV.keycloakUrl} client=${ENV.directGrantClientId});` +
          ` rig may be remote with no local Keycloak passthrough`,
      )
    }

    const timestamp = `${Date.now()}`
    const checkout = cloneAndCutBranch(timestamp)
    const sha = headSha(checkout.dir)

    let pr: PrInfo | null = null
    const followUps: string[] = []

    try {
      // ── 1. Open the PR via gh ─────────────────────────────────────────
      const prCreateOut = gh([
        'pr',
        'create',
        '--repo',
        FIXTURE_REPO,
        '--base',
        'main',
        '--head',
        checkout.branch,
        '--title',
        `spec 50 walkthrough ${timestamp}`,
        '--body',
        'automated',
      ])
      // gh pr create prints the html url on the last line.
      const htmlUrl = prCreateOut.split('\n').filter((l) => l.startsWith('https://')).pop()
      expect(htmlUrl, `gh pr create did not emit an https URL: ${prCreateOut}`).toBeTruthy()
      const m = htmlUrl!.match(/\/pull\/(\d+)/)
      expect(m, `cannot extract PR number from ${htmlUrl}`).toBeTruthy()
      const prNumber = Number(m![1])
      pr = { number: prNumber, headSha: sha, branch: checkout.branch, htmlUrl: htmlUrl! }
      // eslint-disable-next-line no-console
      console.log(`[spec-50] opened PR #${prNumber} sha=${sha} branch=${checkout.branch}`)

      // ── 2. Poll for Titan-related check or commit status ──────────────
      // (Production posts commit STATUSES with context=ci/titan; we poll
      //  both APIs and record which surfaces results.)
      const seenStatuses: CommitStatus[] = []
      const seenChecks: CheckRun[] = []
      let titanStatus: CommitStatus | undefined
      let titanCheck: CheckRun | undefined

      const startedAt = Date.now()
      while (Date.now() - startedAt < CHECK_APPEARED_DEADLINE_MS) {
        const statuses = listCommitStatuses(sha)
        const checks = listCheckRuns(sha)
        seenStatuses.splice(0, seenStatuses.length, ...statuses)
        seenChecks.splice(0, seenChecks.length, ...checks)
        titanStatus = statuses.find((s) => /titan/i.test(s.context))
        titanCheck = checks.find(
          (c) => /titan/i.test(c.name) || /titan/i.test(c.app?.slug ?? c.app?.name ?? ''),
        )
        if (titanStatus || titanCheck) break
        await new Promise((r) => setTimeout(r, 3_000))
      }

      // eslint-disable-next-line no-console
      console.log(
        `[spec-50] visible after ${Date.now() - startedAt}ms — ` +
          `statuses=${seenStatuses.map((s) => `${s.context}=${s.state}`).join(',') || 'none'} ` +
          `checks=${seenChecks.map((c) => `${c.name}=${c.status}/${c.conclusion ?? '?'}`).join(',') || 'none'}`,
      )

      expect(
        titanStatus !== undefined || titanCheck !== undefined,
        `No Titan commit-status or check-run surfaced within ${CHECK_APPEARED_DEADLINE_MS}ms ` +
          `after PR creation. statuses=${JSON.stringify(seenStatuses)} ` +
          `checks=${JSON.stringify(seenChecks)} — likely follow-up: ` +
          `GitHub App not installed on fixture repo, or webhook not delivering, ` +
          `or GithubStatusReporter not firing.`,
      ).toBe(true)

      // Honest follow-up surfacing: production posts statuses; if check-runs
      // were absent, that's worth flagging.
      if (!titanCheck && titanStatus) {
        followUps.push(
          `Titan publishes a commit STATUS (context="${titanStatus.context}") but no check-run` +
            ` — PR-checks tab shows the legacy status badge, not the richer check-runs panel.`,
        )
      }

      const expectedCheckName = titanCheck?.name ?? titanStatus?.context ?? '(none)'
      // eslint-disable-next-line no-console
      console.log(`[spec-50] discovered check-name format: "${expectedCheckName}"`)

      // ── 3. Find the build on the rig by head sha ──────────────────────
      let rigBuild: BuildDto | null = null
      const buildAppearedDeadline = Date.now() + 90_000
      while (Date.now() < buildAppearedDeadline) {
        rigBuild = await findBuildByHeadSha(bearer!, sha)
        if (rigBuild) break
        await new Promise((r) => setTimeout(r, 3_000))
      }
      expect(
        rigBuild,
        `rig /api/v1/builds?search=<sha> did not return any build for sha=${sha} ` +
          `within 90s. Likely follow-up: GitHub App webhook not enqueueing a build, ` +
          `or trigger_meta_json.commitSha not populated (BuildDao searches that key).`,
      ).not.toBeNull()
      // eslint-disable-next-line no-console
      console.log(
        `[spec-50] rig build id=${rigBuild!.id} status=${rigBuild!.status} ` +
          `(query used: /api/v1/builds?search=<sha>)`,
      )

      // ── 4. Wait for build to reach a terminal state ───────────────────
      let final: BuildDto | null = rigBuild
      const terminalDeadline = Date.now() + BUILD_TERMINAL_DEADLINE_MS
      while (Date.now() < terminalDeadline) {
        final = await readBuild(bearer!, rigBuild!.id)
        if (final && TERMINAL.has(final.status)) break
        await new Promise((r) => setTimeout(r, 4_000))
      }
      expect(
        final && TERMINAL.has(final.status),
        `build ${rigBuild!.id} did not reach a terminal status within ` +
          `${BUILD_TERMINAL_DEADLINE_MS}ms; last status=${final?.status}`,
      ).toBe(true)
      // eslint-disable-next-line no-console
      console.log(`[spec-50] rig terminal status=${final!.status}`)

      // ── 5. Poll GitHub side until commit status / check is terminal ──
      // GitHub "completed" lingo:
      //   - commit statuses: state ∈ {pending, success, failure, error}.
      //     A completed-state is anything other than 'pending'.
      //   - check runs:      status ∈ {queued, in_progress, completed}.
      const githubTerminalDeadline = Date.now() + 90_000
      let finalStatus: CommitStatus | undefined = titanStatus
      let finalCheck: CheckRun | undefined = titanCheck
      while (Date.now() < githubTerminalDeadline) {
        if (titanStatus) {
          finalStatus = listCommitStatuses(sha).find((s) => s.context === titanStatus!.context)
        }
        if (titanCheck) {
          finalCheck = listCheckRuns(sha).find((c) => c.name === titanCheck!.name)
        }
        const statusTerminal = !titanStatus || (finalStatus && finalStatus.state !== 'pending')
        const checkTerminal = !titanCheck || (finalCheck && finalCheck.status === 'completed')
        if (statusTerminal && checkTerminal) break
        await new Promise((r) => setTimeout(r, 3_000))
      }

      // ── 6. Assert the GitHub conclusion matches the rig status ────────
      // Mapping ground truth: GithubStatusReporter.mapStatus.
      //   SUCCESS                                   -> success
      //   FAILED / ABORTED / CANCELLED / UNSTABLE   -> failure
      const expectedState = final!.status === 'SUCCESS' ? 'success' : 'failure'
      if (finalStatus) {
        expect(
          finalStatus.state,
          `GitHub status state (${finalStatus.state}) does not match rig terminal ` +
            `status (${final!.status} -> expected ${expectedState}).`,
        ).toBe(expectedState)
      }
      if (finalCheck) {
        // check-runs conclusion vocabulary differs slightly: success | failure | neutral | cancelled | timed_out | action_required | stale
        expect(finalCheck.status, 'check-run did not complete').toBe('completed')
        expect(finalCheck.conclusion, 'check-run conclusion missing').toBeTruthy()
        // accept either success/failure mapping
        if (final!.status === 'SUCCESS') {
          expect(finalCheck.conclusion).toBe('success')
        } else {
          expect(['failure', 'cancelled', 'timed_out'], 'unexpected check conclusion').toContain(
            finalCheck.conclusion!,
          )
        }
      }

      // ── 7. Adversarial: did Titan post a PR comment? (Don't fail.) ────
      const comments = listPrComments(pr.number)
      const titanComment = comments.find(
        (c) =>
          /titan/i.test(c.user.login) ||
          c.user.type === 'Bot' && /titan/i.test(c.body),
      )
      if (!titanComment) {
        followUps.push(
          `Titan posts no PR comment on build completion (only the commit-status badge). ` +
            `PR-author UX is poorer than CircleCI/Buildkite which both leave a build-result comment ` +
            `with logs link.`,
        )
      }

      // ── 8. Surface follow-ups in test stdout so the report captures ──
      if (followUps.length > 0) {
        // eslint-disable-next-line no-console
        console.log(
          `[spec-50] FOLLOW-UPS (do NOT block this PR):\n` +
            followUps.map((f, i) => `  ${i + 1}. ${f}`).join('\n'),
        )
      }
      // Final summary line for the parent agent to scrape.
      // eslint-disable-next-line no-console
      console.log(
        `[spec-50] SUMMARY pr=${pr.number} sha=${sha} rigBuild=${rigBuild!.id} ` +
          `rigStatus=${final!.status} githubCheckName="${expectedCheckName}" ` +
          `followUps=${followUps.length}`,
      )
    } finally {
      // ── 9. Cleanup (always) ───────────────────────────────────────────
      if (pr) {
        spawnSync('gh', [
          'pr',
          'close',
          String(pr.number),
          '--repo',
          FIXTURE_REPO,
          '--delete-branch',
        ], { encoding: 'utf8' })
      }
      // Belt-and-braces — if pr was null we never tried to close.
      deleteRemoteBranch(checkout.branch)
      checkout.cleanup()
    }
  })
})

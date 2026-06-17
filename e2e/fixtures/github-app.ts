/**
 * github-app — fixture helper for the v3 GitHub App golden-path spec (#838).
 *
 * Encapsulates the wire calls a customer's first install makes:
 *   1. POST /api/v1/github-app/manifest-callback?code=...   (Child A — #832)
 *   2. GET  /api/v1/github-app                              (returns App row)
 *   3. POST /api/v1/github-app/installations/{id}/sync      (Child C — #834)
 *   4. GET  /api/v1/github-app/installations                (verify discovery)
 *   5. GET  /api/v1/builds?search=<sha>                     (poll for triggered build)
 *
 * Plus webhook utilities for the adversarial sub-cases:
 *   - postWebhookWithBadHmac(sha) → 401 expected, no build row.
 *
 * All requests are routed through `fetch` against the rig base URL; no
 * inline cURL, no raw fetch in specs. Helpers throw on network failure;
 * status assertions are the caller's job (the spec asserts; the fixture
 * just exposes the raw response).
 */
import { execFileSync, spawnSync } from 'node:child_process'
import { createHmac } from 'node:crypto'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { authEnv, fetchBearerToken, type AuthEnv } from './auth-v3'

export const FIXTURE_REPO =
  process.env.TITAN_E2E_FIXTURE_REPO ?? 'hadamrd/titan-e2e-fixture'

export const RIG_BASE_URL =
  process.env.TITAN_RIG_URL ??
  process.env.TITAN_UI_URL ??
  'http://localhost:5180'

export interface GithubAppDto {
  id: number
  appId: number
  slug: string
  name: string
  htmlUrl: string
  createdAt: string
}

export interface GithubInstallationDto {
  id: number
  githubInstallationId: number
  accountLogin: string
  accountType: 'Organization' | 'User'
  suspended: boolean
  createdAt: string
  repos?: Array<{
    fullName: string
    defaultBranch: string
    htmlUrl: string
    pipelines: Array<{ filename: string; name: string }>
  }>
}

export interface BuildDto {
  id: number
  status: string
  jobId?: number
  buildNumber?: number
}

export interface BuildsPage {
  items: BuildDto[]
  total: number
}

export interface CommitStatus {
  context: string
  state: 'pending' | 'success' | 'failure' | 'error'
  description?: string
  target_url?: string
}

export class GithubAppFixture {
  readonly env: AuthEnv
  readonly base: string
  private bearer: string | null = null

  constructor(env: AuthEnv = authEnv(), base: string = RIG_BASE_URL) {
    this.env = env
    this.base = base
  }

  /** Lazily fetch a bearer; throw if the rig's Keycloak is unreachable. */
  async getBearer(): Promise<string> {
    if (this.bearer) return this.bearer
    this.bearer = await fetchBearerToken(this.env)
    return this.bearer
  }

  async authHeaders(): Promise<Record<string, string>> {
    const b = await this.getBearer()
    return { Authorization: `Bearer ${b}`, Accept: 'application/json' }
  }

  /** GET /api/v1/github-app — returns null on 404. */
  async getApp(): Promise<GithubAppDto | null> {
    const res = await fetch(`${this.base}/api/v1/github-app`, {
      headers: await this.authHeaders(),
    })
    if (res.status === 404) return null
    if (!res.ok) throw new Error(`getApp failed: ${res.status} ${await res.text()}`)
    return (await res.json()) as GithubAppDto
  }

  /** POST /api/v1/github-app/manifest-callback?code=... */
  async manifestCallback(code: string): Promise<Response> {
    const url = new URL(`${this.base}/api/v1/github-app/manifest-callback`)
    url.searchParams.set('code', code)
    return fetch(url.toString(), {
      method: 'POST',
      headers: await this.authHeaders(),
    })
  }

  /** GET /api/v1/github-app/installations */
  async listInstallations(): Promise<GithubInstallationDto[]> {
    const res = await fetch(`${this.base}/api/v1/github-app/installations`, {
      headers: await this.authHeaders(),
    })
    if (!res.ok) throw new Error(`listInstallations: ${res.status}`)
    return (await res.json()) as GithubInstallationDto[]
  }

  /** POST /api/v1/github-app/installations/{id}/sync. Returns the Response so
   * callers can probe non-2xx (used by the adversarial expired-token block). */
  async syncInstallation(installId: number): Promise<Response> {
    return fetch(
      `${this.base}/api/v1/github-app/installations/${installId}/sync`,
      { method: 'POST', headers: await this.authHeaders() },
    )
  }

  /** Poll /api/v1/builds?search=<sha> until a build appears or deadline. */
  async findBuildBySha(
    sha: string,
    opts: { timeoutMs?: number; intervalMs?: number } = {},
  ): Promise<BuildDto | null> {
    const { timeoutMs = 120_000, intervalMs = 2_000 } = opts
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      const url = new URL(`${this.base}/api/v1/builds`)
      url.searchParams.set('search', sha)
      url.searchParams.set('limit', '10')
      const res = await fetch(url.toString(), { headers: await this.authHeaders() })
      if (res.ok) {
        const page = (await res.json()) as BuildsPage
        const first = page.items[0]
        if (first) return first
      }
      await new Promise((r) => setTimeout(r, intervalMs))
    }
    return null
  }

  async readBuild(id: number): Promise<BuildDto | null> {
    const res = await fetch(`${this.base}/api/v1/builds/${id}`, {
      headers: await this.authHeaders(),
    })
    if (!res.ok) return null
    return (await res.json()) as BuildDto
  }

  /** Wait until a build hits a terminal state. */
  async waitForTerminal(
    id: number,
    opts: { timeoutMs?: number; intervalMs?: number } = {},
  ): Promise<BuildDto | null> {
    const { timeoutMs = 8 * 60_000, intervalMs = 4_000 } = opts
    const TERMINAL = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'CANCELLED', 'UNSTABLE'])
    const deadline = Date.now() + timeoutMs
    let last: BuildDto | null = null
    while (Date.now() < deadline) {
      last = await this.readBuild(id)
      if (last && TERMINAL.has(last.status)) return last
      await new Promise((r) => setTimeout(r, intervalMs))
    }
    return last
  }

  /**
   * Post a webhook event with a deliberately wrong HMAC. The rig must reject
   * with 401 (or comparable 4xx) and NOT enqueue a build. Used by the
   * @adversarial sub-case.
   */
  async postWebhookWithBadHmac(eventBody: object): Promise<Response> {
    const raw = JSON.stringify(eventBody)
    // Deliberately wrong secret → wrong signature → rejected.
    const badSig =
      'sha256=' + createHmac('sha256', 'wrong-secret').update(raw).digest('hex')
    return fetch(`${this.base}/api/v1/github-app/events`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-GitHub-Event': 'push',
        'X-GitHub-Delivery': `bad-hmac-${Date.now()}`,
        'X-Hub-Signature-256': badSig,
      },
      body: raw,
    })
  }
}

// ── git / gh shellouts ──────────────────────────────────────────────────

export interface CheckoutHandle {
  dir: string
  branch: string
  sha: string
  cleanup: () => void
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

/** Clone the fixture repo to a tmp dir, cut a unique branch, write a sentinel
 * file containing the per-run UUID, commit + push. Returns handle including
 * head SHA and a cleanup() that rm-rfs the tmp dir. */
export function cutFixtureBranch(uuid: string): CheckoutHandle {
  const tmp = mkdtempSync(join(tmpdir(), 'titan-spec-40-'))
  const branch = `spec-40/golden-path-${uuid}`
  execFileSync('gh', ['repo', 'clone', FIXTURE_REPO, tmp], { encoding: 'utf8' })
  runGit(tmp, ['config', 'user.email', 'spec-40@titan.test'])
  runGit(tmp, ['config', 'user.name', 'spec 40 golden-path'])
  runGit(tmp, ['checkout', '-b', branch])
  writeFileSync(join(tmp, 'SENTINEL.txt'), `spec-40 golden-path ${uuid}\n`)
  runGit(tmp, ['add', 'SENTINEL.txt'])
  runGit(tmp, ['commit', '-m', `spec 40 golden path ${uuid}`])
  runGit(tmp, ['push', '-u', 'origin', branch])
  const sha = execFileSync('git', ['rev-parse', 'HEAD'], {
    cwd: tmp,
    encoding: 'utf8',
  }).trim()
  return {
    dir: tmp,
    branch,
    sha,
    cleanup: () => {
      try {
        rmSync(tmp, { recursive: true, force: true })
      } catch {
        /* best effort */
      }
    },
  }
}

/** Force-delete a remote branch. Idempotent; never throws. */
export function deleteRemoteBranch(branch: string): void {
  spawnSync(
    'gh',
    ['api', '-X', 'DELETE', `/repos/${FIXTURE_REPO}/git/refs/heads/${branch}`],
    { encoding: 'utf8' },
  )
}

/** Verify the branch is actually gone on the remote (post-cleanup assertion). */
export function remoteBranchExists(branch: string): boolean {
  const r = spawnSync(
    'git',
    ['ls-remote', '--exit-code', `https://github.com/${FIXTURE_REPO}`, `refs/heads/${branch}`],
    { encoding: 'utf8' },
  )
  return r.status === 0
}

/** GET /repos/{repo}/commits/{sha}/statuses via gh CLI. */
export function listCommitStatuses(sha: string): CommitStatus[] {
  try {
    const out = execFileSync(
      'gh',
      ['api', `/repos/${FIXTURE_REPO}/commits/${sha}/statuses`],
      { encoding: 'utf8' },
    )
    return JSON.parse(out) as CommitStatus[]
  } catch {
    return []
  }
}

export function ghAuthOk(): boolean {
  return spawnSync('gh', ['auth', 'status'], { encoding: 'utf8' }).status === 0
}

export async function rigReachable(base: string = RIG_BASE_URL): Promise<boolean> {
  try {
    const ctrl = new AbortController()
    const t = setTimeout(() => ctrl.abort(), 5_000)
    const res = await fetch(`${base}/api/v1/builds`, { signal: ctrl.signal })
    clearTimeout(t)
    return res.status === 401 || res.ok
  } catch {
    return false
  }
}

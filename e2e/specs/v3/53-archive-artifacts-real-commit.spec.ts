/**
 * 53-archive-artifacts-real-commit @real-commit — Layer-2 (real-commit) round-
 * trip for the archiveArtifacts primitive (#1051).
 *
 * The next-sprint skill §5 mandates Layer-2 (real commit, k3s rig) coverage as
 * the unit of trust for 0.1.0. Layer-1 (synthesized HMAC push) for archive-
 * Artifacts already exists in spec 26-fixture-simple-build; this spec is the
 * real-commit equivalent against the public k3s rig at
 * titan.test.example.com — only with this can we honestly say "Titan
 * archives artifacts" end-to-end.
 *
 * What it does:
 *   1. Cuts a unique branch on hadamrd/titan-e2e-fixture (timestamp-suffixed).
 *   2. Writes .titan/pipelines/archive-artifacts.yml — a pipeline that emits a
 *      sentinel file whose bytes are unique per run (so SHA-256 is unique).
 *   3. Real-commits + pushes the branch via git.
 *   4. Polls the rig's /api/v1/builds?search=<sha> for the build to appear
 *      (the GitHub App-triggered build path); then polls /api/v1/builds/{id}
 *      until terminal.
 *   5. Asserts SUCCESS, fetches /api/v1/builds/{id}/artifacts, finds the
 *      sentinel, downloads it, and verifies SHA-256 matches the bytes we wrote.
 *   6. finally{} deletes the remote branch — discipline #1 for these tests is
 *      "every run cleans up after itself, even on failure".
 *
 * Adversarial sibling: same spec, but the YAML is mutated to declare a
 * deliberately-wrong glob (`does-not-exist-*.txt`). archiveArtifacts must FAIL
 * the build (configured as fail-on-empty by default) — we assert the terminal
 * status is non-SUCCESS, surfacing whether the archive step actually validates
 * its input or silently passes.
 *
 * Skip rules (deterministic):
 *   - LAYER2_RIG_AVAILABLE env unset                    => skip (default local-dev posture)
 *   - `gh auth status` fails (no GitHub creds)          => skip
 *   - rig /api/v1/builds not reachable (5s)             => skip
 *   - cannot fetch Keycloak bearer                      => skip
 *
 * @tag @real-commit
 */
import { test, expect } from '@playwright/test'
import { execFileSync, spawnSync } from 'node:child_process'
import { mkdtempSync, rmSync, writeFileSync, mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createHash } from 'node:crypto'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'

const ENV = authEnv()
const FIXTURE_REPO = 'hadamrd/titan-e2e-fixture'
const RIG_BASE_URL =
  process.env.TITAN_RIG_URL ??
  process.env.LAYER2_RIG_URL ??
  'https://titan.test.example.com'

const BUILD_APPEARED_DEADLINE_MS = 2 * 60_000
const BUILD_TERMINAL_DEADLINE_MS = 10 * 60_000
const TERMINAL = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'CANCELLED', 'UNSTABLE', 'ERROR'])

interface BuildDto {
  id: number
  status: string
  buildNumber?: number
  jobId?: number
}

interface BuildsPage {
  items: BuildDto[]
  total: number
}

interface ArtifactDto {
  id: number
  name: string
  sizeBytes: number
  downloadUrl: string
}

interface ArtifactsPage {
  items: ArtifactDto[]
  total: number
}

interface CheckoutHandle {
  dir: string
  branch: string
  cleanup: () => void
}

// ── env / preflight ────────────────────────────────────────────────────

function layer2Available(): boolean {
  return !!process.env.LAYER2_RIG_AVAILABLE
}

function ghAuthOk(): boolean {
  const r = spawnSync('gh', ['auth', 'status'], { encoding: 'utf8' })
  return r.status === 0
}

async function rigReachable(): Promise<boolean> {
  try {
    const ctrl = new AbortController()
    const t = setTimeout(() => ctrl.abort(), 5_000)
    const res = await fetch(`${RIG_BASE_URL}/api/v1/builds`, { signal: ctrl.signal })
    clearTimeout(t)
    // 401 means rig is up + protected; reachable for our purposes.
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

// ── git / fixture-repo helpers ─────────────────────────────────────────

function runGit(cwd: string, args: string[]): void {
  const r = spawnSync('git', args, { cwd, encoding: 'utf8' })
  if (r.status !== 0) {
    throw new Error(
      `git ${args.join(' ')} failed in ${cwd}: status=${r.status} ` +
        `stderr=${r.stderr?.slice(0, 500) ?? ''}`,
    )
  }
}

function cloneAndCutBranch(branchPrefix: string, timestamp: string): CheckoutHandle {
  const tmp = mkdtempSync(join(tmpdir(), 'titan-l2-archive-'))
  const branch = `${branchPrefix}-${timestamp}`
  execFileSync('gh', ['repo', 'clone', FIXTURE_REPO, tmp], { encoding: 'utf8' })
  runGit(tmp, ['config', 'user.email', 'spec-53@titan.test'])
  runGit(tmp, ['config', 'user.name', 'spec 53 archive-artifacts'])
  runGit(tmp, ['checkout', '-b', branch])
  return {
    dir: tmp,
    branch,
    cleanup: () => {
      try {
        rmSync(tmp, { recursive: true, force: true })
      } catch {
        /* best-effort */
      }
    },
  }
}

function writePipelineAndSentinel(
  dir: string,
  pipelineYaml: string,
  sentinelPath: string,
  sentinelBytes: string,
): void {
  // Pipeline YAML.
  const pipelineFsPath = join(dir, '.titan', 'pipelines', 'archive-artifacts.yml')
  mkdirSync(join(dir, '.titan', 'pipelines'), { recursive: true })
  writeFileSync(pipelineFsPath, pipelineYaml)
  // We don't pre-commit the sentinel content — the pipeline `sh` step writes
  // it at build time. But we *do* commit a small marker so the diff is non-
  // trivial (otherwise some SCM setups skip CI for "no file changes").
  writeFileSync(join(dir, '.titan', 'pipelines', '.run-marker'), sentinelPath + '\n')
  void sentinelBytes // bytes are produced server-side by the sh step
}

function commitAndPush(checkout: CheckoutHandle, message: string): string {
  runGit(checkout.dir, ['add', '-A'])
  runGit(checkout.dir, ['commit', '-m', message])
  runGit(checkout.dir, ['push', '-u', 'origin', checkout.branch])
  return execFileSync('git', ['rev-parse', 'HEAD'], {
    cwd: checkout.dir,
    encoding: 'utf8',
  }).trim()
}

function deleteRemoteBranch(branch: string): void {
  spawnSync(
    'gh',
    ['api', '-X', 'DELETE', `/repos/${FIXTURE_REPO}/git/refs/heads/${branch}`],
    { encoding: 'utf8' },
  )
}

// ── rig API helpers ────────────────────────────────────────────────────

async function findBuildByHeadSha(bearer: string, sha: string): Promise<BuildDto | null> {
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

async function listArtifacts(bearer: string, buildId: number): Promise<ArtifactDto[]> {
  const res = await fetch(`${RIG_BASE_URL}/api/v1/builds/${buildId}/artifacts`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  if (!res.ok) return []
  const page = (await res.json()) as ArtifactsPage
  return page.items
}

async function downloadArtifactBytes(bearer: string, id: number): Promise<Buffer> {
  const res = await fetch(`${RIG_BASE_URL}/api/v1/artifacts/${id}/download`, {
    headers: { Authorization: `Bearer ${bearer}` },
  })
  if (!res.ok) {
    throw new Error(`download HTTP ${res.status} for artifact ${id}`)
  }
  return Buffer.from(await res.arrayBuffer())
}

function sha256Hex(buf: Buffer | string): string {
  return createHash('sha256').update(buf).digest('hex')
}

// ── pipeline YAML builders ────────────────────────────────────────────

function pipelineYamlHappy(sentinelPath: string, sentinelContent: string): string {
  // Single-stage pipeline that writes a sentinel and archives it. We pick a
  // unique-per-run content so the SHA-256 oracle is independent of any cached
  // artifact from prior runs.
  return [
    'stages:',
    '  - stage: Build',
    '    steps:',
    `      - sh: "printf %s '${sentinelContent}' > ${sentinelPath}"`,
    `      - archiveArtifacts: { artifacts: "${sentinelPath}" }`,
    '',
  ].join('\n')
}

function pipelineYamlBadGlob(sentinelPath: string, sentinelContent: string): string {
  return [
    'stages:',
    '  - stage: Build',
    '    steps:',
    `      - sh: "printf %s '${sentinelContent}' > ${sentinelPath}"`,
    `      - archiveArtifacts: { artifacts: "does-not-exist-${Date.now()}-*.txt" }`,
    '',
  ].join('\n')
}

// ── the specs ─────────────────────────────────────────────────────────

test.describe('@real-commit v3 archive-artifacts-real-commit', () => {
  test.beforeAll(() => {
    if (!layer2Available()) {
      // eslint-disable-next-line no-console
      console.log(
        '[spec-53] LAYER2_RIG_AVAILABLE not set — skipping real-commit Layer-2 roundtrip. ' +
          'Set LAYER2_RIG_AVAILABLE=1 + point TITAN_RIG_URL/LAYER2_RIG_URL at the k3s rig to run.',
      )
    }
  })

  test('happy: real push → build appears on k3s rig → SUCCESS → artifact SHA-256 round-trips', async () => {
    test.skip(!layer2Available(), 'LAYER2_RIG_AVAILABLE unset; local-dev posture')
    test.setTimeout(20 * 60_000)

    if (!ghAuthOk()) {
      test.skip(true, 'gh CLI not authenticated; cannot push to fixture repo')
    }
    if (!(await rigReachable())) {
      test.skip(true, `Layer-2 rig not reachable at ${RIG_BASE_URL}`)
    }
    const bearer = await tryBearerToken()
    if (!bearer) {
      test.skip(true, `cannot fetch Keycloak bearer for ${ENV.keycloakUrl}`)
    }

    const timestamp = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`
    const sentinelPath = `sentinel-${timestamp}.txt`
    // Unique-per-run bytes so the SHA-256 oracle is independent.
    const sentinelContent = `titan-spec-53-${timestamp}-${Math.random().toString(36).slice(2)}`
    const expectedSha = sha256Hex(sentinelContent)

    const checkout = cloneAndCutBranch('spec-53/archive-artifacts', timestamp)
    let sha = ''
    try {
      writePipelineAndSentinel(
        checkout.dir,
        pipelineYamlHappy(sentinelPath, sentinelContent),
        sentinelPath,
        sentinelContent,
      )
      sha = commitAndPush(checkout, `spec 53 archive-artifacts happy ${timestamp}`)
      // eslint-disable-next-line no-console
      console.log(`[spec-53] pushed branch=${checkout.branch} sha=${sha}`)

      // Poll for the rig to pick up the build.
      let build: BuildDto | null = null
      const appearedDeadline = Date.now() + BUILD_APPEARED_DEADLINE_MS
      while (Date.now() < appearedDeadline) {
        build = await findBuildByHeadSha(bearer!, sha)
        if (build) break
        await new Promise((r) => setTimeout(r, 4_000))
      }
      expect(
        build,
        `rig /api/v1/builds?search=${sha} did not return a build within ` +
          `${BUILD_APPEARED_DEADLINE_MS}ms — GitHub App webhook not enqueueing, or ` +
          `commitSha not stored on trigger_meta_json.`,
      ).not.toBeNull()
      // eslint-disable-next-line no-console
      console.log(`[spec-53] rig build id=${build!.id} status=${build!.status}`)

      // Poll until terminal.
      let final: BuildDto | null = build
      const terminalDeadline = Date.now() + BUILD_TERMINAL_DEADLINE_MS
      while (Date.now() < terminalDeadline) {
        final = await readBuild(bearer!, build!.id)
        if (final && TERMINAL.has(final.status)) break
        await new Promise((r) => setTimeout(r, 4_000))
      }
      expect(
        final && TERMINAL.has(final.status),
        `build ${build!.id} did not reach terminal within ` +
          `${BUILD_TERMINAL_DEADLINE_MS}ms (last=${final?.status})`,
      ).toBe(true)
      expect(
        final!.status,
        `build ${build!.id} terminal=${final!.status}; expected SUCCESS for happy-path ` +
          `archiveArtifacts roundtrip.`,
      ).toBe('SUCCESS')

      // Artifact must exist and SHA-256 must match.
      const arts = await listArtifacts(bearer!, build!.id)
      const sentinel = arts.find((a) => a.name === sentinelPath || a.name.endsWith(sentinelPath))
      expect(
        sentinel,
        `no artifact named ${sentinelPath} on build ${build!.id}; observed=` +
          JSON.stringify(arts.map((a) => a.name)),
      ).toBeDefined()
      expect(sentinel!.sizeBytes, `${sentinelPath} archived with zero bytes`).toBe(
        Buffer.byteLength(sentinelContent, 'utf8'),
      )

      const bytes = await downloadArtifactBytes(bearer!, sentinel!.id)
      const gotSha = sha256Hex(bytes)
      expect(
        gotSha,
        `SHA-256 mismatch: artifact bytes hash=${gotSha} but pipeline wrote ` +
          `content with hash=${expectedSha}. Storage corrupted the file, or the ` +
          `archive step captured a different file than declared.`,
      ).toBe(expectedSha)

      // eslint-disable-next-line no-console
      console.log(
        `[spec-53] SUMMARY ok build=${build!.id} sha=${sha} artifact=${sentinel!.id} sha256=${gotSha}`,
      )
    } finally {
      deleteRemoteBranch(checkout.branch)
      checkout.cleanup()
    }
  })

  test('sad: deliberately-wrong glob → archiveArtifacts fails the build', async () => {
    test.skip(!layer2Available(), 'LAYER2_RIG_AVAILABLE unset; local-dev posture')
    test.setTimeout(20 * 60_000)

    if (!ghAuthOk()) {
      test.skip(true, 'gh CLI not authenticated; cannot push to fixture repo')
    }
    if (!(await rigReachable())) {
      test.skip(true, `Layer-2 rig not reachable at ${RIG_BASE_URL}`)
    }
    const bearer = await tryBearerToken()
    if (!bearer) {
      test.skip(true, `cannot fetch Keycloak bearer for ${ENV.keycloakUrl}`)
    }

    const timestamp = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`
    const sentinelPath = `sentinel-${timestamp}.txt`
    const sentinelContent = `titan-spec-53-sad-${timestamp}`

    const checkout = cloneAndCutBranch('spec-53/archive-bad-glob', timestamp)
    let sha = ''
    try {
      writePipelineAndSentinel(
        checkout.dir,
        pipelineYamlBadGlob(sentinelPath, sentinelContent),
        sentinelPath,
        sentinelContent,
      )
      sha = commitAndPush(checkout, `spec 53 archive-artifacts bad glob ${timestamp}`)

      let build: BuildDto | null = null
      const appearedDeadline = Date.now() + BUILD_APPEARED_DEADLINE_MS
      while (Date.now() < appearedDeadline) {
        build = await findBuildByHeadSha(bearer!, sha)
        if (build) break
        await new Promise((r) => setTimeout(r, 4_000))
      }
      expect(
        build,
        `rig /api/v1/builds?search=${sha} did not return a build within ` +
          `${BUILD_APPEARED_DEADLINE_MS}ms (sad-path)`,
      ).not.toBeNull()

      let final: BuildDto | null = build
      const terminalDeadline = Date.now() + BUILD_TERMINAL_DEADLINE_MS
      while (Date.now() < terminalDeadline) {
        final = await readBuild(bearer!, build!.id)
        if (final && TERMINAL.has(final.status)) break
        await new Promise((r) => setTimeout(r, 4_000))
      }
      expect(
        final && TERMINAL.has(final.status),
        `sad-path build ${build!.id} did not reach terminal within ${BUILD_TERMINAL_DEADLINE_MS}ms`,
      ).toBe(true)
      // The adversarial assertion: archive of a non-matching glob MUST NOT
      // surface as SUCCESS. If it does, that's a defect — archiveArtifacts is
      // silently no-op'ing on empty matches.
      expect(
        final!.status,
        `sad-path build ${build!.id} ended SUCCESS — archiveArtifacts silently ignored ` +
          `a non-matching glob ("does-not-exist-*.txt"). Should FAIL the build.`,
      ).not.toBe('SUCCESS')

      // eslint-disable-next-line no-console
      console.log(
        `[spec-53] sad-path SUMMARY build=${build!.id} status=${final!.status} (non-SUCCESS, good)`,
      )
    } finally {
      deleteRemoteBranch(checkout.branch)
      checkout.cleanup()
    }
  })

  test('finally{} cleanup discipline: branch is deleted even when assertions throw', async () => {
    // This is a shell-level test of the cleanup contract — we don't need
    // the rig for it. We exercise the cleanup helper directly: create a
    // branch on the fixture repo, throw mid-flight, and verify the finally
    // block deleted the branch.
    test.skip(!layer2Available(), 'LAYER2_RIG_AVAILABLE unset; local-dev posture')
    if (!ghAuthOk()) {
      test.skip(true, 'gh CLI not authenticated; cannot test cleanup against fixture repo')
    }

    const timestamp = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`
    const checkout = cloneAndCutBranch('spec-53/cleanup-discipline', timestamp)
    let threw = false
    try {
      try {
        writePipelineAndSentinel(
          checkout.dir,
          pipelineYamlHappy('cleanup.txt', 'cleanup'),
          'cleanup.txt',
          'cleanup',
        )
        commitAndPush(checkout, `spec 53 cleanup-discipline ${timestamp}`)
        // Force the same code path real specs use: an assertion throw inside
        // the try block, then finally cleans up.
        throw new Error('synthetic-assertion-failure')
      } finally {
        deleteRemoteBranch(checkout.branch)
        checkout.cleanup()
      }
    } catch (e) {
      threw = (e as Error).message === 'synthetic-assertion-failure'
    }
    expect(threw, 'synthetic assertion did not propagate').toBe(true)

    // Verify the branch is actually gone via the GitHub API.
    const r = spawnSync(
      'gh',
      ['api', `/repos/${FIXTURE_REPO}/git/refs/heads/${checkout.branch}`],
      { encoding: 'utf8' },
    )
    // gh api returns non-zero (404) when the ref doesn't exist — that's the
    // "branch deleted" outcome we want.
    expect(
      r.status,
      `branch ${checkout.branch} still exists on remote — finally{} did not delete it. ` +
        `stdout=${r.stdout?.slice(0, 200)} stderr=${r.stderr?.slice(0, 200)}`,
    ).not.toBe(0)
  })
})

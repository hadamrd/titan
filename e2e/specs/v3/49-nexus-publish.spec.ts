/**
 * 49-nexus-publish @real-commit — real Nexus/Artifactory publish round-trip
 * from a pipeline (#1229).
 *
 * Until now "Titan publishes to Nexus/Artifactory" was asserted only by a
 * Testcontainers component IT (NexusArtifactStoreIT) — never by a build a user
 * would actually run, and never verified independently of Titan's own download
 * API. This spec closes that gap.
 *
 * MECHANISM (justification, per the ticket's "pick one"): archiveArtifacts
 * routed to the Nexus backend. The rig's worker is booted with
 * TITAN_ARTIFACT_STORE=nexus (see rig/local/docker-compose.nexus.yml +
 * rig/local/README.md "Artifact store (Nexus)"), so an ordinary
 * archiveArtifacts step PUTs the blob into a Nexus raw hosted repo at the
 * store's layout `<buildId>/ARTIFACT/<name>` (NexusArtifactStore §Layout). We
 * pick this over a bespoke `mvn deploy` step because it exercises the exact
 * production code path (NexusArtifactStore.put) end-to-end with zero new
 * pipeline grammar, and because the independent oracle below reads the very
 * bytes that path wrote.
 *
 * INDEPENDENT VERIFICATION: after the rig build reaches SUCCESS we do NOT trust
 * Titan's own /api/v1/artifacts download. We HTTP GET straight at the Nexus
 * REST content endpoint
 *   {NEXUS_URL}/repository/{NEXUS_REPOSITORY}/{buildId}/ARTIFACT/{name}
 * with HTTP Basic auth, and assert the artifact exists with the expected
 * Content-Length and SHA-256. This is the "fetch from Nexus, not from Titan"
 * contract the ticket demands.
 *
 * ADVERSARIAL (acceptance criterion 4 — repo-policy behaviour): we PUT the same
 * coordinates to Nexus twice, directly, and assert the configured repo policy:
 *   - NEXUS_WRITE_POLICY=allow (snapshot-like default) → second PUT 2xx and the
 *     GET returns the second content (overwrite/converge);
 *   - NEXUS_WRITE_POLICY=allow_once|deny (release/immutable) → second PUT is
 *     refused (4xx) and the GET still returns the first content.
 * We also assert a never-published coordinate returns 404, so the happy-path
 * GET-200 can't be a false positive from a misconfigured "serve everything"
 * repo. The store-level immutability proof lives in NexusArtifactStoreIT
 * (republishSameCoordinatesToImmutableRepoIsRejected).
 *
 * Credentials/endpoint come from env (mirroring how R2 creds are handled — no
 * plaintext in the fixture):
 *   NEXUS_URL, NEXUS_REPOSITORY, NEXUS_USERNAME, NEXUS_PASSWORD,
 *   NEXUS_WRITE_POLICY (optional, default 'allow').
 *
 * Skip rules (deterministic):
 *   - LAYER2_RIG_AVAILABLE unset                          => skip
 *   - NEXUS_URL / creds unset                             => skip
 *   - `gh auth status` fails                              => skip (happy only)
 *   - rig /api/v1/builds not reachable (5s)               => skip (happy only)
 *   - cannot fetch Keycloak bearer                        => skip (happy only)
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
  process.env.TITAN_RIG_URL ?? process.env.LAYER2_RIG_URL ?? 'https://titan.test.example.com'

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

interface CheckoutHandle {
  dir: string
  branch: string
  cleanup: () => void
}

interface NexusCreds {
  url: string
  repository: string
  username: string
  password: string
  writePolicy: 'allow' | 'allow_once' | 'deny'
}

// ── env / preflight ────────────────────────────────────────────────────

function layer2Available(): boolean {
  return !!process.env.LAYER2_RIG_AVAILABLE
}

function nexusCreds(): NexusCreds | null {
  const url = process.env.NEXUS_URL
  const repository = process.env.NEXUS_REPOSITORY
  const username = process.env.NEXUS_USERNAME
  const password = process.env.NEXUS_PASSWORD
  if (!url || !repository || !username || !password) return null
  const policy = (process.env.NEXUS_WRITE_POLICY ?? 'allow').toLowerCase()
  const writePolicy =
    policy === 'allow_once' || policy === 'deny' ? (policy as 'allow_once' | 'deny') : 'allow'
  return { url: url.replace(/\/$/, ''), repository, username, password, writePolicy }
}

function ghAuthOk(): boolean {
  return spawnSync('gh', ['auth', 'status'], { encoding: 'utf8' }).status === 0
}

async function rigReachable(): Promise<boolean> {
  try {
    const ctrl = new AbortController()
    const t = setTimeout(() => ctrl.abort(), 5_000)
    const res = await fetch(`${RIG_BASE_URL}/api/v1/builds`, { signal: ctrl.signal })
    clearTimeout(t)
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

// ── nexus REST helpers (independent of Titan) ──────────────────────────

function basicAuth(c: NexusCreds): string {
  return 'Basic ' + Buffer.from(`${c.username}:${c.password}`).toString('base64')
}

function contentUrl(c: NexusCreds, storageRef: string): string {
  const segs = storageRef
    .split('/')
    .filter(Boolean)
    .map((s) => encodeURIComponent(s))
    .join('/')
  return `${c.url}/repository/${c.repository}/${segs}`
}

async function nexusGet(
  c: NexusCreds,
  storageRef: string,
): Promise<{ status: number; bytes: Buffer; contentLength: number | null }> {
  const res = await fetch(contentUrl(c, storageRef), { headers: { Authorization: basicAuth(c) } })
  const bytes = res.ok ? Buffer.from(await res.arrayBuffer()) : Buffer.alloc(0)
  const cl = res.headers.get('content-length')
  return { status: res.status, bytes, contentLength: cl !== null ? Number(cl) : null }
}

async function nexusPut(c: NexusCreds, storageRef: string, body: string): Promise<number> {
  const res = await fetch(contentUrl(c, storageRef), {
    method: 'PUT',
    headers: { Authorization: basicAuth(c), 'Content-Type': 'application/octet-stream' },
    body,
  })
  return res.status
}

async function nexusDelete(c: NexusCreds, storageRef: string): Promise<void> {
  try {
    await fetch(contentUrl(c, storageRef), {
      method: 'DELETE',
      headers: { Authorization: basicAuth(c) },
    })
  } catch {
    /* best-effort cleanup */
  }
}

// ── git / fixture-repo helpers ─────────────────────────────────────────

function runGit(cwd: string, args: string[]): void {
  const r = spawnSync('git', args, { cwd, encoding: 'utf8' })
  if (r.status !== 0) {
    throw new Error(
      `git ${args.join(' ')} failed in ${cwd}: status=${r.status} stderr=${r.stderr?.slice(0, 500) ?? ''}`,
    )
  }
}

function cloneAndCutBranch(branchPrefix: string, timestamp: string): CheckoutHandle {
  const tmp = mkdtempSync(join(tmpdir(), 'titan-l2-nexus-'))
  const branch = `${branchPrefix}-${timestamp}`
  execFileSync('gh', ['repo', 'clone', FIXTURE_REPO, tmp], { encoding: 'utf8' })
  runGit(tmp, ['config', 'user.email', 'spec-49@titan.test'])
  runGit(tmp, ['config', 'user.name', 'spec 49 nexus-publish'])
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

function writePipeline(dir: string, sentinelPath: string, sentinelContent: string): void {
  const pipelineFsPath = join(dir, '.titan', 'pipelines', 'nexus-publish.yml')
  mkdirSync(join(dir, '.titan', 'pipelines'), { recursive: true })
  // Single-stage pipeline: write a unique-per-run sentinel and archive it. With
  // TITAN_ARTIFACT_STORE=nexus on the rig worker, the archive lands in Nexus.
  writeFileSync(
    pipelineFsPath,
    [
      'stages:',
      '  - stage: Build',
      '    steps:',
      `      - sh: "printf %s '${sentinelContent}' > ${sentinelPath}"`,
      `      - archiveArtifacts: { artifacts: "${sentinelPath}" }`,
      '',
    ].join('\n'),
  )
}

function commitAndPush(checkout: CheckoutHandle, message: string): string {
  runGit(checkout.dir, ['add', '-A'])
  runGit(checkout.dir, ['commit', '-m', message])
  runGit(checkout.dir, ['push', '-u', 'origin', checkout.branch])
  return execFileSync('git', ['rev-parse', 'HEAD'], { cwd: checkout.dir, encoding: 'utf8' }).trim()
}

function deleteRemoteBranch(branch: string): void {
  spawnSync('gh', ['api', '-X', 'DELETE', `/repos/${FIXTURE_REPO}/git/refs/heads/${branch}`], {
    encoding: 'utf8',
  })
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

function sha256Hex(buf: Buffer | string): string {
  return createHash('sha256').update(buf).digest('hex')
}

// ── the specs ─────────────────────────────────────────────────────────

test.describe('@real-commit v3 nexus-publish', () => {
  test.beforeAll(() => {
    if (!layer2Available()) {
      // eslint-disable-next-line no-console
      console.log(
        '[spec-49] LAYER2_RIG_AVAILABLE not set — skipping real Nexus publish roundtrip. ' +
          'Set LAYER2_RIG_AVAILABLE=1, point TITAN_RIG_URL at the rig, and export ' +
          'NEXUS_URL/NEXUS_REPOSITORY/NEXUS_USERNAME/NEXUS_PASSWORD to run.',
      )
    } else if (!nexusCreds()) {
      // eslint-disable-next-line no-console
      console.log('[spec-49] NEXUS_* env unset — skipping; see rig/local/README.md.')
    }
  })

  test('happy: rig build → archiveArtifacts → independent Nexus GET asserts artifact + SHA-256', async () => {
    test.skip(!layer2Available(), 'LAYER2_RIG_AVAILABLE unset; local-dev posture')
    const creds = nexusCreds()
    test.skip(!creds, 'NEXUS_* env unset; cannot verify the published artifact independently')
    test.setTimeout(20 * 60_000)

    if (!ghAuthOk()) test.skip(true, 'gh CLI not authenticated; cannot push to fixture repo')
    if (!(await rigReachable())) test.skip(true, `rig not reachable at ${RIG_BASE_URL}`)
    const bearer = await tryBearerToken()
    if (!bearer) test.skip(true, `cannot fetch Keycloak bearer for ${ENV.keycloakUrl}`)

    const c = creds!
    const timestamp = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`
    const sentinelPath = `nexus-sentinel-${timestamp}.txt`
    const sentinelContent = `titan-spec-49-${timestamp}-${Math.random().toString(36).slice(2)}`
    const expectedSha = sha256Hex(sentinelContent)
    const expectedLen = Buffer.byteLength(sentinelContent, 'utf8')

    const checkout = cloneAndCutBranch('spec-49/nexus-publish', timestamp)
    let storageRef = ''
    try {
      writePipeline(checkout.dir, sentinelPath, sentinelContent)
      const sha = commitAndPush(checkout, `spec 49 nexus-publish ${timestamp}`)
      // eslint-disable-next-line no-console
      console.log(`[spec-49] pushed branch=${checkout.branch} sha=${sha}`)

      // Wait for the rig to enqueue + run the build.
      let build: BuildDto | null = null
      const appeared = Date.now() + BUILD_APPEARED_DEADLINE_MS
      while (Date.now() < appeared) {
        build = await findBuildByHeadSha(bearer!, sha)
        if (build) break
        await new Promise((r) => setTimeout(r, 4_000))
      }
      expect(build, `rig /api/v1/builds?search=${sha} produced no build in time`).not.toBeNull()

      let final: BuildDto | null = build
      const terminal = Date.now() + BUILD_TERMINAL_DEADLINE_MS
      while (Date.now() < terminal) {
        final = await readBuild(bearer!, build!.id)
        if (final && TERMINAL.has(final.status)) break
        await new Promise((r) => setTimeout(r, 4_000))
      }
      expect(final && TERMINAL.has(final.status), `build ${build!.id} never reached terminal`).toBe(
        true,
      )
      expect(final!.status, `build ${build!.id} terminal=${final!.status}; expected SUCCESS`).toBe(
        'SUCCESS',
      )

      // Independent Nexus fetch — the store layout is `<buildId>/ARTIFACT/<name>`.
      storageRef = `${build!.id}/ARTIFACT/${sentinelPath}`
      const got = await nexusGet(c, storageRef)
      expect(
        got.status,
        `Nexus GET ${storageRef} returned HTTP ${got.status}; the archiveArtifacts step did not ` +
          `publish to Nexus (is the rig worker booted with TITAN_ARTIFACT_STORE=nexus?).`,
      ).toBe(200)
      expect(got.contentLength, `Nexus Content-Length mismatch for ${storageRef}`).toBe(expectedLen)
      const gotSha = sha256Hex(got.bytes)
      expect(
        gotSha,
        `SHA-256 mismatch: Nexus bytes=${gotSha} but pipeline wrote=${expectedSha}.`,
      ).toBe(expectedSha)

      // Guard against a "serve everything" false positive: an unpublished sibling 404s.
      const bogus = await nexusGet(c, `${build!.id}/ARTIFACT/never-published-${timestamp}.txt`)
      expect(bogus.status, 'Nexus served a never-published path — repo is not addressing by path').toBe(
        404,
      )

      // eslint-disable-next-line no-console
      console.log(`[spec-49] SUMMARY ok build=${build!.id} ref=${storageRef} sha256=${gotSha}`)
    } finally {
      if (storageRef) await nexusDelete(c, storageRef)
      deleteRemoteBranch(checkout.branch)
      checkout.cleanup()
    }
  })

  test('adversarial: re-publish same coordinates behaves per the repo write policy', async () => {
    test.skip(!layer2Available(), 'LAYER2_RIG_AVAILABLE unset; local-dev posture')
    const creds = nexusCreds()
    test.skip(!creds, 'NEXUS_* env unset')
    test.setTimeout(2 * 60_000)

    const c = creds!
    const timestamp = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`
    // A synthetic coordinate we own — does not collide with any build's layout.
    const ref = `spec-49-policy/${timestamp}/coord.txt`
    const first = `first-${timestamp}`
    const second = `second-${timestamp}`
    try {
      const put1 = await nexusPut(c, ref, first)
      expect(put1, `initial PUT ${ref} should succeed`).toBeGreaterThanOrEqual(200)
      expect(put1).toBeLessThan(300)

      const put2 = await nexusPut(c, ref, second)
      const after = await nexusGet(c, ref)

      if (c.writePolicy === 'allow') {
        // Snapshot-like: redeploy converges, the latest bytes win.
        expect(put2, `overwrite PUT to an 'allow' repo should succeed`).toBeGreaterThanOrEqual(200)
        expect(put2).toBeLessThan(300)
        expect(after.status).toBe(200)
        expect(sha256Hex(after.bytes)).toBe(sha256Hex(second))
      } else {
        // Release/immutable: redeploy refused, the original bytes remain.
        expect(
          put2,
          `redeploy to a '${c.writePolicy}' (immutable) repo must be refused, got ${put2}`,
        ).toBeGreaterThanOrEqual(400)
        expect(after.status).toBe(200)
        expect(sha256Hex(after.bytes)).toBe(sha256Hex(first))
      }
      // eslint-disable-next-line no-console
      console.log(`[spec-49] policy=${c.writePolicy} put1=${put1} put2=${put2} OK`)
    } finally {
      await nexusDelete(c, ref)
    }
  })
})

/**
 * 54-r2-independent-verify @real-commit — prove `archiveArtifacts` physically
 * lands the bytes in Cloudflare R2, read straight from the bucket with an
 * INDEPENDENT S3 client (NOT Titan's API) (#1226).
 *
 * Why this exists (the gap spec-53 leaves open):
 *   spec-53 verifies the artifact through Titan's own API
 *   (`GET /api/v1/builds/{id}/artifacts` + `/artifacts/{id}/download`). That
 *   proves the API reads back what it *thinks* it wrote — it does NOT prove the
 *   bytes physically exist in the R2 bucket. If `S3ArtifactStore` silently wrote
 *   to a DB fallback, or wrote a truncated/wrong object, spec-53 still passes.
 *
 *   This spec closes that hole: it opens the R2 bucket with a fresh
 *   `@aws-sdk/client-s3` client built from the rig's R2 creds, lists the build's
 *   artifact key prefix (`<buildId>/ARTIFACT/`), and asserts the object exists
 *   with byte-length + SHA-256 equal to the sentinel the pipeline archived.
 *
 * The R2 object layout is `S3ArtifactStore`'s storageRef: `<buildId>/<kind>/<name>`
 * → for an archived artifact, `<buildId>/ARTIFACT/<sentinel>` (see
 * titan-extensions/titan-artifact-s3/.../S3ArtifactStore#put).
 *
 * What it does (per build):
 *   1. Clones hadamrd/titan-e2e-fixture, cuts a unique timestamped branch.
 *   2. Writes .titan/pipelines/archive-artifacts.yml — emits a sentinel whose
 *      bytes are unique per run (so the SHA-256 oracle is independent).
 *   3. Real-commits + pushes; polls the rig for the build → SUCCESS. Every
 *      /builds?search=<sha> hit is verified against the detail endpoint's
 *      triggerMeta.commitSha before adoption (#118 — never trust items[0]).
 *   4. Connects to R2 DIRECTLY and asserts: object exists under the build's
 *      prefix, ContentLength == sentinel length, SHA-256(bytes) == sentinel SHA.
 *   5. finally{} deletes the R2 objects under the prefix AND — only when the
 *      job provably belongs to this run (its name carries the unique branch
 *      marker; see deleteJobIfOwned, #118) — the job row
 *      (`DELETE /api/v1/jobs/{jobId}` cascades to its builds) AND the branch.
 *
 * Test matrix:
 *   - happy: one build → independent R2 byte + SHA-256 assertion.
 *   - adversarial: a SECOND build with DIFFERENT bytes lands a DIFFERENT R2
 *     key + SHA (no collision, no stale read of the first build's object).
 *
 * Skip rules (deterministic — skips clean, never fails, when prereqs absent):
 *   - LAYER2_RIG_AVAILABLE unset                  => skip
 *   - R2 creds absent from the e2e env            => skip  (the #1226 ask)
 *   - `gh auth status` fails                       => skip
 *   - rig /api/v1/builds not reachable (5s)        => skip
 *   - cannot fetch Keycloak bearer                 => skip
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
  /** Present on the DETAIL endpoint; carries the triggering commit SHA. */
  triggerMeta?: { commitSha?: string | null } | null
}

/** Job detail shape — only the bits the ownership guard reads. */
interface JobDto {
  id: number
  fullName?: string | null
  displayName?: string | null
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

/** The rig's R2 connection, read from the e2e env. `null` => skip. */
interface R2Env {
  endpoint: string
  bucket: string
  region: string
  accessKeyId: string
  secretAccessKey: string
}

/** Minimal structural typing of the bits of `@aws-sdk/client-s3` we use. */
interface R2Client {
  listKeys(prefix: string): Promise<string[]>
  head(key: string): Promise<{ contentLength: number }>
  getBytes(key: string): Promise<Buffer>
  deletePrefix(prefix: string): Promise<void>
  close(): void
}

// ── env / preflight ────────────────────────────────────────────────────

function layer2Available(): boolean {
  return !!process.env.LAYER2_RIG_AVAILABLE
}

/**
 * Resolve the rig's R2 connection from the e2e env. Accepts both the
 * `TITAN_R2_*` convention and the chart's `ARTIFACTS_*` secret names so the
 * spec runs whether you export it from rig/local/.env or straight from the k3s
 * Secret. Returns `null` (→ skip) when any required field is missing.
 */
function r2Env(): R2Env | null {
  const endpoint = process.env.TITAN_R2_ENDPOINT ?? process.env.ARTIFACTS_ENDPOINT
  const accessKeyId = process.env.TITAN_R2_ACCESS_KEY ?? process.env.ARTIFACTS_ACCESS_KEY
  const secretAccessKey = process.env.TITAN_R2_SECRET_KEY ?? process.env.ARTIFACTS_SECRET_KEY
  if (!endpoint || !accessKeyId || !secretAccessKey) return null
  return {
    endpoint,
    bucket: process.env.TITAN_R2_BUCKET ?? process.env.ARTIFACTS_BUCKET ?? 'titan-artifacts',
    region: process.env.TITAN_R2_REGION ?? process.env.ARTIFACTS_REGION ?? 'auto',
    accessKeyId,
    secretAccessKey,
  }
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

// ── independent R2 client ──────────────────────────────────────────────

/**
 * Build an independent S3 client against R2. The module specifier is held in a
 * variable so `tsc` does not try to resolve `@aws-sdk/client-s3` at type-check
 * time — the dependency is only needed in the real-commit env where this spec
 * actually runs (everywhere else it skips before reaching here). When the dep
 * is genuinely absent at runtime the import throws and the test skips.
 */
async function openR2(env: R2Env): Promise<R2Client | null> {
  let mod: any
  try {
    const specifier = '@aws-sdk/client-s3'
    mod = await import(specifier)
  } catch {
    return null
  }
  const {
    S3Client,
    ListObjectsV2Command,
    HeadObjectCommand,
    GetObjectCommand,
    DeleteObjectsCommand,
  } = mod
  // forcePathStyle: R2 (like MinIO) is happiest with path-style addressing, the
  // same choice S3ArtifactStoreProvider makes on the write side.
  const client = new S3Client({
    endpoint: env.endpoint,
    region: env.region,
    credentials: { accessKeyId: env.accessKeyId, secretAccessKey: env.secretAccessKey },
    forcePathStyle: true,
  })
  const listKeys = async (prefix: string): Promise<string[]> => {
    const out = await client.send(new ListObjectsV2Command({ Bucket: env.bucket, Prefix: prefix }))
    return ((out.Contents ?? []) as Array<{ Key?: string }>)
      .map((o) => o.Key)
      .filter((k): k is string => typeof k === 'string')
  }
  return {
    listKeys,
    async head(key: string): Promise<{ contentLength: number }> {
      const out = await client.send(new HeadObjectCommand({ Bucket: env.bucket, Key: key }))
      return { contentLength: Number(out.ContentLength ?? -1) }
    },
    async getBytes(key: string): Promise<Buffer> {
      const out = await client.send(new GetObjectCommand({ Bucket: env.bucket, Key: key }))
      const bytes = await out.Body.transformToByteArray()
      return Buffer.from(bytes)
    },
    async deletePrefix(prefix: string): Promise<void> {
      const keys = await listKeys(prefix)
      if (keys.length === 0) return
      await client.send(
        new DeleteObjectsCommand({
          Bucket: env.bucket,
          Delete: { Objects: keys.map((Key: string) => ({ Key })) },
        }),
      )
    },
    close(): void {
      client.destroy?.()
    },
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
  const tmp = mkdtempSync(join(tmpdir(), 'titan-r2-verify-'))
  const branch = `${branchPrefix}-${timestamp}`
  execFileSync('gh', ['repo', 'clone', FIXTURE_REPO, tmp], { encoding: 'utf8' })
  runGit(tmp, ['config', 'user.email', 'spec-54@titan.test'])
  runGit(tmp, ['config', 'user.name', 'spec 54 r2-independent-verify'])
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

function pipelineYamlHappy(sentinelPath: string, sentinelContent: string): string {
  return [
    'stages:',
    '  - stage: Build',
    '    steps:',
    `      - sh: "printf %s '${sentinelContent}' > ${sentinelPath}"`,
    `      - archiveArtifacts: { artifacts: "${sentinelPath}" }`,
    '',
  ].join('\n')
}

function commitAndPush(checkout: CheckoutHandle, pipelineYaml: string, message: string): string {
  mkdirSync(join(checkout.dir, '.titan', 'pipelines'), { recursive: true })
  writeFileSync(join(checkout.dir, '.titan', 'pipelines', 'archive-artifacts.yml'), pipelineYaml)
  writeFileSync(join(checkout.dir, '.titan', 'pipelines', '.run-marker'), message + '\n')
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

/**
 * Find the build triggered by OUR pushed commit — and only ours. The list
 * item DTO does not carry the commit SHA, so every search hit is verified
 * against its DETAIL endpoint (`triggerMeta.commitSha`) before being
 * returned (#118: `items[0]` used to be trusted blind — if the `search`
 * matcher ever loosened, the spec would adopt and later DELETE someone
 * else's build/job). A hit whose SHA does not equal `sha` is skipped.
 */
async function findBuildByHeadSha(bearer: string, sha: string): Promise<BuildDto | null> {
  const url = new URL(`${RIG_BASE_URL}/api/v1/builds`)
  url.searchParams.set('search', sha)
  url.searchParams.set('limit', '10')
  const res = await fetch(url.toString(), {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  if (!res.ok) return null
  const page = (await res.json()) as BuildsPage
  for (const candidate of page.items) {
    const detail = await readBuild(bearer, candidate.id)
    if (detail?.triggerMeta?.commitSha === sha) return detail
    // eslint-disable-next-line no-console
    console.warn(
      `[spec-54] search=${sha} returned build ${candidate.id} whose ` +
        `triggerMeta.commitSha=${detail?.triggerMeta?.commitSha ?? 'absent'} — NOT ours, skipping`,
    )
  }
  return null
}

async function readBuild(bearer: string, id: number): Promise<BuildDto | null> {
  const res = await fetch(`${RIG_BASE_URL}/api/v1/builds/${id}`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  if (!res.ok) return null
  return (await res.json()) as BuildDto
}

/**
 * Best-effort row cleanup, scoped to PROVABLY-OWN rows (#118): deleting the
 * job cascades to its builds (ON DELETE CASCADE), so we must never delete a
 * job this spec did not create. The job the rig discovers for our push is
 * named after the branch we cut (`spec-54/<label>-<timestamp>` — unique per
 * run); the guard reads the job and only DELETEs when its fullName or
 * displayName carries that unique branch marker. Anything else (a shared /
 * pre-existing repo job) is left in place with a loud note — losing one row
 * of cleanup beats deleting someone else's job.
 *
 * Fetches a fresh bearer: the token minted at test start may be past
 * Keycloak's 5-min TTL by teardown time (same reasoning as the poll-loop
 * token factory, critic #1231).
 */
async function deleteJobIfOwned(jobId: number | undefined, branchMarker: string): Promise<void> {
  if (jobId === undefined) return
  try {
    const bearer = await fetchBearerToken(ENV)
    const res = await fetch(`${RIG_BASE_URL}/api/v1/jobs/${jobId}`, {
      headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
    })
    if (!res.ok) return
    const job = (await res.json()) as JobDto
    const name = `${job.fullName ?? ''} ${job.displayName ?? ''}`
    if (!name.includes(branchMarker)) {
      // eslint-disable-next-line no-console
      console.warn(
        `[spec-54] job ${jobId} (fullName="${job.fullName ?? ''}") does not carry the ` +
          `run's branch marker "${branchMarker}" — it pre-exists or is shared. ` +
          `Skipping delete (never remove rows this spec did not create).`,
      )
      return
    }
    await fetch(`${RIG_BASE_URL}/api/v1/jobs/${jobId}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${bearer}` },
    })
  } catch {
    /* best-effort */
  }
}

function sha256Hex(buf: Buffer | string): string {
  return createHash('sha256').update(buf).digest('hex')
}

// ── build driver ───────────────────────────────────────────────────────

interface DrivenBuild {
  build: BuildDto
  sha: string
  /** The unique branch this run cut — the ownership marker for teardown. */
  branch: string
  sentinelPath: string
  content: string
  expectedSha: string
  prefix: string
  cleanup: () => void
}

/**
 * Push a one-stage `archiveArtifacts` pipeline with unique-per-run bytes, wait
 * for the rig build to reach SUCCESS, and return its coordinates + an R2 key
 * prefix. The caller owns the R2 + DB cleanup via the returned `cleanup`.
 */
// Takes a token FACTORY, not a raw token: a happy+adversarial run can span >24min
// (two sequential builds, each up to BUILD_APPEARED + BUILD_TERMINAL deadlines), well past
// Keycloak's 5-min access-token TTL. Re-fetching per poll iteration avoids a silent mid-run
// 401 that would surface as a misleading "did not reach terminal" timeout (critic #1231 sev2).
async function driveSuccessfulBuild(
  getBearer: () => Promise<string>,
  label: string,
): Promise<DrivenBuild> {
  const timestamp = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`
  const sentinelPath = `sentinel-${timestamp}.txt`
  const content = `titan-spec-54-${label}-${timestamp}-${Math.random().toString(36).slice(2)}`
  const expectedSha = sha256Hex(content)
  const checkout = cloneAndCutBranch(`spec-54/${label}`, timestamp)

  const sha = commitAndPush(
    checkout,
    pipelineYamlHappy(sentinelPath, content),
    `spec 54 r2-verify ${label} ${timestamp}`,
  )
  // eslint-disable-next-line no-console
  console.log(`[spec-54] pushed branch=${checkout.branch} sha=${sha}`)

  let build: BuildDto | null = null
  const appearedDeadline = Date.now() + BUILD_APPEARED_DEADLINE_MS
  while (Date.now() < appearedDeadline) {
    build = await findBuildByHeadSha(await getBearer(), sha)
    if (build) break
    await new Promise((r) => setTimeout(r, 4_000))
  }
  expect(
    build,
    `rig /api/v1/builds?search=${sha} did not return a build within ${BUILD_APPEARED_DEADLINE_MS}ms`,
  ).not.toBeNull()

  let final: BuildDto | null = build
  const terminalDeadline = Date.now() + BUILD_TERMINAL_DEADLINE_MS
  while (Date.now() < terminalDeadline) {
    final = await readBuild(await getBearer(), build!.id)
    if (final && TERMINAL.has(final.status)) break
    await new Promise((r) => setTimeout(r, 4_000))
  }
  expect(
    final && TERMINAL.has(final.status),
    `build ${build!.id} did not reach terminal within ${BUILD_TERMINAL_DEADLINE_MS}ms (last=${final?.status})`,
  ).toBe(true)
  expect(final!.status, `build ${build!.id} terminal=${final!.status}; expected SUCCESS`).toBe(
    'SUCCESS',
  )

  return {
    build: final!,
    sha,
    branch: checkout.branch,
    sentinelPath,
    content,
    expectedSha,
    prefix: `${final!.id}/${'ARTIFACT'}/`,
    cleanup: () => {
      deleteRemoteBranch(checkout.branch)
      checkout.cleanup()
    },
  }
}

// ── the specs ─────────────────────────────────────────────────────────

test.describe('@real-commit v3 r2-independent-verify', () => {
  test.beforeAll(() => {
    if (!layer2Available() || !r2Env()) {
      // eslint-disable-next-line no-console
      console.log(
        '[spec-54] LAYER2_RIG_AVAILABLE and/or R2 creds (TITAN_R2_*/ARTIFACTS_*) absent — ' +
          'skipping independent-R2 verification. Export the rig R2 endpoint + access/secret keys to run.',
      )
    }
  })

  test('happy: archiveArtifacts → object physically in R2 with matching length + SHA-256', async () => {
    test.skip(!layer2Available(), 'LAYER2_RIG_AVAILABLE unset; local-dev posture')
    const r2cfg = r2Env()
    test.skip(!r2cfg, 'R2 creds absent from e2e env; cannot read the bucket independently')
    test.setTimeout(20 * 60_000)

    if (!ghAuthOk()) test.skip(true, 'gh CLI not authenticated; cannot push to fixture repo')
    if (!(await rigReachable())) test.skip(true, `Layer-2 rig not reachable at ${RIG_BASE_URL}`)
    const bearer = await tryBearerToken()
    if (!bearer) test.skip(true, `cannot fetch Keycloak bearer for ${ENV.keycloakUrl}`)

    const r2 = await openR2(r2cfg!)
    test.skip(!r2, '@aws-sdk/client-s3 not installed in e2e env; run `pnpm add -D @aws-sdk/client-s3`')

    const driven = await driveSuccessfulBuild(() => fetchBearerToken(ENV), 'happy')
    try {
      // Read the bucket DIRECTLY — this is the whole point of the ticket.
      const keys = await r2!.listKeys(driven.prefix)
      const objKey = keys.find((k) => k.endsWith(driven.sentinelPath))
      expect(
        objKey,
        `no R2 object under ${driven.prefix} ending in ${driven.sentinelPath}. ` +
          `archiveArtifacts reported SUCCESS but the bytes are NOT in the bucket — ` +
          `likely a silent DB/fs fallback. Observed keys=${JSON.stringify(keys)}`,
      ).toBeDefined()

      const expectedLen = Buffer.byteLength(driven.content, 'utf8')
      const meta = await r2!.head(objKey!)
      expect(
        meta.contentLength,
        `R2 object ${objKey} ContentLength=${meta.contentLength}, expected ${expectedLen} — ` +
          `the publish truncated or padded the object.`,
      ).toBe(expectedLen)

      const bytes = await r2!.getBytes(objKey!)
      const gotSha = sha256Hex(bytes)
      expect(
        gotSha,
        `SHA-256 of the R2 object (${gotSha}) != SHA-256 of the archived sentinel ` +
          `(${driven.expectedSha}). The bytes in the bucket are not the bytes the build wrote.`,
      ).toBe(driven.expectedSha)

      // eslint-disable-next-line no-console
      console.log(
        `[spec-54] OK build=${driven.build.id} r2key=${objKey} len=${meta.contentLength} sha256=${gotSha}`,
      )
    } finally {
      await r2!.deletePrefix(driven.prefix).catch(() => {})
      r2!.close()
      await deleteJobIfOwned(driven.build.jobId, driven.branch)
      driven.cleanup()
    }
  })

  test('adversarial: a second build with different bytes lands a different R2 key + SHA (no collision / no stale read)', async () => {
    test.skip(!layer2Available(), 'LAYER2_RIG_AVAILABLE unset; local-dev posture')
    const r2cfg = r2Env()
    test.skip(!r2cfg, 'R2 creds absent from e2e env; cannot read the bucket independently')
    test.setTimeout(30 * 60_000)

    if (!ghAuthOk()) test.skip(true, 'gh CLI not authenticated; cannot push to fixture repo')
    if (!(await rigReachable())) test.skip(true, `Layer-2 rig not reachable at ${RIG_BASE_URL}`)
    const bearer = await tryBearerToken()
    if (!bearer) test.skip(true, `cannot fetch Keycloak bearer for ${ENV.keycloakUrl}`)

    const r2 = await openR2(r2cfg!)
    test.skip(!r2, '@aws-sdk/client-s3 not installed in e2e env; run `pnpm add -D @aws-sdk/client-s3`')

    const first = await driveSuccessfulBuild(() => fetchBearerToken(ENV), 'adv-a')
    let secondRef: DrivenBuild | null = null
    try {
      const second = await driveSuccessfulBuild(() => fetchBearerToken(ENV), 'adv-b')
      secondRef = second

      const firstKeys = await r2!.listKeys(first.prefix)
      const firstKey = firstKeys.find((k) => k.endsWith(first.sentinelPath))
      const secondKeys = await r2!.listKeys(second.prefix)
      const secondKey = secondKeys.find((k) => k.endsWith(second.sentinelPath))
      expect(firstKey, `first build object missing under ${first.prefix}`).toBeDefined()
      expect(secondKey, `second build object missing under ${second.prefix}`).toBeDefined()

      // Different builds MUST occupy different keys (the buildId prefix differs).
      expect(
        secondKey,
        `second build reused the first build's R2 key (${firstKey}) — buildId prefix collision.`,
      ).not.toBe(firstKey)

      const firstSha = sha256Hex(await r2!.getBytes(firstKey!))
      const secondSha = sha256Hex(await r2!.getBytes(secondKey!))
      // Each object holds its OWN sentinel — proves no stale read / no overwrite.
      expect(firstSha, 'first R2 object SHA drifted from its sentinel').toBe(first.expectedSha)
      expect(secondSha, 'second R2 object SHA drifted from its sentinel').toBe(second.expectedSha)
      expect(
        secondSha,
        `the two builds wrote identical SHA-256 (${secondSha}) despite different bytes — ` +
          `the second read is stale (served the first object).`,
      ).not.toBe(firstSha)

      // eslint-disable-next-line no-console
      console.log(
        `[spec-54] adversarial OK k1=${firstKey} sha1=${firstSha} k2=${secondKey} sha2=${secondSha}`,
      )
    } finally {
      await r2!.deletePrefix(first.prefix).catch(() => {})
      if (secondRef) await r2!.deletePrefix(secondRef.prefix).catch(() => {})
      r2!.close()
      await deleteJobIfOwned(first.build.jobId, first.branch)
      if (secondRef) await deleteJobIfOwned(secondRef.build.jobId, secondRef.branch)
      first.cleanup()
      if (secondRef) secondRef.cleanup()
    }
  })
})

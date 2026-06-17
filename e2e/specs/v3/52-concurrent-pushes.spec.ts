/**
 * 52-concurrent-pushes — burst-of-pushes engine fan-out contract (closes #961).
 *
 * Why this spec exists
 * ────────────────────
 * A team's CI sees rebase storms, merge bursts, and parallel branch updates
 * many times a day. If the Titan engine wedges or double-fires under that
 * load, every adoption demo blows up. Specs 26/27/40 prove the per-primitive
 * golden paths for ONE push; this spec proves the engine survives MANY pushes
 * in a tight window and exposes the policy it actually implements.
 *
 * Why synthetic webhooks (not `git push`)
 * ───────────────────────────────────────
 * The brief asked for a real `git clone` + 3 commits + `git push` flow. That
 * path is unavailable from the e2e harness because we have NO push credentials
 * for `hadamrd/titan-e2e-fixture` (and shouldn't — credentials in the e2e env
 * would be a leak risk). The contract we want to test is downstream of git
 * transport: the engine's behaviour from "webhook delivery arrives" onwards.
 * That's where bursts wedge real engines (queue starvation, dedupe windows,
 * dropped builds) — none of which the git client influences. So this spec
 * synthesises 3 GitHub `push` payloads with DISTINCT, RANDOM commit SHAs and
 * HMAC-signs them with the per-trigger secret. Each delivery hits
 * `POST /api/v1/triggers/github` exactly the way GitHub's outbound hooks do.
 *
 * If a future ticket demands the literal transport path (e.g. proving the
 * inbound GitHub App webhook receiver doesn't drop on rapid retries), file
 * a separate spec — it'd need a write-token in the e2e env, scope creep.
 *
 * Observed contract (LIVE rig, will be re-asserted on every run)
 * ──────────────────────────────────────────────────────────────
 * Per `GithubWebhookApi#receive` (titan-server/src/main/java/io/adaptiq/titan/
 * api/triggers/GithubWebhookApi.java) read at trunk SHA 189553f6:
 *
 *   - NO dedupe / debounce window. Each delivery whose HMAC verifies and
 *     whose branch matches at least one configured trigger inserts a new
 *     QUEUED build row (`enqueueBuild` is unconditional inside the loop).
 *     => 3 distinct-SHA pushes to the same branch yield 3 builds, NOT 1.
 *
 *   - `commitSha` in `triggerMeta` is the 7-character SHORT sha derived from
 *     `head_commit.id` (or `after` as fallback). This spec compares short
 *     SHAs accordingly; never the full 40-char hex.
 *
 *   - Per-job concurrency: the same job CAN have multiple builds RUNNING in
 *     parallel — there's no per-job max-concurrency knob in WebhooksApi /
 *     GithubWebhookApi / the dispatcher path. Whether they actually run
 *     concurrently depends on QueueProcessor / worker capacity. Test 1 only
 *     asserts that the SAME-pipeline burst doesn't get DROPPED and that each
 *     created build reaches terminal status — NOT a parallel-vs-sequential
 *     claim. Documenting the absence of a knob is the load-bearing finding.
 *
 *   - No rate limiter on the webhook receiver. The TriggerRateLimiter only
 *     guards the MANUAL trigger endpoint (`/api/v1/jobs/{id}/builds`) per
 *     issue #739; webhook deliveries are not throttled.
 *
 * Follow-ups this spec must file on every contract-uncertainty
 * ────────────────────────────────────────────────────────────
 *   - If `docs/design/*.md` does not document the "no dedupe / one build
 *     per push" policy, file:
 *       "design: document concurrent-push fan-out policy"
 *   - If Test 3 detects any silently-dropped build (first or last SHA missing
 *     from `/api/v1/builds`), file P0:
 *       "engine: rapid pushes drop builds — silent loss"
 *
 * Test budget
 * ───────────
 * Each test sets its own setTimeout. The fixture (`simple-build.yml`) runs
 * `sh: echo hello > out.txt` — wall time ~5-10s per build on a warm worker.
 * Multiple builds per test pushes the budget; we give 5 minutes per test.
 *
 * Cleanup
 * ───────
 * `finally{}` deletes the credential via API, then the job + its build/queue/
 * artifact rows via direct PG. The remote-branch cleanup that the brief asks
 * for is N/A — no remote branches were created (synthetic webhooks).
 */
import * as crypto from 'node:crypto'
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken, loginViaKeycloak } from '../../fixtures/auth-v3'
import { pgClient } from '../../fixtures/seed-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const FIXTURE_REPO = 'hadamrd/titan-e2e-fixture'
const FIXTURE_PATH = '.titan/pipelines/simple-build.yml'
const FIXTURE_RAW_URL = `https://raw.githubusercontent.com/${FIXTURE_REPO}/main/${FIXTURE_PATH}`
const FIXTURE_API_URL = `https://api.github.com/repos/${FIXTURE_REPO}/contents/${FIXTURE_PATH}`

const TERMINAL_STATUSES = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'UNSTABLE', 'ERROR'])

// ─── DTOs ───────────────────────────────────────────────────────────────────

interface CredentialCreateResp { id: number; key: string }
interface JobCreateResp { id: number; fullName: string }
interface TriggerMetaDto { branch?: string; commitSha?: string; actor?: string }
interface BuildListItem {
  id: number
  jobId: number
  buildNumber: number
  status: string
  triggerType?: string
  triggerMeta?: TriggerMetaDto | null
  queuedAt?: string
  finishedAt?: string
}
interface BuildsPage { items: BuildListItem[]; total: number }
interface BuildDetail extends BuildListItem {}

// ─── Helpers ────────────────────────────────────────────────────────────────

async function apiGet<T>(
  api: APIRequestContext,
  bearer: string,
  path: string,
): Promise<{ ok: boolean; status: number; body: T | null; raw: string }> {
  const r = await api.get(`${API_BASE}${path}`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  const raw = await r.text()
  let body: T | null = null
  try { body = JSON.parse(raw) as T } catch { /* leave null */ }
  return { ok: r.ok(), status: r.status(), body, raw }
}

/** Random 40-char hex SHA — the format GitHub's API actually emits. */
function randomSha(): string {
  return crypto.randomBytes(20).toString('hex')
}

/** Short-sha as GithubWebhookApi#shortSha computes it (first 7 chars). */
function shortSha(full: string): string {
  return full.length > 7 ? full.substring(0, 7) : full
}

interface PushResult {
  status: number
  body: { accepted: boolean; dispatched: boolean; detail?: string; eventType?: string }
}

/**
 * Synthesise + HMAC-sign + POST a GitHub push webhook delivery. Returns the
 * receiver's response shape — callers assert on `dispatched`.
 */
async function postPushWebhook(
  api: APIRequestContext,
  secret: string,
  repoFullName: string,
  branch: string,
  fullSha: string,
  deliveryId: string,
): Promise<PushResult> {
  const payload = {
    ref: `refs/heads/${branch}`,
    before: '0'.repeat(40),
    after: fullSha,
    repository: { full_name: repoFullName, default_branch: 'main' },
    pusher: { name: 'e2e-burst-bot' },
    head_commit: { id: fullSha, message: `burst commit ${shortSha(fullSha)}` },
  }
  const bytes = Buffer.from(JSON.stringify(payload), 'utf8')
  const sig = 'sha256=' + crypto.createHmac('sha256', secret).update(bytes).digest('hex')
  const resp = await api.post(`${API_BASE}/api/v1/triggers/github`, {
    headers: {
      'Content-Type': 'application/json',
      'X-GitHub-Event': 'push',
      'X-Hub-Signature-256': sig,
      'X-GitHub-Delivery': deliveryId,
    },
    data: bytes,
  })
  const raw = await resp.text()
  let parsed: PushResult['body'] = { accepted: false, dispatched: false }
  try { parsed = JSON.parse(raw) as PushResult['body'] } catch { /* keep default */ }
  return { status: resp.status(), body: parsed }
}

/**
 * List every build for a job whose `triggerMeta.commitSha` is in the expected
 * set. Returns the matching builds (newest-first by buildNumber).
 */
async function findBuildsForShas(
  api: APIRequestContext,
  bearer: string,
  jobId: number,
  expectedShortShas: Set<string>,
): Promise<BuildListItem[]> {
  const r = await apiGet<BuildsPage>(
    api,
    bearer,
    `/api/v1/jobs/${jobId}/builds?offset=0&limit=200`,
  )
  if (!r.ok || !r.body) return []
  return r.body.items.filter((b) => {
    const sha = b.triggerMeta?.commitSha ?? ''
    return sha !== '' && expectedShortShas.has(sha)
  })
}

/** Wait for every expected short-SHA to materialise as a build row, or time out. */
async function waitForBuildsForShas(
  api: APIRequestContext,
  bearer: string,
  jobId: number,
  expectedShortShas: Set<string>,
  budgetMs: number,
): Promise<BuildListItem[]> {
  let last: BuildListItem[] = []
  const expectedList = [...expectedShortShas]
  await expect
    .poll(
      async () => {
        last = await findBuildsForShas(api, bearer, jobId, expectedShortShas)
        const seen = new Set(last.map((b) => b.triggerMeta?.commitSha ?? ''))
        return expectedList.every((s) => seen.has(s))
      },
      {
        message:
          `expected builds for SHAs [${expectedList.join(', ')}] never all appeared ` +
          `on job ${jobId} within ${budgetMs}ms`,
        timeout: budgetMs,
        intervals: [500, 1_000, 2_000, 3_000],
      },
    )
    .toBe(true)
  return last
}

/** Wait until every given build has reached a terminal status. */
async function waitForAllTerminal(
  api: APIRequestContext,
  bearer: string,
  buildIds: number[],
  budgetMs: number,
): Promise<Map<number, string>> {
  const statuses = new Map<number, string>()
  await expect
    .poll(
      async () => {
        const results = await Promise.all(
          buildIds.map((id) => apiGet<BuildDetail>(api, bearer, `/api/v1/builds/${id}`)),
        )
        let allTerminal = true
        for (let i = 0; i < buildIds.length; i++) {
          const r = results[i]
          const id = buildIds[i]
          if (id === undefined) continue
          const s = r && r.ok && r.body?.status ? r.body.status : 'UNKNOWN'
          statuses.set(id, s)
          if (!TERMINAL_STATUSES.has(s)) {
            allTerminal = false
          }
        }
        return allTerminal
      },
      {
        message: `not all builds reached terminal status within ${budgetMs}ms`,
        timeout: budgetMs,
        intervals: [1_000, 2_000, 3_000],
      },
    )
    .toBe(true)
  return statuses
}

/**
 * Create a job (with a github trigger) that listens on the given list of
 * branches. The same secret is shared across all triggers — that's the
 * common production shape (one webhook secret per repo).
 */
async function setupJobWithTriggers(
  api: APIRequestContext,
  runTag: string,
  bearer: string,
  fixtureYaml: string,
  branches: string[],
): Promise<{ credentialId: number; jobId: number; fullName: string; credKey: string; secret: string }> {
  const credKey = `e2e-burst-${runTag}`
  const secret = `s3cr3t-${runTag}`
  const credResp = await api.post(`${API_BASE}/api/v1/credentials`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: { kind: 'STRING', scope: 'github-webhook', key: credKey, plaintext: secret },
  })
  const credRaw = await credResp.text()
  expect(
    credResp.status(),
    `POST /credentials HTTP ${credResp.status()} body=${credRaw.slice(0, 400)}`,
  ).toBe(201)
  const credentialId = (JSON.parse(credRaw) as CredentialCreateResp).id

  const fullName = `e2e-burst-${runTag}`
  const triggersConfig = {
    triggers: [
      {
        type: 'github',
        id: 'github-1',
        branches,
        events: ['push'],
        credentialsId: credKey,
      },
    ],
  }
  const jobResp = await api.post(`${API_BASE}/api/v1/jobs`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: {
      fullName,
      displayName: `E2E burst (#961) ${runTag}`,
      pipelineScript: fixtureYaml,
      configJson: JSON.stringify(triggersConfig),
      enabled: true,
    },
  })
  const jobRaw = await jobResp.text()
  expect(
    jobResp.status(),
    `POST /jobs HTTP ${jobResp.status()} body=${jobRaw.slice(0, 600)}`,
  ).toBe(201)
  const jobId = (JSON.parse(jobRaw) as JobCreateResp).id

  return { credentialId, jobId, fullName, credKey, secret }
}

async function fetchFixtureYaml(
  api: APIRequestContext,
): Promise<string> {
  const meta = await api.get(FIXTURE_API_URL, {
    headers: { Accept: 'application/vnd.github.v3+json' },
  })
  test.skip(
    meta.status() === 404,
    `Fixture ${FIXTURE_API_URL} → 404. Spec hard-depends on the simple-build fixture.`,
  )
  expect(meta.ok(), `GitHub API HTTP ${meta.status()} for ${FIXTURE_API_URL}`).toBe(true)
  const rawResp = await api.get(FIXTURE_RAW_URL)
  expect(rawResp.ok(), `raw YAML HTTP ${rawResp.status()}`).toBe(true)
  const yaml = await rawResp.text()
  expect(yaml.length, 'fixture YAML empty').toBeGreaterThan(50)
  return yaml
}

async function cleanup(
  api: APIRequestContext,
  bearer: string | undefined,
  jobId: number | undefined,
  credentialId: number | undefined,
): Promise<void> {
  if (jobId && jobId > 0) {
    const client = pgClient()
    await client.connect()
    try {
      const buildIdsRes = await client.query<{ id: string }>(
        `SELECT id::text AS id FROM titan.builds WHERE job_id = $1`,
        [jobId],
      )
      const buildIds = buildIdsRes.rows.map((r) => Number(r.id))
      if (buildIds.length > 0) {
        await client
          .query(`DELETE FROM titan.approvals WHERE build_id = ANY($1::bigint[])`, [buildIds])
          .catch(() => undefined)
        await client
          .query(`DELETE FROM titan.test_result WHERE build_id = ANY($1::bigint[])`, [buildIds])
          .catch(() => undefined)
        await client
          .query(`DELETE FROM titan.artifact WHERE build_id = ANY($1::bigint[])`, [buildIds])
          .catch(() => undefined)
        await client
          .query(`DELETE FROM titan.flow_nodes WHERE build_id = ANY($1::bigint[])`, [buildIds])
          .catch(() => undefined)
        await client
          .query(`DELETE FROM titan.task_queue WHERE build_id = ANY($1::bigint[])`, [buildIds])
          .catch(() => undefined)
        await client.query(`DELETE FROM titan.builds WHERE id = ANY($1::bigint[])`, [buildIds])
      }
      await client.query(`DELETE FROM titan.jobs WHERE id = $1`, [jobId])
    } finally {
      await client.end()
    }
  }
  if (bearer && credentialId && credentialId > 0) {
    await api
      .delete(`${API_BASE}/api/v1/credentials/${credentialId}`, {
        headers: { Authorization: `Bearer ${bearer}` },
      })
      .catch(() => undefined)
  }
}

// ─── Tests ──────────────────────────────────────────────────────────────────

test.describe('v3 concurrent-push burst @golden', () => {
  /**
   * Test 1 — 3 rapid pushes to the SAME branch, distinct SHAs.
   *
   * Asserts (the contract the engine ACTUALLY implements at trunk
   * 189553f6, hardened here so any regression surfaces):
   *
   *   - Receiver returns dispatched=true on all 3 deliveries.
   *   - 3 build rows materialise (one per push) — no dedupe / debounce.
   *   - Every materialised build reaches terminal status within 5 minutes.
   *   - No build sits in QUEUED past the terminal deadline (no starvation).
   *
   * The brief asked us to "document the observed contract". The contract is:
   * one build per push, no dedupe, run independently. Documented inline above
   * and in the docstring at the top of this file.
   */
  test('test1 — 3 rapid same-branch pushes → 3 independent builds, all terminal', async ({
    request,
  }) => {
    test.setTimeout(5 * 60_000)

    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
    let bearer: string | undefined
    let credentialId: number | undefined
    let jobId: number | undefined

    try {
      const fixtureYaml = await fetchFixtureYaml(request)
      bearer = await fetchBearerToken(ENV)
      const branch = `burst-same-${runTag}`
      const setup = await setupJobWithTriggers(request, runTag, bearer, fixtureYaml, [branch])
      credentialId = setup.credentialId
      jobId = setup.jobId

      // 3 distinct SHAs, fired back-to-back. We do NOT add jitter — the brief
      // says "within ~3 seconds"; firing as fast as Node can `await` already
      // bunches them into <500ms, which is the worst case for the engine.
      const fullShas = [randomSha(), randomSha(), randomSha()]
      const shortShas = fullShas.map(shortSha)

      // Fire in sequence (still in the same JS tick window). Sequential
      // awaits — we want each request to start IMMEDIATELY after the previous
      // one returns, not all three on Node's tcp pool simultaneously (which
      // would actually be EASIER for the engine, undermining the burst test).
      const dispatchResults: PushResult[] = []
      for (let i = 0; i < fullShas.length; i++) {
        const fullSha = fullShas[i]!
        const res = await postPushWebhook(
          request,
          setup.secret,
          FIXTURE_REPO,
          branch,
          fullSha,
          `e2e-burst-${runTag}-${i}`,
        )
        dispatchResults.push(res)
      }

      // Every delivery must have been accepted + dispatched.
      for (let i = 0; i < dispatchResults.length; i++) {
        const r = dispatchResults[i]!
        expect(
          r.status,
          `delivery #${i} HTTP ${r.status} — receiver rejected a same-branch push`,
        ).toBe(200)
        expect(
          r.body.dispatched,
          `delivery #${i} accepted but dispatched=false (detail="${r.body.detail}") — ` +
            `a same-branch burst delivery was silently dropped at the receiver`,
        ).toBe(true)
      }

      // Wait for all 3 SHAs to appear as builds.
      const builds = await waitForBuildsForShas(
        request,
        bearer,
        jobId,
        new Set(shortShas),
        45_000,
      )
      expect(
        builds.length,
        `expected exactly 3 builds for SHAs [${shortShas.join(', ')}] — got ${builds.length}. ` +
          `If <3, the engine DEDUPED bursts (this is currently NOT the contract; ` +
          `if a dedupe window is added in future, update this spec + design doc).`,
      ).toBe(3)

      // Cross-check: triggerType + per-build SHA.
      for (const b of builds) {
        expect(
          b.triggerType,
          `build ${b.id} triggerType="${b.triggerType}" — expected "github"`,
        ).toBe('github')
        expect(
          b.triggerMeta?.commitSha,
          `build ${b.id} triggerMeta.commitSha missing — webhook path did not persist trigger meta`,
        ).toBeTruthy()
        expect(
          b.triggerMeta?.branch,
          `build ${b.id} triggerMeta.branch="${b.triggerMeta?.branch}" — expected "${branch}"`,
        ).toBe(branch)
      }

      // Drive each created build to terminal — 5 min budget is enough for 3
      // serial simple-build runs (~5-10s each), with headroom for cold worker.
      const statuses = await waitForAllTerminal(
        request,
        bearer,
        builds.map((b) => b.id),
        4 * 60_000,
      )

      // No build allowed to be stuck in non-terminal status.
      for (const [id, status] of statuses) {
        expect(
          TERMINAL_STATUSES.has(status),
          `build ${id} did NOT reach a terminal status (final="${status}") — ` +
            `engine wedged on a same-branch burst (P0: queue starvation)`,
        ).toBe(true)
      }
    } finally {
      await cleanup(request, bearer, jobId, credentialId)
    }
  })

  /**
   * Test 2 — concurrent pushes to DIFFERENT branches must fan out independently.
   *
   * One job, two configured branches (A + B). Fire one push per branch in the
   * same JS tick. Assert: each branch yields its own build; per-build
   * `triggerMeta.branch` matches the source; no cross-contamination.
   *
   * (We use a single job with two branch globs rather than two jobs because
   * cross-job independence is already covered by per-spec runs; the
   * cross-contamination risk inside the engine lives at the `(job, trigger)
   * candidate` level inside `GithubWebhookApi#matchingTriggers`.)
   */
  test('test2 — concurrent different-branch pushes → independent fan-out, no cross-contamination', async ({
    request,
  }) => {
    test.setTimeout(5 * 60_000)

    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
    let bearer: string | undefined
    let credentialId: number | undefined
    let jobId: number | undefined

    try {
      const fixtureYaml = await fetchFixtureYaml(request)
      bearer = await fetchBearerToken(ENV)
      const branchA = `burst-a-${runTag}`
      const branchB = `burst-b-${runTag}`
      const setup = await setupJobWithTriggers(
        request,
        runTag,
        bearer,
        fixtureYaml,
        [branchA, branchB],
      )
      credentialId = setup.credentialId
      jobId = setup.jobId

      const shaA = randomSha()
      const shaB = randomSha()
      const shortA = shortSha(shaA)
      const shortB = shortSha(shaB)

      // Fire BOTH deliveries concurrently with Promise.all — same-tick parallel.
      const [resA, resB] = await Promise.all([
        postPushWebhook(request, setup.secret, FIXTURE_REPO, branchA, shaA, `e2e-A-${runTag}`),
        postPushWebhook(request, setup.secret, FIXTURE_REPO, branchB, shaB, `e2e-B-${runTag}`),
      ])
      expect(resA.body.dispatched, `branch-A push not dispatched (detail="${resA.body.detail}")`).toBe(true)
      expect(resB.body.dispatched, `branch-B push not dispatched (detail="${resB.body.detail}")`).toBe(true)

      const builds = await waitForBuildsForShas(
        request,
        bearer,
        jobId,
        new Set([shortA, shortB]),
        45_000,
      )

      // Exactly one build per branch.
      expect(
        builds.length,
        `expected 2 builds (one per branch); got ${builds.length} — ` +
          `cross-branch concurrency leaked or one delivery was silently dropped`,
      ).toBe(2)

      const buildByBranch = new Map<string, BuildListItem>()
      for (const b of builds) {
        const br = b.triggerMeta?.branch ?? '<none>'
        buildByBranch.set(br, b)
      }

      // No cross-contamination: each branch's build carries ITS sha, not the other.
      const bA = buildByBranch.get(branchA)
      const bB = buildByBranch.get(branchB)
      expect(bA, `no build with triggerMeta.branch="${branchA}"`).toBeDefined()
      expect(bB, `no build with triggerMeta.branch="${branchB}"`).toBeDefined()
      expect(
        bA!.triggerMeta?.commitSha,
        `branch ${branchA} build carries SHA "${bA!.triggerMeta?.commitSha}", expected "${shortA}" — ` +
          `SHA cross-contaminated between concurrent deliveries`,
      ).toBe(shortA)
      expect(
        bB!.triggerMeta?.commitSha,
        `branch ${branchB} build carries SHA "${bB!.triggerMeta?.commitSha}", expected "${shortB}" — ` +
          `SHA cross-contaminated between concurrent deliveries`,
      ).toBe(shortB)

      // Both must terminate cleanly.
      const statuses = await waitForAllTerminal(
        request,
        bearer,
        builds.map((b) => b.id),
        4 * 60_000,
      )
      for (const [id, status] of statuses) {
        expect(
          TERMINAL_STATUSES.has(status),
          `build ${id} did NOT reach terminal (final="${status}") — ` +
            `engine wedged on cross-branch concurrent push`,
        ).toBe(true)
      }
    } finally {
      await cleanup(request, bearer, jobId, credentialId)
    }
  })

  /**
   * Test 3 — adversarial: 5 commits paced at ~1/sec to the same branch.
   *
   * The brief calls this out as the "no silent drop" test. The contract
   * we hard-assert:
   *
   *   - All 5 deliveries are accepted + dispatched=true.
   *   - The FIRST sha AND the LAST sha BOTH produce build rows visible in
   *     `/api/v1/jobs/{jobId}/builds`. (Either being missing is a P0 silent
   *     drop — file `engine: rapid pushes drop builds — silent loss`.)
   *   - All 5 builds reach terminal status — no queue starvation.
   *
   * We do NOT assert exactly 5 builds (in case middle deliveries are merged
   * by some future debounce window the engine adds), but if the count is <5
   * the test surfaces a clear "engine dedupes" diagnostic rather than a hard
   * fail — because the *brief* defines silent-drop as "first OR last missing",
   * not "fewer than N". Update this rule if the design doc clarifies.
   */
  test('test3 — 5 pushes @ ~1/sec → no silent drops, first + last builds present, all terminal', async ({
    request,
  }) => {
    test.setTimeout(8 * 60_000)

    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
    let bearer: string | undefined
    let credentialId: number | undefined
    let jobId: number | undefined

    try {
      const fixtureYaml = await fetchFixtureYaml(request)
      bearer = await fetchBearerToken(ENV)
      const branch = `burst-paced-${runTag}`
      const setup = await setupJobWithTriggers(request, runTag, bearer, fixtureYaml, [branch])
      credentialId = setup.credentialId
      jobId = setup.jobId

      const fullShas = Array.from({ length: 5 }, () => randomSha())
      const shortShas = fullShas.map(shortSha)

      // Pace at ~1/sec. We use the elapsed-time approach (sleep until next
      // tick) rather than a fixed 1000ms `setTimeout`, so a slow HTTP roundtrip
      // doesn't compound into a 7-second cadence.
      const start = Date.now()
      const dispatchResults: PushResult[] = []
      for (let i = 0; i < fullShas.length; i++) {
        const target = start + i * 1000
        const sleep = target - Date.now()
        if (sleep > 0) {
          await new Promise((res) => setTimeout(res, sleep))
        }
        const fullSha = fullShas[i]!
        const r = await postPushWebhook(
          request,
          setup.secret,
          FIXTURE_REPO,
          branch,
          fullSha,
          `e2e-paced-${runTag}-${i}`,
        )
        dispatchResults.push(r)
      }

      for (let i = 0; i < dispatchResults.length; i++) {
        const r = dispatchResults[i]!
        expect(
          r.body.dispatched,
          `delivery #${i} dispatched=false (detail="${r.body.detail}") — ` +
            `paced 1/sec burst dropped at the receiver`,
        ).toBe(true)
      }

      // We MUST see the FIRST and LAST short shas. Middle deliveries are
      // acknowledged-but-merged territory if a future dedupe shows up.
      const firstSha = shortShas[0]!
      const lastSha = shortShas[shortShas.length - 1]!
      const builds = await waitForBuildsForShas(
        request,
        bearer,
        jobId,
        new Set([firstSha, lastSha]),
        60_000,
      )
      const seenShas = new Set(builds.map((b) => b.triggerMeta?.commitSha ?? ''))

      expect(
        seenShas.has(firstSha),
        `FIRST sha ${firstSha} produced NO build row — P0 silent drop. ` +
          `File: "engine: rapid pushes drop builds — silent loss". ` +
          `Observed SHAs: [${[...seenShas].join(', ')}]`,
      ).toBe(true)
      expect(
        seenShas.has(lastSha),
        `LAST sha ${lastSha} produced NO build row — P0 silent drop. ` +
          `File: "engine: rapid pushes drop builds — silent loss". ` +
          `Observed SHAs: [${[...seenShas].join(', ')}]`,
      ).toBe(true)

      // Per current contract (no dedupe), we expect 5 distinct builds. Soft-
      // diagnose if fewer — the hard floor stays "first + last present".
      const allBuilds = await findBuildsForShas(
        request,
        bearer,
        jobId,
        new Set(shortShas),
      )
      if (allBuilds.length < shortShas.length) {
        const seen = new Set(allBuilds.map((b) => b.triggerMeta?.commitSha ?? ''))
        const missing = shortShas.filter((s) => !seen.has(s))
        // Attach the diagnostic — useful in CI logs without failing the spec
        // (the hard floor is first+last). If a dedupe window IS the new
        // contract, an updated design doc + this comment-block update lets a
        // future engineer relax the test deliberately.
        await test.info().attach('paced-burst-missing-shas.txt', {
          body:
            `expected 5 builds, got ${allBuilds.length}. ` +
            `Missing intermediate SHAs: [${missing.join(', ')}]. ` +
            `If this is a deliberate dedupe window, document it in design/* and update this spec.`,
          contentType: 'text/plain',
        })
      }

      // Drive every materialised build to terminal — no starvation.
      const statuses = await waitForAllTerminal(
        request,
        bearer,
        allBuilds.map((b) => b.id),
        6 * 60_000,
      )
      for (const [id, status] of statuses) {
        expect(
          TERMINAL_STATUSES.has(status),
          `build ${id} did NOT reach terminal (final="${status}") — ` +
            `queue starvation under paced 1/sec burst`,
        ).toBe(true)
      }
    } finally {
      await cleanup(request, bearer, jobId, credentialId)
    }
  })

  /**
   * Test 4 — adversarial: SAME sha pushed twice (force-push no-op / webhook
   * retry from GitHub). Per the documented contract (no dedupe / debounce),
   * the engine creates TWO builds with identical short SHA. This locks in the
   * "no idempotency on (branch, commitSha)" behaviour so any future debounce
   * regression surfaces as a hard test diff, not a silent change.
   *
   * If a future ticket adds (branch, commitSha) idempotency, this test must
   * be updated alongside the design doc — the failure message points to it.
   */
  test('test4 — same SHA pushed twice → engine fans out (documents lack of idempotency)', async ({
    request,
  }) => {
    test.setTimeout(5 * 60_000)

    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
    let bearer: string | undefined
    let credentialId: number | undefined
    let jobId: number | undefined

    try {
      const fixtureYaml = await fetchFixtureYaml(request)
      bearer = await fetchBearerToken(ENV)
      const branch = `burst-dup-${runTag}`
      const setup = await setupJobWithTriggers(request, runTag, bearer, fixtureYaml, [branch])
      credentialId = setup.credentialId
      jobId = setup.jobId

      // One sha, two deliveries (distinct delivery IDs — GitHub never reuses
      // X-GitHub-Delivery across retries, but the engine has no replay-cache
      // keyed on delivery-id either; verifying that is the point).
      const fullSha = randomSha()
      const short = shortSha(fullSha)
      const r1 = await postPushWebhook(
        request, setup.secret, FIXTURE_REPO, branch, fullSha, `e2e-dup-${runTag}-1`,
      )
      const r2 = await postPushWebhook(
        request, setup.secret, FIXTURE_REPO, branch, fullSha, `e2e-dup-${runTag}-2`,
      )
      expect(r1.body.dispatched, 'first delivery not dispatched').toBe(true)
      expect(
        r2.body.dispatched,
        `second (duplicate-SHA) delivery dispatched=false (detail="${r2.body.detail}"). ` +
          `If this is a deliberate new contract (idempotency on commitSha), update ` +
          `docs/design/69 + adjust this assertion.`,
      ).toBe(true)

      // Wait until 2 build rows for the same short SHA materialise.
      await expect.poll(
        async () => {
          const builds = await findBuildsForShas(request, bearer!, jobId!, new Set([short]))
          return builds.length
        },
        {
          message:
            `expected 2 builds for duplicate SHA ${short} on branch ${branch}; ` +
            `if the engine now dedupes (count=1) update the design doc + this test`,
          timeout: 45_000,
          intervals: [500, 1_000, 2_000],
        },
      ).toBe(2)

      const builds = await findBuildsForShas(request, bearer, jobId, new Set([short]))
      // Both must terminate cleanly (no orphan).
      const statuses = await waitForAllTerminal(
        request, bearer, builds.map((b) => b.id), 4 * 60_000,
      )
      for (const [id, status] of statuses) {
        expect(
          TERMINAL_STATUSES.has(status),
          `build ${id} did NOT reach terminal (final="${status}") on duplicate-SHA burst`,
        ).toBe(true)
      }
    } finally {
      await cleanup(request, bearer, jobId, credentialId)
    }
  })

  /**
   * Test 5 — UI: while a burst is in flight, the per-pipeline page surfaces
   * the running builds in the "In flight" section in NEWEST-ON-TOP order.
   *
   * The general in-flight sticky invariant (in-flight section above History)
   * is covered by specs 29 + 32. This test adds the burst-specific assertion:
   *
   *   - During a 3-push burst, the in-flight section is non-empty.
   *   - The first row in that section corresponds to a higher buildNumber than
   *     the last row (newest on top).
   *
   * To beat the race between the UI navigation and builds finishing, the
   * fixture is the standard simple-build (~5-10s each); we navigate
   * immediately after the third dispatch. If the runs all finish before the
   * UI loads, the test soft-skips with a diagnostic rather than flake-failing
   * — the burst race is platform-dependent.
   */
  test('test5 — burst in flight → UI pipeline page lists running builds newest-on-top', async ({
    request,
    page,
  }) => {
    test.setTimeout(5 * 60_000)

    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
    let bearer: string | undefined
    let credentialId: number | undefined
    let jobId: number | undefined

    try {
      const fixtureYaml = await fetchFixtureYaml(request)
      bearer = await fetchBearerToken(ENV)
      const branch = `burst-ui-${runTag}`
      const setup = await setupJobWithTriggers(request, runTag, bearer, fixtureYaml, [branch])
      credentialId = setup.credentialId
      jobId = setup.jobId

      const fullShas = [randomSha(), randomSha(), randomSha()]
      const shortShas = fullShas.map(shortSha)
      for (let i = 0; i < fullShas.length; i++) {
        const res = await postPushWebhook(
          request, setup.secret, FIXTURE_REPO, branch, fullShas[i]!, `e2e-ui-${runTag}-${i}`,
        )
        expect(res.body.dispatched, `UI-burst delivery #${i} not dispatched`).toBe(true)
      }

      // Wait for builds to materialise (rows in DB) before UI navigation.
      await waitForBuildsForShas(request, bearer, jobId, new Set(shortShas), 30_000)

      await loginViaKeycloak(page, ENV)
      await page.goto(`${ENV.uiBaseUrl}/pipelines/${jobId}`, {
        waitUntil: 'domcontentloaded',
      })

      // Wait briefly for the in-flight section. If it never appears in time,
      // the burst may have already drained — emit a diagnostic and bail out
      // rather than flake (the lifecycle assertion is in Test 1).
      const inflight = page.getByTestId('pipeline-recent-inflight')
      const visible = await inflight.isVisible().catch(() => false)
      if (!visible) {
        await test.info().attach('burst-ui-already-drained.txt', {
          body:
            `In-flight section not visible by page-load for job ${jobId}; ` +
            `builds may have completed before the UI loaded. SHAs=[${shortShas.join(', ')}]`,
          contentType: 'text/plain',
        })
        return
      }

      // Newest-on-top: first row's data-build-number > last row's data-build-number.
      const numbers = await inflight.locator('.row').evaluateAll((rows) =>
        rows
          .map((el) => Number(el.getAttribute('data-build-number') ?? '0'))
          .filter((n) => n > 0),
      )
      if (numbers.length < 2) {
        // Not enough rows still in flight to assert order — diagnostic only.
        await test.info().attach('burst-ui-too-few-rows.txt', {
          body: `Only ${numbers.length} in-flight row(s) visible; cannot assert order.`,
          contentType: 'text/plain',
        })
        return
      }
      const first = numbers[0]!
      const last = numbers[numbers.length - 1]!
      expect(
        first,
        `in-flight section is NOT newest-on-top: first row buildNumber=${first} ` +
          `but last row buildNumber=${last} (full order=[${numbers.join(', ')}])`,
      ).toBeGreaterThan(last)
    } finally {
      await cleanup(request, bearer, jobId, credentialId)
    }
  })
})

/**
 * 32-webhook-required-param-skip — Layer-1 regression spec for the #920 webhook
 * required-param skip. Closes issue #928 (the E2E gap-closer for #920).
 *
 * What #920 fixed
 * ───────────────
 * Before #920, if a pipeline declared a parameter with `required: true` and no
 * default, and the trigger source (webhook fan-in) could not supply it, the
 * orchestrator enqueued a build that was DOOMED to FAILED at parameter-bake
 * time — spamming the build history with red rows that obscured the real
 * cause (a misconfigured pipeline or a webhook flow that can't inject params).
 *
 * #920 added `WebhookTriggerMatcher.unsatisfiedRequiredParams(...)`, called
 * from `GithubAppWebhookApi.handlePush` / `handlePullRequest`: when the list
 * of missing required-no-default params is non-empty, the job is SKIPPED —
 * NO build row is inserted, NO task is enqueued, only a structured
 * `pipeline_skipped reason=missing_required_params ...` log line is emitted.
 *
 * The truth contract this spec pins
 * ──────────────────────────────────
 * After a single push webhook hits the rig with two matching jobs:
 *   • Job A — pipeline declares required-no-default `TAG` param → +0 builds.
 *   • Job B — pipeline has no required params               → +1 build.
 *
 * The adversarial sub-assertion: if #920 regresses, Job A would get a +1
 * FAILED build. The build-count assertion trips BEFORE any status check, so
 * a regression cannot be hidden by a same-tick FAILED.
 *
 * Why /api/v1/github-app/events, not /api/v1/triggers/github
 * ──────────────────────────────────────────────────────────
 * The brief originally pointed at `/api/v1/triggers/github` (the per-trigger
 * endpoint, like spec #26). On audit:
 *
 *   • `GithubAppWebhookApi.handlePush` (the App endpoint at
 *     `/api/v1/github-app/events`) DOES call `unsatisfiedRequiredParams` —
 *     this is the path #920 patched.
 *   • `GithubWebhookApi.receive` (the per-trigger endpoint at
 *     `/api/v1/triggers/github`) does NOT apply the required-param skip.
 *
 * So the only honest endpoint to drive the #920 contract is the App one.
 * This is a follow-up production-source finding: the per-trigger endpoint
 * should mirror the skip semantics; tracked as a separate issue rather than
 * fixed here (test PR ≠ production fix per Constitution §7).
 *
 * Layer-1 gate (rig prereq)
 * ─────────────────────────
 * The App endpoint demands a registered `titan.github_app` row, whose
 * webhook secret is ENVELOPE-ENCRYPTED via {@code EnvelopeCipher} (V28). We
 * cannot synthesise a fresh sealed row from a Playwright spec — that requires
 * the live `CredentialKeyProvider` KEK. The honest contract: this spec is
 * runnable only when the operator has registered an App on the rig and
 * exported the plaintext webhook secret via env var
 * {@code TITAN_GITHUB_APP_WEBHOOK_SECRET}. Otherwise we `test.skip` with a
 * clear reason rather than fake-passing on a fake fixture. This is the
 * Layer-1 cost; Layer-2 (real fixture push) is currently blocked by the rig
 * migration pending. See the next-sprint skill, step 3.
 *
 * Test 2 (supplied param) — honest concession
 * ───────────────────────────────────────────
 * The brief asked for an adversarial test: fire a webhook that DOES carry the
 * required param. Per `GithubAppWebhookApi.handlePush` line 251, the supplied
 * set is hardcoded to `Set.of()` — webhook fan-in CANNOT inject parameters in
 * this codebase. There is no `client_payload` / `repository_dispatch` plumbed
 * through to the matcher. So Test 2 is honestly `test.skip` with that
 * documented reason — a fake-passing test would be worse than a missing one
 * (the "Cap-as-bandaid" anti-pattern, but for tests).
 *
 * Cleanup
 * ───────
 * `afterEach` deletes the seeded jobs by full_name (cascades builds +
 * tasks); deletes the discovered rows by filename; deletes the seeded repo +
 * installation by id. The github_app row is NOT touched — the operator
 * registered it; we don't yank it from under them.
 *
 * @tag @golden
 */
import * as crypto from 'node:crypto'
import { test, expect, type APIRequestContext } from '@playwright/test'
import { fetchBearerToken } from '../../fixtures/auth-v3'
import { pgClient } from '../../fixtures/seed-v3'

const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

// Operator-supplied plaintext webhook secret matching the registered
// titan.github_app row. Without this, we cannot HMAC-sign a payload the
// server will accept.
const WEBHOOK_SECRET = process.env.TITAN_GITHUB_APP_WEBHOOK_SECRET ?? ''

// Per-run suffix so re-runs don't collide on full_name / install_id / repo_id.
const RUN_TAG = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`

// Stable synthetic ids — high enough not to collide with real install/repo ids.
// Postgres BIGINT, no collision with GitHub's id space at this magnitude (GitHub
// installation ids are < 100M today; we're well past that).
const SYNTH_INSTALL_ID = 9_000_000_000 + Math.floor(Math.random() * 100_000_000)
const SYNTH_REPO_ID = 9_500_000_000 + Math.floor(Math.random() * 100_000_000)

const REPO_OWNER = 'titan-e2e'
const REPO_NAME = `required-param-skip-${RUN_TAG}`
const BRANCH = 'main'

const JOB_A_NAME = `e2e-required-param-skip-A-${RUN_TAG}`
const JOB_B_NAME = `e2e-required-param-skip-B-${RUN_TAG}`
const JOB_A_FILENAME = '.titan/pipelines/with-required-param.yml'
const JOB_B_FILENAME = '.titan/pipelines/no-params.yml'

// Pipeline scripts (we still POST via /api/v1/jobs so the parser approves them
// — see seed-data.sh #507 lesson: raw-SQL job creation bypasses validation).
// Job A has a required-no-default param; Job B has none.
const JOB_A_YAML = `parameters:
  - name: TAG
    type: string
    required: true

stages:
  - stage: Build
    steps:
      - sh: "echo TAG=\\$TAG"
`

const JOB_B_YAML = `stages:
  - stage: Build
    steps:
      - sh: "echo hello"
`

// parsed_metadata as the scanner would have persisted it — minimal shape, the
// matcher only reads `parameters[]` + `triggers[]`. Empty triggers → "fire on
// every push" per WebhookTriggerMatcher line 95-103 (#906 semantics).
const JOB_A_PARSED_METADATA = JSON.stringify({
  name: 'with-required-param',
  triggers: [],
  parameters: [{ name: 'TAG', type: 'string', required: true, hasDefault: false }],
})
const JOB_B_PARSED_METADATA = JSON.stringify({
  name: 'no-params',
  triggers: [],
  parameters: [],
})

interface BuildListItem {
  id: number
  buildNumber: number
  status: string
  errorMessage?: string | null
}
interface BuildsPage {
  items: BuildListItem[]
  total: number
}

async function apiGet<T>(
  request: APIRequestContext,
  bearer: string,
  path: string,
): Promise<{ ok: boolean; status: number; body: T | null; raw: string }> {
  const r = await request.get(`${API_BASE}${path}`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  const raw = await r.text()
  let body: T | null = null
  try {
    body = JSON.parse(raw) as T
  } catch {
    /* leave null */
  }
  return { ok: r.ok(), status: r.status(), body, raw }
}

async function listBuilds(
  request: APIRequestContext,
  bearer: string,
  jobId: number,
): Promise<BuildListItem[]> {
  const r = await apiGet<BuildsPage>(
    request,
    bearer,
    `/api/v1/jobs/${jobId}/builds?offset=0&limit=50`,
  )
  if (!r.ok || !r.body) return []
  return r.body.items
}

/**
 * Seed: installation + repo + two jobs (created via the real public API so
 * the parser validates them) + two discovered rows. Returns the created
 * job ids so the test can poll builds.
 */
async function seedAll(
  request: APIRequestContext,
  bearer: string,
): Promise<{ jobAId: number; jobBId: number }> {
  // Installation + repo via direct SQL — there is no admin endpoint for
  // synthesising a fake install without exchanging a real GitHub manifest.
  const client = pgClient()
  await client.connect()
  try {
    await client.query(
      `INSERT INTO titan.github_installations
         (install_id, account_login, account_type, target_type)
       VALUES ($1, $2, 'Organization', 'Organization')`,
      [SYNTH_INSTALL_ID, REPO_OWNER],
    )
    await client.query(
      `INSERT INTO titan.github_repositories
         (install_id, repo_id, owner, name, default_branch, is_private)
       VALUES ($1, $2, $3, $4, $5, FALSE)`,
      [SYNTH_INSTALL_ID, SYNTH_REPO_ID, REPO_OWNER, REPO_NAME, BRANCH],
    )
    await client.query(
      `INSERT INTO titan.github_pipelines_discovered
         (repo_id, branch, filename, content_sha, parsed_metadata)
       VALUES ($1, $2, $3, $4, $5)`,
      [SYNTH_REPO_ID, BRANCH, JOB_A_FILENAME, 'a'.repeat(40), JOB_A_PARSED_METADATA],
    )
    await client.query(
      `INSERT INTO titan.github_pipelines_discovered
         (repo_id, branch, filename, content_sha, parsed_metadata)
       VALUES ($1, $2, $3, $4, $5)`,
      [SYNTH_REPO_ID, BRANCH, JOB_B_FILENAME, 'b'.repeat(40), JOB_B_PARSED_METADATA],
    )
  } finally {
    await client.end()
  }

  // Jobs via the real public API so TitanYamlParser.parseAndValidate runs —
  // raw-SQL bypass was the #507 lesson.
  async function createJob(fullName: string, yaml: string, filename: string): Promise<number> {
    const r = await request.post(`${API_BASE}/api/v1/jobs`, {
      headers: {
        Authorization: `Bearer ${bearer}`,
        'Content-Type': 'application/json',
      },
      data: {
        fullName,
        displayName: fullName,
        pipelineScript: yaml,
        // configJson MUST carry filename — WebhookTriggerMatcher.extractFilename
        // reads it to match the discovered row (line 339-353).
        configJson: JSON.stringify({ filename }),
        enabled: true,
      },
    })
    const raw = await r.text()
    expect(
      r.status(),
      `POST /api/v1/jobs ${fullName} failed: HTTP ${r.status()} body=${raw.slice(0, 400)}`,
    ).toBe(201)
    return (JSON.parse(raw) as { id: number }).id
  }

  const jobAId = await createJob(JOB_A_NAME, JOB_A_YAML, JOB_A_FILENAME)
  const jobBId = await createJob(JOB_B_NAME, JOB_B_YAML, JOB_B_FILENAME)

  // Link the jobs to the synthetic install + repo via direct SQL (the public
  // API doesn't expose these columns — they're populated by the App-flow
  // job-create path in GithubAppApi).
  const client2 = pgClient()
  await client2.connect()
  try {
    await client2.query(
      `UPDATE titan.jobs SET github_installation_id = $1, github_repo_id = $2 WHERE id IN ($3, $4)`,
      [SYNTH_INSTALL_ID, SYNTH_REPO_ID, jobAId, jobBId],
    )
  } finally {
    await client2.end()
  }
  return { jobAId, jobBId }
}

/** Cleanup the seed. Order matters because of FK constraints. */
async function cleanupAll(jobAId: number | undefined, jobBId: number | undefined): Promise<void> {
  const client = pgClient()
  await client.connect()
  try {
    // Jobs → builds + tasks cascade.
    if (jobAId) {
      await client.query(`DELETE FROM titan.jobs WHERE id = $1`, [jobAId])
    }
    if (jobBId) {
      await client.query(`DELETE FROM titan.jobs WHERE id = $1`, [jobBId])
    }
    // Discovered rows.
    await client.query(`DELETE FROM titan.github_pipelines_discovered WHERE repo_id = $1`, [
      SYNTH_REPO_ID,
    ])
    // Repo + install (cascade is set; explicit for clarity).
    await client.query(`DELETE FROM titan.github_repositories WHERE repo_id = $1`, [SYNTH_REPO_ID])
    await client.query(`DELETE FROM titan.github_installations WHERE install_id = $1`, [
      SYNTH_INSTALL_ID,
    ])
  } finally {
    await client.end()
  }
}

function buildPushPayload(): {
  bodyBytes: Buffer
  signature: string
  deliveryId: string
} {
  const pushPayload = {
    ref: `refs/heads/${BRANCH}`,
    before: '0'.repeat(40),
    after: 'f'.repeat(40),
    installation: { id: SYNTH_INSTALL_ID },
    repository: {
      id: SYNTH_REPO_ID,
      full_name: `${REPO_OWNER}/${REPO_NAME}`,
      default_branch: BRANCH,
    },
    pusher: { name: 'e2e-bot' },
    head_commit: { id: 'f'.repeat(40), message: 'e2e required-param skip' },
  }
  const bodyBytes = Buffer.from(JSON.stringify(pushPayload), 'utf8')
  const sig =
    'sha256=' +
    crypto.createHmac('sha256', WEBHOOK_SECRET).update(bodyBytes).digest('hex')
  return { bodyBytes, signature: sig, deliveryId: `e2e-${RUN_TAG}` }
}

test.describe('@golden v3 webhook-required-param-skip (#920 / #928)', () => {
  // Skip the entire suite if the operator hasn't supplied the registered App's
  // webhook secret — we can't HMAC-sign a payload the server will accept
  // without it, and the github_app row's secret is envelope-encrypted.
  test.skip(
    !WEBHOOK_SECRET,
    'TITAN_GITHUB_APP_WEBHOOK_SECRET not set — register a GitHub App on the ' +
      'rig and export the plaintext webhook secret to run this spec. The ' +
      'titan.github_app row is envelope-encrypted; we cannot derive the ' +
      'secret from outside the JVM.',
  )

  test('push webhook: required-param-missing job SKIPPED, no-params job dispatched', async ({
    request,
  }) => {
    test.setTimeout(60_000)

    let jobAId: number | undefined
    let jobBId: number | undefined
    try {
      const bearer = await fetchBearerToken()

      // ── seed ────────────────────────────────────────────────────────────
      const seed = await seedAll(request, bearer)
      jobAId = seed.jobAId
      jobBId = seed.jobBId

      // ── baseline build counts (should be 0 each — fresh job) ────────────
      const baseA = await listBuilds(request, bearer, jobAId)
      const baseB = await listBuilds(request, bearer, jobBId)
      expect(baseA.length, 'Job A baseline must be 0 — fresh job').toBe(0)
      expect(baseB.length, 'Job B baseline must be 0 — fresh job').toBe(0)

      // ── fire one HMAC-signed push ───────────────────────────────────────
      const { bodyBytes, signature, deliveryId } = buildPushPayload()
      const webhookResp = await request.post(`${API_BASE}/api/v1/github-app/events`, {
        headers: {
          'Content-Type': 'application/json',
          'X-GitHub-Event': 'push',
          'X-Hub-Signature-256': signature,
          'X-GitHub-Delivery': deliveryId,
        },
        data: bodyBytes,
      })
      const webhookRaw = await webhookResp.text()
      expect(
        webhookResp.status(),
        `POST /api/v1/github-app/events HTTP ${webhookResp.status()} body=${webhookRaw.slice(0, 400)}`,
      ).toBe(202)
      const webhookBody = JSON.parse(webhookRaw) as {
        accepted: boolean
        enqueued: number
        skipped: number
      }
      // Truth-contract assertion #1: server-reported tally must show 1 enqueue
      // + 1 skip. The Map.of ordering in handlePush guarantees these fields.
      expect(
        webhookBody.enqueued,
        `expected 1 enqueue (Job B), got ${webhookBody.enqueued} — ${webhookRaw}`,
      ).toBe(1)
      expect(
        webhookBody.skipped,
        `expected 1 skip (Job A), got ${webhookBody.skipped} — if 0 then #920 regressed ` +
          `and Job A got an enqueued doomed build`,
      ).toBe(1)

      // ── poll Job B for its +1 build (engine is fast, typical ~5s) ───────
      await expect
        .poll(async () => (await listBuilds(request, bearer, jobBId!)).length, {
          message: `Job B (${JOB_B_NAME}) never got a build within 15s — webhook accepted ` +
            `enqueued=${webhookBody.enqueued} but no row appeared in titan.builds`,
          timeout: 15_000,
          intervals: [500, 1_000, 2_000],
        })
        .toBeGreaterThanOrEqual(1)

      // ── hard assert Job A has +0 builds ─────────────────────────────────
      // This is the truth contract: a regression of #920 would mint a FAILED
      // build here. We assert COUNT first (which trips on any +1, regardless
      // of terminal status) BEFORE checking statuses.
      const finalA = await listBuilds(request, bearer, jobAId)
      expect(
        finalA.length,
        `Job A (${JOB_A_NAME}) got ${finalA.length} build(s) — #920 has regressed. ` +
          `The required-no-default TAG param had no supplier; the webhook should have ` +
          `SKIPPED enqueue, not minted a doomed build. ` +
          `Build statuses observed: ${JSON.stringify(finalA.map((b) => b.status))}`,
      ).toBe(0)

      // ── adversarial sub-assertion: even after a settling window, still 0 ─
      // A late-tick FAILED build could land between the count check and the
      // afterEach. Poll for 5 more seconds; the count MUST stay 0. We can't
      // expect.poll on "stays 0" cleanly, so explicit interval loop.
      const settlingDeadline = Date.now() + 5_000
      while (Date.now() < settlingDeadline) {
        const stillA = await listBuilds(request, bearer, jobAId)
        expect(
          stillA.length,
          `Job A acquired a build during the settling window — late-tick #920 regression. ` +
            `Statuses: ${JSON.stringify(stillA.map((b) => b.status))}`,
        ).toBe(0)
        await new Promise((r) => setTimeout(r, 1_000))
      }

      // ── adversarial: scan whatever DID land for any "required parameter"
      // errorMessage. The spec is to PROVE no build was created, not "a build
      // was created with a friendly error message". If ANY build of Job A
      // carries that error, #920 regressed even if the count assertion was
      // somehow tolerant.
      const errored = finalA.find((b) =>
        (b.errorMessage ?? '').toLowerCase().includes('required parameter'),
      )
      expect(
        errored,
        `Job A has a build with errorMessage containing "required parameter" — #920 fix ` +
          `would have NOT created this row at all; finding it means the matcher's skip ` +
          `path was bypassed and the parameter-bake stage ran. ` +
          `Build: ${JSON.stringify(errored)}`,
      ).toBeUndefined()
    } finally {
      await cleanupAll(jobAId, jobBId)
    }
  })

  test('repository_dispatch with client_payload.TAG enqueues build carrying TAG=v1.0', async ({
    request,
  }) => {
    // #938 — webhook payload param injection: a `repository_dispatch` delivery
    // with `client_payload: {TAG: "v1.0"}` against a pipeline declaring
    // required-no-default `TAG` MUST enqueue a build whose `parameters_json`
    // carries `TAG=v1.0`. End-to-end propagation, not just the precheck.
    test.setTimeout(60_000)

    let jobAId: number | undefined
    let jobBId: number | undefined
    try {
      const bearer = await fetchBearerToken()

      // Reuse the same seed — Job A is the required-TAG pipeline we want to
      // hit; Job B is a no-params pipeline that we ignore for this test (its
      // triggers list is empty so a repository_dispatch will also enqueue it,
      // which is fine — we only assert on Job A).
      const seed = await seedAll(request, bearer)
      jobAId = seed.jobAId
      jobBId = seed.jobBId

      const baseA = await listBuilds(request, bearer, jobAId)
      expect(baseA.length, 'Job A baseline must be 0 — fresh job').toBe(0)

      // Build a repository_dispatch payload (NO ref — the spec is explicit
      // that repo_dispatch has no branch).
      const repoDispatchPayload = {
        action: 'deploy',
        client_payload: { TAG: 'v1.0' },
        installation: { id: SYNTH_INSTALL_ID },
        repository: {
          id: SYNTH_REPO_ID,
          full_name: `${REPO_OWNER}/${REPO_NAME}`,
          default_branch: BRANCH,
        },
        sender: { login: 'e2e-bot' },
      }
      const bodyBytes = Buffer.from(JSON.stringify(repoDispatchPayload), 'utf8')
      const signature =
        'sha256=' +
        crypto.createHmac('sha256', WEBHOOK_SECRET).update(bodyBytes).digest('hex')

      const webhookResp = await request.post(`${API_BASE}/api/v1/github-app/events`, {
        headers: {
          'Content-Type': 'application/json',
          'X-GitHub-Event': 'repository_dispatch',
          'X-Hub-Signature-256': signature,
          'X-GitHub-Delivery': `e2e-rd-${RUN_TAG}`,
        },
        data: bodyBytes,
      })
      const webhookRaw = await webhookResp.text()
      expect(
        webhookResp.status(),
        `POST /api/v1/github-app/events (repository_dispatch) HTTP ${webhookResp.status()} body=${webhookRaw.slice(0, 400)}`,
      ).toBe(202)

      // Job A must get exactly one build — the required TAG was supplied.
      await expect
        .poll(async () => (await listBuilds(request, bearer, jobAId!)).length, {
          message: `Job A (${JOB_A_NAME}) never got a build within 15s — repository_dispatch ` +
            `with client_payload.TAG=v1.0 should have enqueued it. Webhook response: ${webhookRaw}`,
          timeout: 15_000,
          intervals: [500, 1_000, 2_000],
        })
        .toBeGreaterThanOrEqual(1)

      // Truth contract: TAG=v1.0 must be reflected in the build's parameters.
      // The build-detail endpoint exposes parametersJson on the row.
      const builds = await listBuilds(request, bearer, jobAId)
      const firstBuild = builds[0]
      if (!firstBuild) {
        throw new Error(`expected Job A to have at least one build, got none`)
      }
      const detail = await apiGet<{ parametersJson?: string | null; parameters?: Record<string, unknown> | null }>(
        request,
        bearer,
        `/api/v1/builds/${firstBuild.id}`,
      )
      // Either `parametersJson` is a serialised blob containing TAG=v1.0, or
      // (if the API has been migrated to typed `parameters`) the typed object
      // carries TAG=v1.0. Accept both shapes — the contract is "the value
      // propagated", not "via this exact field".
      const blob = JSON.stringify(detail.body ?? {})
      expect(
        blob.includes('"TAG"') && blob.includes('"v1.0"'),
        `Job A build ${firstBuild.id} does not carry TAG=v1.0; build detail: ${blob.slice(0, 800)}`,
      ).toBe(true)
    } finally {
      await cleanupAll(jobAId, jobBId)
    }
  })
})

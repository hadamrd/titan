/**
 * 62-scm-webhooks — golden coverage for the GitLab + Bitbucket webhook
 * trigger receivers (closes #135).
 *
 * Why this spec exists
 * ────────────────────
 * The GitHub webhook path has standing golden coverage (specs 26/52), but
 * `GitlabWebhookApi` and `BitbucketWebhookApi` had ZERO e2e specs. Post-#71
 * all three share the atomic enqueue path, yet each SCM has its own
 * signature scheme and payload parser — exactly the seams that only a
 * rig-level delivery exercises:
 *
 *   - GitLab:    shared-secret token in `X-Gitlab-Token` (constant-time
 *                compare), event discriminated by `X-Gitlab-Event` +
 *                `object_kind`. Endpoint: POST /api/v1/triggers/gitlab.
 *   - Bitbucket: HMAC-SHA256 over the raw body in `X-Hub-Signature:
 *                sha256=…`, event discriminated by `X-Event-Key`.
 *                Endpoint: POST /api/v1/triggers/bitbucket.
 *
 * Payload shapes mirror the receivers' unit tests (the source of truth):
 *   titan-server/src/test/java/io/adaptiq/titan/api/triggers/gitlab/
 *     GitlabWebhookApiTest.java  (pushBody / headers helpers)
 *   titan-server/src/test/java/io/adaptiq/titan/api/triggers/bitbucket/
 *     BitbucketWebhookApiTest.java  (pushBody / sign helpers)
 *
 * Why synthetic webhooks (not a real GitLab/Bitbucket instance)
 * ─────────────────────────────────────────────────────────────
 * Same reasoning as spec 52: the contract under test starts at "webhook
 * delivery arrives". We synthesise the exact bytes GitLab/Bitbucket Cloud
 * emit for a `push` and authenticate them the way the real senders do
 * (shared token / HMAC over the raw body). No external SCM needed.
 *
 * Rig seeding: NONE required. The webhook secret lives in the credentials
 * store under the per-SCM scope (`gitlab-webhook` / `bitbucket-webhook`)
 * and is created per-test via POST /api/v1/credentials — the same
 * self-provisioning pattern spec 52 uses for `github-webhook`.
 *
 * Contract asserted per SCM
 * ─────────────────────────
 * Happy path (@golden):
 *   1. Job with the SCM's trigger config (own rows, unique-per-run names).
 *   2. Correctly-authenticated synthesized push → HTTP 200, dispatched=true.
 *   3. Exactly one build row materialises with:
 *        triggerType  = "gitlab" | "bitbucket"
 *        triggeredBy  = "gitlab:webhook" | "bitbucket:webhook"
 *        triggerMeta  = { branch, commitSha (FULL 40-char), actor }
 *      (TriggerMetaDto whitelists exactly those scalars at the API — the
 *      raw payload / secret must never leak into the DTO.)
 *   4. The worker drives the build to SUCCESS (vendored simple-build
 *      fixture — hermetic, reads from e2e/fixtures/titan-e2e-fixture).
 *
 * Sad path (same describe, still @golden-gated):
 *   - Tampered auth (wrong token / HMAC minted with the wrong secret) → 401
 *     with the receiver's structured problem-detail reason, AND no build row.
 *   - Absent auth header → 401, AND no build row.
 *   The no-build check is deterministic without a wait: enqueue happens
 *   synchronously inside the receive() handler, so once the 401 response is
 *   in hand a zero-length build list is a stable fact, not a race.
 *
 * Ownership + teardown (e2e/README "Spec-ownership rule")
 * ───────────────────────────────────────────────────────
 * Every row (credential, job, builds) is created by THIS spec with
 * unique-per-run names. `finally{}` tears down via `safeDeleteJobCascade`
 * (cancel → terminal wait → lease drain → scoped delete) + credential
 * delete via the API. Zero litter.
 */
import * as crypto from 'node:crypto'
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'
import { readFixtureYaml } from '../../fixtures/fixture-files'
import { safeDeleteJobCascade } from '../../fixtures/teardown-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const FIXTURE_PATH = '.titan/pipelines/simple-build.yml'

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
  triggeredBy?: string
  triggerType?: string
  triggerMeta?: TriggerMetaDto | null
}
interface BuildsPage { items: BuildListItem[]; total: number }
interface WebhookResp {
  ok?: boolean
  dispatched?: boolean
  detail?: string
  reason?: string
  status?: number
}

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

/** Random 40-char hex SHA — the format both SCMs emit for commit hashes. */
function randomSha(): string {
  return crypto.randomBytes(20).toString('hex')
}

/** Read the vendored simple-build fixture YAML from disk (hermetic — #48). */
function loadFixtureYaml(): string {
  const yaml = readFixtureYaml(FIXTURE_PATH)
  expect(yaml.length, 'fixture YAML empty').toBeGreaterThan(50)
  return yaml
}

/**
 * Create a webhook credential (per-SCM scope) + a job carrying the SCM's
 * trigger config. Mirrors spec 52's setup, including the #83 lesson: if the
 * job create fails, delete the already-created credential before rethrowing
 * so a setup failure never litters the rig.
 */
async function setupScmJob(
  api: APIRequestContext,
  bearer: string,
  opts: {
    scm: 'gitlab' | 'bitbucket'
    runTag: string
    branch: string
    fixtureYaml: string
  },
): Promise<{ credentialId: number; jobId: number; credKey: string; secret: string }> {
  const credKey = `e2e-scm-${opts.scm}-${opts.runTag}`
  const secret = `s3cr3t-${opts.scm}-${opts.runTag}`
  const scope = `${opts.scm}-webhook` // CREDENTIALS_SCOPE in the receiver
  const credResp = await api.post(`${API_BASE}/api/v1/credentials`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    data: { kind: 'STRING', scope, key: credKey, plaintext: secret },
  })
  const credRaw = await credResp.text()
  expect(
    credResp.status(),
    `POST /credentials HTTP ${credResp.status()} body=${credRaw.slice(0, 400)}`,
  ).toBe(201)
  const credentialId = (JSON.parse(credRaw) as CredentialCreateResp).id

  try {
    const fullName = `e2e-scm-${opts.scm}-${opts.runTag}`
    const triggersConfig = {
      triggers: [
        {
          type: opts.scm,
          id: `${opts.scm}-1`,
          branches: [opts.branch],
          events: ['push'],
          credentialsId: credKey,
        },
      ],
    }
    const jobResp = await api.post(`${API_BASE}/api/v1/jobs`, {
      headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
      data: {
        fullName,
        displayName: `E2E ${opts.scm} webhook (#135) ${opts.runTag}`,
        pipelineScript: opts.fixtureYaml,
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
    return { credentialId, jobId, credKey, secret }
  } catch (err) {
    await cleanup(api, bearer, undefined, credentialId)
    throw err
  }
}

async function cleanup(
  api: APIRequestContext,
  bearer: string | undefined,
  jobId: number | undefined,
  credentialId: number | undefined,
): Promise<void> {
  if (jobId && jobId > 0) {
    await safeDeleteJobCascade(api, jobId)
  }
  if (bearer && credentialId && credentialId > 0) {
    await api
      .delete(`${API_BASE}/api/v1/credentials/${credentialId}`, {
        headers: { Authorization: `Bearer ${bearer}` },
      })
      .catch(() => undefined)
  }
}

interface DeliveryResult { status: number; body: WebhookResp; raw: string }

/**
 * Synthesise a GitLab `Push Hook` delivery. Payload shape mirrors
 * GitlabWebhookApiTest.pushBody + the #1080 project block. Auth is the
 * shared-secret token header — GitLab sends the secret verbatim, no HMAC.
 */
async function postGitlabPush(
  api: APIRequestContext,
  opts: { token?: string; branch: string; sha: string; actor: string },
): Promise<DeliveryResult> {
  const payload = {
    object_kind: 'push',
    ref: `refs/heads/${opts.branch}`,
    checkout_sha: opts.sha,
    user_username: opts.actor,
    project: { id: 98765, path_with_namespace: 'e2e-group/scm-webhook-fixture' },
  }
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Gitlab-Event': 'Push Hook',
  }
  if (opts.token !== undefined) headers['X-Gitlab-Token'] = opts.token
  const resp = await api.post(`${API_BASE}/api/v1/triggers/gitlab`, {
    headers,
    data: Buffer.from(JSON.stringify(payload), 'utf8'),
  })
  const raw = await resp.text()
  let body: WebhookResp = {}
  try { body = JSON.parse(raw) as WebhookResp } catch { /* keep {} */ }
  return { status: resp.status(), body, raw }
}

/**
 * Synthesise a Bitbucket Cloud `repo:push` delivery. Payload shape mirrors
 * BitbucketWebhookApiTest.pushBody. Auth is HMAC-SHA256 over the raw body,
 * carried as `X-Hub-Signature: sha256=<hex>` — signed with `signWith` (pass
 * the wrong secret to synthesise a tampered delivery whose signature is
 * well-formed but does not verify).
 */
async function postBitbucketPush(
  api: APIRequestContext,
  opts: { signWith?: string; branch: string; sha: string; actor: string },
): Promise<DeliveryResult> {
  const payload = {
    push: {
      changes: [
        { new: { type: 'branch', name: opts.branch, target: { hash: opts.sha } } },
      ],
    },
    actor: { display_name: opts.actor },
    repository: { full_name: 'e2e-team/scm-webhook-fixture' },
  }
  const bytes = Buffer.from(JSON.stringify(payload), 'utf8')
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Event-Key': 'repo:push',
  }
  if (opts.signWith !== undefined) {
    headers['X-Hub-Signature'] =
      'sha256=' + crypto.createHmac('sha256', opts.signWith).update(bytes).digest('hex')
  }
  const resp = await api.post(`${API_BASE}/api/v1/triggers/bitbucket`, {
    headers,
    data: bytes,
  })
  const raw = await resp.text()
  let body: WebhookResp = {}
  try { body = JSON.parse(raw) as WebhookResp } catch { /* keep {} */ }
  return { status: resp.status(), body, raw }
}

/** Wait until the job has a build whose triggerMeta.commitSha === sha. */
async function waitForBuildWithSha(
  api: APIRequestContext,
  bearer: string,
  jobId: number,
  sha: string,
  budgetMs: number,
): Promise<BuildListItem> {
  let match: BuildListItem | undefined
  await expect
    .poll(
      async () => {
        const r = await apiGet<BuildsPage>(
          api, bearer, `/api/v1/jobs/${jobId}/builds?offset=0&limit=50`,
        )
        match = r.body?.items.find((b) => b.triggerMeta?.commitSha === sha)
        return match !== undefined
      },
      {
        message: `no build with triggerMeta.commitSha=${sha} appeared on job ${jobId} within ${budgetMs}ms`,
        timeout: budgetMs,
        intervals: [500, 1_000, 2_000],
      },
    )
    .toBe(true)
  return match!
}

/** Wait until the build reaches a terminal status; return the final status. */
async function waitForTerminal(
  api: APIRequestContext,
  bearer: string,
  buildId: number,
  budgetMs: number,
): Promise<string> {
  let last = 'UNKNOWN'
  await expect
    .poll(
      async () => {
        const r = await apiGet<BuildListItem>(api, bearer, `/api/v1/builds/${buildId}`)
        last = r.body?.status ?? 'UNKNOWN'
        return TERMINAL_STATUSES.has(last)
      },
      {
        message: `build ${buildId} never reached terminal status within ${budgetMs}ms (last=${last})`,
        timeout: budgetMs,
        intervals: [1_000, 2_000, 3_000],
      },
    )
    .toBe(true)
  return last
}

/** Deterministic no-build assertion — enqueue is synchronous in receive(). */
async function expectZeroBuilds(
  api: APIRequestContext,
  bearer: string,
  jobId: number,
  context: string,
): Promise<void> {
  const r = await apiGet<BuildsPage>(api, bearer, `/api/v1/jobs/${jobId}/builds?offset=0&limit=50`)
  expect(r.ok, `GET /jobs/${jobId}/builds failed: HTTP ${r.status} ${r.raw.slice(0, 300)}`).toBe(true)
  expect(
    r.body?.items.length ?? -1,
    `${context}: a rejected delivery MUST NOT create a build row — found ` +
      `${JSON.stringify(r.body?.items.map((b) => ({ id: b.id, status: b.status })))}`,
  ).toBe(0)
}

// ─── GitLab ─────────────────────────────────────────────────────────────────

test.describe('v3 gitlab webhook trigger @golden', () => {
  test('signed push → build with gitlab trigger metadata → worker SUCCESS', async ({
    request,
  }) => {
    test.setTimeout(3 * 60_000)

    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
    let bearer: string | undefined
    let credentialId: number | undefined
    let jobId: number | undefined

    try {
      bearer = await fetchBearerToken(ENV)
      const branch = `gl-push-${runTag}`
      const setup = await setupScmJob(request, bearer, {
        scm: 'gitlab',
        runTag,
        branch,
        fixtureYaml: loadFixtureYaml(),
      })
      credentialId = setup.credentialId
      jobId = setup.jobId

      const sha = randomSha()
      const delivery = await postGitlabPush(request, {
        token: setup.secret,
        branch,
        sha,
        actor: 'e2e-gitlab-bot',
      })
      expect(
        delivery.status,
        `POST /triggers/gitlab HTTP ${delivery.status} body=${delivery.raw.slice(0, 400)}`,
      ).toBe(200)
      expect(
        delivery.body.dispatched,
        `delivery accepted but dispatched=false (detail="${delivery.body.detail}") — ` +
          `the receiver verified the token but did not enqueue`,
      ).toBe(true)

      // Build row materialises with the trigger metadata the receiver persists.
      const build = await waitForBuildWithSha(request, bearer, jobId, sha, 45_000)
      expect(
        build.triggerType,
        `build ${build.id} triggerType="${build.triggerType}" — expected "gitlab"`,
      ).toBe('gitlab')
      expect(
        build.triggeredBy,
        `build ${build.id} triggeredBy="${build.triggeredBy}" — expected "gitlab:webhook"`,
      ).toBe('gitlab:webhook')
      expect(
        build.triggerMeta?.branch,
        `build ${build.id} triggerMeta.branch — receiver must strip refs/heads/ down to the branch`,
      ).toBe(branch)
      expect(
        build.triggerMeta?.commitSha,
        `build ${build.id} triggerMeta.commitSha — FULL 40-char sha from checkout_sha (#971 convention)`,
      ).toBe(sha)
      expect(
        build.triggerMeta?.actor,
        `build ${build.id} triggerMeta.actor — expected the payload's user_username`,
      ).toBe('e2e-gitlab-bot')

      // Exactly ONE build for this delivery — no double-fire.
      const list = await apiGet<BuildsPage>(
        request, bearer, `/api/v1/jobs/${jobId}/builds?offset=0&limit=50`,
      )
      expect(
        list.body?.items.length,
        `expected exactly 1 build after 1 delivery; got ${list.body?.items.length}`,
      ).toBe(1)

      // Worker drives it to SUCCESS (simple-build fixture: echo + archive).
      const final = await waitForTerminal(request, bearer, build.id, 2 * 60_000)
      expect(
        final,
        `build ${build.id} finished ${final} — the gitlab-triggered build must SUCCEED ` +
          `(simple-build fixture is deterministic on a healthy worker)`,
      ).toBe('SUCCESS')
    } finally {
      await cleanup(request, bearer, jobId, credentialId)
    }
  })

  test('tampered or absent token → 401, and NO build row', async ({ request }) => {
    test.setTimeout(60_000)

    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
    let bearer: string | undefined
    let credentialId: number | undefined
    let jobId: number | undefined

    try {
      bearer = await fetchBearerToken(ENV)
      const branch = `gl-sad-${runTag}`
      const setup = await setupScmJob(request, bearer, {
        scm: 'gitlab',
        runTag,
        branch,
        fixtureYaml: loadFixtureYaml(),
      })
      credentialId = setup.credentialId
      jobId = setup.jobId

      // Tampered: a matching job exists, but the token is wrong → 401
      // invalid_token (the receiver found candidates, none verified).
      const tampered = await postGitlabPush(request, {
        token: `${setup.secret}-tampered`,
        branch,
        sha: randomSha(),
        actor: 'e2e-mallory',
      })
      expect(
        tampered.status,
        `tampered-token delivery HTTP ${tampered.status} body=${tampered.raw.slice(0, 400)} — expected 401`,
      ).toBe(401)
      expect(tampered.body.reason, 'structured problem-detail reason').toBe('invalid_token')
      // SECURITY: the 401 body must never echo the expected secret.
      expect(
        tampered.raw.includes(setup.secret),
        '401 body leaked the expected webhook secret',
      ).toBe(false)

      // Absent: no X-Gitlab-Token header at all → 401 missing_token.
      const absent = await postGitlabPush(request, {
        branch,
        sha: randomSha(),
        actor: 'e2e-mallory',
      })
      expect(
        absent.status,
        `missing-token delivery HTTP ${absent.status} body=${absent.raw.slice(0, 400)} — expected 401`,
      ).toBe(401)
      expect(absent.body.reason, 'structured problem-detail reason').toBe('missing_token')

      // Neither rejected delivery may have created a build.
      await expectZeroBuilds(request, bearer, jobId, 'gitlab tampered/absent token')
    } finally {
      await cleanup(request, bearer, jobId, credentialId)
    }
  })
})

// ─── Bitbucket ──────────────────────────────────────────────────────────────

test.describe('v3 bitbucket webhook trigger @golden', () => {
  test('HMAC-signed push → build with bitbucket trigger metadata → worker SUCCESS', async ({
    request,
  }) => {
    test.setTimeout(3 * 60_000)

    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
    let bearer: string | undefined
    let credentialId: number | undefined
    let jobId: number | undefined

    try {
      bearer = await fetchBearerToken(ENV)
      const branch = `bb-push-${runTag}`
      const setup = await setupScmJob(request, bearer, {
        scm: 'bitbucket',
        runTag,
        branch,
        fixtureYaml: loadFixtureYaml(),
      })
      credentialId = setup.credentialId
      jobId = setup.jobId

      const sha = randomSha()
      const delivery = await postBitbucketPush(request, {
        signWith: setup.secret,
        branch,
        sha,
        actor: 'E2E Bitbucket Bot',
      })
      expect(
        delivery.status,
        `POST /triggers/bitbucket HTTP ${delivery.status} body=${delivery.raw.slice(0, 400)}`,
      ).toBe(200)
      expect(
        delivery.body.dispatched,
        `delivery accepted but dispatched=false (detail="${delivery.body.detail}") — ` +
          `the receiver verified the HMAC but did not enqueue`,
      ).toBe(true)

      const build = await waitForBuildWithSha(request, bearer, jobId, sha, 45_000)
      expect(
        build.triggerType,
        `build ${build.id} triggerType="${build.triggerType}" — expected "bitbucket"`,
      ).toBe('bitbucket')
      expect(
        build.triggeredBy,
        `build ${build.id} triggeredBy="${build.triggeredBy}" — expected "bitbucket:webhook"`,
      ).toBe('bitbucket:webhook')
      expect(
        build.triggerMeta?.branch,
        `build ${build.id} triggerMeta.branch — expected the pushed branch name`,
      ).toBe(branch)
      expect(
        build.triggerMeta?.commitSha,
        `build ${build.id} triggerMeta.commitSha — FULL 40-char hash from push.changes[].new.target.hash`,
      ).toBe(sha)
      expect(
        build.triggerMeta?.actor,
        `build ${build.id} triggerMeta.actor — expected the payload's actor.display_name`,
      ).toBe('E2E Bitbucket Bot')

      const list = await apiGet<BuildsPage>(
        request, bearer, `/api/v1/jobs/${jobId}/builds?offset=0&limit=50`,
      )
      expect(
        list.body?.items.length,
        `expected exactly 1 build after 1 delivery; got ${list.body?.items.length}`,
      ).toBe(1)

      const final = await waitForTerminal(request, bearer, build.id, 2 * 60_000)
      expect(
        final,
        `build ${build.id} finished ${final} — the bitbucket-triggered build must SUCCEED ` +
          `(simple-build fixture is deterministic on a healthy worker)`,
      ).toBe('SUCCESS')
    } finally {
      await cleanup(request, bearer, jobId, credentialId)
    }
  })

  test('tampered or absent HMAC signature → 401, and NO build row', async ({ request }) => {
    test.setTimeout(60_000)

    const runTag = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
    let bearer: string | undefined
    let credentialId: number | undefined
    let jobId: number | undefined

    try {
      bearer = await fetchBearerToken(ENV)
      const branch = `bb-sad-${runTag}`
      const setup = await setupScmJob(request, bearer, {
        scm: 'bitbucket',
        runTag,
        branch,
        fixtureYaml: loadFixtureYaml(),
      })
      credentialId = setup.credentialId
      jobId = setup.jobId

      // Tampered: well-formed sha256=… header, minted with the WRONG secret.
      // A matching job exists → the receiver recomputes the HMAC, none
      // verifies → 401 invalid_signature.
      const tampered = await postBitbucketPush(request, {
        signWith: 'not-the-configured-secret',
        branch,
        sha: randomSha(),
        actor: 'E2E Mallory',
      })
      expect(
        tampered.status,
        `tampered-HMAC delivery HTTP ${tampered.status} body=${tampered.raw.slice(0, 400)} — expected 401`,
      ).toBe(401)
      expect(tampered.body.reason, 'structured problem-detail reason').toBe('invalid_signature')
      expect(
        tampered.raw.includes(setup.secret),
        '401 body leaked the expected webhook secret',
      ).toBe(false)

      // Absent: no X-Hub-Signature header at all → 401 missing_signature.
      const absent = await postBitbucketPush(request, {
        branch,
        sha: randomSha(),
        actor: 'E2E Mallory',
      })
      expect(
        absent.status,
        `missing-signature delivery HTTP ${absent.status} body=${absent.raw.slice(0, 400)} — expected 401`,
      ).toBe(401)
      expect(absent.body.reason, 'structured problem-detail reason').toBe('missing_signature')

      await expectZeroBuilds(request, bearer, jobId, 'bitbucket tampered/absent signature')
    } finally {
      await cleanup(request, bearer, jobId, credentialId)
    }
  })
})

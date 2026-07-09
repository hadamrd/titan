/**
 * 58-pulsar-check-lifecycle — Layer-1 @golden spec for the scm-pulsar seam
 * (closes #97).
 *
 * The scm-pulsar primitives (PulsarWebhookApi / PulsarEventSource /
 * PulsarCheckReporter) are IT-covered in isolation but had zero golden
 * coverage through the deployed rig. This spec drives the whole loop against
 * `task dev:titan`:
 *
 *   synthesized HMAC-signed change webhook (X-Pulsar-Signature-256)
 *     → POST /api/v1/pulsar/events (202 + buildId)
 *     → real in-server clone of refs/pulsar/changes/<id> from the fake node
 *       (git dumb-HTTP) + .titan/pipelines discovery
 *     → QUEUED build + SYNTHESIZE task → REAL worker executes the pipeline
 *     → build reaches SUCCESS
 *     → PulsarCheckReporter posts the `build` check events back to the fake
 *       node's ledger endpoint — the recorded events are the oracle.
 *
 * ── The check-lifecycle oracle, and why it is shaped this way ──────────────
 *
 * The fake Pulsar node (fixtures/pulsar-node.ts) records every
 * `POST /_pulsar/ledger/<repo>/changes/<changeId>/events` verbatim. That is
 * the STRONGEST oracle available at the rig seam: it observes the reporter's
 * actual outbound HTTP, not persisted intermediate state. Asserted hard:
 *
 *   1. an enqueue-time event `{kind:ci, check:build, conclusion:pending,
 *      phase:queued}` arrives for OUR change (PulsarWebhookApi.fireEnqueued →
 *      BuildEnqueuedEvent → PulsarCheckReporter.onBuildEnqueued fires
 *      synchronously inside the webhook request, so it must exist by the 202);
 *   2. a terminal `{conclusion:success}` event with NO phase arrives after
 *      the worker-executed build reaches SUCCESS (BuildCloser fires
 *      BuildStateChangedEvent at close);
 *   3. ordering: queued strictly precedes success; any intermediate events
 *      are pending-only; no failure conclusion ever appears; nothing lands on
 *      any other repo/change path.
 *
 * Why `phase:in_progress` is NOT asserted here: on the live engine the
 * QUEUED→RUNNING flip is a direct DAO write inside the bake transaction
 * (TitanFlowExecution.bake → BuildDao.activateIfQueued) — no
 * BuildStateChangedEvent is fired for it (only BuildCloser/terminal and
 * BuildAbortService fire; see the "#835 follow-up" note in
 * TitanOrchestrator.advance). The in_progress leg is IT-covered by driving
 * the reporter directly (PulsarCheckLifecycleIT); at the rig seam it simply
 * never fires today, and asserting it would be asserting a fabrication. If
 * the RUNNING emit lands later, the ordering assertion below already admits
 * it (pending-only between queued and success) and a follow-up should then
 * hard-assert it.
 *
 * ── Rig prerequisites (seeded by rig/local/docker-compose.yml since #97) ───
 *
 *   • PULSAR_WEBHOOK_SECRET  — HMAC secret for the webhook endpoint.
 *   • PULSAR_NODE_BASE_URL   — http://host.docker.internal:18098; this spec
 *     binds the fake node on that host port (0.0.0.0).
 *
 * A signed-handshake preflight distinguishes "secret not seeded (stale rig)"
 * from a real regression before any state is created.
 *
 * ── Ownership + teardown (e2e/README #59) ──────────────────────────────────
 *
 * Everything this spec creates is per-run unique and removed in finally{}:
 * the job + its engine-run builds via safeDeleteJobCascade (cancel-first,
 * lease-drain-aware), the spec's own titan.scm_event_seen dedupe row (exact
 * event_id key), the host temp git fixture, and the fake node listener. No
 * credential rows are created (the pulsar secret is rig config, not a
 * credential).
 */
import * as crypto from 'node:crypto'
import * as fs from 'node:fs'
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'
import {
  buildChangeFixture,
  startFakePulsarNode,
  type FakePulsarNode,
  type PulsarChangeFixture,
  type RecordedCheckEvent,
} from '../../fixtures/pulsar-node'
import { pgClient } from '../../fixtures/seed-v3'
import { safeDeleteJobCascade } from '../../fixtures/teardown-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

// Must match the rig's PULSAR_NODE_BASE_URL port / PULSAR_WEBHOOK_SECRET
// defaults (rig/local/docker-compose.yml). Overridable for non-default rigs.
const NODE_PORT = Number(process.env.PULSAR_FAKE_NODE_PORT ?? 18098)
const WEBHOOK_SECRET =
  process.env.PULSAR_WEBHOOK_SECRET ?? 'titan-dev-pulsar-webhook-secret'

// Per-run uniqueness: job full_name == the pulsar repo (that equality IS the
// webhook's job-resolution contract), change id, dedupe key.
const RUN_TAG = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
const REPO = `e2e-pulsar58-${RUN_TAG}`
const CHANGE_ID = `chg-${RUN_TAG}`

// What the worker actually executes: SYNTHESIZE parses the JOB's
// pipeline_script (SynthesizeHandler → job.pipelineScript). The clone tree's
// .titan/pipelines/ci.yml only gates dispatch (PulsarEventSource discovery).
const PIPELINE_YAML =
  'stages:\n  - stage: Build\n    steps:\n      - sh: echo pulsar-golden-ok\n'

const TERMINAL_STATUSES = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'UNSTABLE', 'ERROR'])

function sign(body: Buffer): string {
  return 'sha256=' + crypto.createHmac('sha256', WEBHOOK_SECRET).update(body).digest('hex')
}

async function postSignedWebhook(
  request: APIRequestContext,
  payload: { repo: string; changeId: string; revision: string },
  deliveryId: string,
) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8')
  return request.post(`${API_BASE}/api/v1/pulsar/events`, {
    headers: {
      'Content-Type': 'application/json',
      'X-Pulsar-Signature-256': sign(body),
      'X-Pulsar-Delivery': deliveryId,
    },
    data: body,
    // The 202 is only returned AFTER the in-server git clone + discovery +
    // enqueue + synchronous enqueue-time check post — give it real headroom.
    timeout: 60_000,
  })
}

/** Delete this run's own scm_event_seen claim (exact key — ownership rule). */
async function deleteOwnDedupeRow(eventId: string): Promise<void> {
  const client = pgClient()
  await client.connect()
  try {
    await client.query(
      `DELETE FROM titan.scm_event_seen WHERE provider = 'pulsar' AND event_id = $1`,
      [eventId],
    )
  } finally {
    await client.end()
  }
}

test.describe('v3 pulsar-check-lifecycle @golden', () => {
  test('signed pulsar webhook → worker-executed SUCCESS → queued+success checks on the change ledger', async ({
    request,
  }) => {
    test.setTimeout(240_000)

    let fixture: PulsarChangeFixture | undefined
    let node: FakePulsarNode | undefined
    let bearer: string | undefined
    let jobId: number | undefined
    let buildId: number | undefined

    const dump = async (label: string) => {
      try {
        if (node) {
          await test.info().attach(`ledger-events-${label}.json`, {
            body: JSON.stringify(node.events, null, 2),
            contentType: 'application/json',
          })
        }
        if (bearer && buildId) {
          for (const leg of ['', '/nodes']) {
            const r = await request.get(`${API_BASE}/api/v1/builds/${buildId}${leg}`, {
              headers: { Authorization: `Bearer ${bearer}` },
            })
            await test.info().attach(`build${leg.replace('/', '-')}-${label}.json`, {
              body: await r.text(),
              contentType: 'application/json',
            })
          }
        }
      } catch {
        /* diagnostics only */
      }
    }

    try {
      // ── 1. Fixture: change tree + bare mirror + fake node ────────────────
      fixture = buildChangeFixture(REPO, CHANGE_ID, PIPELINE_YAML)
      node = await startFakePulsarNode(NODE_PORT, new Map([[REPO, fixture.bareDir]]))

      bearer = await fetchBearerToken(ENV)

      // ── 2. Preflight handshake: prove the rig seeds the webhook secret. ──
      // A correctly signed event for a repo with no job is an AUTHENTICATED
      // no-op (204). A 401 here means the rig predates the #97 compose seed
      // (PULSAR_WEBHOOK_SECRET) — a stale-rig failure, not a code regression.
      const handshake = await postSignedWebhook(
        request,
        { repo: `${REPO}-noexist`, changeId: CHANGE_ID, revision: fixture.revision },
        `e2e-58-handshake-${RUN_TAG}`,
      )
      expect(
        handshake.status(),
        `signed handshake webhook returned HTTP ${handshake.status()} ` +
          `(body=${await handshake.text()}). 401 ⇒ the rig is missing ` +
          `PULSAR_WEBHOOK_SECRET — re-up it (task dev:down && task dev:titan); ` +
          `rig/local/docker-compose.yml seeds it since #97.`,
      ).toBe(204)

      // ── 3. Job whose full_name == the pulsar repo (resolution contract). ─
      const jobCreate = await request.post(`${API_BASE}/api/v1/jobs`, {
        headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
        data: {
          fullName: REPO,
          displayName: 'E2E pulsar check-lifecycle (spec 58)',
          pipelineScript: PIPELINE_YAML,
          configJson: '{}',
          enabled: true,
        },
      })
      const jobRaw = await jobCreate.text()
      expect(
        jobCreate.status(),
        `POST /api/v1/jobs failed: HTTP ${jobCreate.status()} body=${jobRaw.slice(0, 400)}`,
      ).toBe(201)
      jobId = (JSON.parse(jobRaw) as { id: number }).id
      expect(jobId).toBeGreaterThan(0)

      // ── 4. The real signed change webhook. ───────────────────────────────
      const webhook = await postSignedWebhook(
        request,
        { repo: REPO, changeId: CHANGE_ID, revision: fixture.revision },
        `e2e-58-${RUN_TAG}`,
      )
      const webhookRaw = await webhook.text()
      expect(
        webhook.status(),
        `POST /api/v1/pulsar/events HTTP ${webhook.status()} body=${webhookRaw.slice(0, 600)} — ` +
          `a 500 usually means the in-server git fetch from ` +
          `http://host.docker.internal:${NODE_PORT}/${REPO}.git failed (extra_hosts / ` +
          `PULSAR_NODE_BASE_URL wiring); a 204 means job resolution or pipeline ` +
          `discovery no-opped.`,
      ).toBe(202)
      const accepted = JSON.parse(webhookRaw) as {
        accepted: boolean
        changeId: string
        buildId: number
      }
      expect(accepted.accepted).toBe(true)
      expect(accepted.changeId, 'webhook echoed a different changeId').toBe(CHANGE_ID)
      buildId = accepted.buildId
      expect(buildId).toBeGreaterThan(0)

      // ── 5. Enqueue-time check: pending/queued already on OUR change. ─────
      // fireEnqueued → CDI → reporter runs synchronously inside the webhook
      // request, so the event must exist by now; the short poll only covers
      // the reporter's single transient retry.
      await expect
        .poll(() => node!.eventsFor(REPO, CHANGE_ID).length, {
          message:
            `no enqueue-time check event arrived on the fake node for ` +
            `${REPO}/${CHANGE_ID} — PulsarWebhookApi.fireEnqueued → ` +
            `PulsarCheckReporter.onBuildEnqueued did not post`,
          timeout: 10_000,
        })
        .toBeGreaterThan(0)
      const queuedEvent = node.eventsFor(REPO, CHANGE_ID)[0]!
      expect(queuedEvent.body.kind, 'enqueue-time event kind').toBe('ci')
      expect(queuedEvent.body.check, 'the required_checks contract name').toBe('build')
      expect(queuedEvent.body.conclusion, 'enqueued build must report pending').toBe('pending')
      expect(queuedEvent.body.phase, 'enqueued build must carry phase:queued').toBe('queued')

      // ── 6. The build must be REALLY executed to SUCCESS by the worker. ───
      let finalStatus = ''
      await expect
        .poll(
          async () => {
            const r = await request.get(`${API_BASE}/api/v1/builds/${buildId}`, {
              headers: { Authorization: `Bearer ${bearer}` },
            })
            if (!r.ok()) return ''
            finalStatus = ((await r.json()) as { status: string }).status
            return TERMINAL_STATUSES.has(finalStatus) ? finalStatus : ''
          },
          {
            message:
              `build ${buildId} not terminal within 150s (last: "${finalStatus}") — ` +
              `worker not picking up SYNTHESIZE/EXECUTE, or engine stuck`,
            timeout: 150_000,
            intervals: [1_000, 2_000, 3_000],
          },
        )
        .not.toBe('')
      expect(
        finalStatus,
        `build ${buildId} ended "${finalStatus}", expected SUCCESS — the one-step ` +
          `echo pipeline is the golden path; any non-SUCCESS is a regression`,
      ).toBe('SUCCESS')

      // ── 7. Terminal check event lands on the SAME change. ────────────────
      await expect
        .poll(
          () =>
            node!
              .eventsFor(REPO, CHANGE_ID)
              .some((e) => e.body.conclusion === 'success'),
          {
            message:
              `build ${buildId} reached SUCCESS but no conclusion:success check event ` +
              `arrived on the ledger for ${REPO}/${CHANGE_ID} — BuildCloser → ` +
              `PulsarCheckReporter terminal report is broken. Ledger so far: ` +
              JSON.stringify(node.events),
            timeout: 30_000,
          },
        )
        .toBe(true)

      // ── 8. Full-lifecycle shape on the recorded ledger. ──────────────────
      const ours: RecordedCheckEvent[] = node.eventsFor(REPO, CHANGE_ID)
      // Every event is the `build` CI check.
      for (const e of ours) {
        expect(e.body.kind, `non-ci event on the ledger: ${JSON.stringify(e.body)}`).toBe('ci')
        expect(e.body.check, `unexpected check name: ${JSON.stringify(e.body)}`).toBe('build')
      }
      // First = enqueue-time queued; last = terminal success with NO phase
      // (a finished build has no in-flight phase).
      expect(ours[0]!.body.conclusion).toBe('pending')
      expect(ours[0]!.body.phase).toBe('queued')
      const last = ours[ours.length - 1]!
      expect(last.body.conclusion, 'the gate-clearing terminal event must be last').toBe(
        'success',
      )
      expect(last.body.phase, 'terminal success must carry no phase').toBeUndefined()
      // A SUCCESS build must never have reported failure, and any events
      // between queued and success must be pending-only (this admits the
      // in_progress leg if the engine ever starts emitting RUNNING — see the
      // header note on why it is not hard-asserted today).
      for (const e of ours.slice(0, -1)) {
        expect(
          e.body.conclusion,
          `non-pending event before the terminal one: ${JSON.stringify(e.body)}`,
        ).toBe('pending')
      }
      // Mis-routing guard: nothing may land on any other repo/change path.
      expect(
        node.events.length,
        `check events were posted to a foreign repo/change: ` +
          JSON.stringify(node.events.filter((e) => e.repo !== REPO || e.changeId !== CHANGE_ID)),
      ).toBe(ours.length)
    } catch (err) {
      await dump('failure')
      throw err
    } finally {
      // ── Teardown (ownership rule #59) — everything this spec created. ────
      if (jobId !== undefined) {
        const result = await safeDeleteJobCascade(request, jobId).catch((e) => {
          console.warn(`[58-pulsar] safeDeleteJobCascade failed: ${String(e)}`)
          return undefined
        })
        if (result && !result.deleted) {
          console.warn(
            `[58-pulsar] job ${jobId} left in place (leftover builds: ` +
              `${result.leftoverBuildIds.join(',')}) — per-run unique name prevents collisions`,
          )
        }
      }
      if (fixture) {
        await deleteOwnDedupeRow(`${REPO}:${CHANGE_ID}:${fixture.revision}`).catch(() => undefined)
        fs.rmSync(fixture.root, { recursive: true, force: true })
      }
      if (node) await node.close()
    }
  })
})

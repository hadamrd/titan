/**
 * 31-fixture-with-retry — discovery → trigger → flaky stage recovers on attempt 2
 * (closes #809).
 *
 * Drives the per-step retry primitive end-to-end:
 *
 *   1. Pre-check the fixture YAML is reachable:
 *      `hadamrd/titan-e2e-fixture/.titan/pipelines/with-retry.yml`.
 *      That YAML has one stage with one `sh` step carrying
 *      `retry: { maxAttempts: 3 }`. The `sh` body fails when a marker file
 *      doesn't exist and succeeds when it does — so attempt #1 FAILED, attempt
 *      #2 SUCCESS. Per-build success here proves retry actually fires (#785).
 *
 *   2. **Fixture limitation workaround.** The upstream YAML hardcodes the
 *      marker path to `/tmp/titan-retry-marker`. On a worker that's reused
 *      across multiple builds (the local rig's default), the marker created
 *      by build N survives into build N+1 → attempt #1 of N+1 immediately
 *      sees the file and passes, so the spec would silently *not* test retry.
 *      We patch the YAML client-side here, substituting the hardcoded path
 *      for a per-run-unique path keyed by RUN_TAG. (b)-strategy from the
 *      brief; a follow-up issue against the fixture repo should add
 *      `${{ build.id }}` templating so consumers don't have to patch.
 *
 *   3. Create a job whose `pipelineScript` is the patched YAML (discovery
 *      shape — same as specs 26/27/28).
 *
 *   4. Trigger the build manually via `POST /api/v1/jobs/{id}/builds`. Webhook
 *      path is overkill for a retry-mechanics assertion; the manual trigger
 *      keeps this spec independent of CredentialKeyProvider rig wiring.
 *
 *   5. Poll for terminal status with a generous 90s budget — retry imposes a
 *      backoff between attempt #1 (FAILED) and attempt #2's re-dispatch
 *      through the durable queue's `available_at`. 60s has been observed flaky
 *      on a cold worker.
 *
 *   6. HARD assert: build.status === 'SUCCESS'. If retry isn't wired, the
 *      build terminates FAILED after attempt #1 — that's the P1 product bug
 *      this spec is meant to catch.
 *
 *   7. HARD assert: `GET /api/v1/builds/{id}/nodes` returns a node whose
 *      `attempt >= 2` and `maxAttempts === 3`. The retry data model
 *      (V4__retry.sql) stores attempt as a counter ON the same flow_nodes
 *      row — a retry is the SAME unit of work re-run, not a new node — so we
 *      assert the counter rather than counting rows. `attempt >= 2`
 *      definitively proves the orchestrator re-dispatched after a failure.
 *
 *   8. HARD assert: the node carrying `attempt >= 2` has `status === SUCCESS`.
 *      The current-attempt status overwrites the previous attempt's status
 *      (same row), so we cannot assert "one attempt was FAILED" from the
 *      flow_nodes table alone — but a node with `attempt >= 2 && SUCCESS`
 *      necessarily had at least one prior FAILED attempt to trigger the
 *      re-dispatch. That's the strongest assertion the current data model
 *      supports. (If future schema adds a `flow_node_attempt` history table,
 *      this spec can be hardened.)
 *
 *   9. finally{}: delete the job (cascades the build). The per-run-unique
 *      marker path means we don't have to clean up the worker filesystem
 *      across runs.
 *
 * Diagnostics on fail: dumps build JSON + flow_nodes JSON. Two common bug
 * shapes to triage: (a) build FAILED + node attempt==1 → orchestrator never
 * re-dispatched; (b) build SUCCESS + node attempt==1 → marker leaked across
 * runs (the workaround in step 2 failed) and the spec is no longer testing
 * retry.
 */
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'
import { readFixtureYaml } from '../../fixtures/fixture-files'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

// Vendored mirror of hadamrd/titan-e2e-fixture — read from disk, never fetched (#48).
const FIXTURE_PATH = '.titan/pipelines/with-retry.yml'

const HARDCODED_MARKER = '/tmp/titan-retry-marker'

const RUN_TAG = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
// NOTE: must NOT share a string prefix with HARDCODED_MARKER, otherwise the
// post-substitution "still present" guard below trips on its own prefix.
const UNIQUE_MARKER = `/tmp/titan-e2e31-${RUN_TAG}.flag`
const JOB_FULL_NAME = `e2e-with-retry-${RUN_TAG}`

const TERMINAL_STATUSES = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'CANCELLED', 'ERROR'])

interface JobCreateResp {
  id: number
  fullName: string
}
interface TriggerBuildResp {
  buildId: number
  buildNumber: number
  status: string
}
interface BuildDetail {
  id: number
  status: string
}
interface FlowNode {
  buildId: number
  nodeId: string
  nodeType: string
  displayName: string
  stepDescriptor: string
  status: string
  attempt: number
  maxAttempts: number
  failureCategory?: string | null
  failureReason?: string | null
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
    // leave null
  }
  return { ok: r.ok(), status: r.status(), body, raw }
}

test.describe('v3 fixture-with-retry @golden', () => {
  test('discovery → trigger → flaky stage recovers on attempt 2', async ({ request }) => {
    test.setTimeout(150_000)

    // ── 1. Read the vendored fixture YAML (hermetic — #48) ──────────────────
    const upstreamYaml = readFixtureYaml(FIXTURE_PATH)
    expect(upstreamYaml.length, 'fixture YAML is empty').toBeGreaterThan(50)
    // Shape guard — fail loud BEFORE we burn 90s on a build if the fixture
    // mutated upstream in a way that breaks the marker workaround or removes
    // the retry policy this spec exists to exercise.
    expect(
      upstreamYaml,
      `fixture YAML shape changed — expected the hardcoded marker path ${HARDCODED_MARKER}`,
    ).toContain(HARDCODED_MARKER)
    expect(
      upstreamYaml,
      `fixture YAML shape changed — expected a retry block (maxAttempts:)`,
    ).toMatch(/retry\s*:/)
    expect(upstreamYaml).toMatch(/maxAttempts\s*:\s*3/)

    // ── 2. Patch the marker path to be per-run-unique. ──────────────────────
    // The upstream fixture hardcodes /tmp/titan-retry-marker. A worker reused
    // across builds keeps the file → next build's attempt #1 passes → spec
    // silently stops testing retry. Substituting a RUN_TAG-keyed path makes
    // every run start from a clean slate without touching the worker.
    const patchedYaml = upstreamYaml.split(HARDCODED_MARKER).join(UNIQUE_MARKER)
    expect(
      patchedYaml,
      'YAML substitution failed — RUN_TAG marker not present',
    ).toContain(UNIQUE_MARKER)
    expect(
      patchedYaml.includes(HARDCODED_MARKER),
      'YAML substitution incomplete — hardcoded marker still present',
    ).toBe(false)

    // ── Mutable state for cleanup + diagnostics ─────────────────────────────
    let bearer: string | undefined
    let jobId: number | undefined
    let buildId: number | undefined
    let attached = false

    const dump = async (label: string) => {
      if (attached) return
      attached = true
      try {
        await test.info().attach(`fixture-${label}.yml`, {
          body: patchedYaml,
          contentType: 'text/yaml',
        })
        if (bearer && buildId) {
          const build = await apiGet(request, bearer, `/api/v1/builds/${buildId}`)
          await test.info().attach(`build-${label}.json`, {
            body: build.raw,
            contentType: 'application/json',
          })
          const nodes = await apiGet(request, bearer, `/api/v1/builds/${buildId}/nodes`)
          await test.info().attach(`flow-nodes-${label}.json`, {
            body: nodes.raw,
            contentType: 'application/json',
          })
        }
        if (bearer && jobId) {
          const job = await apiGet(request, bearer, `/api/v1/jobs/${jobId}`)
          await test.info().attach(`job-${label}.json`, {
            body: job.raw,
            contentType: 'application/json',
          })
        }
      } catch (e) {
        await test.info().attach('diagnostics-error.txt', {
          body: String(e),
          contentType: 'text/plain',
        })
      }
    }

    try {
      // ── 3. Bearer via ROPC. ─────────────────────────────────────────────
      bearer = await fetchBearerToken(ENV)

      // ── 4. Create the job from the patched YAML (discovery shape). ──────
      const jobCreate = await request.post(`${API_BASE}/api/v1/jobs`, {
        headers: {
          Authorization: `Bearer ${bearer}`,
          'Content-Type': 'application/json',
        },
        data: {
          fullName: JOB_FULL_NAME,
          displayName: 'E2E with-retry fixture',
          pipelineScript: patchedYaml,
          enabled: true,
        },
      })
      const jobRaw = await jobCreate.text()
      expect(
        jobCreate.status(),
        `POST /api/v1/jobs HTTP ${jobCreate.status()} body=${jobRaw.slice(0, 600)}`,
      ).toBe(201)
      jobId = (JSON.parse(jobRaw) as JobCreateResp).id
      expect(jobId).toBeGreaterThan(0)

      // ── 5. Trigger the build. ───────────────────────────────────────────
      const triggerResp = await request.post(
        `${API_BASE}/api/v1/jobs/${jobId}/builds`,
        {
          headers: {
            Authorization: `Bearer ${bearer}`,
            'Content-Type': 'application/json',
          },
          data: { triggeredBy: 'e2e-spec-31' },
        },
      )
      const triggerRaw = await triggerResp.text()
      expect(
        triggerResp.status(),
        `POST /api/v1/jobs/${jobId}/builds HTTP ${triggerResp.status()} body=${triggerRaw.slice(0, 400)}`,
      ).toBe(201)
      const trig = JSON.parse(triggerRaw) as TriggerBuildResp
      buildId = trig.buildId
      expect(buildId).toBeGreaterThan(0)

      // ── 6. Poll until terminal — 90s budget for retry backoff. ──────────
      let observedStatus = 'QUEUED'
      await expect
        .poll(
          async () => {
            const r = await apiGet<BuildDetail>(
              request,
              bearer!,
              `/api/v1/builds/${buildId}`,
            )
            if (!r.ok || !r.body) return false
            observedStatus = r.body.status
            return TERMINAL_STATUSES.has(r.body.status)
          },
          {
            message:
              `build ${buildId} never reached a terminal status within 90s ` +
              `(last observed: ${observedStatus}) — orchestrator stuck, ` +
              `retry re-dispatch never fired, or worker not picking tasks`,
            timeout: 90_000,
            intervals: [500, 1_000, 2_000, 3_000],
          },
        )
        .toBe(true)

      // ── 7. HARD assert SUCCESS. ────────────────────────────────────────
      expect(
        observedStatus,
        `build ${buildId} terminated with status=${observedStatus}, expected SUCCESS. ` +
          `The fixture's flaky step fails on attempt #1 and succeeds on attempt #2 ` +
          `via marker file ${UNIQUE_MARKER}. Build-level FAILED means retry never ` +
          `fired — P1 product bug in the orchestrator's retry re-dispatch ` +
          `(V4__retry.sql + design/44 §4 CAS path). Diagnostics attached.`,
      ).toBe('SUCCESS')

      // ── 8. HARD assert attempt counter >= 2 on the flaky node. ─────────
      const nodesResp = await apiGet<FlowNode[]>(
        request,
        bearer,
        `/api/v1/builds/${buildId}/nodes`,
      )
      expect(nodesResp.ok, `GET /builds/${buildId}/nodes HTTP ${nodesResp.status}`).toBe(true)
      expect(nodesResp.body, 'flow-nodes body parsed').not.toBeNull()
      const nodes = nodesResp.body ?? []
      expect(nodes.length, 'flow_nodes empty — orchestrator never baked the DAG').toBeGreaterThan(0)

      // The flaky step is the only one carrying maxAttempts > 1 (the fixture
      // has exactly one stage / one step with retry). Find it that way rather
      // than coupling to a brittle nodeType/displayName.
      const retryNodes = nodes.filter((n) => n.maxAttempts >= 2)
      expect(
        retryNodes.length,
        `no flow_node carries maxAttempts>=2 — RetryPolicy never made it onto the ` +
          `flow_nodes row at bake time (V4__retry.sql column unpopulated, or the ` +
          `parser dropped the retry block). Nodes seen: ` +
          JSON.stringify(
            nodes.map((n) => ({
              nodeId: n.nodeId,
              type: n.nodeType,
              status: n.status,
              attempt: n.attempt,
              maxAttempts: n.maxAttempts,
            })),
          ),
      ).toBeGreaterThan(0)

      const flaky = retryNodes[0]!
      expect(
        flaky.maxAttempts,
        `flaky node maxAttempts=${flaky.maxAttempts}, expected 3 (per fixture YAML)`,
      ).toBe(3)
      expect(
        flaky.attempt,
        `flaky node attempt=${flaky.attempt}, expected >=2. attempt==1 with build SUCCESS ` +
          `means the marker leaked across runs (RUN_TAG substitution failed) and the spec ` +
          `is no longer testing retry — fix the marker workaround. attempt==1 with build ` +
          `FAILED means retry never fired (caught above).`,
      ).toBeGreaterThanOrEqual(2)

      // ── 9. HARD assert: a node with attempt>=2 is SUCCESS. ────────────
      // Retry-as-same-row means we can't observe the FAILED attempt #1 status
      // directly (it's overwritten on re-dispatch). But attempt>=2 + SUCCESS
      // necessarily implies a prior FAILED attempt triggered the
      // re-dispatch — that's the strongest assertion the current data
      // model supports. See spec header note on a future flow_node_attempt
      // history table that would let us assert "FAILED then SUCCESS" directly.
      expect(
        flaky.status,
        `flaky node attempt=${flaky.attempt} status=${flaky.status}, expected SUCCESS. ` +
          `A node with attempt>=2 and non-SUCCESS terminal status means retry ran but ` +
          `every attempt failed — either the marker workaround broke (file never gets ` +
          `created), or the shell step itself is broken on the worker.`,
      ).toBe('SUCCESS')
    } catch (err) {
      await dump('assertion-failure')
      throw err
    } finally {
      // ── Cleanup: must actually delete the job (closes #964). Pre-fix this
      // returned 405 and silently leaked a row per run — the rig's /jobs list
      // grew unbounded. Now DELETE returns 204 and the follow-up GET returns
      // 404. We don't fail the test if cleanup itself fails (the assertion
      // above is what we care about), but we do log the outcome so a future
      // regression is visible in the spec output.
      if (bearer && jobId) {
        const delResp = await request
          .delete(`${API_BASE}/api/v1/jobs/${jobId}`, {
            headers: { Authorization: `Bearer ${bearer}` },
          })
          .catch(() => null)
        if (delResp && delResp.status() !== 204) {
          console.warn(
            `cleanup: DELETE /api/v1/jobs/${jobId} expected 204, got ${delResp.status()}`,
          )
        }
        const getResp = await apiGet(request, bearer, `/api/v1/jobs/${jobId}`).catch(
          () => null,
        )
        if (getResp && getResp.status !== 404) {
          console.warn(
            `cleanup: GET /api/v1/jobs/${jobId} after delete expected 404, got ${getResp.status}`,
          )
        }
      }
    }
  })
})

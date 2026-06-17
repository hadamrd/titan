/**
 * 28-fixture-with-setBuildName — discovery → trigger-with-params → setBuildName
 * persists builds.display_name end-to-end (closes #798).
 *
 * Drives the full chain that PR #767 (V27 migration + SetBuildNameResolver),
 * PR #771 (BuildDto.displayName), and PR #778 (trigger-with-params) compose:
 *
 *   1. Pre-check: GET the fixture YAML
 *      `hadamrd/titan-e2e-fixture/.titan/pipelines/with-setBuildName.yml`.
 *      The fixture declares one parameter (`VERSION`, default `v0.0.0-dev`)
 *      and one stage with a single `setBuildName: "deploy-${{ params.VERSION }}"`
 *      step. Spec hard-depends on this file being reachable.
 *   2. Create a job whose `pipelineScript` is the fixture YAML (this exercises
 *      the discovery path — the same shape PR #590/#797 walked). No webhook
 *      credential is needed because we trigger via the manual API (step 3).
 *      That keeps the spec independent of CredentialKeyProvider rig config.
 *   3. **Alternative trigger path** (the #778 contract): instead of POSTing a
 *      synthetic GitHub push (which only ever supplies default params), we
 *      POST /api/v1/jobs/{id}/builds with `{parameters: {VERSION: 'v1.2.3'}}`.
 *      This is what PR #778 enables and what PR #767's resolver needs to
 *      observe in order to expand the YAML expression to `deploy-v1.2.3`.
 *   4. Poll until the build reaches a terminal status.
 *   5. HARD assert: build.status === 'SUCCESS' (the fixture stage is a cheap
 *      `setBuildName` step on `agent: linux` — any non-success is a real bug
 *      in resolver invocation, param wiring, or orchestrator).
 *   6. HARD assert: GET /api/v1/builds/{id} returns `displayName === 'deploy-v1.2.3'`
 *      (closes the persistence contract: V27 column populated + BuildDto
 *      surfaces it + parameter expansion produced the right string).
 *   7. HARD assert: GET /api/v1/builds?limit=10 contains this build's row
 *      with the same `displayName` field — proves the list endpoint also
 *      surfaces the column (the bug #771 fixed, regression-guarded here).
 *   8. finally{}: delete the job (cascades the build).
 *
 * Diagnostics on fail: dumps build JSON + flow_nodes JSON so triage can tell
 * "resolver not invoked" (displayName null, nodes finished SUCCESS) from
 * "param expansion failed" (displayName literally `deploy-${{ params.VERSION }}`)
 * from "stage execution broke" (nodes carry FAILED status).
 */
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const FIXTURE_REPO = 'hadamrd/titan-e2e-fixture'
const FIXTURE_BRANCH = 'main'
const FIXTURE_PATH = '.titan/pipelines/with-setBuildName.yml'
const FIXTURE_RAW_URL = `https://raw.githubusercontent.com/${FIXTURE_REPO}/${FIXTURE_BRANCH}/${FIXTURE_PATH}`
const FIXTURE_API_URL = `https://api.github.com/repos/${FIXTURE_REPO}/contents/${FIXTURE_PATH}`

const VERSION_OVERRIDE = 'v1.2.3'
const EXPECTED_DISPLAY_NAME = `deploy-${VERSION_OVERRIDE}`

const RUN_TAG = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
const JOB_FULL_NAME = `e2e-setbuildname-${RUN_TAG}`

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
  displayName?: string | null
}
interface BuildsListItem {
  id: number
  status: string
  displayName?: string | null
}
interface BuildsPage {
  items: BuildsListItem[]
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
    // leave null
  }
  return { ok: r.ok(), status: r.status(), body, raw }
}

test.describe('v3 fixture-with-setBuildName @golden', () => {
  test('discovery → trigger-with-params → setBuildName persists display_name', async ({
    request,
  }) => {
    test.setTimeout(120_000)

    // ── 1. Pre-check fixture availability ───────────────────────────────────
    const fixtureMeta = await request.get(FIXTURE_API_URL, {
      headers: { Accept: 'application/vnd.github.v3+json' },
    })
    test.skip(
      fixtureMeta.status() === 404,
      `Fixture file gone — ${FIXTURE_API_URL} returned 404. ` +
        `Spec hard-depends on hadamrd/titan-e2e-fixture carrying ${FIXTURE_PATH}.`,
    )
    expect(
      fixtureMeta.ok(),
      `GitHub API HTTP ${fixtureMeta.status()} for ${FIXTURE_API_URL}`,
    ).toBe(true)

    const rawResp = await request.get(FIXTURE_RAW_URL)
    expect(rawResp.ok(), `raw YAML fetch HTTP ${rawResp.status()}`).toBe(true)
    const fixtureYaml = await rawResp.text()
    expect(fixtureYaml.length, 'fixture YAML is empty').toBeGreaterThan(50)
    // Shape guard — if the fixture is mutated upstream so the expression no
    // longer interpolates VERSION, the whole spec premise breaks. Fail loud
    // with a precise message before we burn 60s on a build.
    expect(
      fixtureYaml,
      `fixture YAML shape changed — expected a setBuildName step referencing params.VERSION`,
    ).toContain('setBuildName')
    expect(fixtureYaml).toContain('params.VERSION')
    expect(fixtureYaml).toContain('VERSION')

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
          body: fixtureYaml,
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
      // ── 2. Bearer via ROPC (API-only spec — no SPA dependency). ───────────
      bearer = await fetchBearerToken(ENV)

      // ── 3. Create the job from the fixture YAML (discovery path). ─────────
      //      We trigger manually in step 4, so no webhook config / credential
      //      is needed; that keeps the spec independent of the rig's
      //      CredentialKeyProvider configuration.
      const jobCreate = await request.post(`${API_BASE}/api/v1/jobs`, {
        headers: {
          Authorization: `Bearer ${bearer}`,
          'Content-Type': 'application/json',
        },
        data: {
          fullName: JOB_FULL_NAME,
          displayName: 'E2E setBuildName fixture',
          pipelineScript: fixtureYaml,
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

      // ── 4. Trigger the build with typed parameter override (#778). ────────
      // This exercises the alternative-trigger path that lets us pass a
      // non-default VERSION. The webhook path would only ever supply defaults.
      const triggerResp = await request.post(
        `${API_BASE}/api/v1/jobs/${jobId}/builds`,
        {
          headers: {
            Authorization: `Bearer ${bearer}`,
            'Content-Type': 'application/json',
          },
          data: {
            parameters: { VERSION: VERSION_OVERRIDE },
            triggeredBy: 'e2e-spec-28',
          },
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

      // ── 5. Poll until the build reaches a terminal status. ────────────────
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
            message: `build ${buildId} never reached a terminal status within 60s ` +
              `(last observed: ${observedStatus}) — ` +
              `orchestrator stuck or worker not picking ORCHESTRATE/BAKE/EXECUTE tasks`,
            timeout: 60_000,
            intervals: [500, 1_000, 2_000, 3_000],
          },
        )
        .toBe(true)

      // ── 6. HARD assert SUCCESS. setBuildName on agent:linux is cheap; ─────
      //      any non-success is a real bug in the resolver/orchestrator chain.
      expect(
        observedStatus,
        `build ${buildId} terminated with status=${observedStatus}, expected SUCCESS. ` +
          `setBuildName step is a single noop-ish primitive on agent:linux — ` +
          `failure here means resolver dispatch, param expansion, or stage runner is broken. ` +
          `Diagnostics attached.`,
      ).toBe('SUCCESS')

      // ── 7. HARD assert displayName persisted with expanded param. ─────────
      const detail = await apiGet<BuildDetail>(
        request,
        bearer,
        `/api/v1/builds/${buildId}`,
      )
      expect(detail.ok, `GET /builds/${buildId} HTTP ${detail.status}`).toBe(true)
      expect(detail.body, 'build detail body parsed').not.toBeNull()
      expect(
        detail.body?.displayName ?? null,
        `build ${buildId} displayName is null. Two candidate bugs: ` +
          `(a) SetBuildNameResolver never ran (V27 migration unapplied, or step not ` +
          `wired into the executor), (b) the resolver ran but the parameter expression ` +
          `couldn't see params.VERSION=${VERSION_OVERRIDE}. ` +
          `See attached build + flow_nodes JSON to triage.`,
      ).not.toBeNull()
      expect(
        detail.body?.displayName,
        `build ${buildId} displayName="${detail.body?.displayName}", expected ` +
          `"${EXPECTED_DISPLAY_NAME}". If the literal string '\${{ params.VERSION }}' ` +
          `appears, param expansion is bypassed entirely. If 'deploy-v0.0.0-dev' appears, ` +
          `the typed parameters payload was ignored and the default kicked in (#778 regression).`,
      ).toBe(EXPECTED_DISPLAY_NAME)

      // ── 8. HARD assert the list endpoint surfaces displayName too. ────────
      const list = await apiGet<BuildsPage>(
        request,
        bearer,
        `/api/v1/builds?limit=10&offset=0`,
      )
      expect(list.ok, `GET /builds?limit=10 HTTP ${list.status}`).toBe(true)
      const row = list.body?.items.find((it) => it.id === buildId)
      expect(
        row,
        `build ${buildId} not in /api/v1/builds?limit=10 page — ordering changed ` +
          `or list endpoint filters out fresh builds`,
      ).toBeDefined()
      expect(
        row?.displayName,
        `list endpoint row for build ${buildId} carries displayName=` +
          `"${row?.displayName}" — list/detail divergence; #771 regression`,
      ).toBe(EXPECTED_DISPLAY_NAME)
    } catch (err) {
      await dump('assertion-failure')
      throw err
    } finally {
      // ── Cleanup: best-effort, never throws past the test boundary. ────────
      if (bearer && jobId) {
        await request
          .delete(`${API_BASE}/api/v1/jobs/${jobId}`, {
            headers: { Authorization: `Bearer ${bearer}` },
          })
          .catch(() => null)
      }
    }
  })
})

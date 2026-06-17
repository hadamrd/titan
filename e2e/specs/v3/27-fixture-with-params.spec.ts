/**
 * 29-fixture-with-params — discovery → trigger-with-params → string/choice/boolean
 * parameter override expansion + step-level `when:` evaluation end-to-end (closes #807).
 *
 * Drives the chain composed by PR #778 (trigger-with-params), the parser's
 * `parameters:` declaration support, and the worker's parameter resolver +
 * step-level `when:` evaluator. The fixture at
 * `hadamrd/titan-e2e-fixture/.titan/pipelines/with-params.yml` declares
 * three parameters covering the primitives:
 *
 *   - `GREETING` (string,  default "hello")
 *   - `MODE`     (choice,  default "dev", choices [dev, prod])
 *   - `VERBOSE`  (boolean, default false)
 *
 * and a single stage with two steps:
 *
 *   - `sh: "echo ${{ params.GREETING }} ${{ params.MODE }}"`
 *   - `sh: "echo verbose"` gated by `when: "params.VERBOSE"`
 *
 * Spec flow:
 *   1. Pre-check the fixture YAML is reachable + still has the expected shape
 *      (params + when:). Skip-on-404 (upstream rename) vs hard-fail on shape
 *      drift (which would silently invalidate the test).
 *   2. Create a credential (kind STRING — see PR #793 — even though we trigger
 *      manually here, we match the production-shape job config used by #26/#27/#28).
 *      Then create the job from the fixture YAML via the discovery contract
 *      (`pipelineScript = fixtureYaml`), with a github trigger config block so
 *      the job's `configJson` round-trips the same shape as a real customer job.
 *   3. POST /api/v1/jobs/{id}/builds with `{parameters: {GREETING:'world',
 *      MODE:'prod', VERBOSE:'true'}}` (the #778 contract — Map<String,String>).
 *   4. Poll until build reaches a terminal status. Budget 60s.
 *   5. HARD assert: build.status === SUCCESS.
 *   6. HARD assert: the SSE-aggregated log text for the build contains the
 *      substring `world prod` — proving GREETING + MODE expressions expanded
 *      into the rendered sh command at bake/exec time.
 *   7. HARD assert: the verbose-echo step actually ran. We trust two oracles
 *      (whichever wins): (a) the build log contains `verbose`, or (b) the
 *      flow_node for the verbose sh step has status SUCCESS (not SKIPPED /
 *      missing). If neither holds, the step-level `when:` evaluator rejected
 *      the truthy `params.VERBOSE` override — a real P1 regression.
 *   8. finally{}: cleanup job + credential.
 *
 * Diagnostics on fail: attach fixture YAML, full build JSON, flow_nodes JSON,
 * and the aggregated log text. Lets triage distinguish:
 *   - param expression not expanded  (log shows the literal `${{ params... }}`)
 *   - override ignored, default used (log shows `hello dev`)
 *   - `when:` evaluator wrong       (verbose-step SKIPPED despite VERBOSE=true)
 *   - worker dispatch broken         (terminal non-SUCCESS; nodes carry FAILED)
 */
import { test, expect, type APIRequestContext } from '@playwright/test'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const FIXTURE_REPO = 'hadamrd/titan-e2e-fixture'
const FIXTURE_BRANCH = 'main'
const FIXTURE_PATH = '.titan/pipelines/with-params.yml'
const FIXTURE_RAW_URL = `https://raw.githubusercontent.com/${FIXTURE_REPO}/${FIXTURE_BRANCH}/${FIXTURE_PATH}`
const FIXTURE_API_URL = `https://api.github.com/repos/${FIXTURE_REPO}/contents/${FIXTURE_PATH}`

// Parameter overrides (chosen to be distinct from the fixture defaults so the
// assertion really does prove override-vs-default). `world prod` is the
// concatenated echo, `verbose` is the conditional second echo.
const PARAM_GREETING = 'world'
const PARAM_MODE = 'prod'
const PARAM_VERBOSE = 'true'
const EXPECTED_GREET_LINE = `${PARAM_GREETING} ${PARAM_MODE}` // "world prod"

const RUN_TAG = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
const JOB_FULL_NAME = `e2e-with-params-${RUN_TAG}`
const CREDENTIAL_KEY = `e2e-with-params-cred-${RUN_TAG}`
const WEBHOOK_SECRET = `e2e-secret-${RUN_TAG}`

const TERMINAL_STATUSES = new Set(['SUCCESS', 'FAILED', 'ABORTED', 'CANCELLED', 'ERROR'])

interface JobCreateResp {
  id: number
  fullName: string
}
interface CredentialCreateResp {
  id: number
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
interface FlowNode {
  buildId: number
  nodeId: string
  parentIds?: string
  nodeType: string
  displayName?: string
  stepDescriptor?: string
  status: string
  logTaskId?: string | null
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

/**
 * Fetch the SSE log stream as plain text. The titan server closes the SSE
 * connection once the build is terminal (we've already polled for terminal),
 * so this `request.get` returns the buffered body promptly. We then strip the
 * `data:` SSE framing to extract the actual log lines.
 */
async function fetchBuildLogText(
  request: APIRequestContext,
  bearer: string,
  buildId: number,
): Promise<string> {
  const r = await request.get(`${API_BASE}/api/v1/builds/${buildId}/logs`, {
    headers: {
      Authorization: `Bearer ${bearer}`,
      Accept: 'text/event-stream',
    },
    timeout: 15_000,
  })
  if (!r.ok() && r.status() !== 204) {
    return `__LOG_FETCH_HTTP_${r.status()}__`
  }
  const raw = await r.text()
  // Strip SSE framing: keep only `data: ...` lines, drop the `data: ` prefix.
  const lines = raw
    .split(/\r?\n/)
    .filter((l) => l.startsWith('data:'))
    .map((l) => l.replace(/^data:\s?/, ''))
  return lines.join('\n')
}

test.describe('v3 fixture-with-params @golden', () => {
  test('discovery → trigger-with-params → string+choice+boolean override + step `when:` expansion', async ({
    request,
  }) => {
    test.setTimeout(150_000)

    // ── 1. Pre-check fixture availability + shape. ──────────────────────────
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

    // Shape guard — if upstream drops a param or rewrites the when-step, the
    // assertions below stop meaning what they claim. Fail loud, fast.
    expect(fixtureYaml, 'fixture missing GREETING param').toContain('GREETING')
    expect(fixtureYaml, 'fixture missing MODE param').toContain('MODE')
    expect(fixtureYaml, 'fixture missing VERBOSE param').toContain('VERBOSE')
    expect(fixtureYaml, 'fixture missing params.GREETING expr').toContain('params.GREETING')
    expect(fixtureYaml, 'fixture missing params.MODE expr').toContain('params.MODE')
    expect(fixtureYaml, 'fixture missing `when: params.VERBOSE` guard').toMatch(
      /when:\s*['"]?params\.VERBOSE/,
    )

    // ── Mutable state for cleanup + diagnostics ─────────────────────────────
    let bearer: string | undefined
    let jobId: number | undefined
    let credentialId: number | undefined
    let buildId: number | undefined
    let logText = ''
    let nodesRaw = ''
    let buildRaw = ''
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
          buildRaw = build.raw
          await test.info().attach(`build-${label}.json`, {
            body: buildRaw,
            contentType: 'application/json',
          })
          const nodes = await apiGet(request, bearer, `/api/v1/builds/${buildId}/nodes`)
          nodesRaw = nodes.raw
          await test.info().attach(`flow-nodes-${label}.json`, {
            body: nodesRaw,
            contentType: 'application/json',
          })
          if (!logText) {
            logText = await fetchBuildLogText(request, bearer, buildId).catch(
              (e) => `__LOG_FETCH_THROWN_${String(e)}__`,
            )
          }
          await test.info().attach(`logs-${label}.txt`, {
            body: logText,
            contentType: 'text/plain',
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
      // ── 2. Bearer via ROPC. ──────────────────────────────────────────────
      bearer = await fetchBearerToken(ENV)

      // ── 3. Credential (STRING per PR #793) — matches the production job
      //      shape used by specs #26/#27/#28, even though we trigger manually
      //      below. Keeps job-config round-trip realistic.
      const credCreate = await request.post(`${API_BASE}/api/v1/credentials`, {
        headers: {
          Authorization: `Bearer ${bearer}`,
          'Content-Type': 'application/json',
        },
        data: {
          kind: 'STRING',
          scope: 'github-webhook',
          key: CREDENTIAL_KEY,
          plaintext: WEBHOOK_SECRET,
        },
      })
      const credRaw = await credCreate.text()
      expect(
        credCreate.status(),
        `POST /api/v1/credentials HTTP ${credCreate.status()} body=${credRaw.slice(0, 400)}`,
      ).toBe(201)
      credentialId = (JSON.parse(credRaw) as CredentialCreateResp).id
      expect(credentialId).toBeGreaterThan(0)

      // ── 4. Create the job from the fixture YAML + github trigger config. ─
      const triggersConfig = {
        triggers: [
          {
            type: 'github',
            id: 'github-1',
            branches: [FIXTURE_BRANCH],
            events: ['push'],
            credentialsId: CREDENTIAL_KEY,
          },
        ],
      }
      const jobCreate = await request.post(`${API_BASE}/api/v1/jobs`, {
        headers: {
          Authorization: `Bearer ${bearer}`,
          'Content-Type': 'application/json',
        },
        data: {
          fullName: JOB_FULL_NAME,
          displayName: 'E2E with-params fixture',
          pipelineScript: fixtureYaml,
          configJson: JSON.stringify(triggersConfig),
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

      // ── 5. Trigger build with typed parameter overrides (PR #778 contract). ─
      //      All values are strings — the backend signature is Map<String,String>
      //      regardless of declared param type; the parser coerces.
      const triggerResp = await request.post(
        `${API_BASE}/api/v1/jobs/${jobId}/builds`,
        {
          headers: {
            Authorization: `Bearer ${bearer}`,
            'Content-Type': 'application/json',
          },
          data: {
            parameters: {
              GREETING: PARAM_GREETING,
              MODE: PARAM_MODE,
              VERBOSE: PARAM_VERBOSE,
            },
            triggeredBy: 'e2e-spec-29',
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

      // ── 6. Poll until terminal (60s budget per brief). ───────────────────
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
              `build ${buildId} never reached a terminal status within 60s ` +
              `(last observed: ${observedStatus}) — ` +
              `orchestrator stuck, worker not dispatched, or param-resolver hung`,
            timeout: 60_000,
            intervals: [500, 1_000, 2_000, 3_000],
          },
        )
        .toBe(true)

      // ── 7. HARD assert SUCCESS. Two cheap `sh: echo` steps on agent:linux —
      //      any non-success is a real bug in the param-resolution pipeline.
      expect(
        observedStatus,
        `build ${buildId} terminated with status=${observedStatus}, expected SUCCESS. ` +
          `Two echo steps on agent:linux can only fail because: ` +
          `(a) parameter expansion blew up at bake, ` +
          `(b) the step-level when: evaluator threw on params.VERBOSE, ` +
          `(c) worker dispatch broken. Diagnostics attached.`,
      ).toBe('SUCCESS')

      // ── 8. HARD assert log contains the expanded GREETING+MODE echo. ─────
      logText = await fetchBuildLogText(request, bearer, buildId)
      expect(
        logText.length,
        `log fetch for build ${buildId} returned 0 bytes — SSE endpoint never ` +
          `flushed buffered lines for a terminal build (catches a logs/archive ` +
          `linkage regression; see PR #490 class).`,
      ).toBeGreaterThan(0)
      expect(
        logText,
        `log text does not contain "${EXPECTED_GREET_LINE}". ` +
          `If the literal "\${{ params.GREETING }}" appears, parameter expression ` +
          `expansion is bypassed entirely. If "hello dev" appears, the typed parameters ` +
          `payload was ignored and the fixture defaults kicked in (PR #778 regression). ` +
          `Log excerpt: ${logText.slice(0, 800)}`,
      ).toContain(EXPECTED_GREET_LINE)

      // ── 9. HARD assert the conditional verbose-echo ran (step-level `when:`). ─
      //      Two independent oracles — either is sufficient. We need at least
      //      one to fire, otherwise the when: evaluator rejected the truthy
      //      VERBOSE override and we have a P1 regression.
      const nodesResp = await apiGet<FlowNode[]>(
        request,
        bearer,
        `/api/v1/builds/${buildId}/nodes`,
      )
      nodesRaw = nodesResp.raw
      expect(
        nodesResp.ok,
        `GET /builds/${buildId}/nodes HTTP ${nodesResp.status}`,
      ).toBe(true)
      const nodes = nodesResp.body ?? []

      // Oracle A: log carries the literal `verbose` (only printed by the
      // guarded echo). The first echo prints `world prod`, not `verbose`.
      const logHasVerbose = /(^|\n|\r)verbose(\r|\n|$)/.test(logText) ||
        logText.split(/\r?\n/).some((l) => l.trim() === 'verbose')

      // Oracle B: a flow_node corresponding to `echo verbose` finished SUCCESS
      // (and was not SKIPPED). Match conservatively via stepDescriptor or
      // displayName containing "verbose".
      const verboseNode = nodes.find(
        (n) =>
          (n.stepDescriptor && /verbose/i.test(n.stepDescriptor)) ||
          (n.displayName && /verbose/i.test(n.displayName)),
      )
      const nodeRanSuccessfully =
        !!verboseNode && verboseNode.status === 'SUCCESS'
      const nodeSkipped = !!verboseNode && verboseNode.status === 'SKIPPED'

      expect(
        logHasVerbose || nodeRanSuccessfully,
        `Step-level \`when: params.VERBOSE\` did NOT cause the second echo to run ` +
          `despite VERBOSE='true' override. ` +
          `Oracle A (log contains 'verbose'): ${logHasVerbose}. ` +
          `Oracle B (verbose-node status=SUCCESS): ${nodeRanSuccessfully} ` +
          `(node found: ${!!verboseNode}, status: ${verboseNode?.status ?? 'n/a'}). ` +
          `If the verbose node is SKIPPED, the when: evaluator returned false for ` +
          `a truthy "true" string — P1 regression in the parameter-coercion path. ` +
          `If the verbose node is absent entirely, the parser never wired the ` +
          `conditional step into the flow graph. ` +
          `Nodes: ${nodesRaw.slice(0, 1200)}. ` +
          `Log excerpt: ${logText.slice(0, 800)}`,
      ).toBe(true)

      // Bonus belt-and-braces: if we found the node, it MUST NOT be SKIPPED.
      // (SKIPPED + log-line-by-coincidence would falsely pass the OR above —
      // unlikely but worth guarding.)
      expect(
        nodeSkipped,
        `verbose-echo flow_node status=SKIPPED despite VERBOSE='true' override — ` +
          `when: evaluator coerced 'true' (string) to false. Bug class: ` +
          `boolean-param string-truthiness. Node: ${JSON.stringify(verboseNode)}`,
      ).toBe(false)
    } catch (err) {
      await dump('assertion-failure')
      throw err
    } finally {
      // ── Cleanup: best-effort, never throws past the test boundary. ──────
      if (bearer && jobId) {
        await request
          .delete(`${API_BASE}/api/v1/jobs/${jobId}`, {
            headers: { Authorization: `Bearer ${bearer}` },
          })
          .catch(() => null)
      }
      if (bearer && credentialId) {
        await request
          .delete(`${API_BASE}/api/v1/credentials/${credentialId}`, {
            headers: { Authorization: `Bearer ${bearer}` },
          })
          .catch(() => null)
      }
    }
  })
})

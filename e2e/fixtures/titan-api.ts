/**
 * The `titanApi` fixture — design/43 §2 axis B + §5.
 *
 * A thin client over Titan's own surfaces, built on Playwright's `request`
 * fixture so every call is traced:
 *   - submitPipeline(name, yaml)  — create/update a TitanJob, run it, return
 *     the build it produced (white-box: pipelines are declarative data §4).
 *   - graph(buildUrl)            — the DAG `tree` payload from TitanGraphApiAction.
 *   - steps(buildUrl)            — the `allSteps` payload (per-step statuses).
 *   - flowNodes(buildUrl)        — a name->status map keyed by Titan node id.
 *   - pollToCompletion(buildUrl) — poll the graph API until the run is terminal.
 *   - consoleLog(buildUrl)       — the whole build's console text.
 *   - approveGate / rejectGate   — resolve a manual-judgement gate.
 *
 * Assertions go through TitanGraphApiAction's documented JSON (design/43 §2):
 * `<runUrl>stages/tree` and `<runUrl>stages/allSteps`. Asserting on the API a
 * real client uses keeps tests decoupled from the `titan.flow_nodes` schema.
 */
import type { APIRequestContext } from '@playwright/test';

/**
 * The hard wall-clock cap for ANY poll loop in this harness, in ms.
 *
 * No poll — to-completion, gate-wait, build-number, result-settle — may run
 * longer than this. A stuck Titan must surface as a fast, diagnosable failure,
 * never a multi-minute hang (the 20-minute gate hang this bound exists to kill).
 *
 * Override per-environment with `TITAN_POLL_TIMEOUT_MS` (e.g. a slow CI rig).
 */
export const POLL_TIMEOUT_MS = Number(process.env.TITAN_POLL_TIMEOUT_MS ?? 120_000);

/**
 * Thrown by the legacy plugin-host provisioning helpers (`runGroovy`,
 * `submitPipeline`, `seedSecret`, `resolveDbBuildId`). Phase 3 deleted the
 * plugin-host script console these used (docs/design/58-phase3-deletion-completed.md),
 * so they cannot reach the standalone titan-server. The standalone harness
 * provisions via the real REST surface — see `e2e/fixtures/titan-api-v3.ts`
 * and the v3 specs under `e2e/specs/v3/`.
 */
const RETIRED_HARNESS_MESSAGE =
  'legacy plugin-host harness retired (#1175): the script console was deleted in ' +
  'Phase 3. Use the v3 REST client (e2e/fixtures/titan-api-v3.ts) / a v3 spec instead.';

/** Pipeline Graph View `PipelineState` values TitanGraphApiAction emits. */
export type GraphState =
  | 'success'
  | 'failure'
  | 'aborted'
  | 'unstable'
  | 'running'
  | 'queued'
  | 'skipped'
  | 'not_built'
  | 'paused'
  | 'unknown';

export interface GraphStage {
  id: string;
  name: string;
  state: GraphState;
  type: string;
  startTimeMillis: number;
  totalDurationMillis: number | null;
  children?: GraphStage[];
}

export interface GraphStep {
  id: string;
  name: string;
  state: GraphState;
  stageId: string;
  startTimeMillis: number;
  totalDurationMillis: number | null;
  inputStep?: { id: string; message: string; ok: string; cancel: string } | null;
  /** Per-step retry attempt (design/44 §5) — present only for a step with a `retry:` policy. */
  attempt?: number | null;
  /** The step's `retry.maxAttempts` (design/44 §5) — present only for a retrying step. */
  maxAttempts?: number | null;
}

export interface PipelineGraph {
  complete: boolean;
  stages: GraphStage[];
}

/** A node as seen by scenario `expect` blocks — keyed by Titan node id. */
export interface NodeView {
  /** Titan node id, e.g. `a` (stage) or `a-s0` (step) or a gate's slug. */
  id: string;
  /** Build-status string mapped back from PipelineState (SUCCESS/FAILED/...). */
  status: string;
  state: GraphState;
  startTimeMillis: number;
  durationMillis: number;
  /**
   * The per-step retry attempt the node reached (design/44 §5). Present only
   * for a step carrying a `retry:` policy — the graph API omits it otherwise.
   */
  attempt?: number;
}

/** Overall build outcome — the scenario `expect.build` value. */
export type BuildResult = 'SUCCESS' | 'FAILURE' | 'ABORTED' | 'UNSTABLE' | 'UNKNOWN';

/** A build produced by a submission — its number and run-relative URL. */
export interface TitanBuild {
  jobName: string;
  number: number;
  /** Context-absolute, no host: e.g. `/job/foo/3/`. */
  runPath: string;
  /** The Titan engine DB build id (`titan.builds.id`) — the gate-decision key. */
  dbBuildId: number;
}

/** PipelineState -> Titan status string, the inverse of TitanGraphApiAction.state(). */
function statusOf(state: GraphState): string {
  switch (state) {
    case 'success':
      return 'SUCCESS';
    case 'failure':
      return 'FAILED';
    case 'aborted':
      return 'ABORTED';
    case 'unstable':
      return 'UNSTABLE';
    case 'running':
      return 'RUNNING';
    case 'queued':
      return 'QUEUED';
    case 'skipped':
      return 'SKIPPED';
    case 'paused':
      return 'PAUSED';
    case 'not_built':
      return 'PENDING';
    default:
      return 'UNKNOWN';
  }
}

export class TitanApi {
  /** Cached CSRF crumb header — fetched once, reused for every POST. */
  private crumb: Record<string, string> | null = null;

  /**
   * Every build this client has submitted in the current test. The `afterEach`
   * teardown (fixtures/index.ts) reads this and cancels any that are still
   * non-terminal — the safety net so a test that FAILS before deciding its
   * gate never leaves a build "Awaiting approval" on the rig.
   */
  readonly createdBuilds: TitanBuild[] = [];

  constructor(
    private readonly request: APIRequestContext,
    private readonly baseUrl: string,
  ) {}

  /**
   * The CSRF crumb header the controller requires on POSTs when crumb issuance is on.
   * The ephemeral E2E rig runs no security (jcasc.yaml) and issues no crumb —
   * then `/crumbIssuer` 404s and this returns `{}`, which is correct.
   *
   * A SUCCESSFUL crumb is cached (the crumb itself is stable for the request
   * context's session). A failed/absent fetch is NOT cached — a transient
   * miss must not poison every later POST with an empty header.
   */
  private async crumbHeader(): Promise<Record<string, string>> {
    if (this.crumb !== null) return this.crumb;
    try {
      const res = await this.request.get(`${this.baseUrl}/crumbIssuer/api/json`);
      if (res.ok()) {
        const json = (await res.json()) as { crumbRequestField?: string; crumb?: string };
        if (json.crumbRequestField && json.crumb) {
          this.crumb = { [json.crumbRequestField]: json.crumb };
          return this.crumb;
        }
      }
      if (res.status() === 404) {
        // No crumb issuer — the rig runs no security. Cache the empty header.
        this.crumb = {};
        return this.crumb;
      }
    } catch {
      /* transient — fall through, do not cache */
    }
    // Transient miss — return an empty header for this call without caching,
    // so the next POST re-attempts the crumb fetch.
    return {};
  }

  /**
   * RETIRED — legacy plugin-host harness (#1175).
   *
   * This method ran a program on the deleted plugin-host script console
   * (`/scriptText`) to provision rig state. Phase 3 removed that runtime
   * (docs/design/58-phase3-deletion-completed.md), so the endpoint no longer
   * exists on the standalone titan-server. The standalone harness provisions
   * via the real REST surface instead (e.g. `POST /api/v1/credentials`,
   * `POST /api/v1/jobs`) — see `e2e/fixtures/titan-api-v3.ts` and the v3 specs
   * under `e2e/specs/v3/`. Kept only so the legacy callers still type-check;
   * it throws if actually invoked.
   */
  async runGroovy(_script: string): Promise<string> {
    throw new Error(RETIRED_HARNESS_MESSAGE);
  }

  /**
   * RETIRED — legacy plugin-host harness (#1175).
   *
   * Created/ran a job via the deleted plugin-host script console. The
   * standalone path is two REST calls — `POST /api/v1/jobs` then
   * `POST /api/v1/jobs/{id}/builds` — as used by the v3 specs. Kept only so
   * the legacy callers still type-check; it throws if actually invoked.
   */
  async submitPipeline(
    _jobName: string,
    _pipelineYaml: string,
    _opts: { branch?: string } = {},
  ): Promise<TitanBuild> {
    throw new Error(RETIRED_HARNESS_MESSAGE);
  }

  /**
   * RETIRED — legacy plugin-host harness (#1175).
   *
   * Seeded a secret into the deleted plugin-host credential store via the
   * script console. The standalone secret-seed surface is the Keycloak-authed
   * `POST /api/v1/credentials` — see the local `seedSecret` helper in
   * `e2e/specs/golden-path-secret-redaction.spec.ts`. Kept only so the legacy
   * callers still type-check; it throws if actually invoked.
   */
  async seedSecret(_key: string, _value: string): Promise<void> {
    throw new Error(RETIRED_HARNESS_MESSAGE);
  }

  /**
   * RETIRED — legacy plugin-host harness (#1175).
   *
   * Read a controller run's engine DB build id off the deleted plugin-host
   * script console. The standalone build id comes straight from the REST
   * build/job APIs. Kept only so the legacy callers still type-check.
   */
  private async resolveDbBuildId(_jobName: string, _number: number): Promise<number> {
    throw new Error(RETIRED_HARNESS_MESSAGE);
  }

  /** `GET <runUrl>stages/tree` — the DAG graph payload. */
  async graph(build: TitanBuild): Promise<PipelineGraph> {
    const res = await this.request.get(`${this.baseUrl}${build.runPath}stages/tree`);
    if (!res.ok()) {
      throw new Error(`stages/tree failed (${res.status()}) for ${build.runPath}`);
    }
    const env = (await res.json()) as { status: string; data: PipelineGraph };
    return env.data;
  }

  /** `GET <runUrl>stages/allSteps` — every step across the build. */
  async steps(build: TitanBuild): Promise<GraphStep[]> {
    const res = await this.request.get(`${this.baseUrl}${build.runPath}stages/allSteps`);
    if (!res.ok()) {
      throw new Error(`stages/allSteps failed (${res.status()}) for ${build.runPath}`);
    }
    const env = (await res.json()) as { status: string; data: { steps: GraphStep[] } };
    return env.data.steps ?? [];
  }

  /**
   * A flat name->NodeView map keyed by Titan node id — what scenario `expect`
   * blocks assert against. Stage node ids are the stage's slug (`build`);
   * step node ids are `<stageSlug>-s<index>` (`build-s0`). The graph API emits
   * numeric ids on the wire; this fixture re-keys by Titan id by parsing the
   * stage URL's `selected-node` and matching step ids structurally.
   *
   * Because the wire `id` is numeric and the Titan id is not directly carried
   * for stages, scenarios key on stage NAME (slugged) and step ordinal — the
   * resolver below maps wire stages/steps back to Titan-style ids.
   */
  async flowNodes(build: TitanBuild): Promise<Map<string, NodeView>> {
    const graph = await this.graph(build);
    const steps = await this.steps(build);
    const map = new Map<string, NodeView>();

    // Stages: Titan id == slug(name). The bake slugs the stage name; redo it.
    for (const st of flattenStages(graph.stages)) {
      const id = slug(st.name);
      map.set(id, {
        id,
        status: statusOf(st.state),
        state: st.state,
        startTimeMillis: st.startTimeMillis,
        durationMillis: st.totalDurationMillis ?? 0,
      });
    }
    // Steps: id == `<stageSlug>-s<ordinal>`. Group by wire stageId, order by
    // wire step id (numeric, monotonically minted by Plan in stage order).
    const byStage = new Map<string, GraphStep[]>();
    for (const sp of steps) {
      const arr = byStage.get(sp.stageId) ?? [];
      arr.push(sp);
      byStage.set(sp.stageId, arr);
    }
    // Map wire stageId -> Titan stage slug via the graph's stage list order.
    const wireStageToSlug = new Map<string, string>();
    for (const st of flattenStages(graph.stages)) {
      wireStageToSlug.set(st.id, slug(st.name));
    }
    for (const [wireStageId, group] of byStage) {
      const stageSlug = wireStageToSlug.get(wireStageId);
      if (!stageSlug) continue;
      group.sort((a, b) => Number(a.id) - Number(b.id));
      group.forEach((sp, ordinal) => {
        const id = `${stageSlug}-s${ordinal}`;
        map.set(id, {
          id,
          status: statusOf(sp.state),
          state: sp.state,
          startTimeMillis: sp.startTimeMillis,
          durationMillis: sp.totalDurationMillis ?? 0,
          // design/44 §5: the graph API surfaces a retrying step's attempt count.
          ...(sp.attempt != null ? { attempt: sp.attempt } : {}),
        });
      });
    }
    return map;
  }

  /**
   * Poll `stages/tree` until the run is genuinely complete, then return the
   * final graph.
   *
   * A freshly-submitted build has not been baked yet — its `flow_nodes` are
   * empty, and TitanGraphApiAction's `Plan.complete` is vacuously `true` over
   * zero rows. Treating that as "complete" would return before the pipeline
   * even started. So completion requires BOTH `complete` true AND at least one
   * stage present — i.e. the DAG was materialised and then finished.
   */
  async pollToCompletion(build: TitanBuild, timeoutMs = POLL_TIMEOUT_MS): Promise<PipelineGraph> {
    const deadline = Date.now() + timeoutMs;
    let last: PipelineGraph | null = null;
    while (Date.now() < deadline) {
      last = await this.graph(build);
      if (last.complete && last.stages.length > 0) return last;
      // A gate awaiting approval shows `paused`; the run is not "complete"
      // but also will not progress without a decision — callers handle gates
      // explicitly (approveGate) before polling on. If we see a paused gate
      // here it means the gate was never decided — FAIL FAST, do not burn the
      // whole timeout waiting for a run that cannot progress on its own.
      const pausedGate = flattenStages(last.stages).find((s) => s.state === 'paused');
      if (pausedGate) {
        throw new Error(
          `build ${build.runPath} is stuck at an undecided gate ` +
            `'${pausedGate.id}' ("${pausedGate.name}") — a gate decision was ` +
            `never applied. Last DAG:\n${dumpGraph(last)}`,
        );
      }
      await this.sleep(3_000);
    }
    throw new Error(
      `build ${build.runPath} did not complete within ${timeoutMs / 1000}s. ` +
        `Last DAG:\n${dumpGraph(last)}`,
    );
  }

  /**
   * Wait until a gate node is genuinely in its waiting/paused state, then
   * return BOTH the graph stage and the gate's REAL Titan node id (read from
   * the API, never guessed by slug rules).
   *
   * The gate's authoritative node id is the synthetic gate step's id in
   * `stages/allSteps` — TitanGraphApiAction emits `step.id = gate.nodeId` and
   * the same id on `inputStep.id` (design/43; TitanGraphApiAction §gate). The
   * approval POST MUST target that id, otherwise `GateService.decide`'s
   * compare-and-set finds no `RUNNING` row and the gate never releases.
   *
   * Fails fast on timeout with the last-seen DAG dumped.
   */
  async waitForPausedGate(
    build: TitanBuild,
    timeoutMs = POLL_TIMEOUT_MS,
  ): Promise<{ stage: GraphStage; gateNodeId: string }> {
    const deadline = Date.now() + timeoutMs;
    let last: PipelineGraph | null = null;
    while (Date.now() < deadline) {
      last = await this.graph(build);
      const stage = flattenStages(last.stages).find((s) => s.state === 'paused');
      if (stage) {
        // Cross-check against the steps payload: the paused gate's synthetic
        // step carries its real node id (and an `inputStep` while awaiting).
        const steps = await this.steps(build);
        const gateStep = steps.find(
          (sp) => sp.state === 'paused' && (sp.inputStep != null || sp.stageId === stage.id),
        );
        const gateNodeId = gateStep?.inputStep?.id ?? gateStep?.id ?? stage.id;
        return { stage, gateNodeId };
      }
      // `complete` is vacuously true over an un-baked DAG (zero flow_nodes);
      // only a complete DAG that actually has stages is genuinely finished.
      // A finished build that never paused means the gate scenario is wrong.
      if (last.complete && last.stages.length > 0) {
        throw new Error(
          `build ${build.runPath} completed without ever pausing at a gate. ` +
            `Last DAG:\n${dumpGraph(last)}`,
        );
      }
      await this.sleep(2_000);
    }
    throw new Error(
      `no paused gate on ${build.runPath} within ${timeoutMs / 1000}s. ` +
        `Last DAG:\n${dumpGraph(last)}`,
    );
  }

  /**
   * Approve a manual-judgement gate, sequencing the decision correctly:
   *   1. poll (bounded) until the gate node is genuinely PAUSED/awaiting —
   *      approving before the gate node is `RUNNING` would no-op the engine's
   *      compare-and-set and leave the build hanging;
   *   2. read the gate's REAL Titan node id from the API (never a slug guess);
   *   3. POST the decision and ASSERT the response — a silently-failed
   *      approval is exactly what produced the original 20-minute hang.
   *
   * Uses the engine's documented decision endpoint —
   * `POST /titan-gate/approve?buildId=<dbBuildId>&nodeId=<gateNodeId>`
   * (GateApprovalAction, design/29 §7.1) — keyed by the DB build id.
   *
   * @param expectedNodeId when given, asserts the API-read gate id matches it
   *   (a scenario's declared `gate.approve` id) — catches a model/DAG drift.
   */
  async approveGate(build: TitanBuild, expectedNodeId?: string): Promise<void> {
    await this.decideGate(build, 'approve', expectedNodeId);
  }

  /** Reject a manual-judgement gate — same bounded, id-from-API, asserted flow. */
  async rejectGate(build: TitanBuild, expectedNodeId?: string): Promise<void> {
    await this.decideGate(build, 'reject', expectedNodeId);
  }

  private async decideGate(
    build: TitanBuild,
    verb: 'approve' | 'reject',
    expectedNodeId?: string,
  ): Promise<void> {
    // 1. The gate must actually be paused/awaiting before a decision is sent.
    const { stage, gateNodeId } = await this.waitForPausedGate(build);
    if (expectedNodeId && expectedNodeId !== gateNodeId) {
      // Not fatal — the API id is authoritative — but surface the drift so a
      // stale scenario `gate.approve` value is visible, not silently masked.
      // eslint-disable-next-line no-console
      console.warn(
        `[titan] gate id from API is '${gateNodeId}' but scenario expected ` +
          `'${expectedNodeId}'; using the API id (build ${build.runPath}).`,
      );
    }

    // 2. POST the decision against the real DB build id + real gate node id.
    const res = await this.request.post(`${this.baseUrl}/titan-gate/${verb}`, {
      headers: await this.crumbHeader(),
      params: { buildId: String(build.dbBuildId), nodeId: gateNodeId },
    });
    const status = res.status();
    const body = (await res.text()).trim();

    // 3. Assert the response. 409 CONFLICT is benign — the gate was already
    // resolved (a re-delivery, or a prior decision won the compare-and-set);
    // the gate IS decided, which is the intent. Anything else 4xx/5xx is a
    // real failure and must abort the test immediately with the engine's body.
    if (status === 409) {
      // eslint-disable-next-line no-console
      console.warn(`[titan] ${verb}Gate('${gateNodeId}') already resolved (409): ${body}`);
      return;
    }
    if (status < 200 || status >= 300) {
      throw new Error(
        `${verb}Gate failed for build ${build.runPath}: HTTP ${status}\n` +
          `  POST /titan-gate/${verb}?buildId=${build.dbBuildId}&nodeId=${gateNodeId}\n` +
          `  response body: ${body.slice(0, 400)}\n` +
          `  gate node at decision time: id='${gateNodeId}' ` +
          `stage='${stage.id}' state='${stage.state}'`,
      );
    }
  }

  /**
   * Whether a Titan build is genuinely terminal — its DAG has finished.
   *
   * The controller run API's `building` flag is NOT a reliable terminal signal
   * for a Titan build: a `TitanRun.run()` only enqueues a task and returns, so
   * the controller sees the run as `building: false` even while the Titan engine has
   * the build PAUSED at a manual-judgement gate. The authoritative signal is
   * the engine's own DAG — `stages/tree` reports `complete: false` for a build
   * still in flight (including one paused at a gate) and `complete: true` only
   * once every node reached a terminal status.
   *
   * A freshly-submitted build whose DAG is not yet baked has zero stages and a
   * vacuously-`true` `complete`; that is NOT terminal. So terminal requires
   * `complete === true` AND at least one stage present.
   */
  async isTerminal(build: TitanBuild): Promise<boolean> {
    try {
      const graph = await this.graph(build);
      return graph.complete && graph.stages.length > 0;
    } catch {
      // stages/tree 404 — the run was deleted; nothing left to wait on.
      return true;
    }
  }

  /**
   * Cancel a Titan build, driving it to a terminal (ABORTED) state.
   *
   * A `TitanRun` has no controller executor — `run()` only enqueues a task — so
   * the stock `POST <runUrl>stop` would normally do nothing. The Titan plugin's
   * manual-judgement work added a WORKING cancel: `TitanRun.doStop()` (also
   * exposed as `term`/`kill`) is `@RequirePOST` and delegates to
   * `BuildAbortService`, which cancels the build's queue tasks, terminalises
   * every non-terminal `flow_nodes` row, and sets the build row to `ABORTED`.
   * That is the route this targets — `POST <runUrl>stop`.
   *
   * After posting, this polls (bounded by POLL_TIMEOUT_MS) until the build's
   * DAG is genuinely terminal, so a caller (teardown, the startup sweep) knows
   * the rig is actually clean, not merely that the cancel request returned 200.
   *
   * Aborting an already-finished build is a harmless no-op (BuildAbortService
   * short-circuits on a terminal build), so this is safe to call blindly in
   * teardown without first checking the build's state. If the build is already
   * terminal this returns immediately without posting.
   */
  async cancelBuild(build: TitanBuild, timeoutMs = POLL_TIMEOUT_MS): Promise<void> {
    if (await this.isTerminal(build)) return; // already clean — nothing to do

    let res = await this.request.post(`${this.baseUrl}${build.runPath}stop`, {
      headers: await this.crumbHeader(),
    });
    if (res.status() === 403) {
      // Stale crumb behind a proxy — drop, re-fetch, retry once.
      this.crumb = null;
      res = await this.request.post(`${this.baseUrl}${build.runPath}stop`, {
        headers: await this.crumbHeader(),
      });
    }
    // `doStop()` redirects (302) back to the build page on success; a 2xx is
    // equally fine. A 404 means the build/run is already gone — also terminal.
    const status = res.status();
    if (status !== 404 && (status < 200 || status >= 400)) {
      const body = (await res.text()).slice(0, 300);
      throw new Error(`cancelBuild(${build.runPath}) failed: HTTP ${status} — ${body}`);
    }
    await this.waitForTerminal(build, timeoutMs);
  }

  /**
   * Poll until the build's DAG is genuinely terminal — every node reached a
   * terminal status (`complete: true` with stages present). Bounded by the
   * shared poll cap so a build that refuses to die surfaces as a fast failure,
   * not a multi-minute hang. Returns the build's overall result.
   */
  async waitForTerminal(build: TitanBuild, timeoutMs = POLL_TIMEOUT_MS): Promise<BuildResult> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (await this.isTerminal(build)) {
        return this.buildResult(build);
      }
      await this.sleep(2_000);
    }
    throw new Error(
      `build ${build.runPath} did not reach a terminal state within ${timeoutMs / 1000}s`,
    );
  }

  /**
   * Every non-terminal build on `jobName` — a build whose DAG has not finished,
   * which crucially INCLUDES a build paused at a manual-judgement gate. Used by
   * teardown and the startup sweep to find orphaned paused/running builds that
   * must be cancelled.
   *
   * Detection is via the engine DAG (`stages/tree`), NOT the controller `building`
   * flag — a Titan build paused at a gate reads `building: false` on the
   * controller API yet is very much non-terminal. A job that does not exist, or
   * has no builds, yields `[]`.
   */
  async nonTerminalBuilds(jobName: string): Promise<TitanBuild[]> {
    const res = await this.request.get(
      `${this.baseUrl}/job/${encodeURIComponent(jobName)}/api/json?tree=builds[number]`,
    );
    if (!res.ok()) return []; // job absent on this rig — nothing to sweep
    const json = (await res.json()) as { builds?: Array<{ number?: number }> };
    const out: TitanBuild[] = [];
    for (const b of json.builds ?? []) {
      const n = b.number;
      if (typeof n !== 'number') continue;
      const runPath = `/job/${encodeURIComponent(jobName)}/${n}/`;
      let dbBuildId = 0;
      try {
        dbBuildId = await this.resolveDbBuildId(jobName, n);
      } catch {
        /* a build with no Titan DB row — cancelBuild via stop still works */
      }
      const build: TitanBuild = { jobName, number: n, runPath, dbBuildId };
      if (!(await this.isTerminal(build))) {
        out.push(build);
      }
    }
    return out;
  }

  /**
   * Job names on the rig matching a glob-ish substring/regex — used by the
   * startup sweep to find every `e2e-*gate*` job without hard-coding the list.
   */
  async jobNamesMatching(pattern: RegExp): Promise<string[]> {
    const res = await this.request.get(`${this.baseUrl}/api/json?tree=jobs[name]`);
    if (!res.ok()) return [];
    const json = (await res.json()) as { jobs?: Array<{ name?: string }> };
    return (json.jobs ?? [])
      .map((j) => j.name)
      .filter((n): n is string => typeof n === 'string' && pattern.test(n));
  }

  /** `GET <runUrl>stages/consoleBuildOutput` — the whole build's console text. */
  async consoleLog(build: TitanBuild): Promise<string> {
    const res = await this.request.get(`${this.baseUrl}${build.runPath}stages/consoleBuildOutput`);
    if (!res.ok()) {
      throw new Error(`consoleBuildOutput failed (${res.status()}) for ${build.runPath}`);
    }
    return res.text();
  }

  /**
   * The overall build result. Read from the controller run API; the TitanRun's
   * `result` is set asynchronously by the orchestrator just after the DAG
   * reaches a terminal state, so this polls briefly for the run API to settle.
   * If the run API still has not reported a result once the graph is complete,
   * the result is derived from the completed DAG — a failed node means FAILURE,
   * an aborted node means ABORTED, otherwise SUCCESS. Asserting on the engine's
   * own DAG state (design/43 §2 axis B) is the authoritative signal anyway.
   */
  async buildResult(build: TitanBuild, settleMs = 30_000): Promise<BuildResult> {
    const deadline = Date.now() + settleMs;
    while (Date.now() < deadline) {
      const res = await this.request.get(
        `${this.baseUrl}${build.runPath}api/json?tree=result,building`,
      );
      if (res.ok()) {
        const json = (await res.json()) as { result?: string | null; building?: boolean };
        if (!json.building && json.result) {
          return mapResult(json.result);
        }
      }
      await this.sleep(2_000);
    }
    // Run API never settled — derive from the completed graph.
    const graph = await this.graph(build);
    const states = flattenStages(graph.stages).map((s) => s.state);
    if (states.includes('failure')) return 'FAILURE';
    if (states.includes('aborted')) return 'ABORTED';
    if (states.includes('unstable')) return 'UNSTABLE';
    return 'SUCCESS';
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((res) => setTimeout(res, ms));
  }
}

/**
 * A compact, one-per-line dump of a DAG's nodes and statuses — `id name=state`
 * — so a poll-timeout failure is diagnosable from the error message alone
 * without opening the trace viewer.
 */
export function dumpGraph(graph: PipelineGraph | null): string {
  if (!graph) return '<no graph fetched>';
  const lines = flattenStages(graph.stages).map(
    (s) => `  ${s.id} "${s.name}" type=${s.type} state=${s.state}`,
  );
  return `complete=${graph.complete} stages=${graph.stages.length}\n${lines.join('\n')}`;
}

/** Depth-first flatten of the stage tree (parallel branches nest as children). */
export function flattenStages(stages: GraphStage[]): GraphStage[] {
  const out: GraphStage[] = [];
  for (const s of stages) {
    out.push(s);
    if (s.children && s.children.length > 0) {
      out.push(...flattenStages(s.children));
    }
  }
  return out;
}

/** Controller run-API `result` string -> BuildResult. */
function mapResult(result: string): BuildResult {
  switch (result) {
    case 'SUCCESS':
      return 'SUCCESS';
    case 'FAILURE':
      return 'FAILURE';
    case 'ABORTED':
      return 'ABORTED';
    case 'UNSTABLE':
      return 'UNSTABLE';
    default:
      return 'UNKNOWN';
  }
}

/** Mirror of TitanYamlParser.slug() — name -> node id. */
export function slug(name: string): string {
  const s = name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/(^-+)|(-+$)/g, '');
  return s.length > 64 ? s.slice(0, 64) : s;
}

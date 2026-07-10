# Local rig: worker queue routing, executor capacity, and fixture workloads

The local rig (`task dev:titan`) runs ONE pull-based worker
(`titan-worker`, agent id `local-worker-01`). Three related failure
classes trace back to its configuration in
`rig/local/docker-compose.yml`. Closed by GH #73 / #74; fixture
isolation semantics updated by #157 / #158 (read-only mount +
copy-into-workspace).

## Symptoms

1. **Build fuses FAILED after ~60s, step never left QUEUED.**
   Build-detail shows
   `No worker available for queue '<name>' after 60s — register a worker
   or change the step's queue selector.` (the #72 no-worker fuse doing
   its job).
2. **Approval / micro-build e2e specs time out while a heavy build runs.**
   Park -> approve works, but the following step starves; specs with
   30–60s budgets go red intermittently (`25-approval-flow` approve /
   timeout paths in the 2026-07-09 golden smoke).
3. **Node fixture builds fail fast with exit 127.** Step log shows
   `sh: 1: cd: can't cd to ...` and/or `sh: 1: npm: not found`.
4. **First build after a fresh `titan-ws` volume blows a 30s budget; the
   rerun is green.** (GH #84.) The shared npm cache lives at
   `/titan/npm-cache` on the `titan-ws` volume (#157/#158), so it
   survives plain container recreation — but on a fresh/wiped volume the
   first `npm ci` in a node fixture pays a cold install (~6s cold vs ~3s
   warm, measured in-worker) and `golden-path-failure-triage`'s 30s
   trigger→FAILURE budget (a PRODUCT latency property — do not widen it)
   goes red on run 1 of every post-rebuild 3-green sequence.
5. **A build FAILS with `Read-only file system` while writing under
   `/titan/fixtures`.** The pipeline (or a stale stored `pipelineScript`
   seeded pre-#157) is trying to mutate the fixtures mount in place —
   that mount is `:ro` by design since #158; see Fix.

## Diagnosis

- **Queue routing contract.** `StepDispatcher.dispatchStepTask` routes a
  step task onto the queue named by the stage's `agent:` label, else the
  pipeline-level `agent:`, else `default`. Since #824 the worker polls
  the union of `TITAN_QUEUE` + one queue per `TITAN_LABELS` entry + its
  own `TITAN_AGENT_ID` + `default`. On the rig
  (`TITAN_LABELS: docker,linux,amd64`) that means the served queues are:
  `default, docker, linux, amd64, local-worker-01`.
  Check what the worker actually serves — first boot line:

      docker logs local-titan-worker-1 2>&1 | grep "queues="

  A pipeline that pins any OTHER queue (`agent: titan-worker-1` was the
  #73 offender) is fused FAILED by design. **The convention for rig
  pipelines is `agent: linux`** — every fixture under
  `e2e/fixtures/titan-e2e-fixture/` uses it.
- **Executor capacity.** `TITAN_EXECUTORS` is the number of concurrent
  task slots. Worker-synthesis tasks compete for the same slots as
  `EXECUTE_COMMAND` tasks. The golden e2e suite (Playwright, 2 workers)
  overlaps one container-heavy fullstack build (2 parallel lint steps,
  ~90s) with approval micro-builds — with 2 executors that saturates and
  starves the micro-builds (#74). The rig ships `TITAN_EXECUTORS: "4"`.
- **Fixture workloads.** The vendored legacy fixtures
  (`e2e/pipelines/*/titan-pipeline.yml`) run `npm` / `python3` as plain
  `sh` steps directly on the worker. Compose bind-mounts
  `../../e2e/pipelines` at `/titan/fixtures` **read-only** (#157/#158):
  the node fixtures (`node-app`, `node-app-with-failing-test`) copy the
  fixture from the mount into the build's OWN workspace
  (`/titan/worker/build-<id>`, via `tar --exclude=node_modules
  --exclude=dist`) in the install stage and every later stage runs there
  — real CI checkout semantics; no step mutates the shared mount. WHY:
  the pre-#157 rw mount let two concurrent builds of one fixture race
  on a shared in-place `node_modules` — one build's `npm ci` wiped it
  mid-vitest of the other, vitest found zero tests and exited 0, and a
  should-fail build reported phantom SUCCESS (#157). Warm-run speed
  comes from the shared npm cache at `/titan/npm-cache`
  (`npm_config_cache` in the fixture env) on the `titan-ws` volume — a
  cache dir, NOT a workspace. The other fixtures (java-matrix,
  live-log-stream, oom-step, secret-redaction, include-shared) never
  touched the mount. Exit 127 means the mount is missing (stale
  container created before the mount existed) or the worker image
  predates the node/python3 install in
  `rig/local/Dockerfile.titan-worker`.

## Fix

- Queue mismatch: change the pipeline's `agent:` to a label the worker
  advertises (`linux`), or extend `TITAN_LABELS` and recreate the
  worker: `docker compose -f rig/local/docker-compose.yml up -d --build
  titan-worker`.
- Starvation: raise `TITAN_EXECUTORS` (env change + recreate the worker
  container). Size it as: max parallel steps of the heaviest fixture
  build (+2 headroom for a concurrent micro-build step and a synthesis
  task).
- Exit 127 in node fixtures: rebuild + recreate the worker so it picks
  up both the image (node/npm/python3) and the `/titan/fixtures` mount.
  Every build starts from a fresh copy of the fixture in its own
  workspace, so every build runs `npm ci` (falling back to
  `npm install --no-package-lock` for fixtures without
  package-lock.json); the shared `/titan/npm-cache` keeps that fast, not
  a persisted `node_modules`.
- `Read-only file system` under `/titan/fixtures`: the pipeline mutates
  the mount in place — port it to the copy-into-workspace pattern (see
  `e2e/pipelines/node-app/titan-pipeline.yml`). If the offender is a
  server-stored job seeded pre-#157 (the prewarm job was one — warm-up
  build 3018 FAILED against the `:ro` mount), `PATCH` its stored
  `pipelineScript` to the current pattern; do NOT loosen the mount.
- Cold-cache first-build red (#84): `task rig:smoke` handles this itself —
  `dev/rig-smoke/prewarm-worker.sh` detects a worker container younger
  than 15 min (`docker inspect .State.StartedAt`), fires one throwaway
  `rig-smoke-prewarm` build (the same copy-into-workspace + `npm ci`
  pattern the node fixtures use, with the same
  `npm_config_cache=/titan/npm-cache`, so it warms exactly the cache the
  fixtures hit) through the API, and waits for it to finish before
  Playwright starts — look for `[rig-smoke] pre-warmed worker (Xs)` in
  the smoke output. On the 409-reuse path the script `PATCH`es the
  existing job's stored `pipelineScript` before triggering, so a stale
  pre-#157 in-place script can never resurface
  (`dev/rig-smoke/tests/test_prewarm_worker.sh` pins this rotation).
  Debug escape hatch: `RIG_SMOKE_SKIP_PREWARM=1`. Outside the smoke
  harness, fire any node-fixture build manually after recreating the
  worker before trusting first-build latency.

## Prevention

- New rig fixtures/pipelines: use `agent: linux` (or no `agent:` at all
  → `default`). Never pin a worker name the compose does not provision.
- Do NOT weaken the #72 no-worker fuse to make a queue mismatch green —
  a pipeline pinning a truly unserved queue must still fail within ~60s
  with the precise reason.
- The PRODUCT worker contract stays bare-JRE + per-stage `image:`; the
  node/python3 runtimes in the dev worker image exist only for the
  vendored legacy fixtures. New fixtures should bring an `image:`.
- Never run npm (or any mutation) inside `/titan/fixtures`, and never
  drop the `:ro` on the fixtures mount — copy into the build workspace
  instead (it MUST stay a live bind, though: the failure-triage spec
  patches a fixture file on the host and expects the next build's fresh
  copy to pick it up). Point per-fixture caches at `/titan/npm-cache`,
  never at the mount.
- Known engine gap (found while closing #73/#74): `TimerSweepWorker` /
  `ApprovalService.sweepTimedOut` is only invoked from integration
  tests — nothing schedules it in the production server, so `approval:`
  timeouts never flip TIMED_OUT on a live rig. Tracked separately; do
  not paper over it with e2e budget bumps.

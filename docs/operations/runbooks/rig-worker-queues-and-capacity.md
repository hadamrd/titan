# Local rig: worker queue routing, executor capacity, and fixture workloads

The local rig (`task dev:titan`) runs ONE pull-based worker
(`titan-worker`, agent id `local-worker-01`). Three related failure
classes trace back to its configuration in
`rig/local/docker-compose.yml`. Closed by GH #73 / #74.

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
  `sh` steps directly on the worker, against sources bind-mounted at
  `/titan/fixtures` (compose mounts `../../e2e/pipelines` there). Exit
  127 means the mount is missing (stale container created before the
  mount existed) or the worker image predates the node/python3 install
  in `rig/local/Dockerfile.titan-worker`.

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
  First build after a fresh clone runs a real `npm install` (the
  fallback for fixtures without package-lock.json) — subsequent builds
  are no-ops because `node_modules/` persists in the mounted fixture dir
  (gitignored).

## Prevention

- New rig fixtures/pipelines: use `agent: linux` (or no `agent:` at all
  → `default`). Never pin a worker name the compose does not provision.
- Do NOT weaken the #72 no-worker fuse to make a queue mismatch green —
  a pipeline pinning a truly unserved queue must still fail within ~60s
  with the precise reason.
- The PRODUCT worker contract stays bare-JRE + per-stage `image:`; the
  node/python3 runtimes in the dev worker image exist only for the
  vendored legacy fixtures. New fixtures should bring an `image:`.
- Known engine gap (found while closing #73/#74): `TimerSweepWorker` /
  `ApprovalService.sweepTimedOut` is only invoked from integration
  tests — nothing schedules it in the production server, so `approval:`
  timeouts never flip TIMED_OUT on a live rig. Tracked separately; do
  not paper over it with e2e budget bumps.

# titan-e2e — the Titan end-to-end Playwright harness

## Quick start (v3 — Titan-only rig)

```sh
# 1. bring the rig up (Postgres + Keycloak + titan-server + worker + SPA)
task dev:titan

# 2. install playwright deps + browsers (first time)
cd e2e && pnpm install && pnpm exec playwright install --with-deps chromium

# 3. run the v3 suite
task e2e
# or, for a single file:
cd e2e && pnpm exec playwright test specs/v3/00-smoke.spec.ts
```

Codegen + trace viewer:
```sh
cd e2e && pnpm exec playwright codegen http://localhost:5180
cd e2e && pnpm exec playwright show-trace playwright-report/trace.zip
```

### v3 spec status

| file                              | status   | needs                                                  |
|-----------------------------------|----------|--------------------------------------------------------|
| `specs/v3/00-smoke.spec.ts`       | active   | rig up + Keycloak `dev`/`dev`                          |
| `specs/v3/01-gate-approval.spec.ts` | active | rig up + seeded RUNNING titan-ui build                 |
| `specs/v3/02-gate-rejection.spec.ts` | active | rig up + seeded RUNNING titan-ui build                 |
| `specs/v3/03-replay-from-node.spec.ts` | SKIP | needs failed-build flow_nodes seed (follow-up issue) |
| `specs/v3/04-test-results.spec.ts` | SKIP    | TestResultsPanel on trunk + `titan.test_result` seed (follow-up) |
| `specs/v3/05-artifacts-browser.spec.ts` | SKIP | `titan.artifact` seed helper (follow-up issue)        |

### Spec-ownership rule (#59) — direct SQL against shared tables

The rig's Postgres is **shared state across every spec in a run**. Direct SQL
is allowed, but only under the ownership discipline below — violating it has
force-ABORTed other specs' RUNNING builds and cascade-deleted `task_queue`
rows the worker was actively executing (issue #59, evidence in PR #58).

1. **Never mutate (UPDATE/DELETE) a row your spec did not create.** If your
   test needs builds in a particular status mix, create your own job + build
   rows (`seedOwnedJobWithBuilds` in `fixtures/seed-v3.ts`) or drive state
   through the public API. Reads of foreign rows are fine.
2. **Scope every cleanup DELETE to the ids/prefix your spec created.** Blanket
   predicates like `WHERE status IN ('RUNNING','QUEUED')` are forbidden.
3. **Never delete a `task_queue` row in CLAIMED/PROCESSING** — directly or
   transitively (`DELETE FROM titan.builds` cascades onto `task_queue`). For
   jobs whose builds ran through the real engine, tear down via
   `safeDeleteJobCascade` (`fixtures/teardown-v3.ts`): it cancels live builds
   via `POST /api/v1/builds/{id}/cancel`, waits for terminal status and lease
   drain, and only then deletes. If the lease never drains it deletes nothing
   — per-run unique job names keep orphans from colliding.
4. **Snapshot/restore of foreign rows is NOT an acceptable substitute** for
   ownership: restores run after your assertions (the foreign spec already
   observed the mutation) and are themselves fallible — the pre-#59 restore
   SQL failed on every run with `inconsistent types deduced for parameter $2`
   (reusing one untyped `pg` parameter as both a varchar column value and a
   text comparison; compute such flags in JS and pass a dedicated boolean
   parameter instead).
5. **Designated shared fixtures are the one exception:** the seed-data
   `titan-ui` RUNNING build exists *for* the gate/cancel specs (01, 02, 11).
   They may mutate it, must restore it (`setBuildStatus`), and nothing else
   may touch it.

### Legacy (controller-bound) suite

`scenarios.spec.ts` + `fixtures/titan-api.ts` target the legacy controller rig
(`http://localhost:18080` driven by `TITAN_RIG_URL`). They will be removed when
Phase 3 deletes `titan-plugin/`. Don't edit `titan-api.ts` — write against
`fixtures/titan-api-v3.ts` instead.

---



Implements **design/43**. A self-contained TypeScript/Playwright project (not a
Maven module) that tests Titan on two axes with one tool:

- **Axis A — UI flows** through Chromium: the live graph view, the gate
  approval click-through, the build console.
- **Axis B — engine assertions** white-box, through Titan's own DAG JSON API
  (`TitanGraphApiAction`): per-node `flow_nodes` statuses, ordering, masking.

## Layout

```
e2e/
  package.json            npm project — @playwright/test, typescript, js-yaml
  playwright.config.ts    rig resolution, reporters, global setup/teardown
  tsconfig.json           strict TS, mirrors src/main/frontend conventions
  .gitlab-ci.yml          the `titan-e2e` CI job (included by repo-root CI)
  fixtures/
    rig.ts                ephemeral docker-compose lifecycle + readiness wait
    global-setup.ts       Playwright global setup — brings the rig up
    global-teardown.ts    Playwright global teardown — tears the rig down
    titan-api.ts          TitanApi: submit / poll DAG / flow_nodes / console / gate
    scenario.ts           *.e2e.yaml schema, discovery, assertion helpers
    index.ts              the `test` / `expect` fixtures (titanApi, rigUrl)
  scenarios/*.e2e.yaml    declarative scenario data — design/43 §4
  scenarios.spec.ts       the generic scenario runner — design/43 §7 (43-S)
  specs/*.spec.ts         hand-written UI-flow tests — design/43 §7 (43-C)
```

## Running it

### Against a running dev rig (the runnable-today path)

```bash
cd titan-e2e
npm install
npx playwright install chromium

# Point at a rig already up (pwsh dev.ps1 up, or rig/local/up.sh):
TITAN_RIG_URL=http://localhost:18080 npx playwright test
```

`--list` discovers the suite without a rig:

```bash
npx playwright test --list
```

### Ephemeral rig (design/43 §3 — the default-once-stable path)

```bash
# The harness owns the rig: global-setup brings rig/local/ up and waits
# for the controller + the worker to register; global-teardown tears it down (-v).
# Requires docker, and that Maven already built target/release-flow.hpi +
# titan-worker/target/titan-worker.jar (the compose stack stages them).
TITAN_RIG_EPHEMERAL=1 npx playwright test
```

### Authoring loop

```bash
npx playwright test --ui          # watch + debug live
npx playwright show-report        # open the HTML report / trace viewer
```

## Writing a scenario

Drop a `scenarios/<name>.e2e.yaml` file — no test code (design/43 §4):

```yaml
name: my scenario
pipeline: |
  agent: titan-worker-1
  stages:
    - stage: build
      steps: [{ sh: echo hi }]
expect:
  build: SUCCESS
  nodes:
    build: { status: SUCCESS }
    build-s0: { status: SUCCESS }
```

Node ids: a **stage** id is the slug of its name (`Build` → `build`); a **step**
id is `<stageSlug>-s<index>` (`build-s0`). Optional `expect` keys:
`ranInParallel` (execution windows overlap), `logContains`, `logExcludes`.
Optional top-level keys: `setup` (a Groovy preamble for self-provisioned rig
state, e.g. seeding a credential), `gate` (`approve`/`reject` a gate node id),
`pending` (skip with a documented rig-fixture reason).

## Pending scenarios

Some v1 scenarios need rig fixtures the harness cannot self-provision; they
ship as `pending:` (skipped, with the reason in `--list`):

| Scenario | Needs                                                              |
|----------|--------------------------------------------------------------------|
| `retry`  | a counter file seeded on the shared `/titan` volume (engine gap)   |

Each scenario file documents exactly what to add to flip it on.

## Building rig fixtures

The `spi-step` scenario needs a Tier-2 `StepHandler` jar in the worker's
`TITAN_STEPS_DIR`. The jar is a build artifact (git-ignored, never committed).
Produce it reproducibly with:

```bash
npm run build:fixtures
```

This builds `e2e/fixtures/spi-step/` and copies `titan-e2e-spi-step.jar`
into `rig/local/worker-steps/`. Then `pwsh rig/local/dev.ps1 reload`
loads it into the worker. See `rig/local/worker-steps/README.md`.

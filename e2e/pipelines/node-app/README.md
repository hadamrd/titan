# node-app — Titan PDL real-shaped fixture

Reference fixture for **issue #1119** (guards reworked in **#43**). Drives the
PDL features tracked in **#1094** (top-level `env:` + per-step overrides) and
**#1093** (`when:` conditions on stages) end-to-end against the local rig.

## Why this exists

Axis-3 of the PDL roadmap requires every new grammar feature to land WITH a
real-shaped fixture. Without that, features risk passing unit tests but
breaking the first time a customer-shaped pipeline tries to use them.

The fixture mirrors a typical Node service migration off GitHub Actions:

```
install -> lint -> unit-test -> build -> deploy-staging -> smoke-test
```

with:

- a top-level `env:` block (`NODE_ENV`, `REGISTRY_URL`),
- a per-step `env:` override on `lint` (proves merge order — step wins),
- a declared `deployEnv` build parameter (`staging` default / `none`),
- a bake-legal `when: "params.deployEnv == 'staging'"` gating `deploy-staging`
  (stage-level `when:` sees `params.*` only — the pre-#43 `branch ==` /
  `steps.*.result` guards were runtime-only and failed every bake since #42),
- `smoke-test` gated by `dependsOn: [deploy-staging]` + default blockOnFailure
  (skips on deploy FAILURE; a bake-time SKIP of deploy does not block it).

The secret-injection + log-redaction contract moved to the dedicated
`e2e/pipelines/secret-redaction` fixture (env `secret:<id>` refs /
`credentials:` bindings) — the `${{ secrets.NPM_TOKEN }}` spelling this
fixture used to carry was never part of the PDL grammar.

## Running it standalone

```sh
task dev:titan
task e2e -- --grep node-app
```

## The Node project

Dependency-free on purpose — `npm test` uses the built-in `node:test`
runner, `npm run build` is a 3-line copy into `dist/`. The fixture
installs in milliseconds on the worker.

## E2E specs that drive it

| spec                                       | asserts                                                                 |
|--------------------------------------------|-------------------------------------------------------------------------|
| `e2e/tests/node-app-main.spec.ts`          | all 6 stages green (default `deployEnv=staging`)                        |
| `e2e/tests/node-app-feature.spec.ts`       | with `deployEnv=none`, `deploy-staging` is **SKIPPED** (pre-#43 semantics: feature branch) |
| `e2e/tests/node-app-secret-leak.spec.ts`   | superseded — see `e2e/pipelines/secret-redaction` + `specs/v3/45-secrets-handling.spec.ts` |

The `e2e/tests/` specs target the retired plugin-host harness
(`fixtures/titan-api.ts`, `test.skip(true)` — see #1175) and stay skipped;
the live proof for this fixture is the API-driven bake in the #43 PR
(all 6 stages SUCCESS by default; `deploy-staging=SKIPPED` with
`deployEnv=none`). The parse-time invariants are pinned by
`titan-pipeline-model/.../NodePipelineFixtureTest`.

## Pipeline-model unit test

`titan-pipeline-model/.../NodePipelineFixtureTest` parses this YAML and
asserts the parse-time invariants the fixture relies on (top-level env
keys, per-step override, `when:` strings present). That test guards
against grammar regressions silently breaking the fixture.

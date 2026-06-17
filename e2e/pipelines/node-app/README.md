# node-app — Titan PDL real-shaped fixture

Reference fixture for **issue #1119**. Drives the PDL features tracked in
**#1094** (top-level `env:` + per-step overrides + `${{ secrets.* }}`) and
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
- a secret reference (`${{ secrets.NPM_TOKEN }}`) on `deploy-staging`,
- `when: branch == 'main'` gating `deploy-staging`,
- `when: steps.deploy-staging.result == 'success'` gating `smoke-test`.

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
| `e2e/tests/node-app-main.spec.ts`          | on `main`, all 6 stages green                                           |
| `e2e/tests/node-app-feature.spec.ts`       | on a feature branch, `deploy-staging` + `smoke-test` are **SKIPPED**    |
| `e2e/tests/node-app-secret-leak.spec.ts`   | `NPM_TOKEN`'s literal value never appears in any captured log line      |

Until #1094 and #1093 land, the e2e specs are registered with `test.skip()`
so the suite stays green; the YAML + Node project files are complete and
ready to drive against.

## Pipeline-model unit test

`titan-pipeline-model/.../NodePipelineFixtureTest` parses this YAML and
asserts the parse-time invariants the fixture relies on (top-level env
keys, per-step override, `when:` strings present). That test guards
against grammar regressions silently breaking the fixture.

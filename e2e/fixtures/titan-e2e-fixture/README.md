# `titan-e2e-fixture` — vendored fixture mirror (source of truth for Layer-1 specs)

This directory is the **version-controlled, byte-identical mirror** of the fixture
pipelines in the external repo
[`hadamrd/titan-e2e-fixture`](https://github.com/hadamrd/titan-e2e-fixture) that the
v3 e2e specs depend on.

**Since #48 the Layer-1 (@golden) specs read these files from disk** (via
`e2e/fixtures/fixture-files.ts#readFixtureYaml`) instead of fetching them from
`raw.githubusercontent.com` at runtime. The synthesized-webhook flow never needed the
network for the YAML; the runtime fetches were a structural flake source — GitHub
429-throttled the unauthenticated raw fetches under repeated smoke runs.

Only Layer-2 (`@real-commit`) specs still talk to the live repo — that layer declares
the network dependency (real clones, real commits).

## Pinned source

All files below were fetched byte-identical from
`hadamrd/titan-e2e-fixture` at commit **`116b581de1b01f7e79bd9dc1005ad8476d66c0f7`**
(branch `main`, 2026-07-08, issue #48). No header comments were added — byte-identity
with the remote is the invariant, so the source ref is recorded here instead of
inside the YAML files.

| Vendored file | Consumed by spec(s) |
|---|---|
| `titan-pipeline.yml` | 24, 40 |
| `multi-env-deploy.yml` | 42 |
| `matrix-aggregate.yml` | 43 |
| `failure-recovery.yml` | 44 |
| `.titan/pipelines/simple-build.yml` | 26, 52 |
| `.titan/pipelines/with-params.yml` | 27 |
| `.titan/pipelines/with-approval.yml` | 28 |
| `.titan/pipelines/with-retry.yml` | 29 |
| `.titan/pipelines/with-setBuildName.yml` | 30 |
| `.titan/pipelines/with-gitTag.yml` | 31 |
| `.titan/pipelines/with-httpRequest.yml` | 32 |
| `.titan/pipelines/secrets-handling.yml` | 45 |
| `.titan/pipelines/shared-library-build.yml` | 47 |
| `.titan/pipelines/multi-repo-checkout.yml` | 48 |

`00-fixture-guard.spec.ts` hard-fails the run if any spec's `FIXTURE_PATH` has no
vendored file here (dangling-reference prevention — the successor of the #955
silent-skip guard).

## History

- **#1246** — specs 40/45 were dormant because their fixture files 404-ed upstream;
  the first two mirror files (`titan-pipeline.yml`, `.titan/pipelines/secrets-handling.yml`)
  were restored here and pushed upstream. `45-secrets-handling` also switched from
  browser-PKCE to `fetchBearerToken` (direct-access-grant) at that point.
- **#48** — the mirror was extended to every Layer-1 fixture and the specs switched
  from runtime raw fetches to disk reads.

## Re-syncing

The remote repo remains the fixture the *worker* clones in specs whose pipelines do a
real checkout, so intentional changes must land BOTH here and upstream. To push a
local edit upstream:

```sh
gh api -X PUT repos/hadamrd/titan-e2e-fixture/contents/<path> \
  -f message="sync <path>" \
  -f content="$(base64 -w0 e2e/fixtures/titan-e2e-fixture/<path>)" \
  -f sha="$(gh api repos/hadamrd/titan-e2e-fixture/contents/<path> --jq .sha)"
```

To re-import from upstream (update the pinned SHA above when you do):

```sh
gh api "repos/hadamrd/titan-e2e-fixture/contents/<path>?ref=main" --jq .content \
  | base64 -d > e2e/fixtures/titan-e2e-fixture/<path>
```

# `titan-e2e-fixture` — restored fixtures (mirror + contract)

This directory is the **version-controlled source of truth** for the two
fixture files in the external repo [`hadamrd/titan-e2e-fixture`](https://github.com/hadamrd/titan-e2e-fixture)
that the v3 e2e specs hard-depend on. The specs `fetch()` them from GitHub at
run time; keeping a mirror here makes the fixture **shape reviewable in PRs**
and lets us re-sync / detect drift instead of discovering a silent `test.skip`
weeks later.

## Why this exists (issue #1246)

`e2e/specs/v3/40-fullstack-pipeline.spec.ts` and
`e2e/specs/v3/45-secrets-handling.spec.ts` were **dormant**: each opens with a
fixture-availability pre-check —

```ts
const meta = await api.get(FIXTURE_API_URL, { headers: { Accept: '…' } })
test.skip(meta.status() === 404, 'Fixture file gone — … returned 404 …')
```

Both fixture files **404-ed**, so every run skipped silently while asserting
nothing. The green sibling `26-fixture-simple-build.spec.ts` uses the same repo
and ran fine — proving the repo was reachable and only these two specific files
were missing.

### Root cause per spec (diagnosed by probing the live GitHub contents API)

| Spec | Fixture path the spec needs | Before | Cause |
|------|-----------------------------|--------|-------|
| #40  | `titan-pipeline.yml` (repo **root**) | `404` | **Missing / wrong path.** The equivalent pipeline existed only at `.titan/pipelines/full-pipeline.yml`; the spec fetches the **root** path. |
| #45  | `.titan/pipelines/secrets-handling.yml` | `404` | **Missing file.** The adversarial secrets fixture had never been committed to the repo. |

After restoring both files the contents API returns `200` and the
`test.skip(…404…)` path no longer triggers — the specs proceed into their real
assertions.

## The two fixtures

### `titan-pipeline.yml` (spec #40)
Root-level copy of the existing `.titan/pipelines/full-pipeline.yml`. It already
matches every drift-guard the spec asserts: `Checkout` → parallel
`Backend Build` / `Frontend Build` chains → `gate: Publish Approval` → `Report`
(`setOutput`) → `Notify` (`when:` ⇒ SKIPPED), with `archiveArtifacts` + `junit:`
on both stacks.

### `.titan/pipelines/secrets-handling.yml` (spec #45)
Binds a STRING credential `e2e-secrets/__KEY__` into `$SECRET_TOKEN` (design/39
`credentials:` grammar), prints `consume ok: len=<n>` (length, never the value),
then deliberately writes the secret to `leaked.txt` and archives it — the
adversarial leak probe behind the spec's assertions A–D. `__KEY__` is a
per-run placeholder the spec rewrites to `token-<RUN_TAG>` to avoid credential
store collisions.

## Run results (local rig, `task e2e`)

After restoring both fixtures, both specs **run** (the `test.skip(…404…)` path is
dead) and execute their real assertions:

| Spec | Before | After | Notes |
|------|--------|-------|-------|
| #45 secrets-handling | skipped (404) | **PASS** | Assertions A–D all hold: build SUCCESS (credential bound), plaintext absent from `pipelineScript`, from the SSE log stream (`consume ok: len=` present), and from the archived `leaked.txt`. |
| #40 fullstack | skipped (404) | **RUNS → FAILS** at a real engine defect | The DAG executes (`Checkout=SUCCESS`), then the container lint stages fail because the Checkout workspace is not propagated to dependent stages (`POM file backend/pom.xml … does not exist`; the cloned repo genuinely contains `backend/`+`frontend/`). Filed as **#1273**; the adversarial assertion is kept RED, not masked — exactly the behaviour the spec was designed for. |

### #45 auth: browser-PKCE → direct-grant
`45-secrets-handling.spec.ts` originally logged in via the browser OIDC flow
(`loginViaKeycloak`). On the rig that flow stalled on the Keycloak `#kc-form-login`
form (SPA landing-page / OIDC runtime-config drift), preventing the spec from
running to completion. It now obtains its bearer via `fetchBearerToken`
(direct-access-grant) — the **same** auth path the sibling fixture specs #27 and
#40 already use. This is a pure auth-acquisition swap; the spec's testing
architecture (synthetic HMAC-signed push, inline `pipelineScript`, SSE log drain,
adversarial assertions A–D) is unchanged.

### Rig note
`task dev:titan` currently crash-loops `titan-server` on a dev-KEK boot-check vs.
`QUARKUS_PROFILE=prod` mismatch — filed as **#1274**. Worked around locally
(uncommitted override) to obtain the run above; not part of this PR.

## Re-syncing to the external repo

The external repo has no CI in this monorepo, so changes are pushed directly:

```sh
gh api -X PUT repos/hadamrd/titan-e2e-fixture/contents/titan-pipeline.yml \
  -f message="sync root pipeline" \
  -f content="$(base64 -w0 e2e/fixtures/titan-e2e-fixture/titan-pipeline.yml)" \
  -f sha="$(gh api repos/hadamrd/titan-e2e-fixture/contents/titan-pipeline.yml --jq .sha)"

gh api -X PUT repos/hadamrd/titan-e2e-fixture/contents/.titan/pipelines/secrets-handling.yml \
  -f message="sync secrets fixture" \
  -f content="$(base64 -w0 e2e/fixtures/titan-e2e-fixture/.titan/pipelines/secrets-handling.yml)" \
  -f sha="$(gh api repos/hadamrd/titan-e2e-fixture/contents/.titan/pipelines/secrets-handling.yml --jq .sha)"
```

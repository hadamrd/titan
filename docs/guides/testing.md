# Testing

How the Titan test suite is organized and how to run it.

Titan has three test tiers, each in its own location:

| Tier | Location | Tool | Speed |
|---|---|---|---|
| Unit | `<module>/src/test/` | JUnit 5 | milliseconds |
| Integration | `<module>/src/integrationTest/` | JUnit 5 + Testcontainers | seconds (needs Docker) |
| End-to-end | `e2e/` | Playwright | minutes (needs a running rig) |

## Unit tests

Plain JUnit 5, no external services. This is where most logic lives — parsers,
mappers, validators, the step contract tests. Step handlers extend
`StepHandlerTck`, which contributes the contract tests for free (see
[Writing a step](writing-a-step.md)).

Run all unit tests (plus Spotless, SpotBugs, and JaCoCo) across every module:

```bash
task gradle:check
```

Scope to one class while iterating:

```bash
./gradlew -p titan-worker test --tests DeleteDirStepHandlerTest
./gradlew -p titan-pipeline-model test --tests 'TitanYamlParser*'
```

## Integration tests

JUnit 5 backed by [Testcontainers](https://testcontainers.com): real Postgres,
Keycloak, MinIO, Nexus, etc. spun up in Docker per test. Use this tier when a
unit test would have to mock infrastructure — DAO round-trips, the envelope
credential store against real Postgres, an artifact backend against MinIO or a
`sonatype/nexus3` container.

```bash
task gradle:integrationTest          # all modules; needs Docker running
```

## End-to-end tests

`e2e/` is a standalone Playwright project (its own pnpm workspace, not a Gradle
product module) that drives the live UI in Chromium and asserts engine
behaviour through Titan's own APIs.

```bash
# 1. bring the local rig up (Postgres + Keycloak + server + UI + worker)
task dev:titan

# 2. first time only — install browsers
cd e2e && pnpm install && pnpm exec playwright install --with-deps chromium

# 3. run the suite against the running rig
task e2e

# run a single spec, or filter by tag
cd e2e && pnpm exec playwright test specs/v3/00-smoke.spec.ts
task e2e -- --grep @parkour
```

The e2e specs are transpiled untyped at runtime, so a green Playwright run does
not type-check them. `task verify:e2e` runs `tsc --noEmit` over the specs and is
wired into `task verify` to keep them type-honest.

## Coverage

JaCoCo enforces a per-module line/branch floor (configured via the
`jacocoMinLineCoverage` / `jacocoMinBranchCoverage` properties in
`build-logic`). The floor ratchets up as meaningful tests land rather than
flipping in one step. `task gradle:check` runs the coverage verification.

## Running everything

```bash
task verify       # gradle check + UI type-check/vitest/build + e2e type-check
task ci:verify    # the full PR gate: adds integrationTest, Quarkus app-build,
                  # migration scan, and route-tree freshness
```

## Conventions

- Put a test in the tier that matches what it asks. If a unit test would have to
  mock a real service, it belongs in `src/integrationTest/`.
- Mirror the production package path under the test source root.
- Name tests `subject_does_thing` so they read in CI output.
- No sleep-based assertions (use awaitility / Playwright's auto-waiting) and no
  tests that hit the public network outside a Testcontainer.

## See also

- [Writing a step](writing-a-step.md) — the step TCK and parser tests.
- [Quality bar](../building-with-ai/quality-bar.md) — the enforced quality chart.

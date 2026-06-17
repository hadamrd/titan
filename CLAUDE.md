# dashboard-plugin — working instructions

## What this repo is

**Single-Gradle.** Phase 3 deleted `titan-plugin/` and removed
Maven entirely. The repo ships the Titan engine as a standalone CI/CD product.

The Gradle reactor (`settings.gradle.kts`) builds 9 product modules + the
e2e fixture project:

- **Core engine:** `titan-step-api`, `titan-trigger-api`, `titan-pipeline-model`,
  `titan-db-core`, `titan-server`, `titan-worker`
- **First-party extensions** (`titan-extensions/`): `titan-artifact-s3`,
  `titan-artifact-nexus`, `titan-keyprovider-infisical`
- **Frontend:** `titan-ui/` (own pnpm/Vite setup — built via `task ui:build`,
  not via the Gradle reactor)
- **Tests:** `e2e/` (Playwright against the running local rig)

Tests live in `<module>/src/test/` (unit) + `<module>/src/integrationTest/`
(Testcontainers ITs).

## Layout

Root = `settings.gradle.kts` + three role-named dirs: `dev/` (developer
environment), `rig/` (deploy targets: `rig/local`, `rig/k3s`), `e2e/`
(Playwright end-to-end tests — Gradle module), `docs/`. Full map:
**`docs/repo-layout.md`**.

## Ops — everything goes through `task`

```
task                     list every task
task setup               one-time env bootstrap
task verify              full verify (Gradle check on all product modules)
task gradle:build        build all Gradle product modules
task gradle:check        unit tests + Spotless + SpotBugs + JaCoCo
task gradle:integrationTest  Testcontainers ITs (needs Docker)
task build:worker        build titan-worker fat jar
task dev:titan           bring up the local Titan rig (postgres + keycloak + server + ui + worker)
task dev:down / dev:logs
task deploy:k3s          publish artifacts + deploy to the k3s rig
task e2e                 Playwright against the running local rig
```

Scoped unit tests: `./gradlew -p <module> test --tests <Class>`.
Do **not** add a Makefile/justfile/scattered scripts — `task` is the one
entry point.

## Conventions — and how they're enforced

See **`docs/building-with-ai/quality-bar.md`** (the enforced-practices chart). In short:
branch + PR off `trunk` (never commit to trunk directly); Spotless-formatted
(`./gradlew spotlessApply`); tests live in `<module>/src/test/` (unit) or
`<module>/src/integrationTest/` (ITs);
no GitHub Actions; secrets never in git.

Adding a Titan PDL step → use the `add-pdl-step` skill (~3 files, don't touch
the grammar/schema).

## Code search

If a **Lumen** semantic-search MCP server is connected (`mcp__lumen__*`), prefer
it for concept-level discovery ("where is X handled?", cross-package tracing)
before Grep/Glob. Use Grep for known literal strings. If Lumen is not connected,
just use Grep/Glob.

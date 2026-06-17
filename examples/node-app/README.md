# Node.js app

A linear CI pipeline for a Node/npm/pnpm service:
**install → lint → test → build**, with JUnit test reporting and an
archived production bundle.

## What it demonstrates

- Pipeline-wide `env:` merged into every step.
- A linear DAG via `dependsOn` (a failure short-circuits downstream stages).
- `junit:` to publish a test report from JUnit XML.
- `archiveArtifacts:` with `fingerprint: true` to store the build output.

## How to run

Drop `titan-pipeline.yml` at the root of your Node repo (adapt the `npm`
commands to your package manager), register the repo as a pipeline in the
Titan UI, and trigger a build. See the repo root
[`docs/getting-started.md`](../../docs/getting-started.md) for the local rig.

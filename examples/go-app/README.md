# Go app

An idiomatic Go CI pipeline: a fast `vet` static-analysis gate that fans into
parallel `build` and `test` stages.

## What it demonstrates

- A diamond DAG: `vet` → (`build`, `test`) running in parallel.
- `archiveArtifacts:` to store the release binary.
- `junit:` fed by `gotestsum` (or `go-junit-report`) JUnit XML.

## How to run

Drop `titan-pipeline.yml` at the root of your Go module, register the repo as
a pipeline in the Titan UI, and trigger a build. See
[`docs/getting-started.md`](../../docs/getting-started.md) for the local rig.

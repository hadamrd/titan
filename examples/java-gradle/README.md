# Java app (Gradle)

A Gradle build that compiles, tests across a **JDK matrix**, publishes the
JUnit report, and archives the jar. Adapted from
[`e2e/pipelines/java-matrix`](../../e2e/pipelines/java-matrix).

## What it demonstrates

- `matrix:` fan-out over JDK versions, with the `MATRIX_JDK` env var per cell.
- `fail_fast: false` so every JDK reports even if one fails.
- `junit:` reading Gradle's `build/test-results/test/*.xml`.
- `archiveArtifacts:` with `fingerprint: true` for the packaged jar.

## How to run

Drop `titan-pipeline.yml` at the root of your Gradle project (it uses the
`./gradlew` wrapper), register the repo as a pipeline in the Titan UI, and
trigger a build. See
[`docs/getting-started.md`](../../docs/getting-started.md) for the local rig.

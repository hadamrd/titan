# Titan pipeline examples

A library of real, runnable `titan-pipeline.yml` files you can copy into your
own repo as a starting point. Each example is a self-contained directory with
a pipeline and a short README. Every pipeline here is validated against the
published [pipeline schema](../titan-pipeline-model/src/main/resources/io/adaptiq/titan/schemas/titan-pipeline.schema.json).

| Example | What it shows |
|---|---|
| [node-app](node-app/) | install → lint → test → build for a Node service; `junit` + `archiveArtifacts`. |
| [python-app](python-app/) | `pytest` across a `matrix:` of Python versions. |
| [go-app](go-app/) | `go vet` → parallel build + test; JUnit via `gotestsum`. |
| [java-gradle](java-gradle/) | Gradle build + test over a JDK `matrix:`, JUnit report, jar archive. |
| [monorepo-matrix](monorepo-matrix/) | `matrix:` / `each:` fan-out across packages with `dependsOn` fan-in. |
| [showcase](showcase/) | The annotated tour: matrix + when + retry + onFailure + typed credentials + gate + notify. |

## How to run any example

1. Bring up the local rig: `task dev:titan` (see
   [`docs/getting-started.md`](../docs/getting-started.md)).
2. Copy the example's `titan-pipeline.yml` to the root of your repo and adapt
   the shell commands / package names to your project.
3. Register the repo as a pipeline in the Titan UI and trigger a build.

For the complete grammar reference see
[`docs/reference/pdl.md`](../docs/reference/pdl.md) and
[`docs/reference/steps.md`](../docs/reference/steps.md).

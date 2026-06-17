# Getting started

Titan is a standalone, cloud-native CI/CD pipeline execution engine: a
Quarkus controller that turns declarative YAML pipelines into a validated
static DAG, a fleet of pull-based workers that execute it, and a React
operator UI — with all engine state held durably in Postgres. This guide
brings up a complete local rig and runs your first build.

## Prerequisites

- **Java 21+** — the build is self-contained via the Gradle wrapper
  (`./gradlew`); `task setup` can provision the JDK for you.
- **Docker** — the local rig runs Postgres, Keycloak, the server, the UI,
  and a worker as containers.
- **[Task](https://taskfile.dev)** — the single entry point for every
  build / test / deploy / dev operation in this repo.

A `pnpm` toolchain is also needed to build the UI; `task setup` covers the
rest of the dev environment.

## Clone and bootstrap

```sh
git clone https://github.com/hadamrd/titan.git
cd titan
task setup            # one-time: JDK 21 + git hooks
```

`task` with no arguments lists every available task.

## Bring up the local rig

```sh
task dev:titan
```

This builds the product modules, the worker fat jar, and the UI bundle,
then starts the full stack with docker-compose. When it finishes it prints
the endpoints:

```
Titan UI  : http://localhost:5180
Keycloak  : http://localhost:8081  (admin / admin)
Login as  : dev / dev
```

Open <http://localhost:5180> and log in as `dev` / `dev`. The Overview,
Queue, Builds, Workers, and Pipelines routes are all live against the
running rig.

Useful companions:

```sh
task dev:logs        # tail logs from every rig service
task dev:down        # tear down and wipe all volumes
```

## Author your first pipeline

A Titan pipeline is declarative YAML committed to a repository under
`.titan/pipelines/`. The file on disk is the contract — there is no in-UI
mutation. A minimal pipeline:

```yaml
# .titan/pipelines/hello.yml
agent: linux

stages:
  - stage: Hello
    steps:
      - sh: echo "Hello from Titan"

  - stage: Build
    dependsOn: [Hello]
    steps:
      - sh: ./gradlew --version
```

Each `stage` is a node in the DAG; `dependsOn` wires the edges. Steps run
on a worker that matches the `agent` label.

## See a build

Trigger a build of the pipeline (via a configured SCM webhook, a cron
trigger declared in the YAML, or a manual run from the UI) and watch it
execute:

- **Builds** lists every run with its status.
- **Build detail** shows the flow DAG, live log stream (SSE), test
  results, and archived artifacts.
- **Queue** shows work waiting for a worker; **Workers** shows the fleet.

The repository's own dogfood pipeline — `.titan/pipeline.yml`, which
compiles, tests, and ships Titan itself — is the canonical worked example
of every shipped grammar keyword.

## Where to go next

- **[Concepts](concepts/)** — the mental model: pipelines and builds, the
  engine, triggers and discovery.
- **[Reference: PDL](reference/pdl.md)** — the full pipeline language: every
  root key, stage/step shape, and grammar scope.
- **[Guides](guides/)** — task-oriented how-tos: writing a step,
  credentials, artifacts, SCM integration, testing, security.
- **[Operations](operations/)** — deploying and running Titan.

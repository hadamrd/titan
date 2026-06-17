# Architecture

How the Titan engine works internally — for engineers who want depth.

Titan is a CI/CD pipeline **execution engine**: a controller, a worker, and a
PostgreSQL database that is the single source of truth. There is no in-memory
authority and no resume — recovery is reconciliation against the database.

| Document | What it covers |
|---|---|
| [overview.md](overview.md) | The system: controller, worker, the database, the pull-based queue between them. |
| [execution-and-queue.md](execution-and-queue.md) | The durable task queue — leases, visibility timeout, idempotency, scheduling, build-scoped workspaces, production traps. |
| [recovery-and-failure.md](recovery-and-failure.md) | Reconcile-not-resume recovery, the reaper, the structured failure model, why not Temporal. |
| [synthesis.md](synthesis.md) | The parse → synthesize → execute split, and the single-source grammar/schema. |

## Module map

| Module | Role |
|---|---|
| `titan-server` | The controller — queue processor, orchestrator, DAOs, schedulers, HTTP/SSE API. |
| `titan-worker` | The worker — pulls tasks, runs steps in a build-scoped workspace, streams logs, heartbeats. |
| `titan-pipeline-model` | The pipeline grammar, parser, `PipelineModel`, schema generator. |
| `titan-db-core` | Flyway migrations + the `titan` schema. |
| `titan-step-api`, `titan-trigger-api` | The step and trigger SPIs. |

The controller and worker are Quarkus applications. They share no memory and
communicate only through the database.

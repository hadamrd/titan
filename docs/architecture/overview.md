# System overview

Titan is two stateless processes — a controller and a worker — coordinating through a PostgreSQL database that holds all execution state.

## At a glance

```mermaid
flowchart LR
    SCM["SCM / webhook<br/>(GitHub, cron)"] -->|trigger build| CTRL

    subgraph CTRL["Controller (titan-server, Quarkus) — runs NO user code"]
        direction TB
        PARSE["parse + synthesize<br/>→ PipelineModel"] --> BAKE["bake<br/>→ static DAG (flow_nodes)"]
        BAKE --> ADV["advance()<br/>reconcile DAG, enqueue tasks"]
    end

    CTRL -->|enqueue ORCHESTRATE / EXECUTE_COMMAND| DB
    CTRL -->|reads/CAS-writes rows| DB

    subgraph DB["PostgreSQL — single source of truth (titan.*)"]
        direction TB
        Q["task_queue<br/>(durable pull queue)"]
        B["builds<br/>(immutable pipeline_model_json)"]
        FN["flow_nodes<br/>(per-node DAG state)"]
        LG["logs"]
        AG["agents (worker registry)"]
    end

    DB -->|claim via FOR UPDATE SKIP LOCKED| W
    W -->|complete (lease-token guarded)| DB

    subgraph W["Workers (titan-worker, pull-based, N replicas)"]
        direction TB
        EXEC["execute steps<br/>in build-scoped workspace"] --> STREAM["stream logs + heartbeat"]
    end

    W -->|ServiceLoader SPI| ART["Artifact stores<br/>(S3 / Nexus / fs)"]
    W -->|ServiceLoader SPI| SEC["Secrets / KEK<br/>(db-envelope / Infisical)"]

    DB -->|REST + SSE| UI["titan-ui<br/>React/Vite SPA<br/>(live logs over SSE)"]
    CTRL -.->|serves API| UI

    classDef store fill:#0d1b2a,stroke:#415a77,color:#e0e1dd;
    class DB,Q,B,FN,LG,AG store;
```

The controller and worker **never address each other** — every interaction is a
row in PostgreSQL. The controller parses and synthesizes a pipeline into a static
DAG, bakes it into immutable `flow_nodes`, and reconciles that DAG by enqueuing
tasks; it runs no user code. Workers pull `EXECUTE_COMMAND` tasks with
`FOR UPDATE SKIP LOCKED`, run steps, and stream logs back — completion is guarded
by a lease token so a zombie worker can never double-write.

## The three pieces

```
                     ┌──────────────────────────────────┐
                     │           PostgreSQL              │
                     │      (schema: titan.*)            │
   claims ADVANCE    │                                   │   claims EXECUTE
   ┌─────────────────┤  task_queue   ← the pull queue    ├─────────────────┐
   │                 │  builds       ← per-build record  │                 │
   │   ┌─────────────┤  flow_nodes   ← per-node DAG state ├─────────────┐   │
   │   │  completes  │  logs         ← step output        │  completes  │   │
   │   │             │  agents       ← worker registry    │             │   │
   ▼   ▼             └──────────────────────────────────┘             ▲   ▼
┌──────────────┐                                              ┌──────────────────┐
│  Controller  │                                              │      Worker       │
│ titan-server │                                              │   titan-worker    │
│              │   never talks directly to the worker   →     │                   │
│ orchestrate, │   ←   never talks directly to the controller │ run steps, stream │
│ bake, API    │                                              │ logs, heartbeat   │
└──────────────┘                                              └──────────────────┘
```

The controller and worker never address each other. Every interaction is a row
in the database. A worker that comes up knowing only a PostgreSQL connection
string can do its entire job.

## The controller (`titan-server`)

A Quarkus application. It owns orchestration and the API surface; it never runs
user code.

- **Drives the queue.** `QueueProcessorScheduler` ticks `QueueProcessor.tick()`
  on a Quarkus `@Scheduled` loop (default `1s`, non-reentrant). Each tick reaps
  stale claims, then claims up to a batch of controller-side tasks and routes
  each to a handler.
- **Bakes pipelines.** Once a `PipelineModel` exists, `BakeHandler` materialises
  it into `flow_nodes` rows — the static DAG the build executes against.
- **Reconciles the DAG.** `TitanOrchestrator.advance()` is the reconciler: it
  reads `flow_nodes`, decides the next move (dispatch a step, evaluate a gate,
  skip a node, close the build), applies it with compare-and-set transitions,
  and re-enqueues itself. It holds no state between ticks.
- **Schedules and reaps.** Timer/reaper schedulers (e.g. `AgentReaperScheduler`,
  the per-tick task reaper inside the queue loop) release dead leases and mark
  unresponsive workers offline.
- **Serves the API.** HTTP + SSE for triggering builds, reading status, and
  streaming logs.

The controller is deliberately a thin dispatching layer. There is no per-build
state cached in its heap; killing and restarting it loses nothing in flight.

### Decomposition

The orchestrator and queue processor were once two ~1000-line classes. They are
split into focused collaborators (`io.adaptiq.titan.flow.orch.*` and the queue
handlers): `StepDispatcher`, `GateEvaluator`, `BuildCloser`, `StageTeardownService`,
`MatrixCoordinator`, and per-action handlers (`SynthesizeHandler`, `BakeHandler`,
`AdvanceHandler`, `ReplayFromNodeHandler`). `TitanOrchestrator.advance()` keeps
the reconcile loop; the queue processor keeps the claim/reap/route loop.

## The worker (`titan-worker`)

A Quarkus application. It is the only place steps execute.

- **Pulls work.** A polling loop claims `EXECUTE_COMMAND` tasks from the queue it
  is responsible for. The controller never pushes to a worker.
- **Runs steps.** `TaskExecutor` resolves a build-scoped workspace
  (`build-<buildId>`), builds a clean step environment from the task payload,
  runs the step, and writes the result.
- **Streams logs.** Step stdout/stderr is written to `logs` keyed by task token.
- **Heartbeats.** A dedicated heartbeat thread updates the worker's row on a
  fixed cadence, independent of step execution, so a long step does not look
  like a dead worker.

## The database as single source of truth

A build's entire execution state is rows in PostgreSQL (`titan` schema):

- `builds` — one row per build, holding the immutable `pipeline_model_json`.
- `flow_nodes` — per-node progress (`PENDING → QUEUED → RUNNING → SUCCESS /
  FAILED / SKIPPED`), plus the structured failure fields.
- `task_queue` — in-flight work, each claimed row carrying a `claim_token` lease.
- `logs` — step output.
- `agents` — the worker registry and heartbeats.

"What happens next" is a pure function of these rows. No process holds
authoritative state the database does not already have. This is what makes
recovery a reconciliation rather than a resume — see
[recovery-and-failure.md](recovery-and-failure.md).

## The pull-based queue

`task_queue` is the only coordination channel. Both sides **pull**:

- the controller pulls controller-side tasks (`SYNTHESIZE`, `BAKE`, `ADVANCE`),
- the worker pulls `EXECUTE_COMMAND` tasks from its queue.

A claim is a lease, not a hand-off: `SELECT ... FOR UPDATE SKIP LOCKED` stamps a
`claim_token`, the row is processed outside any long transaction, and completion
is rejected if the token no longer matches. Two processes never claim the same
row; a dead claimant's lease is reclaimed by the reaper. The mechanics are in
[execution-and-queue.md](execution-and-queue.md).

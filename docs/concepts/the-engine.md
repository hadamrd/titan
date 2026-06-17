# The engine

Titan is a server that plans work and workers that execute it, coordinated
through a durable task queue in Postgres.

## Two roles

**Server (controller).** Plans and tracks. It bakes a pipeline into a graph,
walks that graph to decide what should run next, and records every transition.
It evaluates conditions and orchestration logic — but it never runs a user's
build step. The server is stateless with respect to build execution: it holds
no authoritative progress in memory. Any number of server instances can run
against the same database.

**Worker.** Executes. A worker runs the actual work — shell commands and the
synthesis program — as ordinary processes, streams their logs back, and
heartbeats while busy. Workers hold no orchestration logic; they claim a unit
of work, run it to completion, and report the result.

The split mirrors Temporal's workflow/activity boundary: lightweight
orchestration decisions on one side, side-effecting work on the other.

## The pull-based task queue

The server and workers never call each other directly. They communicate
through one table, `titan.task_queue`. Work is *pulled*, not pushed:

- The server enqueues tasks. `ORCHESTRATE` tasks (carrying an action —
  `SYNTHESIZE`, `BAKE`, or `ADVANCE`) advance a build's graph and are processed
  by the server. `EXECUTE_COMMAND` tasks are leaf step bodies and are processed
  by workers.
- Each consumer polls for tasks it can handle. A worker polls for the queue
  matching its agent label; the server polls for orchestration tasks.

A task is claimed with a single `SELECT … FOR UPDATE SKIP LOCKED` statement
that stamps a claim token. Two pollers can never claim the same row — the
second simply gets the next available one. The claim transaction commits
immediately; the actual work runs *outside* any transaction, so a thirty-minute
step never holds a row lock or pins the database's vacuum horizon. Completion is
a separate update, guarded by the claim token so a stale worker cannot overwrite
a task that was already reaped and re-run.

## Durable state in Postgres

A build's entire execution state is rows in Postgres — there is no on-disk
workspace file and no in-memory authority:

- `builds` — one row per run: status, parameters, the immutable baked graph.
- `flow_nodes` — one row per stage/step, each with its own status; this is the
  DAG and the progress record.
- `task_queue` — in-flight work and its leases (claim tokens, visibility
  timeouts, attempt counts). Completed tasks are moved to `task_archive` by a
  periodic reaper, keeping the hot table small.
- `logs` — step output, chunked and ordered, streamed from workers.

Because state is rows rather than an append-only event log, there is no history
to compact and no per-build size cliff. A 10,000-step pipeline is 10,000
independently queryable rows.

## Reconcile, not resume

Titan does not resume a build by restoring a memory snapshot, and it does not
replay an event history. It reconciles: "what happens next" is a pure function
of the rows, so killing any server or worker is survivable — a survivor (or the
restarted process) reads the database and continues. The reconcile model, the
three recovery primitives (lease token, visibility-timeout reaper, compare-and-set
transitions), the per-crash behaviour, and the at-least-once-plus-idempotent
guarantee are covered in full in
[recovery-and-failure.md](../architecture/recovery-and-failure.md).

For the schema, the orchestrator internals, and the queue mechanics in full,
see the [architecture docs](../architecture/).

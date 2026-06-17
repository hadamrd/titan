# Execution and the task queue

The durable task queue is the engine's coordination primitive — a PostgreSQL table that delivers work exactly enough times, leased and reclaimable.

The queue lives in `titan.task_queue`. The DAO is `TaskQueueDao`
(`io.adaptiq.titan.store`); the claim loop is `QueueProcessor`
(`io.adaptiq.titan.queue`).

## Task shape

Each row carries a `type`, a `queue_name`, lifecycle status, a JSON payload, and
the lease/retry columns:

| Column | Purpose |
|---|---|
| `type` | `ORCHESTRATE` (controller-side, advances a build's DAG) / `EXECUTE_COMMAND` (worker-side leaf step) / `CANCEL_TASK` (abort). `START_PIPELINE` is a legacy synonym routed to the synthesize handler. |
| `queue_name` | Routing label. Workers poll their own queue; synthesis uses the shared `synthesis` queue. |
| `status` | `QUEUED → CLAIMED → PROCESSING → COMPLETED / FAILED / ...`. |
| `priority` | Higher picked first. |
| `payload_json` | Task arguments. An `ORCHESTRATE` task carries an `action` (`SYNTHESIZE` / `BAKE` / `ADVANCE` / `REPLAY_FROM_NODE`). |
| `result_json` | Written on completion. |
| `attempts` / `max_attempts` | Retry budget. |
| `visibility_timeout_seconds` | Lease length; after it expires a claimed row is reclaimable. |
| `claim_token` | The lease — a UUID stamped on claim, verified on completion. |
| `claimed_by` / `claimed_at` | Who holds the lease, and since when. |
| `available_at` | Delayed-delivery gate — the row is claimable only once `available_at <= now()`. |
| `cancel_requested_at` | Monotonic cancel-intent signal, set once. |
| `task_token` | Stable per-task identity; logs are keyed by it. |
| `build_id` / `node_id` | Correlation back to the build and DAG node. |

Completed/failed rows are swept to `task_archive` so the hot table stays small
and the partial poll index stays tiny.

## How a row is claimed

```sql
-- conceptual: claim one QUEUED, available, matching-queue row
SELECT ... FROM titan.task_queue
WHERE status = 'QUEUED' AND queue_name = :q
  AND available_at <= CURRENT_TIMESTAMP
ORDER BY priority DESC, created_at
LIMIT 1
FOR UPDATE SKIP LOCKED;
-- then stamp status='CLAIMED', claim_token=<uuid>, claimed_by, claimed_at
```

`FOR UPDATE SKIP LOCKED` guarantees two pollers never see the same row. The
claim is a **lease**: it writes a fresh `claim_token` UUID and commits
immediately, so the actual work runs outside the transaction holding no locks.
The lease token, the visibility-timeout reaper, and lease-guarded completion
are the three primitives that make recovery safe — they are covered in full in
[recovery-and-failure.md](recovery-and-failure.md).

## The phase chain

A build moves through controller-side phases, each enqueued as its own task so a
crash between phases is recovered by ordinary re-delivery:

```
build QUEUED
  → SYNTHESIZE   worker produces a PipelineModel → builds.pipeline_model_json
  → BAKE         controller materialises the model into flow_nodes
  → ADVANCE      controller reconciles the DAG, dispatching EXECUTE_COMMAND tasks
```

(`START_PIPELINE` is a legacy synonym routed to the synthesize handler.)
`ADVANCE` re-enqueues itself as the DAG progresses; each worker step is its own
`EXECUTE_COMMAND` task. See [synthesis.md](synthesis.md) for the
synthesize/bake split.

## Scheduling and concurrency

- **Cadence.** `QueueProcessorScheduler` runs `tick()` on a Quarkus `@Scheduled`
  loop, default `1s`, with `ConcurrentExecution.SKIP` plus a non-reentrant guard
  so a long bake pass never stacks overlapping claim passes.
- **Batch.** Each tick reaps stale claims, then claims up to a fixed batch of
  tasks and routes each to its handler.
- **Build → worker affinity.** A build's steps route to its stage's `agent:`
  queue, which one worker polls, so every step of a build lands on the same
  worker. This affinity is a property of the queue model, not a separate
  mechanism — it is what makes build-scoped workspaces work.
- **Concurrency limits / retries.** Failed step retries re-enqueue with a future
  `available_at = now + backoff(attempt)`, reusing the delayed-delivery gate
  rather than a separate scheduler.
- **No-worker fail-fast.** A per-tick sweep fails `QUEUED` step tasks whose
  target queue has no live worker, so a mistargeted pipeline surfaces an error
  instead of stalling silently.

## Build-scoped workspaces

The worker keys each build's workspace by build id, not task token:

```
<workspaceRoot>/build-<buildId>/
```

Every step of every stage in one build resolves the same directory, so
`checkout` → `build` → `archiveArtifacts` see each other's files — a
per-build workspace model. Isolation is preserved where it matters: two *unrelated* builds
never share (`build-<A>` ≠ `build-<B>`). The payload `workDir` is rebased under
this base; absolute paths are rejected. The workspace is reaped when the build
reaches a terminal state.

Per-build was chosen over per-step (the original bug — it isolated the
sequential steps that are supposed to collaborate) and over per-stage (which
would break the common pipeline that relies on a shared workspace).
Parallel stages share the build workspace with eyes open — the conventional
`parallel` semantics, footgun included; the honest escape hatch is an explicit
per-branch `dir(...)`.

## Production hardening traps

The queue is a distributed system. The traps that bite if ignored:

- **Lease token, not bare `SKIP LOCKED`.** Without a `claim_token`, a zombie
  worker can complete a task it no longer owns. Stamp on claim, verify on
  completion.
- **Build row + first task in one transaction.** A crash between the two writes
  leaves a zombie build with no task. Schedule both in a single `withTransaction`.
- **Compare-and-set every DAG transition.** Two controllers processing the same
  node both advance it unless every transition is
  `UPDATE flow_nodes SET status=? WHERE node=? AND status=?`.
- **Write step status and output in one `UPDATE`.** Output-then-mark is a torn
  read for any controller polling in between.
- **Bake once; `pipeline_model_json` is immutable.** Re-baking on retry yields a
  different model. The bake handler detects an existing baked model and skips.
- **Validate the baked DAG before execution.** Cycles, missing deps, duplicate
  ids, self-dependencies — fail at bake, not 30 minutes into a run.
- **Single-table claim query.** Joining other tables in the `FOR UPDATE` claim
  lets its row locks block unrelated queries. Claim from one table; update after.
- **Keep the hot table small.** Partial index on `WHERE status = 'QUEUED'`;
  archive completed rows so `VACUUM` and the poll index stay cheap.
- **Heartbeat thread is independent of step execution.** A step that blocks the
  JVM must not block the heartbeat — dedicated executor.
- **Steps get a clean env, never the worker's.** Build the step environment from
  the payload only, so DB passwords and tokens in the worker JVM do not leak into
  step processes.
- **`Instant.now()` is not monotonic.** Use `System.nanoTime()` for durations and
  timeouts; wall-clock only for timestamps.

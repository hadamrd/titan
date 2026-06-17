# Recovery and failure

Titan recovers by reconciling against the database, not by resuming a process — and a failure is structured state on the node that failed, not a log line.

## Reconcile, don't resume

Classic engines resume a build by restoring in-memory state — a heap snapshot,
or a replayed event history. Titan does neither, on purpose.

> Titan has no resume because it has no program. The database holds the plan,
> the progress, and the leases. Recovery is the reaper releasing a dead holder's
> lease and the next poller re-deriving the next move. It is a reconciler, not a
> continuation.

No process — controller or worker — holds authoritative execution state. There
is nothing to snapshot, nothing to corrupt, nothing to lose. Kill any process; a
survivor (or the restarted one) reads the database and continues. This is the
Kubernetes-controller model: observe desired vs actual, act, repeat.

### The four durable facts

A build's entire execution state is four things in PostgreSQL:

1. **The immutable baked DAG** — `builds.pipeline_model_json`, written once at
   synthesis/bake, never mutated.
2. **Per-node progress** — `flow_nodes` rows (`PENDING → QUEUED → RUNNING →
   SUCCESS / FAILED / SKIPPED`).
3. **In-flight work and leases** — `task_queue` rows, each carrying a
   `claim_token` while claimed.
4. **Step outputs** — `result_json` on the completed node.

`TitanOrchestrator.advance()` is a pure function of these rows; it holds no
memory the database does not already have.

### The three primitives that make recovery safe

1. **Atomic claim + lease token.** `SELECT ... FOR UPDATE SKIP LOCKED` claims a
   row and stamps a `claim_token`. Two pollers never claim the same row.
2. **Visibility-timeout reaper.** A claimed row whose holder stopped progressing
   past its timeout is reset to `QUEUED` — the dead lease is released.
3. **Compare-and-set transitions.** Every `flow_nodes` / `task_queue` transition
   is `UPDATE ... WHERE status = <expected>`, so two controllers cannot both
   advance the same node.

### The crash cases

- **Controller crash.** It held no authoritative state. Its `ADVANCE` task
  carried a `claim_token`; the reaper sees the stale lease, re-queues it, another
  controller claims it and re-derives the next move from `flow_nodes`. Safe
  because orchestration is idempotent and CAS-guarded. The build row and its
  first task are written in one transaction, so a crash there leaves neither —
  never a zombie build with no task.
- **Worker crash mid-step.** It held a task lease. Its heartbeat stops; the
  reaper re-queues the task; another worker claims it and re-runs the whole step
  from scratch. The zombie worker, waking later, tries to record completion — its
  `claim_token` no longer matches, so the completion is rejected. No double
  *write*.
- **Hand-off to another worker.** Not a special operation. A step is a
  `task_queue` row; re-queue it and any eligible worker claims it. Crash-recovery
  and deliberate hand-off are the same machinery.

### The reaper

A scheduled sweep, run conservatively (`AgentReaperScheduler` marks workers whose
heartbeat is stale as offline; the per-tick task reaper inside `QueueProcessor`
reclaims claimed rows past their visibility timeout). Heartbeats catch a
*crashed* worker quickly; a worker wedged on stuck I/O is caught only when its
task's visibility timeout fires. That timeout is the sole hard deadline — it must
exist and be conservative.

### Honest guarantees and the caveats

The strength is real — no in-memory authority means nothing to corrupt or lose —
but it has a precise shape:

1. **Recovery granularity is one whole step.** There is no mid-step checkpoint:
   a step that dies 40 minutes in redoes 40 minutes. The DSL pushes authors
   toward small steps.
2. **A hung-but-alive worker is caught only by the visibility timeout.**
   Heartbeats catch a crash; only the timeout catches a wedge.
3. **Idempotency is a human contract the engine cannot enforce.** The model
   rests on "re-running a step is safe." A step that performs a side effect and
   then dies before recording completion is retried — the side effect happens
   twice. The `claim_token` rejects the stale *completion record*; it does not
   undo the effect. The only mitigation is an idempotency key on the
   side-effecting operation itself.
4. Therefore the honest guarantee is **at-least-once delivery + idempotent steps
   = effectively-once.** Titan never claims exactly-once.

A chaos suite (Testcontainers + injected faults) is the specification's proof:
controller killed before commit (no zombie build), controller killed
mid-advance (DAG still completes exactly once), worker killed mid-step (task
reaped, re-run, late completion rejected on token mismatch), network partition
(no double-complete on reconnect), reaper concurrent with completion
(exactly-once outcome), two controllers on one task (CAS prevents double-advance).

## Why not Temporal

Temporal is the gold standard for durable workflow execution, and a study of its
production pain points shaped Titan's design. Each is avoided structurally, not
patched later:

| Temporal's decision | The problem it creates | Titan's counter-decision |
|---|---|---|
| Append-only event history | Unbounded growth; the `ContinueAsNew` escape hatch | Mutable state rows + an archive reaper — no log to explode. A 10,000-step build is 10,000 `flow_nodes` rows. |
| Replay-based determinism | Versioning hell; workflows stuck on non-determinism errors | Don't replay code — deliver messages. The pipeline is snapshotted to `pipeline_model_json` at start; pipeline scripts have no determinism constraint. |
| Per-activity retry, no global limiter | Retry storms amplify downstream outages | Finite default `max_attempts`; backed-off re-enqueue via `available_at`. |
| Code-first composition | "MEGA" workflows with no domain boundary | A declarative static DAG; composition via synthesis. |
| Controller-local state cache | Memory thrash, OOM | A truly stateless controller — the queue is the cache, the database is the state machine. |

The core inversion: Temporal's durability comes from replaying a history, which
forces deterministic code and an ever-growing log. Titan's durability comes from
the state simply *being* the current rows — so there is no history to replay, no
determinism to enforce, and no log to compact.

## The failure model

A failure is structured state on the unit that failed — persisted, queryable,
rendered consistently — not a string smuggled into a log stream.

- A **step/stage node** that ends `FAILED` carries why: a `failure_category`
  (a closed enum) and a human `failure_reason`.
- A **build** that fails before any node runs — a synthesis or bake failure —
  carries a `failure_summary` on the build itself, because there is no node to
  attach to.
- The **step log** is unchanged: it still holds a step's own stdout/stderr. A
  step that ran and exited non-zero records `failure_category = STEP_EXIT` plus a
  one-line reason; its detail is already in its log. The failure model provides
  the summary and category — and the *only* copy of the reason for failures that
  produced no log.

### The schema

Added by one additive migration; every column is nullable, so finished builds
read identically to "no failure recorded":

- `flow_nodes.failure_category` and `flow_nodes.failure_reason`
- `builds.failure_summary`

### Failure categories — a closed set

| Category | Meaning |
|---|---|
| `STEP_EXIT` | The step ran and exited non-zero — the reason is in its log. |
| `CREDENTIAL` | A `credentials:` / `sshAgent:` binding could not be resolved. |
| `DISPATCH` | The step could not be turned into a runnable task. |
| `BAKE` | The pipeline model could not be materialised into a DAG. |
| `SYNTHESIS` | The pipeline definition could not be synthesised. |
| `PRECONDITION` | A precondition gate was not met. |
| `TIMEOUT` | The step exceeded its budget / was reaped. |
| `INTERNAL` | An unexpected engine error — a bug, not a pipeline mistake. |

A new category is a deliberate, reviewed addition, not an extension point. The
`failure_reason` quality bar is a human sentence that names the thing and the
fix — not a stack trace.

### One source, several renderings

Every controller-side failure path sets the structured field rather than minting
a log line. The graph API exposes `failureCategory` / `failureReason` per node
and `failureSummary` per build; the console renders the reason at the end of a
failed node's section and a build summary line; the UI renders the category as an
icon and the reason inline. A build never "just crashes" with a bare `FAILED` and
no "why".

### Deliberately not built

A general append-only `build_events` table (Temporal-style event history), a
structured cause-chain, and a remediation engine are all named and deferred. A
category plus a sentence is the whole scope.

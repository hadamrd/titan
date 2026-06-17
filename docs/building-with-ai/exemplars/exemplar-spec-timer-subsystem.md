# Design — Titan durable timer subsystem (`TimerService`)

> **Exemplar.** A representative AI-authored design spec, kept verbatim as
> evidence of the build method. Written in 2026-05 against the repo's then-current
> layout — a historical artifact, not maintained documentation. Paired with
> [exemplar-plan-timer-substrate.md](exemplar-plan-timer-substrate.md).

**Date:** 2026-05-18
**Status:** approved — ready for implementation plans (phased)
**Scope:** Titan engine — `titan-plugin` (orchestrator, scheduler), `titan-db-core` (migration), `titan-pipeline-model` (grammar scopes)

## Goal

Give Titan a **durable, controller-native timer** — the Temporal model: a wait is a
persisted fact in the database, not a blocked thread. No worker, no executor slot,
no JVM thread is consumed while a pipeline waits. A wait **resumes** across a
controller restart at the correct wall-clock instant; it does not restart.

This subsystem powers four consumers:

1. **Durable `sleep` / `waitUntil`** — a node parks until an instant.
2. **Stage/step `timeout:`** — fail a node that overruns a deadline.
3. **Retry with backoff** — delay a retry by `backoff(attempt)`.
4. **Scheduled gate resume** — auto-advance a gate at a set time.

## Why the current `sleep` is wrong

The `sleep` step shipped on 2026-05-18 (`SleepStepHandler`) is a worker-side
`Thread.sleep`. It pins a worker executor thread and the task's `task_queue`
claim for the whole duration, and Titan's crash model (design/30) is
*reap-and-re-run-from-the-start* — so a worker bounce mid-sleep **restarts** the
wait from zero, losing all elapsed time. That is acceptable for a few seconds; it
is wrong for hours/days. This subsystem replaces it. `SleepStepHandler` is
**retired** in Phase 2 — not kept alongside the durable one (two `sleep`s with
different semantics would be a trap).

## Non-goals

- Cron / recurring schedules — Titan already has `TriggerEngine` (design/50–52)
  for build-level scheduling. This subsystem is for *in-build* waits only.
- Sub-second precision — the sweeper tick (~5 s) bounds resolution. A timer fires
  within one sweep interval of `fire_at`. That is correct for CI/CD waits.
- Replacing `task_queue.available_at` — that task-level delay primitive stays as
  is for its existing uses; timers are a separate, higher-level concept.

## Architecture

A *timer* is a durable row: "at instant `fire_at`, fire `kind` against
`node_id` of `build_id`." It lives entirely in the controller DB.

### The substrate — three components

**`titan.timers` table** (migration `V8__timers.sql` — V7 is the current head):

| column | type | purpose |
|---|---|---|
| `id` | uuid PK | timer identity |
| `build_id` | bigint NOT NULL | owning build |
| `node_id` | varchar NOT NULL | the `flow_nodes` node the timer concerns |
| `kind` | varchar NOT NULL | `SLEEP` · `TIMEOUT` · `RETRY_BACKOFF` · `GATE_RESUME` |
| `fire_at` | timestamptz NOT NULL | when it fires |
| `status` | varchar NOT NULL | `ARMED` · `FIRED` · `CANCELLED` |
| `payload` | text | kind-specific JSON (retry attempt #, timeout reason, …); nullable |
| `claim_token` | varchar | sweeper visibility-lease token; nullable |
| `claimed_at` | timestamptz | when claimed; nullable |
| `created_at` | timestamptz NOT NULL | audit |
| `fired_at` | timestamptz | when actually fired; nullable |

`status` is a plain `varchar` (no DB `CHECK`) — consistent with `flow_nodes.status`,
which Titan keeps open for additive states. Indexes: a partial index on
`(fire_at) WHERE status = 'ARMED'` for the sweeper claim; an index on
`(build_id, node_id, kind)` for idempotent arm + cascade cancel.

**`TimerDao` + `TimerRow`** — JDBI, following the `FlowNodeDao` / `FlowNodeRow`
pattern; `TimerRow` is a mutable public-field POJO. Registered in the
`TitanStores` façade. Lives in `titan-plugin/.../store/`.

**`TimerService`** — the façade the orchestrator calls. Two operations:

- `arm(kind, buildId, nodeId, fireAt, payload)` — inserts an `ARMED` timer.
  **Idempotent** on `(buildId, nodeId, kind)`: if an `ARMED` timer already exists
  for that triple, arming is a no-op (a re-run of `advance()` never double-arms).
- `cancel(buildId, nodeId, kind)` and `cancelAll(buildId)` — flip matching
  `ARMED` rows to `CANCELLED`.

**`TimerSweeper`** — a new `@Extension PeriodicWork`, ~5 s tick, modeled on
`DiscoveryWorker`'s three-phase loop with an `AtomicBoolean` non-reentrancy guard:

1. **Reclaim** — reset `claimed_at`/`claim_token` on rows claimed longer than a
   stale threshold (a tick that died mid-fire).
2. **Claim** — `SELECT … FOR UPDATE SKIP LOCKED WHERE status='ARMED' AND
   fire_at <= now()` (DB `now()`), stamp `claim_token` + `claimed_at`, batch-limited.
3. **Fire** — run the kind-specific fire action for each claimed timer, then set
   `status='FIRED'`, `fired_at=now()`.

### Two invariants that make it correct

- **The DB clock is the only clock.** `fire_at` is always compared against SQL
  `now()` inside the claim query — never controller JVM time. Multi-controller
  safe, no skew. Tests exploit this: they arm a timer with `fire_at` in the past
  and tick the sweeper once — no real time is ever waited.
- **Every fire is a CAS that loses gracefully.** The `timers` table is
  *advisory*; `flow_nodes.status` is the truth. A `TIMEOUT` fire does
  `compareAndSetStatus(node, RUNNING → FAILED)` — if the node already reached a
  terminal state, the CAS fails and the fire is a harmless no-op. This resolves
  every timer-vs-engine race without Temporal's event-history replay.

### Self-healing

`fire_at` is mirrored onto the `flow_nodes` row (a nullable `wake_at` column,
added in Phase 2). If a timer row were ever lost, any later `ADVANCE` for the
build re-evaluates a `SLEEPING` node against its own `wake_at` and re-arms. The
timer table is an optimization over polling, not a single point of failure.

## The four consumers

Each is a thin user of the substrate. The orchestrator (`TitanOrchestrator.advance()`)
gains one handler per kind. "Firing" a timer almost always means: enqueue an
`ADVANCE` task for the build so the orchestrator re-evaluates.

### ① Durable `sleep` / `waitUntil` — no grammar change

`advance()` recognizes `sleep` and `waitUntil` as **control-plane step ids** via a
small registry, checked before the worker-dispatch path. On reaching such a node
(status `QUEUED`):

- compute `fire_at` — `sleep: 30m` is relative to `now()`; `waitUntil:
  2026-06-01T09:00Z` is absolute;
- `CAS(node, QUEUED → SLEEPING)`, set `flow_nodes.wake_at = fire_at`;
- `TimerService.arm(SLEEP, build, node, fireAt, null)`;
- dispatch **no** worker task.

On `SLEEP` fire: `CAS(node, SLEEPING → SUCCESS)`, enqueue `ADVANCE`.

`SLEEPING` is the one new `flow_nodes` state. `SleepStepHandler` (worker) and its
registration in `SocleStepHandlerProvider` are removed; its tests are deleted.
The YAML surface (`sleep: 30`) is unchanged — only the execution layer moves.

A `fire_at` already in the past fires on the next sweep (immediate) — matches
Temporal, least-surprising. A loose sanity ceiling (~1 year) rejects an obviously
typo'd duration/timestamp; there is otherwise **no maximum** — `sleep: 30d` is
legitimate (the 24 h cap on the old worker `sleep` existed only because it blocked
a thread).

### ② Stage/step `timeout:` — new grammar scope (Path B)

A new `TimeoutScope` in `titan-pipeline-model/.../flow/parser/`, a sibling of the
existing `RetryScope` / `WhenScope`, registered in `TitanYamlParser`'s `SCOPES`
list; the JSON schema is regenerated via `TitanSchemaGenerator.main()` (never
hand-edited).

When a node inside a `timeout:` scope transitions to `RUNNING`, `advance()` arms
a `TIMEOUT` timer at `now() + limit`. When the node finishes normally, `advance()`
calls `TimerService.cancel(TIMEOUT, …)`. If the timer fires first, the fire
action `CAS(node, RUNNING → FAILED)` and — on a winning CAS — enqueues a `CANCEL`
task so the worker actually stops the process. The existing `task_queue`
`claim_token` guard rejects the losing party's late completion.

### ③ Retry with backoff — enhances `RetryScope`

`RetryScope` already exists (migration `V4__retry.sql`, `RetryScope.java`). Add an
optional `backoff:` key (e.g. `backoff: 30s` fixed, or `backoff: exponential`).
Today a retryable failure re-dispatches immediately. With `backoff:` set, on a
retryable failure with attempts remaining, instead of re-queuing now, `advance()`
arms a `RETRY_BACKOFF` timer at `now() + backoff(attempt)`; the attempt number is
carried in the timer `payload`. On fire: re-queue the node (`CAS` to `QUEUED`,
enqueue `ADVANCE`). No new node state — the node sits `FAILED` for the brief
backoff window, then the timer re-queues it.

### ④ Scheduled gate resume — small grammar addition to gates

Add optional `autoResumeAt:` / `autoResumeAfter:` to the gate grammar. A gate
normally sits `RUNNING` awaiting human approval; with this set, `advance()` also
arms a `GATE_RESUME` timer. On fire: advance the gate as if approved, recorded
against a **system actor** (not a person) in the approval audit. A manual
approval before the timer simply `cancel`s the `GATE_RESUME` timer.

## State machine

One new `flow_nodes` state — `SLEEPING`:

```
QUEUED  ──(sleep/waitUntil node)─────────▶ SLEEPING ──(SLEEP fires)──▶ SUCCESS
RUNNING ──(TIMEOUT fires, CAS wins)──────▶ FAILED
FAILED  ──(retryable, attempts left)─────▶ [RETRY_BACKOFF armed] ──(fires)──▶ QUEUED
RUNNING ──(GATE_RESUME fires | approval)─▶ SUCCESS   (gate node)
```

Timeouts and retry-backoff need no new node state.

## Races & crash behavior

| Situation | Resolution |
|---|---|
| `TIMEOUT` fires as the node completes | `CAS(node, RUNNING→FAILED)`; loser is a no-op. Late worker completion rejected by the `task_queue.claim_token` guard. |
| Manual gate approval vs `GATE_RESUME` timer | Whoever acts first wins via `CAS`; the other action is a no-op / the timer is `cancel`'d. |
| `advance()` re-runs and re-arms | `arm()` is idempotent on `(build,node,kind)`. |
| Two controllers sweep the same timer | `FOR UPDATE SKIP LOCKED` — exactly one fires it. |
| Sweeper tick dies mid-fire | Timer stays claimed; reclaim phase returns it; fire retried. Every fire action is idempotent (CAS + idempotent enqueue) — a double-fire is harmless. |
| Controller dies mid-sleep | The `timers` row persists. First sweep after restart reclaims and fires every due timer. The wait **resumes at the correct instant** — does not restart. |
| Worker dies mid-sleep | Irrelevant — no worker is involved. (The core fix vs. the old step.) |
| Build aborted with timers armed | Abort cascades `TimerService.cancelAll(buildId)`. |

## Testing

Adversarial, and built so no real time is waited — a test arms a timer with
`fire_at` in the past and ticks the sweeper once.

- **`TimerService` / `TimerDao` unit tests** — arm; cancel; idempotent re-arm
  yields one row; the `fire_at <= now()` claim boundary; `cancelAll`.
- **`TimerSweeper` tests** — fires only due timers; the reclaim phase returns a
  stale-claimed row; a fire action that throws leaves the timer re-claimable
  (not lost, not double-`FIRED`).
- **Concurrency ITs** (`TitanQueueConcurrencyIT` / `TitanChaosIT` style) — each
  race in the table above asserted: two sweepers never double-fire; `TIMEOUT`
  vs. completion settles to exactly one node outcome; `cancelAll` on abort.
- **Per-consumer end-to-end** — a pipeline exercising `sleep`, one `timeout:`,
  one `retry: + backoff:`, one `autoResumeAt:` gate; each asserts the node-state
  trajectory against an independent oracle.
- **Crash simulation** — arm a timer, drop the claim as if the controller died,
  assert the next sweep fires it at the right instant.
- **Regression guard** — `TitanSchemaGenerationTest` + `GrammarSchemaContractTest`
  pass after the `TimeoutScope` is added (schema regenerated, not hand-edited).
  Phase 1 and 2 must leave both passing **unchanged** (no grammar touched there).

## Implementation phasing

One spec, four shippable plans, each merging before the next begins:

| Phase | Delivers | Grammar |
|---|---|---|
| **1 — Substrate** | `V8__timers.sql`, `TimerRow`/`TimerDao`, `TimerService`, `TimerSweeper`, `TitanStores` wiring. Fully testable alone; no user-facing feature. | no |
| **2 — Durable sleep** | `sleep`/`waitUntil` as control-plane steps; `SLEEPING` state; `flow_nodes.wake_at`; retire worker `SleepStepHandler`. | no |
| **3 — Timeouts** | `TimeoutScope` grammar + `TIMEOUT` consumer + worker `CANCEL` wiring. | Path B |
| **4 — Retry backoff + scheduled resume** | `backoff:` on `RetryScope`; `autoResumeAt:` on gates. | small |

Each phase is independently reviewable and revertible.

## Source of truth

`docs/design/05-execution-model.md`, `docs/design/07-state-and-storage.md`,
`docs/design/26-titan-hardening.md` (reaping / `claim_token`),
`docs/design/30` (idempotency / reap-and-re-run), `docs/design/50–52` (scheduler
subsystem). Existing patterns to copy: `DiscoveryWorker` (sweeper loop),
`TaskQueueDao.reapStale` (visibility lease), `FlowNodeDao` (DAO + CAS),
`RetryScope` / `WhenScope` (grammar scopes).

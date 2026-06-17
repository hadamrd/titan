# ADR-0004: Pull-based workers over a SKIP LOCKED queue

**Status:** Accepted

**Context** — The controller must dispatch step execution to workers without holding open connections to them, without requiring inbound network access to worker hosts, and without losing work when a worker dies. A push model — where the controller opens a channel and pushes work to each worker — needs reachable agents and couples controller liveness to worker liveness.

**Decision** — Workers are pull-based. They poll the `task_queue` table and atomically claim a row with `SELECT … FOR UPDATE SKIP LOCKED`, stamping a `claim_token` lease. The controller never RPCs into a worker. Draining a worker means it stops polling.

**Consequences**
- No inbound connectivity to workers is required; they only need outbound access to the DB/API. Adding capacity is starting another poller.
- `SKIP LOCKED` guarantees two pollers never claim the same row, so claim contention does not need a separate lock service.
- A claimed row whose holder stops heartbeating is reset to `QUEUED` by a visibility-timeout reaper; a late completion from the dead holder is rejected on `claim_token` mismatch.
- Backpressure is delivered by worker-pool size alone — the queue intake is 1:1 with triggers, with no hidden debounce or rate limit (operators tune by sizing the pool).

# ADR-0019: Finite default retries and a priority queue

**Status:** Accepted

**Context** — Per-task retry policies with an infinite default (Temporal's choice) produce retry storms: when a downstream service fails, every in-flight task retries at once and amplifies load. FIFO-only queues separately cause priority inversion, where a batch of low-priority work starves urgent builds.

**Decision** — Retry attempts are finite by default (`maxAttempts = 3`); infinite retry is an explicit opt-in, not the default. The `task_queue` has a first-class `priority` column and the claim query orders by `priority DESC, created_at ASC`, so higher-priority work is always claimed first. `queue_name` is a routing hint a single worker can multiplex, not separate infrastructure.

**Consequences**
- A failing dependency exhausts a bounded number of attempts instead of retrying forever, capping the blast radius.
- Urgent tasks are not starved by bulk low-priority work, without fragmenting the worker pool into per-priority queues.
- Authors who genuinely need unbounded retry must say so explicitly.
- Adaptive global rate-limiting / weighted-fair-queuing / starvation-escalation are designed-for extensions on top of this base, not part of the current default.

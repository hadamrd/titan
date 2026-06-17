# ADR-0003: No event-history replay (why not Temporal)

**Status:** Accepted

**Context** — Temporal is the gold-standard durable-workflow engine, and Titan adopts its goals (crash-safe, horizontally-scalable execution). But Temporal's durability model rests on an append-only event history that is replayed to rebuild workflow state. That choice carries two structural costs that are now too late for Temporal to remove: histories grow without bound (the ~50k-event cap and the ContinueAsNew escape hatch), and replay demands that workflow code stay perfectly deterministic across deploys (the `getVersion` versioning nightmare). Running Temporal *as a dependency* would also reintroduce the multi-service distributed system Titan exists to avoid.

**Decision** — Titan does not replay code or events. State is the current contents of mutable tables (`flow_nodes`, `task_queue`), not an append-only log. The pipeline is parsed once into an immutable `pipeline_model_json` snapshot at bake time; that snapshot, not the live script, drives the build. Queue-message *payloads* are schema-versioned for evolution, so old workers can process messages written by new controllers.

**Consequences**
- There is no history to explode and no ContinueAsNew — a build that runs for weeks is just rows that haven't reached a terminal status.
- No determinism constraint on user pipelines; scripts may use clocks, randomness, and branching freely because they are never replayed.
- Mid-build edits to the job's YAML do not affect the running build — it uses the baked snapshot.
- Completed tasks are moved from `task_queue` to an archive table by a reaper so the hot table stays small.

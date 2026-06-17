# ADR-0002: Reconcile, don't resume

**Status:** Accepted

**Context** — A durable execution engine must survive a controller or worker crash mid-build. Some engines resume by restoring an in-process heap snapshot; Temporal resumes by replaying an event history. Both keep authoritative execution state in a process, which is the thing that can corrupt, drift, or be lost. Titan has no in-process execution snapshot and no replay log.

**Decision** — Recovery is reconciliation, not resumption. A build's entire state is four facts in the database — the immutable baked DAG (`builds.pipeline_model_json`), per-node progress rows (`flow_nodes`), in-flight work plus leases (`task_queue`), and step outputs (`result_json`). "What happens next" is a pure function of those rows; the orchestrator holds no memory the database does not already have.

**Consequences**
- No snapshot to restore, no program to corrupt; kill any process and a survivor re-derives the next move from the DB. This is the Kubernetes-controller model (observe desired vs actual, act, repeat).
- Recovery granularity is one whole step — there is no mid-step checkpoint, so a step that dies 40 minutes in re-runs from scratch. The DSL and docs steer authors toward small steps.
- A hung-but-alive worker is caught only by its task's visibility timeout, which must therefore always exist and be conservative.
- Crash recovery and deliberate hand-off to another worker are the same machinery: re-queue the `task_queue` row.

# ADR-0010: Matrix / each / templates fan out at parse time

**Status:** Accepted

**Context** — PDL supports `matrix`, `each`, and `template` constructs that expand one authored stage into many concrete stages. This expansion could happen at runtime (the orchestrator grows the DAG while the build runs) or at bake time (the YAML is expanded into a flat, concrete DAG before the build starts). The reconcile model (ADR-0002) depends on an immutable baked DAG that fully describes the build.

**Decision** — `matrix`, `each`, and `template` fan out at parse/bake time. The pipeline is expanded into a fully concrete DAG that is frozen into `pipeline_model_json`; there is no runtime fan-out.

**Consequences**
- The baked DAG is complete and immutable — recovery, progress accounting, and the UI all reason over a fixed node set.
- Replay-from-node reuses the parent build's baked model: no SCM re-fetch and no re-parse.
- Pipeline shape cannot depend on values only known at runtime; dynamic, data-driven fan-out is explicitly not supported in this model.
- Very large fan-outs materialize as many `flow_nodes` rows up front, which the complexity limits are designed to bound.

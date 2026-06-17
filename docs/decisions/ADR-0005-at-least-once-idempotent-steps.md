# ADR-0005: At-least-once delivery, idempotent steps

**Status:** Accepted

**Context** — The reconcile model (ADR-0002) re-runs a whole step when its worker dies. If a step performs a side effect (publish, deploy, POST) and then dies *before* recording completion, the retry runs the side effect again. The `claim_token` lease rejects the stale completion *record*, but it cannot un-happen the effect. This is the same hole every at-least-once system — including Temporal — has.

**Decision** — Titan guarantees **at-least-once** task delivery and never claims exactly-once. Effectively-once is achieved only when steps are idempotent. The engine provides the idempotency primitives (e.g. idempotency keys on side-effecting step helpers); making a given step safe to retry is a human contract the engine cannot enforce.

**Consequences**
- Every node transition is a compare-and-set (`UPDATE … WHERE status = <expected>`), so two controllers cannot double-advance the same node and a zombie worker cannot double-write completion.
- Side-effecting steps must carry an idempotency key, or accept that a crash between effect and completion-record retries the effect.
- The chaos suite is the proof: controller-crash, worker-crash, network-partition, and concurrent-reaper scenarios must all yield exactly-once outcomes for idempotent steps.
- Documentation must be explicit that the guarantee is at-least-once, not exactly-once.

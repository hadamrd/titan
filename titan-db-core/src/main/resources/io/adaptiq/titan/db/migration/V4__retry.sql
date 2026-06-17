-- Titan schema V4 — per-step retry attempt tracking (design/44 §5, build step 44-O).
--
-- design/44 makes `retry:` a declarative per-step StepScope: a step carrying a
-- RetryPolicy is re-dispatched by the orchestrator through the durable queue's
-- delayed `available_at` when its EXECUTE_COMMAND task completes FAILED and the
-- failure is retryable, bounded by `maxAttempts`. A retry is the SAME unit of
-- work re-run, not a new node (the DAG shape is static, design/29 §3) — so the
-- attempt count is a counter ON the step's flow_nodes row, not a new row.
--
-- Two columns:
--   attempt      — which attempt the node is currently on; starts at 1, the
--                  orchestrator increments it on every re-dispatch. The retry
--                  CAS keys on (status, attempt) so a re-dispatch is idempotent
--                  and cannot race the DAG scan (design/44 §4 — clean CAS).
--   max_attempts — the policy's maxAttempts, copied onto the node at bake so
--                  the orchestrator's failure path and the graph API can read
--                  "attempt k of N" without re-loading the pipeline model.
--                  1 = no retry (today's behaviour), the default.
--
-- Portable DDL only — exactly the V1/V3 conventions. Existing rows default to
-- attempt = 1, max_attempts = 1: a node with no retry policy behaves as today.

ALTER TABLE titan.flow_nodes
    ADD COLUMN attempt INT NOT NULL DEFAULT 1;

ALTER TABLE titan.flow_nodes
    ADD COLUMN max_attempts INT NOT NULL DEFAULT 1;

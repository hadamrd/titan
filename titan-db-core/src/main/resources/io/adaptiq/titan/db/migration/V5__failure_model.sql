-- Titan failure model (design/45) — structured failure on the node that failed,
-- and on the build for pre-node failures. Replaces the TitanConsole synthetic-task
-- bandaid: a controller-side failure now carries a first-class field, not a log line
-- smuggled in on a phantom queue.
--
-- Additive only. Every column is nullable; existing rows default to NULL, which reads
-- identically to "no failure recorded" — correct for already-finished builds.
-- Portable DDL: plain TEXT columns, one ADD COLUMN per statement (H2 + PostgreSQL).

-- flow_nodes — a step/stage node that ends FAILED carries why.
--   failure_category — one of the design/45 §3 closed set
--     (STEP_EXIT, CREDENTIAL, DISPATCH, BAKE, SYNTHESIS, PRECONDITION, TIMEOUT, INTERNAL);
--     set only on a terminal FAILED node.
--   failure_reason   — a concise, customer-facing sentence (names the thing and the fix).
ALTER TABLE titan.flow_nodes ADD COLUMN failure_category TEXT;
ALTER TABLE titan.flow_nodes ADD COLUMN failure_reason   TEXT;

-- builds — a build that fails before any node runs (synthesis / bake) has no node to
-- carry the reason; failure_summary is that reason. Null on a build that failed at a node.
ALTER TABLE titan.builds ADD COLUMN failure_summary TEXT;

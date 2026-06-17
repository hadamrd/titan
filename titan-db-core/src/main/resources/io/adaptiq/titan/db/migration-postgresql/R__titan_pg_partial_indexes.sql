-- PostgreSQL-only refinement of the Titan schema.
--
-- This vendor-specific repeatable migration runs ONLY when the engine is
-- backed by PostgreSQL (the `migration/postgresql` location is layered on top
-- of the portable `migration` baseline for the `postgresql` vendor). H2 never
-- sees this file, so the partial-index syntax it cannot parse is never run.
--
-- The portable V1 baseline already created a *full* index named
-- `ix_task_queue_claimable`. On PostgreSQL we replace it with the partial
-- index `WHERE status = 'QUEUED'`: the claim hot path only ever scans QUEUED
-- rows, and a partial index keeps the index small and the claim O(log n) even
-- as COMPLETED/FAILED history accumulates (doc-26 Tier A).
--
-- Repeatable (R__) + IF EXISTS / IF NOT EXISTS keep it idempotent: it converges
-- the index to the partial form on every migrate without ever failing.

DROP INDEX IF EXISTS titan.ix_task_queue_claimable;

CREATE INDEX IF NOT EXISTS ix_task_queue_claimable
    ON titan.task_queue (queue_name, priority DESC, created_at)
    WHERE status = 'QUEUED';

-- issue #244 — pipeline-root `timeout:`. Persist the computed wall-clock deadline on the
-- build row so the QueueProcessor reaper can scan once per tick: rows where status='RUNNING'
-- and deadline_at < now are marked FAILED. Null = no pipeline-level timeout declared
-- (an absent root `timeout:`), so the build runs to natural completion.
-- TIMESTAMP WITH TIME ZONE (Postgres alias: TIMESTAMPTZ) — spelled in full so the
-- shared migration parses on H2 as well (H2 PG-mode rejects the TIMESTAMPTZ alias).
ALTER TABLE titan.builds
  ADD COLUMN deadline_at TIMESTAMP WITH TIME ZONE NULL;

-- The Postgres-only partial index lives in migration-postgresql/V15_1; H2 PG-mode does not
-- accept the WHERE clause on CREATE INDEX, and the API unit-test fake (FakeTitanStores) does
-- not need the index for correctness.

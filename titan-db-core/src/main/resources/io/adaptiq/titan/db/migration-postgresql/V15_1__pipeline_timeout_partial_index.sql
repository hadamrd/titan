-- Postgres-only partial index on the reaper's hot predicate (issue #244). Most builds never
-- have a deadline; this keeps the per-tick sweep O(running-with-timeout) rather than
-- O(all-builds). Lives in migration-postgresql/ because H2 PG-mode rejects the WHERE clause
-- on CREATE INDEX — the index is irrelevant for the API unit-test fake datasource anyway.
CREATE INDEX idx_builds_deadline_at ON titan.builds (deadline_at)
  WHERE deadline_at IS NOT NULL AND status = 'RUNNING';

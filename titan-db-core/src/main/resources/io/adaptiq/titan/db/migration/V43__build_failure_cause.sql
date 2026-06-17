-- Build-failure root-cause classifier (issue #1105). A heuristic classifier runs
-- async after a build closes FAILED, scans the failing step's log for known
-- signatures, and records the diagnosed cause here so operators see "why this
-- build failed" without scrolling logs.
--
-- Additive only. Both columns nullable; existing rows default to NULL, which the
-- UI reads as "no cause classified" (no badge) — correct for builds that closed
-- before this feature shipped and for every non-FAILED build. Backfill plan:
-- there is intentionally NO backfill — the classifier is a forward-looking,
-- best-effort enrichment; historical FAILED builds simply show no cause badge.
-- Portable DDL: plain TEXT, one ADD COLUMN per statement (H2 + PostgreSQL).

-- failure_cause — the diagnosed cause, one of the closed set:
--   test_failure | compile_error | oom | timeout | network | rate_limit | unknown
-- Stored as the lowercase wire name of FailureCause; NULL until the async
-- classifier writes it (or for builds that never failed).
ALTER TABLE titan.builds ADD COLUMN failure_cause TEXT;

-- failure_cause_detail — the matching log snippet that drove the classification,
-- surfaced in the "why this was classified" tooltip. NULL for an UNKNOWN verdict
-- (no signature matched) and for un-classified builds.
ALTER TABLE titan.builds ADD COLUMN failure_cause_detail TEXT;

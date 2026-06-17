-- Titan schema V34 — per-PAT job pattern (closes #1082).
--
-- Extends the per-PAT scope restriction model (V21 / #500) with a *path*
-- dimension: a token can now be narrowed to operate only on jobs whose
-- `full_name` matches a glob (e.g. "acme/web-*" or "org/my-app/**").
--
-- Semantics (enforced by PatJobScopeFilter on the request path):
--   * NULL   → no path restriction; the token operates on every job within
--              its role scope. Backward-compatible: existing PATs grandfather
--              in as "all jobs".
--   * glob   → the request's resolved {jobId|buildId}→job.full_name MUST match
--              the glob, else the filter denies with 403 problem+json and
--              writes an AuditAction.PAT_SCOPE_DENIED row.
--
-- The column is plain VARCHAR (the glob lives in app code, not the SQL layer)
-- to keep the schema portable across H2 (test) and PostgreSQL (prod). Length
-- cap of 200 matches the cap enforced by PatJobPattern.MAX_PATTERN_LEN so a
-- caller can never blow past either layer.
--
-- A NULL default plus no backfill guarantees zero behavioural drift for the
-- million-token-day-1 hypothetical: a deploy of this migration on a hot DB
-- changes ONLY the schema, never an existing row.

ALTER TABLE titan.personal_access_tokens
    ADD COLUMN job_pattern VARCHAR(200);

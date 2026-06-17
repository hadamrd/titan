-- V33 (#965): GitHub Check-Run id backing column on titan.builds.
--
-- The Check-Runs API (https://docs.github.com/en/rest/checks/runs) is the richer
-- counterpart to the commit-status API used by GithubStatusReporter (#835):
-- creating a check-run with POST /repos/{owner}/{repo}/check-runs returns a
-- numeric `id` we must then carry to the matching PATCH at completion. The id
-- lives ON the build (one-to-one — every build owns at most one check-run);
-- a join table would be over-engineering at the V1 cardinality (one-per-build).
--
-- NULL means either:
--   * the build was not GitHub-App triggered (manual / cron / replay), OR
--   * the build was App-triggered but the POST failed (we log + skip — see
--     GithubCheckRunReporter.onBuildStart) — the PATCH at finish then no-ops.
--
-- The index supports the (rare) reverse-lookup needed by webhook handlers that
-- surface a check-run id and want to find the matching Titan build. We do NOT
-- use a partial-index `WHERE external_check_run_id IS NOT NULL` here — H2 (the
-- in-memory engine the @QuarkusTest suite runs against) does not parse the
-- WHERE clause on CREATE INDEX. PostgreSQL would let us skip NULL rows for a
-- slimmer index, but the saving is negligible at expected cardinality (one row
-- per build) and migration portability matters more than that micro-optimization.
--
-- BIGINT because GitHub check-run ids are 64-bit (their docs say "integer" but
-- the values are already past 2^32 in the wild; safer to match the API shape).

ALTER TABLE titan.builds ADD COLUMN external_check_run_id BIGINT;

CREATE INDEX idx_builds_external_check_run_id
    ON titan.builds(external_check_run_id);

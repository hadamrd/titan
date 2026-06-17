-- =====================================================================
-- V34 — Full-text search across step logs (closes #1083).
--
-- Lives in migration-postgresql/ (NOT the portable migration/ dir): the
-- `tsvector` type, `to_tsvector(...)` and the GIN index are Postgres-only.
-- The shared migration/ dir must stay H2-neutral — every @QuarkusTest IT
-- and SchemaNeutralDatabaseTest boot Flyway against H2 and skip the
-- migration-postgresql/ overrides. (Moved here in #1174 after #1086
-- shipped it in migration/, which broke the whole H2 IT suite with
-- `Unknown data type: "TSVECTOR"`. Real-Postgres ITs and prod via
-- TitanSchema load BOTH dirs, so the FTS column still ships unchanged.)
--
-- The /api/v1/builds/search endpoint needs to query log content cheaply
-- ("when did this error last happen", "which builds touched file X").
-- Postgres' built-in FTS is enough for v1 — no Elastic, no Meili.
--
-- Strategy: a STORED GENERATED tsvector column on titan.logs.data. The
-- column updates atomically on every INSERT (no trigger to maintain,
-- no risk of drift between data and index), and the GIN index gives
-- us sub-100ms phrase lookup over chunked log content.
--
-- `english` is the default text-search config for v1. If the rig later
-- wants `simple` (less stemming, better for stack traces / file paths)
-- the column is dropped and re-added — small, contained migration.
-- =====================================================================

ALTER TABLE titan.logs
    ADD COLUMN IF NOT EXISTS tsv tsvector
        GENERATED ALWAYS AS (to_tsvector('english', data)) STORED;

CREATE INDEX IF NOT EXISTS idx_logs_tsv
    ON titan.logs USING GIN (tsv);

COMMENT ON COLUMN titan.logs.tsv IS
    'Generated FTS vector over `data` — drives /api/v1/builds/search (#1083). '
    'STORED GENERATED so writes can never desync from the source column.';

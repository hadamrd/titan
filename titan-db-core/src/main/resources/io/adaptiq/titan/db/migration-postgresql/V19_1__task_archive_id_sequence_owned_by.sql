-- =====================================================================
-- V19_1 — Postgres-only refinement of V19: bind the sequence's lifecycle
-- to the column it backs, so `DROP TABLE titan.task_archive CASCADE`
-- also drops the sequence. Purely cosmetic — the sequence is a top-
-- level schema object and an orphan would be harmless — but matches
-- the shape Postgres expects when a table is fully decommissioned.
--
-- H2's PG-compat mode (used by the @QuarkusTest Flyway bootstrap) has
-- no syntax for `ALTER SEQUENCE ... OWNED BY ...`, so this lives in
-- the PG-only migrations directory; ITs that override
-- `quarkus.flyway.locations` to include `migration-postgresql/` pick
-- it up against the real Postgres Testcontainer. See #501.
-- =====================================================================

ALTER SEQUENCE titan.task_archive_id_seq OWNED BY titan.task_archive.id;

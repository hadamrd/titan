-- =====================================================================
-- V19 — Fix task_archive.id collisions on archive sweep (#490 follow-up)
--
-- V1 created task_archive.id as BIGINT NOT NULL PRIMARY KEY (no default,
-- no identity) on the assumption that the reaper would copy task_queue.id
-- verbatim. That assumption broke as soon as anything else (seed scripts,
-- replay, manual inserts) populated task_archive with its own ids: the
-- archive sweep then crashed with
--   duplicate key value violates unique constraint "task_archive_pkey"
-- and the build's logs were never reachable via task_token.
--
-- Fix:
--   1) attach a dedicated sequence to task_archive.id and use it as the
--      column default — the reaper will then OMIT id in its INSERT and
--      let the destination mint a fresh, collision-free key.
--   2) prime the sequence above the explicit-id seed range using the
--      portable `ALTER SEQUENCE ... RESTART WITH` form. Both Postgres
--      and H2 (PG-compat mode, used by the @QuarkusTest Flyway runner)
--      accept this; the earlier `setval(...)` form was PG-only and
--      broke every @QuarkusTest boot — see #501.
--   3) add UNIQUE (task_token) so the archive sweep can use
--      ON CONFLICT (task_token) DO NOTHING for true idempotency
--      (a row already archived is a no-op, not a crash).
--
-- Schema-additive only: existing rows keep their ids, existing columns
-- and types are unchanged, no data migrated.
--
-- Checksum note: this file's body changed relative to the version that
-- shipped with PR #491 (`SELECT setval(...)` → `ALTER SEQUENCE ...
-- RESTART WITH 1000000000`). Production rigs that already ran the
-- earlier V19 will see a Flyway checksum mismatch on next boot; this
-- is healed automatically by `quarkus.flyway.repair-at-start=true`
-- in %prod (see application.properties). The functional outcome is
-- identical: the sequence is primed to 10^9 in both cases, and ids
-- already past 10^9 are not realistic for the lifetime of any normal
-- deployment (PR #491 just shipped, so no prod has produced a billion
-- archives yet).
-- =====================================================================

CREATE SEQUENCE IF NOT EXISTS titan.task_archive_id_seq AS BIGINT;

ALTER TABLE titan.task_archive
    ALTER COLUMN id SET DEFAULT nextval('titan.task_archive_id_seq');

-- NOTE: the earlier V19 included `ALTER SEQUENCE ... OWNED BY ...` here for
-- automatic sequence cleanup on DROP TABLE CASCADE. That clause is PG-only
-- (H2 has no syntax for it) and is purely cosmetic — the sequence is a top
-- level schema object that survives column drops harmlessly. It is moved out
-- of the portable migration so the @QuarkusTest H2 bootstrap succeeds; the
-- PG-only refinement lives in migration-postgresql/V19_1 for IT rigs that
-- want full PG-shape fidelity. See #501.
--
-- Prime well above every id already in place AND well above anything an
-- explicit-id INSERT (rig/local/seed-data.sh uses COALESCE(MAX(id),0)+N)
-- could realistically mint. Starting at 10^9 reserves the low id space
-- for replay/seed fixtures and the high range for the live reaper — no
-- arithmetic overlap is possible for the lifetime of any normal
-- deployment. `RESTART WITH` is portable across Postgres + H2; the
-- earlier `setval(...)` form was PG-only.
ALTER SEQUENCE titan.task_archive_id_seq RESTART WITH 1000000000;

-- task_token is the durable join key shared with titan.logs.task_id and
-- the read path in TaskQueueDao.logTokensForBuild. Making it UNIQUE lets
-- the reaper express "already archived" declaratively via ON CONFLICT.
ALTER TABLE titan.task_archive
    ADD CONSTRAINT task_archive_task_token_key UNIQUE (task_token);
